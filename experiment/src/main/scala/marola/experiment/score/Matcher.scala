package marola.experiment.score

import java.time.{Instant, LocalDate, ZoneOffset}

import kyo.Chunk

import marola.experiment.schema.*

/** One run's forecast at one point and valid time, its K strided members, and the observation. */
final case class Matched(
    provider: String,
    runInit: Instant,
    point: String,
    variable: Variable,
    validTime: Instant,
    leadH: Int,
    members: Seq[Double],
    observed: Double
) derives CanEqual:
  def day: LocalDate = LocalDate.ofInstant(validTime, ZoneOffset.UTC)
  def score: Score = Score.of(members, observed)

/** A forecast at a ground-truth point, with its observation when one is in tolerance. */
final case class Prediction(
    provider: String,
    runInit: Instant,
    point: String,
    instrument: String,
    variable: Variable,
    validTime: Instant,
    leadH: Int,
    members: Seq[Double],
    observed: Option[Double]
) derives CanEqual

/** §5.2's pullback: only (point, valid time) pairs a forecast with an observation (§5.5). */
object Matcher:
  val MetarToleranceS: Long = 10 * 60

  def pairs(
      forecasts: Chunk[ForecastSample],
      observations: Chunk[ObservationSample],
      points: Chunk[SamplingPoint],
      instruments: Chunk[Instrument],
      k: Int
  ): Chunk[Matched] =
    predictions(forecasts, observations, points, instruments, k).collect {
      case Prediction(provider, run, point, _, variable, valid, lead, members, Some(obs)) =>
        Matched(provider, run, point, variable, valid, lead, members, obs)
    }

  def predictions(
      forecasts: Chunk[ForecastSample],
      observations: Chunk[ObservationSample],
      points: Chunk[SamplingPoint],
      instruments: Chunk[Instrument],
      k: Int
  ): Chunk[Prediction] =
    val byId = instruments.map(i => i.id -> i).toMap
    val instrument = points.flatMap(p => p.instrument.flatMap(byId.get).map(p.id -> _)).toMap
    val observed = observations.filter(_.qcPassed).groupBy(o => (o.instrument, o.variable))
    forecasts
      .groupBy(f => (f.provider, f.runInit, f.point, f.variable, f.validTime, f.leadH))
      .toSeq
      .flatMap {
        case ((provider, run, point, variable, valid, lead), members) =>
          instrument.get(point).map { inst =>
            val tolerance = if inst.network == Network.Metar then MetarToleranceS else 0L
            val obs = observed
              .getOrElse((inst.id, variable), Chunk.empty)
              .map(o => (math.abs(o.validTime.getEpochSecond - valid.getEpochSecond), o.value))
              .filter(_._1 <= tolerance)
              .minByOption(_._1)
            val values = Score.strided(members.sortBy(_.member.getOrElse(0)).map(_.value), k)
            Prediction(provider, run, point, inst.id, variable, valid, lead, values, obs.map(_._2))
          }
      }
      .to(Chunk)
