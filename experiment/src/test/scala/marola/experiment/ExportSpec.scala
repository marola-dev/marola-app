package marola.experiment

import java.time.Instant

import scala.io.Source
import scala.util.Using

import kyo.*

import marola.experiment.forecast.OpenMeteoFixtures.{protocol, sbfl}
import marola.experiment.schema.*
import marola.experiment.score.Score
import marola.json.JsonValue

class ExportSpec extends munit.FunSuite:

  private val gt = GroundTruth.bundled.toOption.get
  private val scored = gt.copy(points =
    gt.points.map(p =>
      if p.id == sbfl.instrument.get then p.copy(status = GroundTruth.Status.Scored) else p
    )
  )
  private val now = Instant.parse("2026-10-10T00:17:00Z")

  private val window: Export.Window = Map(
    ("ifs", sbfl.id, Variable.WindSpeed10m, 24, "all") -> Score.of(Seq(2.0), 3.0),
    ("ifs", sbfl.id, Variable.WindSpeed10m, 30, "all") -> Score.of(Seq(9.0), 3.0),
    ("ifs", "elsewhere", Variable.WindSpeed10m, 24, "all") -> Score.of(Seq(9.0), 3.0)
  )

  private def keys(j: JsonValue): Set[String] = j match
    case JsonValue.JObject(fields) => fields.keySet
    case other                     => fail(s"not an object: $other")

  test("export_matches_schema") {
    val card = Export.scorecard(scored, Chunk(sbfl), protocol, window, now).get
    // Only the scored point, only a scorecard day's lead.
    assertEquals(
      card.rows,
      Chunk(ScorecardRow("ifs", Variable.WindSpeed10m, 1, 1, -1.0, 1.0, 1.0, true))
    )

    val schema = JsonValue.parse(
      Using.resource(Source.fromResource("experiment/scorecard.schema.json"))(_.mkString)
    )
    val written = JsonValue.parse(Json.encode(card))
    def required(s: JsonValue) = s("required").arr.flatMap(_.str).toSet
    assertEquals(keys(written), required(schema))
    assertEquals(keys(written("rows").arr.head), required(schema("properties")("rows")("items")))
    assertEquals(Json.decode[Scorecard](Json.encode(card)).getOrThrow, card)
  }

  test("no_scorecard_without_a_scored_point") {
    assertEquals(Export.scorecard(gt, Chunk(sbfl), protocol, window, now), None)
  }

  test("metrics_keep_headline_leads") {
    val m = Export.metrics(protocol, window)
    assertEquals(m.keySet, Set(24))
    assert(m(24).contains(s"rmse.wind_speed_10m.90d.${sbfl.id}.ifs"), m)
  }
end ExportSpec
