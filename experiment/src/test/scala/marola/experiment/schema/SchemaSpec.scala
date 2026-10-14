package marola.experiment.schema

import java.time.{Instant, LocalDate}

import scala.io.Source

import kyo.*

class SchemaSpec extends munit.FunSuite:

  private val t0 = Instant.parse("2026-10-09T12:00:00Z")
  private val t1 = Instant.parse("2026-10-10T00:00:00Z")
  private val day0 = LocalDate.parse("2026-10-09")

  // Every Option is Some, so an encoded fixture carries every wire field.
  private val provider = Provider("ifs", Route.OpenMeteo, "ecmwf_ifs", Chunk(0, 12), 1, 360, day0)
  private val instrument =
    Instrument("inmet:A806", Network.Inmet, -27.6, -48.6, Some(10.0), "10min", "CC-BY-4.0")
  private val point =
    SamplingPoint(
      "sc-floripa-sbfl",
      -27.67,
      -48.55,
      PointKind.Station,
      Some("metar:SBFL"),
      day0,
      Some(day0)
    )
  private val protocol =
    Protocol(1, Chunk(Variable.WindSpeed10m), Chunk(24, 48), Chunk(1, 3), 90, 4, 3, 30, Chunk(0, 5))
  private val forecast =
    ForecastSample(
      "ifs",
      t0,
      t0,
      "sc-floripa-sbfl",
      t1,
      12,
      Variable.Temperature2m,
      Some(0),
      21.5,
      -27.6,
      -48.5,
      "u"
    )
  private val observation =
    ObservationSample("metar:SBFL", t1, Variable.WindSpeed10m, 4.1, "10min", true)
  private val run =
    RunIndexRow("ifs", t0, Some(t0), Some("sha"), RunState.Backfilled, Some("late"), Some(t1))
  private val score =
    ScoreCell("ifs", "sc-floripa-sbfl", Variable.WindSpeed10m, 24, "5-10", day0, 3, 1, 2, 3, 4, 5)
  private val pair =
    PairCell("ifs", "gfs", "sc-floripa-sbfl", Variable.WindSpeed10m, 24, day0, 3, 1, 2, 3, 4, 5)
  private val warning = NavyWarning(812, "Sul Oceânica", 8, Some(9), t0, t1, t0, "sha")
  private val row = ScorecardRow("ifs", Variable.WindSpeed10m, 1, 40, 0.1, 1.2, 0.8, false)
  private val scorecard = Scorecard(1, t1, 90, Chunk(row), Chunk("CC-BY-4.0"))

  private def roundTrips[A: Schema](a: A): Unit =
    assertEquals(Json.decode[A](Json.encode(a)).toEither, Right(a))

  private def wireKeys[A: Schema](a: A): Set[String] =
    """"([a-z0-9_]+)":""".r.findAllMatchIn(Json.encode(a)).map(_.group(1)).toSet

  private def resource(name: String): String =
    val src = Source.fromResource(s"experiment/$name")
    try src.mkString
    finally src.close()

  test("every_schema_round_trips") {
    roundTrips(provider)
    roundTrips(instrument)
    roundTrips(point)
    roundTrips(protocol)
    roundTrips(forecast)
    roundTrips(forecast.copy(member = None))
    roundTrips(observation)
    roundTrips(run)
    roundTrips(RunIndexRow("gfs", t0, None, None, RunState.Missing, None, None))
    roundTrips(score)
    roundTrips(pair)
    roundTrips(warning)
    roundTrips(scorecard)
  }

  test("json_schema_matches_checked_in") {
    assertEquals(resource("scorecard.schema.json").trim, ScorecardSchema.json)
    // The schema is snake-cased by hand, so it must name exactly the fields the encoder writes.
    def names(s: Json.JsonSchema): Set[String] = s match
      case o: Json.JsonSchema.Obj => o.properties.flatMap((k, v) => names(v) + k).toSet
      case a: Json.JsonSchema.Arr => names(a.items)
      case _                      => Set.empty
    assertEquals(names(ScorecardSchema.schema), wireKeys(scorecard))
  }

  test("ddl_columns_match_schema_fields") {
    val tables = """(?s)CREATE TABLE (\w+) \((.*?)\);""".r
      .findAllMatchIn(resource("lake.sql"))
      .map(m =>
        m.group(1) -> m
          .group(2)
          .linesIterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(_.takeWhile(_ != ' '))
          .toList
      )
      .toMap
    val expected = Map(
      "experiment_point" -> wireKeys(point),
      "forecast_sample" -> wireKeys(forecast),
      "observation_sample" -> wireKeys(observation),
      "run_index" -> wireKeys(run),
      "score_cell" -> wireKeys(score),
      "pair_cell" -> wireKeys(pair),
      "navy_warning" -> wireKeys(warning)
    )
    assertEquals(tables.keySet, expected.keySet)
    expected.foreach((table, keys) => assertEquals(tables(table).toSet, keys, table))
    tables.foreach((table, cols) => assertEquals(cols.distinct, cols, table))
  }

  test("unknown_label_is_malformed") {
    val json =
      """{"id":"x","route":"nope","model_id":"m","runs_utc":[],"members":1,"max_lead_h":1,"added_on":"2026-10-09"}"""
    Json.decode[Provider](json) match
      case Result.Failure(e: UnknownVariantException) => assertEquals(e.variantName, "nope")
      case other => fail(s"expected a decode failure, got $other")
    assertEquals(
      Json.decode[Variable]("\"temperature_2m\"").toEither,
      Right(Variable.Temperature2m)
    )
    assertEquals(Json.decode[PointKind]("\"upper_air\"").toEither, Right(PointKind.UpperAir))
  }
end SchemaSpec
