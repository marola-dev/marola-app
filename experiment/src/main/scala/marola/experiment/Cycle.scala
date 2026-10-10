package marola.experiment

import java.nio.file.{Files, Path}
import java.time.{Duration as JDuration, Instant, LocalDate, ZoneOffset}

import kyo.*

import marola.experiment.forecast.{FetchError, ForecastSource}
import marola.experiment.schema.*
import marola.experiment.score.{Matcher, Prediction, Scorer}
import marola.ledger.RunLedger
import marola.log.Log

/** What a cycle reads and writes, provided through `Env` so a test swaps in fixtures (§5.13). */
final case class Deps(
    store: SampleStore,
    sources: Chunk[ForecastSource],
    observe: Instant => Chunk[ObservationSample] < Sync,
    ledger: RunLedger,
    groundTruth: GroundTruth,
    points: Chunk[SamplingPoint],
    protocol: Protocol,
    out: Path,
    params: Map[String, String] = Map.empty
)

/** The days a cycle scored, or `rescore` rebuilt: `scores.json`. */
final case class Scores(cells: Chunk[ScoreCell], pairs: Chunk[PairCell]) derives CanEqual

object Scores:
  given Schema[Scores] = Schema.derived[Scores]

final case class Report(
    ok: Boolean,
    runs: Chunk[RunIndexRow],
    scores: Scores,
    failures: Chunk[String],
    exported: Option[Path]
)

