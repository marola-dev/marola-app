package marola.experiment.forecast

import java.time.Instant

import kyo.*

import marola.experiment.schema.*
import marola.http.Http

import OpenMeteoFixtures.*

/** MIP-0083 §5.14's fetch cases; the scoring cases are task 3's. */
class FairnessSpec extends munit.FunSuite:

  private given CanEqual[Instant, Instant] = CanEqual.derived

  private val known = Chunk(
    RunIndexRow("gfs", gfsEarlier, None, None, RunState.Sampled, None, None)
  )

  /** The GFS 18Z cycle with every clock reading shifted to `fetchAt`. */
  private def collectAt(fetchAt: Instant, t: Http.Transport = Replay(recorded*)): Collected =
    run(t)(m =>
      Clock.now.map { now =>
        Clock.withTimeOffset(Clock.TimeOffset.between(now, kyo.Instant.fromJava(fetchAt)))(
          source(gfs, m).collect(known, Chunk(sbfl))
        )
      }
    )

  test("lead_counts_from_run_init_not_fetch") {
    val twoDaysLate = Instant.parse("2026-10-11T20:00:00Z")
    val got = collectAt(twoDaysLate).samples
    assert(got.forall(s => !s.fetchedAt.isBefore(twoDaysLate)), got.head.fetchedAt)
    assert(got.forall(s => s.validTime == s.runInit.plusSeconds(s.leadH * 3600L)))
    assertEquals(got.map(_.leadH).distinct, protocol.leadsH)
    assertEquals(got.map(_.runInit).distinct, Chunk(gfsRun))
  }

  test("late_fetch_gives_identical_rows") {
    // 20 minutes after GFS 18Z was available, and two days after.
    val soon = collectAt(Instant.parse("2026-10-09T23:52:00Z"))
    val late = collectAt(Instant.parse("2026-10-11T23:52:00Z"))
    assertEquals(withoutFetchTime(late.samples), withoutFetchTime(soon.samples))
    assertEquals(late.runs.map(_.contentSha), soon.runs.map(_.contentSha))

    // The same run read back from the single-run archive: recorded live hours apart, the
    // archive matched the live forecast value for value, so only the endpoint differs.
    val archived = run(Replay(recorded*))(m => source(gfs, m).fetch(gfsRun, Chunk(sbfl)))
    assertEquals(
      withoutFetchTime(archived).map(_.copy(sourceUrl = "")),
      withoutFetchTime(soon.samples).map(_.copy(sourceUrl = ""))
    )
    assertEquals(Some(OpenMeteoForecasts.contentSha(archived)), soon.runs.head.contentSha)
  }

  test("run_changed_during_fetch_is_discarded") {
    // The metadata names 12Z before the fetch and 18Z after it: what the forecast API returned
    // may be either run, so it is dropped and 12Z is read by its init time instead.
    val t = Replay(
      (isMeta("ncep_gfs013") -> Seq(
        Http.Response(200, metaWithInit("ncep_gfs013", gfsEarlier)),
        Http.Response(200, metaWithInit("ncep_gfs013", gfsRun))
      )) +: recorded*
    )
    val known06 = Chunk(
      RunIndexRow(
        "gfs",
        gfsEarlier.minusSeconds(6 * 3600),
        None,
        None,
        RunState.Sampled,
        None,
        None
      )
    )
    val got = run(t)(m => source(gfs, m).collect(known06, Chunk(sbfl)))
    assertEquals(got.runs.map(r => (r.runInit, r.state)), Chunk(gfsEarlier -> RunState.Backfilled))
    assert(got.samples.forall(_.sourceUrl.startsWith(OpenMeteoForecasts.SingleRunBase)))
    assertEquals(got.samples.map(_.runInit).distinct, Chunk(gfsEarlier))
  }

  test("older_run_on_reread_keeps_the_latest") {
    // Open-Meteo's servers update apart: live, ecmwf_ifs's metadata alternated between 12Z and
    // 18Z read to read, so a lagging server is not a new run.
    val t = Replay(
      (isMeta("ncep_gfs013") -> Seq(
        Http.Response(200, metaWithInit("ncep_gfs013", gfsRun)),
        Http.Response(200, metaWithInit("ncep_gfs013", gfsEarlier))
      )) +: recorded*
    )
    val got = collectAt(Instant.parse("2026-10-09T23:52:00Z"), t)
    assertEquals(got.runs.map(r => (r.runInit, r.state)), Chunk(gfsRun -> RunState.Sampled))
  }

  test("backfill_hash_mismatch_recorded") {
    val sampled = collectAt(Instant.parse("2026-10-09T23:52:00Z")).runs.head
    val stale = sampled.copy(contentSha = Some("0" * 64))
    val (same, differs) = run(Replay(recorded*))(m =>
      for
        a <- source(gfs, m).backfill(gfsRun, Chunk(sbfl), Chunk(sampled))
        b <- source(gfs, m).backfill(gfsRun, Chunk(sbfl), Chunk(stale))
      yield (a.runs.head, b.runs.head)
    )
    assertEquals((same.state, same.reason), (RunState.Backfilled, None))
    assertEquals(differs.state, RunState.Backfilled)
    assertEquals(differs.reason, Some(s"content_sha differs from the sampled copy ${"0" * 64}"))
    assertEquals(differs.contentSha, sampled.contentSha)
  }
end FairnessSpec
