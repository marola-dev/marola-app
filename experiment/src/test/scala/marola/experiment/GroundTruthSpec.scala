package marola.experiment

import marola.experiment.GroundTruth.{Invalid, Kind}

class GroundTruthSpec extends munit.FunSuite:

  private def point(
      id: String,
      state: String = "SC",
      kind: String = "metar",
      station: String = "SBFL",
      lat: String = "-27.671",
      lon: String = "-48.547",
      status: String = "candidate",
      checked: String = """{ "source": "https://example.org/stationinfo", "on": "2026-10-09" }"""
  ): String =
    s"""{ "id": "$id", "state": "$state", "name": "n", "kind": "$kind", "station": "$station",
       |  "lat": $lat, "lon": $lon, "elevation_m": null, "anemometer_m": null,
       |  "exposure": "e", "status": "$status", "checked": $checked }""".stripMargin

  private def file(frozen: String, points: String*): String =
    s"""{ "thresholds": { "history_years": 5, "strong_ms": 10.8, "strong_hours_per_year": 200,
       |  "gale_ms": 17.2, "gale_days_per_year": 5, "frozen": $frozen },
       |  "points": [${points.mkString(",")}], "deviations": [] }""".stripMargin

  private def errors(text: String): Vector[Invalid] = GroundTruth.parse(text) match
    case Left(es)  => es
    case Right(gt) => fail(s"expected a refusal, got $gt")

  test("bundled_file_is_valid") {
    val gt =
      GroundTruth.bundled.fold(es => fail(s"bundled ground-truth.json refused: $es"), identity)
    assertEquals(gt.points.map(_.state).toSet, GroundTruth.States)
    // No point is scored while #723's thresholds are placeholders.
    assertEquals(gt.thresholds.frozen, None)
    assertEquals(gt.scored, Vector.empty)
  }

  test("duplicate_id_rejected") {
    assertEquals(errors(file("null", point("a"), point("a"))), Vector(Invalid.DuplicateId("a")))
  }

  test("station_code_format_per_kind") {
    assertEquals(
      errors(
        file("null", point("m", station = "A606"), point("i", kind = "inmet", station = "SBFL"))
      ),
      Vector(
        Invalid.BadStationCode("m", "A606", Kind.Metar),
        Invalid.BadStationCode("i", "SBFL", Kind.Inmet)
      )
    )
  }

  test("coordinates_outside_brazil_rejected") {
    // An unsigned latitude is the usual slip: 27.671 N is in the Atlantic off Africa.
    assertEquals(
      errors(file("null", point("a", lat = "27.671"))),
      Vector(Invalid.OutsideBrazil("a"))
    )
    assertEquals(errors(file("null", point("b", lon = "null"))), Vector(Invalid.OutsideBrazil("b")))
  }

  test("qualified_needs_checked_coordinates") {
    assertEquals(
      errors(file("\"2026-11-01\"", point("a", status = "qualified", checked = "null"))),
      Vector(Invalid.UncheckedCoordinates("a"))
    )
    assertEquals(
      errors(file("\"2026-11-01\"", point("b", status = "scored", lat = "null", lon = "null"))),
      Vector(Invalid.UncheckedCoordinates("b"))
    )
  }

  test("qualified_needs_frozen_thresholds") {
    assertEquals(
      errors(file("null", point("a", status = "qualified"))),
      Vector(Invalid.ThresholdsNotFrozen("a"))
    )
    assert(GroundTruth.parse(file("\"2026-11-01\"", point("a", status = "qualified"))).isRight)
  }

  test("one_scored_station_per_kind_and_state") {
    val twoMetars = file(
      "\"2026-11-01\"",
      point("a", status = "scored"),
      point("b", station = "SBNF", status = "scored"),
      point("c", kind = "inmet", station = "A806", status = "scored")
    )
    assertEquals(errors(twoMetars), Vector(Invalid.TooManyScored("SC", Kind.Metar)))
  }

  test("unknown status or kind is malformed, not defaulted") {
    errors(file("null", point("a", status = "chosen"))) match
      case Vector(Invalid.Malformed(detail)) => assert(detail.contains("a.status"), detail)
      case other                             => fail(s"unexpected: $other")
  }
