package marola.experiment.forecast

import java.time.Instant

import kyo.*

import marola.experiment.schema.*
import marola.http.Http

import OpenMeteoFixtures.*

class ForecastClientSpec extends munit.FunSuite:

  private given CanEqual[Instant, Instant] = CanEqual.derived

  private def at(samples: Chunk[ForecastSample], lead: Int, v: Variable): Double =
    samples.find(s => s.leadH == lead && s.variable == v).get.value

  test("ifs_gfs_wind_and_temperature_parsed") {
    val t = Replay(recorded*)
    val (ifsRows, gfsRows) = run(t)(m =>
      for
        i <- source(ifs, m)
          .collect(Chunk(sampledRow(ifs, ifsRun.minusSeconds(6 * 3600))), Chunk(sbfl))
        g <- source(gfs, m).collect(Chunk(sampledRow(gfs, gfsEarlier)), Chunk(sbfl))
      yield (i.samples, g.samples)
    )
    // 60 leads (6–360 h) × 3 variables, each from the run the metadata names.
    assertEquals(ifsRows.size, 180)
    assertEquals(gfsRows.size, 180)
    assertEquals(ifsRows.map(_.runInit).distinct, Chunk(ifsRun))
    assertEquals(gfsRows.map(_.runInit).distinct, Chunk(gfsRun))
    // Values read off the recorded responses by hand.
    assertEquals(at(ifsRows, 6, Variable.WindSpeed10m), 1.54)
    assertEquals(at(ifsRows, 6, Variable.WindDirection10m), 126.0)
    assertEquals(at(ifsRows, 6, Variable.Temperature2m), 21.6)
    assertEquals(at(ifsRows, 360, Variable.WindSpeed10m), 0.61)
    assertEquals(at(gfsRows, 6, Variable.WindSpeed10m), 1.21)
    assertEquals(at(gfsRows, 6, Variable.WindDirection10m), 246.0)
    assertEquals(at(gfsRows, 12, Variable.Temperature2m), 18.7)
    // The nearest cell's centre, as Open-Meteo answered it.
    assertEquals(ifsRows.map(s => (s.cellLat, s.cellLon)).distinct, Chunk((-27.662567, -48.585876)))
    assertEquals(gfsRows.map(s => (s.cellLat, s.cellLon)).distinct, Chunk((-27.705818, -48.515625)))
    assert(ifsRows.forall(_.member.isEmpty))
  }

  test("request_built_from_providers_row") {
    assertEquals(
      OpenMeteoForecasts.latestUrl(gfs, protocol, sbfl, gfsRun),
      "https://api.open-meteo.com/v1/forecast?latitude=-27.671&longitude=-48.547" +
        "&models=ncep_gfs013&hourly=wind_speed_10m,wind_direction_10m,temperature_2m" +
        "&cell_selection=nearest&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT" +
        "&start_hour=2026-10-10T00:00&end_hour=2026-10-24T18:00"
    )
    assertEquals(
      OpenMeteoForecasts.singleRunUrl(ifs, protocol, sbfl, ifsRun),
      "https://single-runs-api.open-meteo.com/v1/forecast?latitude=-27.671&longitude=-48.547" +
        "&models=ecmwf_ifs&hourly=wind_speed_10m,wind_direction_10m,temperature_2m" +
        "&cell_selection=nearest&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT" +
        "&run=2026-10-09T12:00&forecast_hours=361"
    )
    assertEquals(
      OpenMeteoForecasts.metadataUrl(ifs.modelId),
      "https://api.open-meteo.com/data/ecmwf_ifs/static/meta.json"
    )
    // A shorter model range trims the leads; nothing else in the row changes the request.
    val short = gfs.copy(maxLeadH = 120)
    assert(
      OpenMeteoForecasts
        .latestUrl(short, protocol, sbfl, gfsRun)
        .endsWith("end_hour=2026-10-14T18:00")
    )
  }

  test("cell_selection_nearest_sent") {
    val t = Replay(recorded*)
    val _ = run(t)(m =>
      source(gfs, m).collect(Chunk(sampledRow(gfs, gfsRun.minusSeconds(12 * 3600))), Chunk(sbfl))
    )
    val forecasts = t.urls.filterNot(_.contains("/static/meta.json"))
    // The latest run and the backfilled 12Z: both endpoints.
    assert(forecasts.exists(_.startsWith(OpenMeteoForecasts.ForecastBase)), forecasts)
    assert(forecasts.exists(_.startsWith(OpenMeteoForecasts.SingleRunBase)), forecasts)
    assert(forecasts.forall(_.contains("&cell_selection=nearest&")), forecasts)
  }

  test("gfs_gap_backfilled_from_single_run") {
    val t = Replay(recorded*)
    // The last cycle saw 06Z; 12Z was missed and 18Z is the latest.
    val known = Chunk(sampledRow(gfs, gfsEarlier.minusSeconds(6 * 3600)))
    val got = run(t)(m => source(gfs, m).collect(known, Chunk(sbfl)))
    assertEquals(
      got.runs.map(r => (r.runInit, r.state)),
      Chunk(gfsEarlier -> RunState.Backfilled, gfsRun -> RunState.Sampled)
    )
    val backfilled = got.samples.filter(_.runInit == gfsEarlier)
    assertEquals(backfilled.size, 180)
    assert(backfilled.forall(_.sourceUrl.startsWith(OpenMeteoForecasts.SingleRunBase)))
    // 12Z + 6 h, read off the recorded single run.
    assertEquals(at(backfilled, 6, Variable.WindSpeed10m), 2.51)
    assertEquals(got.runs.head.contentSha, Some(OpenMeteoForecasts.contentSha(backfilled)))
    assertEquals(got.runs.last.availableAt, Some(Instant.ofEpochSecond(1791588701L)))
  }

  test("a run the single-run API does not have is a missing row with its reason") {
    val t = Replay(recorded*)
    val known = Chunk(sampledRow(gfs, gfsEarlier.minusSeconds(12 * 3600)))
    val got = run(t)(m => source(gfs, m).collect(known, Chunk(sbfl)))
    val missing = got.runs.filter(_.state == RunState.Missing)
    assertEquals(missing.map(_.runInit), Chunk(Instant.parse("2026-10-09T06:00:00Z")))
    assert(
      missing.head.reason.exists(_.contains("The requested model run is not available")),
      missing
    )
    assertEquals(
      got.runs.map(_.state),
      Chunk(RunState.Missing, RunState.Backfilled, RunState.Sampled)
    )
  }

  test("rate_limit_429_retried") {
    // The body Open-Meteo's single-run API answered from this address on 2026-10-09.
    val limited = Http.Response(429, read("single-run-429.json"))
    val t = Replay(
      isSingleRun("ncep_gfs013", "2026-10-09T12:00") ->
        Seq(
          limited,
          limited,
          Http.Response(200, read("single-run-ncep_gfs013-2026100912-sc-sbfl.json"))
        )
    )
    val got = run(t)(m => source(gfs, m).fetch(gfsEarlier, Chunk(sbfl)))
    assertEquals(got.size, 180)
    assertEquals(t.urls.size, 3)

    // Past the schedule, the 429 is the error.
    val always = Replay(isSingleRun("ncep_gfs013", "2026-10-09T12:00") -> Seq(limited))
    outcome(always, Meter.initRateLimiter(1000, 1.millis))(m =>
      source(gfs, m).fetch(gfsEarlier, Chunk(sbfl))
    ) match
      case Result.Failure(FetchError.Status(429, _, body)) =>
        assert(body.contains("Daily API request limit"))
      case other => fail(s"Unexpected: $other")
    assertEquals(always.urls.size, 4)
  }

  test("the rate limiter spaces requests across points") {
    val body = read("single-run-ncep_gfs013-2026100912-sc-sbfl.json")
    val t = Replay(isSingleRun("ncep_gfs013", "2026-10-09T12:00") -> ok(body))
    val points = Chunk.from((1 to 5).map(i => sbfl.copy(id = s"p$i")))
    val got = run(t, Meter.initRateLimiter(1, 50.millis))(m =>
      OpenMeteoForecasts(gfs, protocol, m, concurrency = 5, backoff = fast)
        .fetch(gfsEarlier, points)
    )
    assertEquals(got.map(_.point).distinct.size, 5)
    val times = t.sent.map(_._1).sorted
    // One permit per 50 ms: five requests cannot all leave inside 150 ms, however many may run.
    assert((times.last - times.head) / 1_000_000 >= 150, times)
  }

  private def sampledRow(p: Provider, run: Instant) =
    RunIndexRow(p.id, run, None, Some("x"), RunState.Sampled, None, None)
end ForecastClientSpec