/** MIP-0083 §5.6: fetch, store, match, score and log, one MLflow run per cycle. */
object Cycle:

  /** Late observations and backfilled runs change the last days, so they are scored again. */
  val RescoredDays = 3

  private val logger = Log.forName(getClass.getName)

  def experiment(p: Protocol): String = s"forecast-benchmark-v${p.version}"

  def run(using Frame): Report < (Async & Env[Deps]) =
    Env
      .get[Deps]
      .map(d =>
        for
          now <- Clock.now.map(_.toJava)
          today = LocalDate.ofInstant(now, ZoneOffset.UTC)
          points = d.points.filter(SamplingRegistry.sampledOn(_, today))
          _ <- d.store.writePoints(d.points)
          known <- d.store.runs(now.minus(JDuration.ofDays(d.protocol.windowDays)), now).run
          fetched <- Kyo.foreach(d.sources)(s =>
            Abort.run[FetchError](s.collect(known, points)).map(s.provider.id -> _)
          )
          collected = fetched.collect { case (_, Result.Success(c)) => c }
          runs = collected.flatMap(_.runs)
          _ <- d.store.writeForecasts(collected.flatMap(_.samples))
          _ <- d.store.writeRuns(runs)
          observed <- Abort.run[Throwable](Abort.catching[Throwable](d.observe(now)))
          _ <- d.store.writeObservations(observed.getOrElse(Chunk.empty))
          from = today.minusDays(RescoredDays - 1)
          scores <- rescore(from, today.plusDays(1))
          _ <- d.store.writeScoreCells(scores.cells)
          _ <- d.store.writePairCells(scores.pairs)
          predicted <- predictions(from, today.plusDays(1))
          _ <- Sync.defer {
            Files.createDirectories(d.out)
            Files
              .writeString(d.out.resolve(Export.PredictionsFile), Export.predictionsCsv(predicted))
          }
          failures = fetched.collect { case (id, Result.Failure(e)) => s"$id: ${e.reason}" } ++
            fetched.collect { case (id, Result.Panic(e)) => s"$id: $e" } ++
            Chunk.from(observed.failure.orElse(observed.panic).map(e => s"observations: $e")) ++
            runs
              .filter(_.state == RunState.Missing)
              .map(r => s"${r.provider} ${r.runInit}: ${r.reason.getOrElse("missing")}")
          report <- log(d, now, runs, scores, failures)
          _ <- Sync.defer(
            logger.info(
              s"cycle stored ${runs.size} runs (${runs.count(_.state == RunState.Sampled)} sampled, " +
                s"${runs.count(_.state == RunState.Backfilled)} backfilled, " +
                s"${runs.count(_.state == RunState.Missing)} missing), " +
                s"${collected.map(_.samples.size).sum} forecast values, " +
                s"${observed.getOrElse(Chunk.empty).size} observations; " +
                s"${predicted.size} predictions at ground-truth points, " +
                s"${predicted.count(_.observed.nonEmpty)} with an observation"
            )
          )
        yield report
      )

  /** Every score of `[from, until)` rebuilt from the lake's samples alone, one day at a time. */
  def rescore(from: LocalDate, until: LocalDate)(using Frame): Scores < (Sync & Env[Deps]) =
    Env.get[Deps].map { d =>
      val providers = d.sources.map(_.provider.id).sorted
      perDay(from, until)(Matcher.pairs).map { days =>
        val cells = days.map(Scorer.cells)
        val pairs = days.map(matched =>
          for
            a <- providers
            b <- providers if a < b
            cell <- Scorer.pairCells(a, b, matched)
          yield cell
        )
        sorted(Scores(cells.flatten, pairs.flatten))
      }
    }

  /** Every forecast of `[from, until)` at a ground-truth point, beside its observation if any. */
  def predictions(from: LocalDate, until: LocalDate)(using
      Frame
  ): Chunk[Prediction] < (Sync & Env[Deps]) =
    perDay(from, until)(Matcher.predictions).map(
      _.flatten.sortBy(p => (p.validTime, p.point, p.variable.label, p.provider, p.leadH))
    )

  private def perDay[A](from: LocalDate, until: LocalDate)(
      f: (
          Chunk[ForecastSample],
          Chunk[ObservationSample],
          Chunk[SamplingPoint],
          Chunk[Instrument],
          Int
      ) => A
  )(using
      Frame
  ): Chunk[A] < (Sync & Env[Deps]) =
    Env.get[Deps].map { d =>
      val instruments = d.groundTruth.points.map(instrument).to(Chunk)
      d.store.points.map(points =>
        Kyo.foreach(Chunk.from(Iterator.iterate(from)(_.plusDays(1)).takeWhile(_.isBefore(until))))(
          day =>
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant
            val end = start.plus(JDuration.ofDays(1))
            val margin = JDuration.ofSeconds(Matcher.MetarToleranceS)
            for
              fs <- d.store.forecasts(start, end).run
              os <- d.store.observations(start.minus(margin), end.plus(margin)).run
            yield f(fs, os, points, instruments, d.protocol.ensembleK)
        )
      )
    }

  private def sorted(s: Scores): Scores = Scores(
    s.cells.sortBy(c => (c.day.toEpochDay, c.provider, c.point, c.variable.label, c.leadH, c.bin)),
    s.pairs.sortBy(c =>
      (c.day.toEpochDay, c.providerA, c.providerB, c.point, c.variable.label, c.leadH)
    )
  )

  // The matcher reads only the id and the network.
  private def instrument(p: GroundTruth.Point): Instrument = Instrument(
    p.id,
    if p.kind == GroundTruth.Kind.Metar then Network.Metar else Network.Inmet,
    p.lat.getOrElse(Double.NaN),
    p.lon.getOrElse(Double.NaN),
    None,
    "",
    ""
  )

  private def log(
      d: Deps,
      now: Instant,
      runs: Chunk[RunIndexRow],
      scores: Scores,
      failures: Chunk[String]
  )(using Frame): Report < (Async & Env[Deps]) =
    def count(s: RunState) = runs.count(_.state == s).toString
    val params = d.params ++ Map(
      "providers" -> d.sources.map(_.provider.id).mkString(","),
      "cadence_h" -> d.protocol.cadenceH.toString,
      "window_days" -> d.protocol.windowDays.toString,
      "sampled" -> count(RunState.Sampled),
      "backfilled" -> count(RunState.Backfilled),
      "missing" -> count(RunState.Missing)
    ) ++ failures.zipWithIndex.map((f, i) => s"failure.$i" -> f.take(500))
    for
      handle <- d.ledger.start(experiment(d.protocol), s"cycle $now", params)
      _ <- Sync.defer(Files.createDirectories(d.out))
      scoresJson <- write(d.out.resolve("scores.json"), Json.encode(scores))
      runsJson <- write(d.out.resolve("run_index.json"), Json.encode(runs))
      _ <- d.ledger.artifact(handle, scoresJson)
      _ <- d.ledger.artifact(handle, runsJson)
      // Metrics once a day, on the first cycle after 00 UTC (§5.6).
      _ <-
        if now.atZone(ZoneOffset.UTC).getHour >= d.protocol.cadenceH then Kyo.unit
        else
          Export
            .window(now)
            .map(w =>
              Kyo.foreachDiscard(Export.metrics(d.protocol, w).toSeq)((lead, values) =>
                d.ledger.metrics(handle, values, step = lead)
              )
            )
      exported <- Export.write(now)
      _ <- Kyo.foreachDiscard(Chunk.from(exported))(d.ledger.artifact(handle, _))
      _ <- d.ledger.end(handle, ok = failures.isEmpty)
    yield Report(failures.isEmpty, runs, scores, failures, exported)
    end for
  end log

  private def write(path: Path, text: String)(using Frame): Path < Sync =
    Sync.defer(Files.writeString(path, text + "\n"))
end Cycle
