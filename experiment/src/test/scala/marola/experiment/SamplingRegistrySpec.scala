package marola.experiment

import java.time.LocalDate

import kyo.*

import marola.experiment.Protocols.Invalid
import marola.experiment.schema.PointKind

class SamplingRegistrySpec extends munit.FunSuite:

  private val gt = GroundTruth.bundled.fold(es => fail(s"ground-truth.json refused: $es"), identity)

  private def point(
      id: String,
      kind: String = "station",
      instrument: String = "\"sc-sbfl\"",
      lat: Double = -27.671,
      lon: Double = -48.547,
      retired: String = "null"
  ): String =
    s"""{ "id": "$id", "lat": $lat, "lon": $lon, "kind": "$kind", "instrument": $instrument,
       |  "added_on": "2026-10-09", "retired_on": $retired }""".stripMargin

  private def parse(points: String*) = SamplingRegistry.parse(points.mkString("[", ",", "]"), gt)

  test("bundled_file_is_valid") {
    val ps = SamplingRegistry.bundled(gt).fold(es => fail(s"refused: $es"), identity)
    assert(ps.forall(p => p.kind == PointKind.Station && SamplingRegistry.scorable(p)))
    assertEquals(ps.flatMap(_.instrument).toSet, ps.map(_.id).toSet)
  }

  test("station_point_needs_ground_truth") {
    assertEquals(
      parse(point("a", instrument = "null"), point("b", instrument = "\"sc-zzzz\"")),
      Left(Vector(Invalid.NoGroundTruth("a"), Invalid.NoGroundTruth("b")))
    )
    assertEquals(parse(point("d", lat = -27.6)), Left(Vector(Invalid.NotAtInstrument("d"))))
  }

  test("coast_point_never_scored") {
    val ps =
      parse(point("joaquina", kind = "coast", instrument = "null", lat = -27.63, lon = -48.45))
        .fold(es => fail(s"refused: $es"), identity)
    assert(!SamplingRegistry.scorable(ps.head))
  }

  test("retired_point_keeps_rows") {
    val retired = parse(point("a", retired = "\"2026-12-31\""))
      .fold(es => fail(s"refused: $es"), _.head)
    assertEquals(retired.addedOn, LocalDate.parse("2026-10-09"))
    assertEquals(retired.retiredOn, Some(LocalDate.parse("2026-12-31")))
    assert(!SamplingRegistry.sampledOn(retired, LocalDate.parse("2026-10-08")))
    assert(SamplingRegistry.sampledOn(retired, LocalDate.parse("2026-12-31")))
    assert(!SamplingRegistry.sampledOn(retired, LocalDate.parse("2027-01-01")))
    assertEquals(
      parse(point("b", retired = "\"2026-10-01\"")),
      Left(Vector(Invalid.RetiredBeforeAdded("b")))
    )
  }
end SamplingRegistrySpec
