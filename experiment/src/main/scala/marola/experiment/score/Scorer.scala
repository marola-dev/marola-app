package marola.experiment.score

import java.time.{Duration, Instant, ZoneOffset}
import java.util.SplittableRandom

import kyo.Chunk

import marola.experiment.schema.*

enum Verdict derives CanEqual:
  case AWins, BWins, Tie

/** Mean CRPS_a − CRPS_b with its 95% bootstrap interval; an interval containing zero is a tie. */
final case class HeadToHead(meanD: Double, low: Double, high: Double) derives CanEqual:
  def verdict: Verdict =
    if high < 0 then Verdict.AWins else if low > 0 then Verdict.BWins else Verdict.Tie

/** §5.14's model and issued skill over matched pairs; pure, no effect. */
object Scorer:
  private type Key = (String, Variable, Instant, Int, Instant)
  private def key(m: Matched): Key = (m.point, m.variable, m.runInit, m.leadH, m.validTime)

  // The day is the valid time's: errors on one day are correlated across leads and points (rule 8).
  def cells(matched: Chunk[Matched], bin: Matched => String = _ => "all"): Chunk[ScoreCell] =
    matched
      .groupBy(m => (m.provider, m.point, m.variable, m.leadH, bin(m), m.day))
      .toSeq
      .map {
        case ((provider, point, variable, lead, b, day), ms) =>
          val s = Monoid.fold(ms.map(_.score))
          ScoreCell(
            provider,
            point,
            variable,
            lead,
            b,
            day,
            s.n,
            s.sumE,
            s.sumSqE,
            s.sumAbsE,
            s.sumCrps,
            s.sumSqSpread
          )
      }
      .to(Chunk)

  /** Daily cells folded into one score per (provider, point, variable, lead, bin). */
  def window(cells: Chunk[ScoreCell]): Map[(String, String, Variable, Int, String), Score] =
    cells
      .groupBy(c => (c.provider, c.point, c.variable, c.leadH, c.bin))
      .map((k, cs) => k -> Monoid.fold(cs.map(Score.of)))

  /** Rule 3: A against B only on the (point, run, lead, valid time) cells both have. */
  def pairCells(a: String, b: String, matched: Chunk[Matched]): Chunk[PairCell] =
    val bs = matched.filter(_.provider == b).map(m => key(m) -> m).toMap
    matched
      .filter(_.provider == a)
      .flatMap(ma => bs.get(key(ma)).map(ma -> _))
      .groupBy((ma, _) => (ma.point, ma.variable, ma.leadH, ma.day))
      .toSeq
      .map {
        case ((point, variable, lead, day), ps) =>
          val s = Monoid.fold(ps.map(paired))
          PairCell(
            a,
            b,
            point,
            variable,
            lead,
            day,
            s.n,
            s.sumD,
            s.sumSqD,
            s.sumEaEb,
            s.sumSqEa,
            s.sumSqEb
          )
      }
      .to(Chunk)

  private def paired(a: Matched, b: Matched): PairScore =
    val (sa, sb) = (a.score, b.score)
    val d = sa.sumCrps - sb.sumCrps
    PairScore(1, d, d * d, sa.sumE * sb.sumE, sa.sumSqE, sb.sumSqE)

  /** Rules 3 and 6: the ranking's pairs, kept only where every ranked provider has one. */
  def common(providers: Set[String], matched: Chunk[Matched]): Chunk[Matched] =
    val ranked = matched.filter(m => providers.contains(m.provider))
    val shared = ranked.groupBy(key).filter((_, ms) => ms.map(_.provider).toSet == providers).keySet
    ranked.filter(m => shared.contains(key(m)))

  /** Rule 4: the 12Z ranking reads 12Z inits only. */
  def twelveZ(matched: Chunk[Matched]): Chunk[Matched] =
    matched.filter(_.runInit.atZone(ZoneOffset.UTC).getHour == 12)

  /**
   * Rule 5's issued skill: each provider's latest run available at `issuedAt`, with the lead
   * counted from the issue time, so a slow publisher scores an older run.
   */
  def issued(issuedAt: Instant, runs: Chunk[RunIndexRow], matched: Chunk[Matched]): Chunk[Matched] =
    val latest = runs
      .filter(_.availableAt.exists(!_.isAfter(issuedAt)))
      .groupBy(_.provider)
      .map((p, rs) => p -> rs.map(_.runInit).max)
    matched
      .filter(m => latest.get(m.provider).contains(m.runInit) && m.validTime.isAfter(issuedAt))
      .map(m => m.copy(leadH = Duration.between(issuedAt, m.validTime).toHours.toInt))

  /** Rule 8: a paired bootstrap that resamples whole days of combined pair sums. */
  def bootstrap(cells: Chunk[PairCell], resamples: Int = 2000, seed: Long = 83L): HeadToHead =
    val days = cells
      .groupBy(_.day)
      .toVector
      .sortBy(_._1.toEpochDay)
      .map((_, cs) => Monoid.fold(cs.map(PairScore.of)))
    val rng = SplittableRandom(seed)
    val means = Vector
      .fill(resamples)(Monoid.fold(Vector.fill(days.size)(days(rng.nextInt(days.size)))).meanD)
      .sorted
    def at(q: Double) = means(math.round((resamples - 1) * q).toInt)
    HeadToHead(Monoid.fold(days).meanD, at(0.025), at(0.975))
