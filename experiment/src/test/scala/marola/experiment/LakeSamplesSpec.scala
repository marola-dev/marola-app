package marola.experiment

import java.nio.file.{Files, Path}
import java.sql.SQLException
import java.time.{Instant, LocalDate}
import java.util.Comparator

import kyo.*

import marola.experiment.schema.*

class LakeSamplesSpec extends munit.FunSuite:

  private def run[A](v: A < (Scope & Sync)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(v).getOrThrow

  // Decided by trying: DuckDB fetches the extension from extensions.duckdb.org on first LOAD, which
  // CI reaches and a sandbox without that host does not.
  private lazy val duckLakeLoads: Boolean =
    val c = LakeSamples.connect()
    try
      val _ = c.createStatement().execute("LOAD ducklake")
      true
    catch case _: SQLException => false
    finally c.close()

  private val catalogs: Seq[(String, Path => LakeCatalog)] = Seq(
    "duckdb file" -> (dir => LakeCatalog.DuckDbFile(dir.resolve("lake.duckdb"))),
    "ducklake" -> (dir => LakeCatalog.DuckLake(dir.resolve("lake")))
  )

  private val t0 = Instant.parse("2026-10-09T00:00:00Z")
  private val day0 = LocalDate.parse("2026-10-09")

  private def forecast(i: Int, member: Option[Int] = None, value: Double = 1.0) =
    ForecastSample(
      "gfs",
      t0,
      t0.plusSeconds(3600),
      s"p${i % 3}",
      t0.plusSeconds(3600L * i),
      i,
      Variable.WindSpeed10m,
      member,
      value,
      -27.5,
      -48.5,
      "https://api.open-meteo.com/v1/forecast?models=gfs"
    )

  private val point =
    SamplingPoint(
      "sc-floripa-sbfl",
      -27.67,
      -48.55,
      PointKind.Station,
      Some("metar:SBFL"),
      day0,
      None
    )
  private val observation =
    ObservationSample("metar:SBFL", t0, Variable.Temperature2m, 21.5, "instant", true)
  private val runRow =
    RunIndexRow("gfs", t0, None, None, RunState.Missing, Some("not published"), None)
  private val score =
    ScoreCell("gfs", "sc-floripa-sbfl", Variable.WindSpeed10m, 24, "5-10", day0, 3, 1, 2, 3, 4, 5)
  private val pair =
    PairCell("gfs", "ifs", "sc-floripa-sbfl", Variable.WindSpeed10m, 24, day0, 3, 1, 2, 3, 4, 5)

  private def writeCycle(
      store: SampleStore,
      forecasts: Chunk[ForecastSample],
      runs: Chunk[RunIndexRow]
  ) =
    for
      _ <- store.writePoints(Chunk(point))
      _ <- store.writeForecasts(forecasts)
      _ <- store.writeObservations(Chunk(observation))
      _ <- store.writeRuns(runs)
      _ <- store.writeScoreCells(Chunk(score))
      _ <- store.writePairCells(Chunk(pair))
    yield ()

  private val far = t0.plusSeconds(3600L * 24 * 365)

  private case class Contents(
      points: Chunk[SamplingPoint],
      forecasts: Chunk[ForecastSample],
      observations: Chunk[ObservationSample],
      runs: Chunk[RunIndexRow],
      scores: Chunk[ScoreCell],
      pairs: Chunk[PairCell]
  )

  private def readAll(store: SampleStore) =
    for
      points <- store.points
      forecasts <- store.forecasts(t0, far).run
      observations <- store.observations(t0, far).run
      runs <- store.runs(t0, far).run
      scores <- store.scoreCells(day0, day0.plusDays(1)).run
      pairs <- store.pairCells(day0, day0.plusDays(1)).run
    yield Contents(points, forecasts, observations, runs, scores, pairs)

  private val lakeDir = FunFixture[Path](
    _ => Files.createTempDirectory("lake-samples-spec"),
    dir =>
      Files
        .walk(dir)
        .sorted(Comparator.reverseOrder())
        .forEach(p => Files.delete(p))
  )

  for (name, catalogOf) <- catalogs do
    def needsExtension(): Unit =
      if name == "ducklake" then assume(duckLakeLoads, "the ducklake extension did not load here")

    lakeDir.test(s"samples_round_trip_through_local_lake ($name)") { dir =>
      needsExtension()
      val forecasts =
        Chunk(forecast(1), forecast(2, member = Some(0)), forecast(2, member = Some(1)))
      run(SampleStore(catalogOf(dir)).map(writeCycle(_, forecasts, Chunk(runRow))))
      // A second scope reopens the lake: what comes back was read from the directory.
      val back = run(SampleStore(catalogOf(dir)).map(readAll))
      assertEquals(
        back,
        Contents(
          Chunk(point),
          forecasts,
          Chunk(observation),
          Chunk(runRow),
          Chunk(score),
          Chunk(pair)
        )
      )
    }

    lakeDir.test(s"rerun_cycle_writes_no_duplicates ($name)") { dir =>
      needsExtension()
      val forecasts = Chunk(forecast(1), forecast(2, member = Some(0)))
      val sampled = runRow.copy(state = RunState.Sampled, reason = None, contentSha = Some("abc"))
      val back = run(
        SampleStore(catalogOf(dir)).map { store =>
          for
            _ <- writeCycle(store, forecasts, Chunk(runRow))
            _ <- writeCycle(store, forecasts, Chunk(runRow))
            // A duplicate inside one batch, and a re-fetch that replaces the earlier value.
            _ <- store.writeForecasts(Chunk(forecast(1, value = 2.0), forecast(1, value = 2.0)))
            _ <- store.writeRuns(Chunk(sampled))
            all <- readAll(store)
          yield all
        }
      )
      assertEquals(
        back,
        Contents(
          Chunk(point),
          Chunk(forecast(1, value = 2.0), forecast(2, member = Some(0))),
          Chunk(observation),
          Chunk(sampled),
          Chunk(score),
          Chunk(pair)
        )
      )
    }

    lakeDir.test(s"rescore_reads_are_paged ($name)") { dir =>
      needsExtension()
      val pageSize = 50
      // 90 days of hourly valid times, plus a day each side that the window must leave out.
      val all = Chunk.from((-24 until 24 * 91).map(i => forecast(i)))
      val inWindow = all.filter(s =>
        !s.validTime.isBefore(t0) && s.validTime.isBefore(t0.plusSeconds(3600L * 24 * 90))
      )
      val (pages, read) = run(
        SampleStore(catalogOf(dir), pageSize).map { store =>
          val window = store.forecasts(t0, t0.plusSeconds(3600L * 24 * 90))
          for
            _ <- store.writeForecasts(all)
            pages <- window.mapChunk(c => Seq(c.size)).run
            read <- window.run
          yield (pages, read)
        }
      )
      assertEquals(inWindow.size, 24 * 90)
      assertEquals(read, inWindow)
      assert(pages.forall(_ <= pageSize), s"a page larger than $pageSize: $pages")
      assertEquals(pages.size, (inWindow.size + pageSize - 1) / pageSize)
    }
  end for

end LakeSamplesSpec
