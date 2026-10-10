package marola.experiment

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}

import scala.collection.mutable.ListBuffer

import kyo.*

import marola.experiment.forecast.OpenMeteoFixtures.*
import marola.experiment.schema.*
import marola.http.Http
import marola.ledger.RunLedger
import marola.ledger.RunLedger.RunHandle

class CycleSpec extends munit.FunSuite:

  /** The first cycle after 00 UTC, so the day's metrics are logged. */
  private val now = Instant.parse("2026-10-10T00:17:00Z")
  private val today = LocalDate.parse("2026-10-10")

  final class Recording extends RunLedger:
    val started = ListBuffer.empty[(String, Map[String, String])]
    val metrics = ListBuffer.empty[(Int, Map[String, Double])]
    val artifacts = ListBuffer.empty[String]
    val ended = ListBuffer.empty[Boolean]
    def start(experiment: String, name: String, params: Map[String, String]): RunHandle < Sync =
      Sync.defer {
        started += experiment -> params
        RunHandle("1", "r", None)
      }
    def metrics(run: RunHandle, values: Map[String, Double], step: Int): Unit < Sync =
      Sync.defer(metrics += step -> values)
    def artifact(run: RunHandle, path: Path): Unit < Sync =
      Sync.defer(artifacts += path.getFileName.toString)
    def end(run: RunHandle, ok: Boolean): Unit < Sync = Sync.defer(ended += ok)
  end Recording

  private def obs(at: String, v: Variable, value: Double) =
    ObservationSample("sc-sbfl", Instant.parse(at), v, value, "10min_mean", true)

  // At IFS 12Z leads 12, 18 and 24 and GFS 18Z leads 6, 12 and 18.
  private val observed = Chunk(
    obs("2026-10-10T00:00:00Z", Variable.WindSpeed10m, 3.0),
    obs("2026-10-10T06:00:00Z", Variable.WindSpeed10m, 2.0),
    obs("2026-10-10T12:00:00Z", Variable.WindSpeed10m, 4.0),
    obs("2026-10-10T12:00:00Z", Variable.Temperature2m, 22.0)
  )

  private def sampledRow(p: Provider, run: Instant) =
    RunIndexRow(p.id, run, None, Some("x"), RunState.Sampled, None, None)

  // The run before each recorded latest one, so nothing else is expected.
  private val earlier = Chunk(
    sampledRow(ifs, ifsRun.minusSeconds(6 * 3600)),
    sampledRow(gfs, gfsEarlier)
  )

  final case class Setup(deps: Deps, ledger: Recording)

  private def setup(
      store: SampleStore,
      meter: Meter,
      gt: GroundTruth,
      observe: Instant => Chunk[ObservationSample] < Sync
  ): Setup =
    val ledger = Recording()
    val out = Files.createTempDirectory("cycle-out")
    Setup(
      Deps(
        store,
        Chunk(source(ifs, meter), source(gfs, meter)),
        observe,
        ledger,
        gt,
        Chunk(sbfl),
        protocol,
        out
      ),
      ledger
    )

  /** A cycle at `now` on a fresh lake seeded with `seed`, then `after` against the same deps. */
  private def cycle[A](
      transport: Http.Transport,
      seed: Chunk[RunIndexRow] = earlier,
      gt: GroundTruth = GroundTruth.bundled.toOption.get,
      observe: Instant => Chunk[ObservationSample] < Sync = _ => observed
  )(after: (Setup, Report) => A < (Sync & Env[Deps])): A =
    import AllowUnsafe.embrace.danger
    val lake = Files.createTempDirectory("cycle-lake").resolve("lake.duckdb")
    Http.withTransport(transport) {
      KyoApp.Unsafe
        .runAndBlock(60.seconds)(
          for
            store <- SampleStore(LakeCatalog.DuckDbFile(lake))
            meter <- Meter.initRateLimiter(1000, 1.millis)
            s = setup(store, meter, gt, observe)
            _ <- store.writeRuns(seed)
            real <- Clock.now
            a <- Clock.withTimeOffset(Clock.TimeOffset.between(real, kyo.Instant.fromJava(now)))(
              Env.run(s.deps)(Cycle.run.map(after(s, _)))
            )
          yield a
        )
        .getOrThrow
    }

  test("cycle_logs_params_and_artifacts") {
    val (ledger, report) = cycle(Replay(recorded*))((s, r) => (s.ledger, r))
    assert(report.ok, report.failures)
    val (experiment, params) = ledger.started.head
    assertEquals(experiment, "forecast-benchmark-v1")
    assertEquals(params("providers"), "ifs,gfs")
    assertEquals((params("sampled"), params("backfilled"), params("missing")), ("2", "0", "0"))
    assertEquals(ledger.artifacts.toList, List("scores.json", "run_index.json"))
    // Lead 24 is IFS 12Z at 12Z on the 10th; GFS 18Z has no headline lead among the observations.
    val (step, values) = ledger.metrics.head
    assertEquals(step, 24)
    assertEquals(values("rmse.wind_speed_10m.90d.sc-sbfl.ifs"), 4.0 - 1.52, 1e-9)
    assertEquals(ledger.metrics.size, 1)
    assertEquals(ledger.ended.toList, List(true))
  }

  test("rescore_equals_logged_scores") {
    val (logged, rescored) = cycle(Replay(recorded*))((s, _) =>
      Cycle
        .rescore(today.minusDays(Cycle.RescoredDays - 1L), today.plusDays(1))
        .map(r =>
          (Json.decode[Scores](Files.readString(s.deps.out.resolve("scores.json"))).getOrThrow, r)
        )
    )
    assertEquals(
      logged.cells.map(c => (c.provider, c.leadH)).toSet,
      Set("ifs" -> 12, "ifs" -> 18, "ifs" -> 24, "gfs" -> 6, "gfs" -> 12, "gfs" -> 18)
    )
    assertEquals(rescored, logged)
  }

  test("predictions_csv_joins_forecasts_with_observations") {
    val (csv, cells) = cycle(Replay(recorded*))((s, r) =>
      (
        Files.readString(s.deps.out.resolve(Export.PredictionsFile)).linesIterator.toList,
        r.scores.cells
      )
    )
    assertEquals(
      csv.head,
      "valid_time,point,instrument,variable,provider,run_init,lead_h,forecast,members,observed,error"
    )
    val rows = csv.tail.map(_.split(",", -1).toList)
    assert(rows.exists(_(9).isEmpty), "a forecast with no observation is kept, blank")
    // Every matched row is one the scorer counted.
    assertEquals(rows.count(_(9).nonEmpty).toLong, cells.filter(_.bin == "all").map(_.n).sum)
    val ifs = rows
      .find(r => r(4) == "ifs" && r(0) == "2026-10-10T12:00:00Z" && r(3) == "wind_speed_10m")
      .get
    assertEquals((ifs(6), ifs(9), ifs(10).toDouble), ("24", "4.0", 1.52 - 4.0))
  }

  test("export_skipped_until_a_point_is_scored") {
    val skipped = cycle(Replay(recorded*))((_, r) => r.exported)
    assertEquals(skipped, None)

    val gt = GroundTruth.bundled.toOption.get
    val scored = gt.copy(points =
      gt.points.map(p =>
        if p.id == "sc-sbfl" then p.copy(status = GroundTruth.Status.Scored) else p
      )
    )
    val card = cycle(Replay(recorded*), gt = scored)((_, r) =>
      r.exported.map(p => Json.decode[Scorecard](Files.readString(p)).getOrThrow)
    )
    val rows = card.get.rows.map(r => (r.provider, r.variable, r.day, r.n, r.lowSample))
    assertEquals(
      rows,
      Chunk(
        ("ifs", Variable.WindSpeed10m, 1, 1L, true),
        ("ifs", Variable.Temperature2m, 1, 1L, true)
      )
    )
  }

  test("failed_fetch_ends_run_failed") {
    // GFS has no earlier run on file, so its 00Z and 06Z are read by init and are not served.
    val (ledger, report, runs) = cycle(
      Replay(recorded*),
      seed = earlier.filter(_.provider == "ifs"),
      gt = GroundTruth.bundled.toOption.get,
      observe = _ => Sync.defer(throw RuntimeException("METAR down"))
    )((s, r) => s.deps.store.runs(Instant.EPOCH, now).run.map(rs => (s.ledger, r, rs)))
    assert(!report.ok)
    assertEquals(ledger.ended.toList, List(false))
    val missing = runs.filter(_.state == RunState.Missing)
    assertEquals(
      missing.map(r => (r.provider, r.runInit.toString)).toSet,
      Set("gfs" -> "2026-10-09T00:00:00Z", "gfs" -> "2026-10-09T06:00:00Z")
    )
    assert(missing.forall(_.reason.nonEmpty), missing)
    assert(report.failures.exists(_.contains("METAR down")), report.failures)
    assertEquals(ledger.started.head._2("missing"), "2", ledger.started)
  }

  test("flags_read_from_documented_env_vars") {
    assertEquals(
      List(Flags.lake.envName, Flags.out.envName, Flags.mlflowUri.envName, Flags.dryRun.envName),
      List(
        "MAROLA_EXPERIMENT_FLAGS_LAKE",
        "MAROLA_EXPERIMENT_FLAGS_OUT",
        "MAROLA_EXPERIMENT_FLAGS_MLFLOWURI",
        "MAROLA_EXPERIMENT_FLAGS_DRYRUN"
      )
    )
  }

  test("bundled_files_logged_by_git_blob_sha") {
    val file = Path.of("experiment/src/main/resources/experiment/protocol.json")
    val git = scala.sys.process.Process(Seq("git", "hash-object", file.toString)).!!.trim
    assertEquals(Main.blobSha("protocol.json"), git)
  }
end CycleSpec
