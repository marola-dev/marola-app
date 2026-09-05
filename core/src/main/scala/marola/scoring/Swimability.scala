package marola.scoring

import marola.model.{HourlyConditions, JellyfishRisk, WhaleSightingLikelihood}

/**
 * Pure decision logic over one already-fetched hourly forecast slice — no I/O, no Kyo effect type,
 * trivially unit-testable (see `SwimabilitySpec`). Same split as `AGENTS.md`'s code-style rule:
 * fetch the data at the effect boundary, score it here.
 *
 * The jellyfish part is a heuristic, not a forecast: there is no free (or, as far as could be
 * found, any) public jellyfish-bloom API. The signals used below — warm sea surface temperature,
 * weak wind, calm seas, weak current — are the commonly cited ecological correlates for jellyfish
 * accumulating near shore, not a validated model. Treat `JellyfishRisk` as "worth a visual check
 * before wading in", not a guarantee either way. See docs/ARCHITECTURE.md's "Future work" section
 * for the plan to calibrate this against real user-reported sightings.
 */
object Swimability:

  private val ComfortableSeaTempMinC = 20.0
  private val ComfortableSeaTempMaxC = 27.0
  private val CalmWaveHeightM = 0.6
  private val RoughWaveHeightM = 1.5
  private val CalmWindKmh = 15.0
  private val StrongWindKmh = 30.0
  private val JellyfishWarmSeaTempC = 24.0
  private val JellyfishCalmCurrentKmh = 2.0
  private val HeavyRainChancePct = 60.0

  // Humpback whales migrate along the Brazilian coast to breed/calve in warmer water roughly
  // July-November (austral winter/spring), peaking around August-September — e.g. Instituto
  // Baleia Jubarte's Abrolhos-bank season runs July-November. This is a calendar fact, not
  // per-hour data, so it's the one signal below that doesn't come from Open-Meteo.
  private val WhaleSeasonMonths: Set[Int] = Set(7, 8, 9, 10, 11)
  private val WhaleCalmWaveHeightM = 1.0 // rougher than the swim-comfort threshold: you just
  private val WhaleCalmWindKmh = 20.0 // need to *spot* a blow/breach, not swim in it

  def jellyfishRisk(hour: HourlyConditions): JellyfishRisk =
    val signals = List(
      hour.seaTempC.exists(_ >= JellyfishWarmSeaTempC),
      hour.windSpeedKmh.exists(_ <= CalmWindKmh),
      hour.waveHeightM.exists(_ <= CalmWaveHeightM),
      hour.currentVelocityKmh.exists(_ <= JellyfishCalmCurrentKmh)
    )
    signals.count(identity) match
      case n if n >= 3 => JellyfishRisk.High
      case 2           => JellyfishRisk.Moderate
      case _           => JellyfishRisk.Low

  /**
   * Informational only — unlike `jellyfishRisk`, this never feeds into `score`: whether you might
   * spot a whale doesn't make an hour more or less safe/pleasant to swim in. Daylight is a hard
   * requirement (you can't spot a blow/breach in the dark), then in-season + calm-enough seas
   * upgrade it from Low.
   */
  def whaleSightingLikelihood(hour: HourlyConditions): WhaleSightingLikelihood =
    val inSeason = WhaleSeasonMonths.contains(hour.time.getMonthValue)
    if !inSeason || !hour.isDaylight.contains(true) then WhaleSightingLikelihood.Low
    else
      val goodVisibility = List(
        hour.windSpeedKmh.exists(_ <= WhaleCalmWindKmh),
        hour.waveHeightM.exists(_ <= WhaleCalmWaveHeightM)
      ).count(identity)
      goodVisibility match
        case 2 => WhaleSightingLikelihood.High
        case 1 => WhaleSightingLikelihood.Moderate
        case _ => WhaleSightingLikelihood.Low

  private def waveDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.waveHeightM match
      case Some(h) if h >= RoughWaveHeightM => (-40, Some(f"rough seas ($h%.1fm waves)"))
      case Some(h) if h >= CalmWaveHeightM  => (-15, Some(f"choppy ($h%.1fm waves)"))
      case Some(_)                          => (0, None)
      case None                             => (-5, Some("no wave data"))

  private def windDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.windSpeedKmh match
      case Some(w) if w >= StrongWindKmh => (-25, Some(f"strong wind (${w}%.0fkm/h)"))
      case Some(w) if w >= CalmWindKmh   => (-10, Some(f"breezy (${w}%.0fkm/h)"))
      case Some(_)                       => (0, None)
      case None                          => (-5, Some("no wind data"))

  private def seaTempDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.seaTempC match
      case Some(t) if t < ComfortableSeaTempMinC => (-20, Some(f"cold water (${t}%.1f°C)"))
      case Some(t) if t > ComfortableSeaTempMaxC => (-5, Some(f"warm water (${t}%.1f°C)"))
      case Some(_)                               => (0, None)
      case None                                  => (-5, Some("no sea temperature data"))

  private def precipitationDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.precipitationProbabilityPct match
      case Some(p) if p >= HeavyRainChancePct => (-10, Some(f"${p}%.0f%% chance of rain"))
      case _                                  => (0, None)

  private def jellyfishDelta(hour: HourlyConditions): (Int, Option[String]) =
    jellyfishRisk(hour) match
      case JellyfishRisk.High     => (-25, Some("elevated jellyfish likelihood"))
      case JellyfishRisk.Moderate => (-10, Some("some jellyfish likelihood"))
      case JellyfishRisk.Low      => (0, None)

  /**
   * 0-100, higher = better conditions for open-water swimming, plus the reasons behind any
   * deduction (empty when conditions are simply good).
   */
  def score(hour: HourlyConditions): (Int, List[String]) =
    val deltas = List(
      waveDelta(hour),
      windDelta(hour),
      seaTempDelta(hour),
      precipitationDelta(hour),
      jellyfishDelta(hour)
    )
    val total = (100 + deltas.map(_._1).sum).max(0).min(100)
    (total, deltas.flatMap(_._2))
