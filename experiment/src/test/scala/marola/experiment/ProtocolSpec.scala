package marola.experiment

import kyo.*

import marola.experiment.Protocols.Invalid
import marola.experiment.schema.Route

class ProtocolSpec extends munit.FunSuite:

  private def row(id: String, route: String, model: String): String =
    s"""{ "id": "$id", "route": "$route", "model_id": "$model", "runs_utc": [0, 12],
       |  "members": 64, "max_lead_h": 360, "added_on": "2026-11-01" }""".stripMargin

  private val bundledRows: String =
    Protocols.bundled("providers.json").fold(es => fail(s"$es"), identity)

  private def withRow(r: String): String = bundledRows.trim.stripSuffix("]") + "," + r + "]"

  test("cadence_and_window_read_from_file") {
    val p = Protocols.bundledProtocol.fold(es => fail(s"protocol.json refused: $es"), identity)
    assertEquals((p.windowDays, p.cadenceH, p.ensembleK, p.minN), (90, 4, 16, 30))
    assertEquals(p.leadsH, Chunk.from(6 to 360 by 6))
    assertEquals(p.scorecardDays, Chunk(1, 3, 5, 7, 10, 15))
    val providers =
      Protocols.bundledProviders.fold(es => fail(s"providers.json refused: $es"), identity)
    assertEquals(
      providers.map(p => (p.id, p.route, p.modelId)),
      Chunk(
        ("ifs", Route.OpenMeteo, "ecmwf_ifs"),
        ("gfs", Route.OpenMeteo, "ncep_gfs013")
      )
    )
  }

  test("unknown_provider_is_malformed") {
    Protocols.providers(withRow(row("wn2", "open_meteo_v2", "x"))) match
      case Left(Vector(Invalid.Malformed("providers.json", detail))) =>
        assert(detail.contains("open_meteo_v2"), detail)
      case other => fail(s"unexpected: $other")
    assertEquals(
      Protocols.providers(withRow(row("ifs", "open_meteo", "ecmwf_ifs"))),
      Left(Vector(Invalid.DuplicateId("providers.json", "ifs")))
    )
  }

  test("new_open_meteo_row_needs_no_code") {
    val ps = Protocols
      .providers(withRow(row("wn2", "open_meteo", "google_weathernext2_ensemble")))
      .fold(es => fail(s"refused: $es"), identity)
    assertEquals(ps.map(_.id), Chunk("ifs", "gfs", "wn2"))
    assertEquals(ps.last.route, Route.OpenMeteo)
  }
end ProtocolSpec
