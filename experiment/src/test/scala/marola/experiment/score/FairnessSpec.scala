package marola.experiment.score

import java.time.LocalDate

import kyo.Chunk

import marola.experiment.schema.*
import marola.experiment.score.Fixtures.{at, matched}

class FairnessSpec extends munit.FunSuite:

  test("head_to_head_uses_pairwise_intersection") {
    // B missed the 12Z run: the A/B pair loses that cell, the A/C pair keeps it.
    val ms = Chunk(
      matched("A", 0, 24, Seq(1.0), 0.0),
      matched("A", 12, 24, Seq(2.0), 0.0),
      matched("B", 0, 24, Seq(3.0), 0.0),
      matched("C", 0, 24, Seq(1.0), 0.0),
      matched("C", 12, 24, Seq(1.0), 0.0)
    )
    val nOf = (a: String, b: String) => Scorer.pairCells(a, b, ms).map(_.n).sum
    assertEquals(nOf("A", "B"), 1L)
    assertEquals(nOf("A", "C"), 2L)
    assertEquals(Scorer.common(Set("A", "B", "C"), ms).size, 3)
  }

  test("twelve_z_ranking_uses_12z_inits_only") {
    // A runs at 00/12 UTC, B four times a day.
    val ms = Chunk.from(
      Seq(0L, 12L).map(r => matched("A", r, 36, Seq(1.0), 0.0)) ++
        Seq(0L, 6L, 12L, 18L).map(r => matched("B", r, 36, Seq(2.0), 0.0))
    )
    val ranked = Scorer.common(Set("A", "B"), Scorer.twelveZ(ms))
    assertEquals(ranked.map(m => m.provider -> m.runInit).toSet, Set("A" -> at(12), "B" -> at(12)))
  }

  test("issued_skill_charges_latency") {
    // Both have 18Z (day −1) and 00Z runs; at the 06Z issue, A's 00Z is out (03Z), B's is not (07Z).
    def row(p: String, runH: Long, availH: Long) =
      RunIndexRow(p, at(runH), Some(at(availH)), None, RunState.Sampled, None, Some(at(availH)))
    val runs = Chunk(row("A", -6, -3), row("A", 0, 3), row("B", -6, -1), row("B", 0, 7))
    val ms = Chunk(
      matched("A", -6, 12, Seq(4.0), 0.0),
      matched("A", 0, 12, Seq(1.0), 0.0),
      matched("B", -6, 12, Seq(3.0), 0.0),
      matched("B", 0, 12, Seq(1.0), 0.0),
      matched("A", 0, 6, Seq(1.0), 0.0) // valid at the issue time: not a forecast any more
    )
    val issued = Scorer.issued(at(6), runs, ms)
    assertEquals(
      issued.map(m => (m.provider, m.runInit, m.leadH)).toSet,
      Set(("A", at(0), 6), ("B", at(-6), 6))
    )
    val crps = Scorer.window(Scorer.cells(issued)).map((k, s) => k._1 -> s.crps)
    assertEquals(crps, Map("A" -> 1.0, "B" -> 3.0))
  }

  test("late_provider_ranked_on_shared_days") {
    // C joins on day 5; A is ranked on days 5–9 only, where its error differs from the full window.
    val ms = Chunk.from(
      (0 until 10).map(d =>
        matched("A", d * 24L, d * 24L + 12, Seq(if d < 5 then 4.0 else 1.0), 0.0)
      ) ++
        (5 until 10).map(d => matched("C", d * 24L, d * 24L + 12, Seq(2.0), 0.0))
    )
    val ranked = Scorer.common(Set("A", "C"), ms)
    assertEquals(ranked.filter(_.provider == "A").map(_.day).min, LocalDate.of(2026, 7, 6))
    val crps = Scorer.window(Scorer.cells(ranked)).map((k, s) => k._1 -> s.crps)
    assertEquals(crps, Map("A" -> 1.0, "C" -> 2.0))
    assertEquals(Monoid.fold(Scorer.cells(ms).filter(_.provider == "A").map(Score.of)).crps, 2.5)
  }

  test("bootstrap_interval_containing_zero_is_tie") {
    val d0 = LocalDate.of(2026, 7, 1)
    def day(i: Int, s: Double) =
      PairCell("A", "B", "P", Variable.WindSpeed10m, 12, d0.plusDays(i), 4, s, s * s, 0, 1, 1)
    val noisy = Chunk.from((0 until 30).map(d => day(d, if d % 2 == 0 then 1.0 else -1.2)))
    val tie = Scorer.bootstrap(noisy)
    assert(tie.low < 0 && tie.high > 0, tie)
    assertEquals(tie.verdict, Verdict.Tie)
    val better = Scorer.bootstrap(Chunk.from((0 until 30).map(d => day(d, -1.0 - d % 3))))
    assertEquals(better.verdict, Verdict.AWins)
    assertEquals(Scorer.bootstrap(noisy), tie) // seeded: the same cells give the same interval
  }
