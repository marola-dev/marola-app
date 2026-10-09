package marola.experiment.score

import java.time.{Instant, LocalDate}

import kyo.Chunk

import marola.experiment.schema.*
import marola.experiment.score.Fixtures.at

class MatcherSpec extends munit.FunSuite:

  private val instruments = Chunk(
    Instrument("I1", Network.Inmet, -27.6, -48.5, Some(10.0), "1h", "CC-BY"),
    Instrument("I2", Network.Inmet, -26.9, -48.6, Some(10.0), "1h", "CC-BY"),
    Instrument("SBFL", Network.Metar, -27.7, -48.5, Some(10.0), "10min", "public")
  )
  private val points = Chunk(
    SamplingPoint(
      "P1",
      -27.6,
      -48.5,
      PointKind.Station,
      Some("I1"),
      LocalDate.of(2026, 7, 1),
      None
    ),
    SamplingPoint(
      "P2",
      -26.9,
      -48.6,
      PointKind.Station,
      Some("I2"),
      LocalDate.of(2026, 7, 1),
      None
    ),
    SamplingPoint(
      "M",
      -27.7,
      -48.5,
      PointKind.Station,
      Some("SBFL"),
      LocalDate.of(2026, 7, 1),
      None
    )
  )

  private def forecast(point: String, validH: Long, value: Double): ForecastSample =
    ForecastSample(
      "A",
      at(0),
      at(0),
      point,
      at(validH),
      validH.toInt,
      Variable.WindSpeed10m,
      None,
      value,
      -27.6,
      -48.5,
      "https://example.org"
    )

  private def obs(instrument: String, valid: Instant, value: Double): ObservationSample =
    ObservationSample(instrument, valid, Variable.WindSpeed10m, value, "1h", qcPassed = true)

  private def run(fs: Chunk[ForecastSample], os: Chunk[ObservationSample]) =
    Matcher.pairs(fs, os, points, instruments, k = 16)

  test("pairs_only_same_point_and_valid_time") {
    val matched = run(
      Chunk(forecast("P1", 12, 5.0), forecast("P1", 18, 6.0), forecast("P2", 12, 7.0)),
      Chunk(
        obs("I1", at(12), 4.0),
        obs("I1", at(12).plusSeconds(300), 9.0), // INMET pairs on the hour only
        obs("I2", at(18), 8.0),
        obs("I1", at(18), 1.0).copy(qcPassed = false)
      )
    )
    assertEquals(
      matched,
      Chunk(Matched("A", at(0), "P1", Variable.WindSpeed10m, at(12), 12, Seq(5.0), 4.0))
    )
  }

  test("metar_outside_10_min_is_missing") {
    val matched = run(
      Chunk(forecast("M", 6, 5.0), forecast("M", 12, 6.0), forecast("M", 18, 7.0)),
      Chunk(
        obs("SBFL", at(6).minusSeconds(600), 4.0),
        obs("SBFL", at(12).plusSeconds(540), 3.0),
        obs("SBFL", at(12).plusSeconds(60), 2.0), // the nearer report wins
        obs("SBFL", at(18).plusSeconds(660), 1.0)
      )
    )
    assertEquals(
      matched.map(m => m.validTime -> m.observed).toMap,
      Map(at(6) -> 4.0, at(12) -> 2.0)
    )
  }
