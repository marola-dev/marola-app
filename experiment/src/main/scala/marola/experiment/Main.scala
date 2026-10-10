package marola.experiment

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.HexFormat

import scala.util.Using

import kyo.*

import marola.experiment.forecast.OpenMeteoForecasts
import marola.experiment.schema.{ObservationSample, Route}
import marola.ledger.{MlflowRunLedger, RunLedger}

/**
 * Read once at start, from a system property or its env var: `MAROLA_EXPERIMENT_FLAGS_LAKE`, …
 * (kyo-config, MIP-0083 §5.13). A lake path ending in `.duckdb` is a plain DuckDB file.
 */
object Flags:
  object lake extends StaticFlag[String](".tmp/experiment/lake")
  object out extends StaticFlag[String](".tmp/experiment/out")
  object mlflowUri extends StaticFlag[String]("")

  /** A throwaway lake and no MLflow run: fetch and score, keep nothing. */
  object dryRun extends StaticFlag[Boolean](false)

/**
 * `cycle`, `rescore [from until]`, `predictions [from until]` and `export` (MIP-0083 §5.6, §5.8).
 */
object Main:

  def main(args: Array[String]): Unit =
    import AllowUnsafe.embrace.danger
    val code = KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Scope.run(program(args.toList)))
    sys.exit(code.getOrThrow)

  def program(args: List[String])(using Frame): Int < (Async & Scope) =
    args match
      case List("cycle") =>
        deps.map(Env.run(_)(Cycle.run)).map { r =>
          r.failures.foreach(f => java.lang.System.err.println(s"failed: $f"))
          if r.ok then 0 else 1
        }
      case "rescore" :: range if range.size == 0 || range.size == 2 =>
        deps.map(d =>
          Env.run(d) {
            Clock.now.map { now =>
              val (from, until) = days(range, now.toJava)
              Cycle
                .rescore(from, until)
                .map(s =>
                  for
                    _ <- d.store.writeScoreCells(s.cells)
                    _ <- d.store.writePairCells(s.pairs)
                    _ <- Sync.defer {
                      Files.createDirectories(d.out)
                      Files.writeString(d.out.resolve("rescore.json"), Json.encode(s) + "\n")
                    }
                  yield 0
                )
            }
          }
        )
      case "predictions" :: range if range.size == 0 || range.size == 2 =>
        deps.map(d =>
          Env.run(d) {
            Clock.now.map { now =>
              val (from, until) = days(range, now.toJava)
              Cycle
                .predictions(from, until)
                .map(rows =>
                  Sync.defer {
                    Files.createDirectories(d.out)
                    Files.writeString(
                      d.out.resolve(Export.PredictionsFile),
                      Export.predictionsCsv(rows)
                    )
                    0
                  }
                )
            }
          }
        )
      case List("export") =>
        deps.map(Env.run(_)(Clock.now.map(n => Export.write(n.toJava)))).map(_ => 0)
      case _ =>
        Sync.defer {
          java.lang.System.err
            .println("usage: cycle | rescore [from until] | predictions [from until] | export")
          2
        }

  /** `[from, until)` from two ISO dates, or the days a cycle rescores. */
  private def days(range: List[String], now: Instant): (LocalDate, LocalDate) =
    range match
      case List(f, u) => (LocalDate.parse(f), LocalDate.parse(u))
      case _ =>
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        (today.minusDays(Cycle.RescoredDays - 1L), today.plusDays(1))

  def catalog(path: String): LakeCatalog =
    if path.endsWith(".duckdb") then LakeCatalog.DuckDbFile(Path.of(path))
    else LakeCatalog.DuckLake(Path.of(path))

  private def bundled[A](loaded: Either[Vector[?], A])(using Frame): A < Sync =
    Sync.defer(loaded.fold(e => throw IllegalStateException(e.mkString("; ")), identity))

  private val Bundled =
    List("ground-truth.json", "sampling-points.json", "protocol.json", "providers.json")

  /** `git hash-object`'s sha, so a param names the committed file. */
  def blobSha(name: String): String =
    val bytes = Using.resource(getClass.getResourceAsStream(s"/experiment/$name"))(_.readAllBytes)
    val sha = MessageDigest.getInstance("SHA-1")
    sha.update(s"blob ${bytes.length}\u0000".getBytes(UTF_8))
    HexFormat.of.formatHex(sha.digest(bytes))

  def deps(using Frame): Deps < (Async & Scope) =
    for
      gt <- bundled(GroundTruth.bundled)
      points <- bundled(SamplingRegistry.bundled(gt))
      protocol <- bundled(Protocols.bundledProtocol)
      providers <- bundled(Protocols.bundledProviders)
      lake <-
        if Flags.dryRun() then Sync.defer(Files.createTempFile("experiment", ".duckdb").toString)
        else Kyo.lift(Flags.lake())
      _ <- Sync.defer(if Flags.dryRun() then Files.deleteIfExists(Path.of(lake)) else false)
      store <- SampleStore(catalog(lake))
      meter <- OpenMeteoForecasts.rateLimiter
      nino <- Abort.run[Throwable](Abort.catching[Throwable](Nino34Reader.latest))
    yield Deps(
      store,
      providers.filter(_.route == Route.OpenMeteo).map(OpenMeteoForecasts(_, protocol, meter)),
      observe(gt),
      if Flags.dryRun() || Flags.mlflowUri().isEmpty then RunLedger.Noop
      else MlflowRunLedger(Flags.mlflowUri()),
      gt,
      points,
      protocol,
      Path.of(Flags.out()),
      Map("app_sha" -> sys.env.getOrElse("GITHUB_SHA", "local")) ++ Bundled.map(f =>
        s"sha.$f" -> blobSha(f)
      ) ++ (nino match
        case Result.Success(Some(w)) =>
          Map("nino34_anomaly_c" -> w.anomalyC.toString, "nino34_week" -> w.week.toString)
        case _ => Map.empty
      )
    )

  // The last RescoredDays days, so late reports reach the days a cycle scores again.
  private def observe(gt: GroundTruth)(now: Instant): Chunk[ObservationSample] < Sync =
    val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
    for
      metar <- MetarClient.fetch(gt.points, hours = Cycle.RescoredDays * 24)
      inmet <- InmetClient.fetch(gt.points, today.minusDays(Cycle.RescoredDays - 1L), today)
    yield Chunk.from(metar ++ inmet)
end Main
