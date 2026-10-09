package marola.experiment.score

import kyo.Chunk

import marola.experiment.score.Fixtures.matched

class ScoreMonoidSpec extends munit.FunSuite:

  private val m = summon[Monoid[Score]]
  private val p = summon[Monoid[PairScore]]
  // Dyadic values, so every sum is exact and the laws hold with ==.
  private val a = Score(2, 1.5, 4.25, 2.5, 1.75, 0.5)
  private val b = Score(3, -2.0, 6.0, 3.0, 2.25, 1.0)
  private val c = Score(1, 0.25, 0.0625, 0.25, 0.125, 0.0)
  private val pa = PairScore(2, 0.5, 0.25, 1.0, 2.0, 3.0)
  private val pb = PairScore(1, -1.5, 2.25, -0.5, 4.0, 0.25)
  private val pc = PairScore(4, 0.75, 1.0, 2.0, 1.5, 1.5)

  test("combine_is_associative") {
    assertEquals(m.combine(m.combine(a, b), c), m.combine(a, m.combine(b, c)))
    assertEquals(p.combine(p.combine(pa, pb), pc), p.combine(pa, p.combine(pb, pc)))
  }

  test("empty_is_identity") {
    assertEquals(m.combine(m.empty, a), a)
    assertEquals(m.combine(a, m.empty), a)
    assertEquals(p.combine(p.empty, pa), pa)
    assertEquals(p.combine(pa, p.empty), pa)
  }

  test("combine_is_commutative") {
    assertEquals(m.combine(a, b), m.combine(b, a))
    assertEquals(p.combine(pa, pb), p.combine(pb, pa))
  }

  test("ninety_daily_cells_equal_one_window") {
    val samples = Chunk.from((0 until 90).flatMap { d =>
      Seq(6L, 12L).map(h =>
        matched("A", d * 24L, d * 24L + h, Seq(d % 7 * 0.5, d % 3 * 0.25), (d % 5).toDouble)
      )
    })
    val cells = Scorer.cells(samples)
    assertEquals(cells.map(_.day).distinct.size, 90)
    val direct = Monoid.fold(samples.map(_.score))
    assertEquals(Monoid.fold(Scorer.window(cells).values), direct)
    assertEquals(Monoid.fold(cells.reverse.map(Score.of)), direct)
    assertEquals(direct.n, 180L)
  }

  test("crps_of_single_run_is_abs_error") {
    assertEquals(Score.fairCrps(Seq(3.5), 1.0), 2.5)
    assertEquals(Score.of(Seq(-1.25), 2.0), Score(1, -3.25, 10.5625, 3.25, 3.25, 0.0))
  }

  // Hand-computed from WeatherBench-X's CRPSEnsemble(fair = True) in
  // weatherbenchX/metrics/probabilistic.py (main, read 2026-10-09): CRPSSkill − 0.5 · CRPSSpread,
  // CRPSSpread = Σᵢⱼ|xᵢ − xⱼ| / (M(M − 1)). Members 1, 2, 4 and y = 3: skill (2 + 1 + 1)/3 = 4/3,
  // spread 12/6 = 2 (also its sorted-rank form: 2 · mean((2i − M − 1)xᵢ)/(M − 1) = 2 · 2/2),
  // so CRPS = 4/3 − 1 = 1/3.
  test("fair_crps_matches_weatherbenchx_case") {
    assertEqualsDouble(Score.fairCrps(Seq(1.0, 2.0, 4.0), 3.0), 1.0 / 3, 1e-12)
    assertEqualsDouble(Score.fairCrps(Seq(4.0, 1.0, 2.0), 3.0), 1.0 / 3, 1e-12)
  }

  test("debiased_ens_mean_rmse") {
    // Mean 2, e = 2, s² = 2, K = 2: MSE − s²/K = 4 − 1.
    val s = Score.of(Seq(1.0, 3.0), 0.0)
    assertEquals(s.rmse, 2.0)
    assertEqualsDouble(s.debiasedEnsMeanRmse(2), math.sqrt(3.0), 1e-12)
  }

  test("spread_skill_rebuilt_from_window") {
    // Day 1: e = 2, s² = 2. Day 2: e = 0, s² = 2, so its own ratio is infinite; the window's is
    // sqrt(3/2 · 4/2) / sqrt(4/2).
    val days = Scorer.cells(
      Chunk(matched("A", 0, 12, Seq(1.0, 3.0), 0.0), matched("A", 24, 36, Seq(0.0, 2.0), 1.0))
    )
    assertEquals(days.size, 2)
    val window = Monoid.fold(days.map(Score.of))
    assertEqualsDouble(window.spreadSkill(2), math.sqrt(1.5), 1e-12)
    assert(days.exists(c => Score.of(c).spreadSkill(2).isInfinite))
  }

  test("strided_k16_members") {
    assertEquals(
      Score.strided(0 until 51, 16),
      Seq(0, 3, 6, 9, 12, 15, 19, 22, 25, 28, 31, 35, 38, 41, 44, 47)
    )
    assertEquals(Score.strided(0 until 32, 16), (0 until 32 by 2).toSeq)
    assertEquals(Score.strided(0 until 10, 16), (0 until 10).toSeq)
    assertEquals(Score.strided(Seq(7), 16), Seq(7))
  }

  test("error_correlation_of_identical_errors_is_one") {
    val ms = Chunk(
      matched("A", 0, 6, Seq(3.0), 1.0),
      matched("A", 0, 12, Seq(-1.0), 0.5),
      matched("A", 0, 18, Seq(4.0), 4.25)
    )
    val cells = Scorer.pairCells("A", "B", ms ++ ms.map(_.copy(provider = "B")))
    val s = Monoid.fold(cells.map(PairScore.of))
    assertEquals(s.n, 3L)
    assertEqualsDouble(s.errorCorrelation, 1.0, 1e-12)
    assertEquals(s.meanD, 0.0)
  }
