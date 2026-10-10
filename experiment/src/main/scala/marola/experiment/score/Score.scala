package marola.experiment.score

import scala.math.{abs, sqrt}

import marola.experiment.schema.{PairCell, ScoreCell}

/** MIP-0083 §5.2: scores are sums, so any window is a fold of daily cells in any order. */
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A

object Monoid:
  def fold[A](as: Iterable[A])(using m: Monoid[A]): A = as.foldLeft(m.empty)(m.combine)

/** A `ScoreCell`'s sums without its key; every metric is read off at the end (§5.5). */
final case class Score(
    n: Long,
    sumE: Double,
    sumSqE: Double,
    sumAbsE: Double,
    sumCrps: Double,
    sumSqSpread: Double
) derives CanEqual:
  def bias: Double = sumE / n
  def rmse: Double = sqrt(sumSqE / n)
  def crps: Double = sumCrps / n
  def spread: Double = sqrt(sumSqSpread / n)

  /** MSE − s²/K: the ensemble-mean RMSE an infinite ensemble would have. */
  def debiasedEnsMeanRmse(k: Int): Double = sqrt(sumSqE / n - sumSqSpread / n / k)

  // WeatherBench 2's (K+1)/K factor, so a calibrated K-member ensemble scores 1.
  def spreadSkill(k: Int): Double = sqrt((k + 1.0) / k * sumSqSpread / n) / rmse

object Score:
  given Monoid[Score] with
    def empty: Score = Score(0, 0, 0, 0, 0, 0)
    def combine(a: Score, b: Score): Score = Score(
      a.n + b.n,
      a.sumE + b.sumE,
      a.sumSqE + b.sumSqE,
      a.sumAbsE + b.sumAbsE,
      a.sumCrps + b.sumCrps,
      a.sumSqSpread + b.sumSqSpread
    )

  def of(cell: ScoreCell): Score =
    Score(cell.n, cell.sumE, cell.sumSqE, cell.sumAbsE, cell.sumCrps, cell.sumSqSpread)

  /** One forecast against one observation; a deterministic run is a one-member ensemble. */
  def of(members: Seq[Double], observed: Double): Score =
    val e = mean(members) - observed
    Score(1, e, e * e, abs(e), fairCrps(members, observed), variance(members))

  def mean(xs: Seq[Double]): Double = xs.sum / xs.size

  /** Unbiased; zero for one member. */
  def variance(xs: Seq[Double]): Double =
    if xs.size < 2 then 0.0
    else
      val m = mean(xs)
      xs.map(x => (x - m) * (x - m)).sum / (xs.size - 1)

  /**
   * Fair CRPS (Zamo & Naveau 2018, WeatherBench-X's `CRPSEnsemble(fair = true)`): E|X − y| − Σᵢⱼ|xᵢ
   * − xⱼ| / (2K(K − 1)). WeatherBench-X refuses K = 1; §5.14 rule 7 makes it the absolute error.
   */
  def fairCrps(members: Seq[Double], observed: Double): Double =
    val k = members.size
    val skill = members.map(x => abs(x - observed)).sum / k
    if k < 2 then skill
    else skill - members.map(a => members.map(b => abs(a - b)).sum).sum / (2.0 * k * (k - 1))

  /** K members at strided positions, as OWB draws them (§5.5); fewer than K are kept whole. */
  def strided[A](members: Seq[A], k: Int): Seq[A] =
    if members.size <= k then members else (0 until k).map(i => members(i * members.size / k))

/**
 * A `PairCell`'s sums. `d` is CRPS_a − CRPS_b, so a negative mean favours A; the error correlation
 * is uncentred (no Σeₐ, Σe_b in the cell), the second-moment form forecast combination uses.
 */
final case class PairScore(
    n: Long,
    sumD: Double,
    sumSqD: Double,
    sumEaEb: Double,
    sumSqEa: Double,
    sumSqEb: Double
) derives CanEqual:
  def meanD: Double = sumD / n
  def errorCorrelation: Double = sumEaEb / sqrt(sumSqEa * sumSqEb)

object PairScore:
  given Monoid[PairScore] with
    def empty: PairScore = PairScore(0, 0, 0, 0, 0, 0)
    def combine(a: PairScore, b: PairScore): PairScore = PairScore(
      a.n + b.n,
      a.sumD + b.sumD,
      a.sumSqD + b.sumSqD,
      a.sumEaEb + b.sumEaEb,
      a.sumSqEa + b.sumSqEa,
      a.sumSqEb + b.sumSqEb
    )

  def of(cell: PairCell): PairScore =
    PairScore(cell.n, cell.sumD, cell.sumSqD, cell.sumEaEb, cell.sumSqEa, cell.sumSqEb)
