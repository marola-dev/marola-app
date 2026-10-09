package marola.water

import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate

import kyo.*

import marola.http.Http
import marola.model.Coordinates

/** From INEMA's saved listing page and a real bulletin PDF to `List[SamplingPoint]` (#44). */
class InemaBaWaterQualityClientSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def resource(name: String): Array[Byte] =
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    try stream.readAllBytes()
    finally stream.close()

  private val Page = InemaBaWaterQualityClient.ListingPage
  private val Files = "https://www.ba.gov.br/inema/sites/site-inema/files/2026-10/"
  private val SalvadorBulletin =
    Files + "Boletim%20de%20Balneabilidade%20para%20%28Litoral%20de%20Salvador%29%20emitido%20em%20%2802_10_2026%29.pdf"
  private val Today = LocalDate.of(2026, 10, 6)

  private val pageHtml = String(resource("inema-page-qualidade-das-praias-2026-10-06.html"), UTF_8)
  // Bulletin 13/2025: the same table layout as the 2026 bulletins, standing in for one of them.
  private val bulletinPdf = resource("inema-boletim-salvador-13-2025.pdf")

  private class Pages(pages: Map[String, String]) extends Http.Transport:
    def send(request: HttpRequest): Http.Response =
      pages.get(request.uri.toString).fold(Http.Response(404, ""))(Http.Response(200, _))

  private class Pdfs(pdfs: Map[String, Array[Byte]]) extends Http.BinaryTransport:
    var requested: List[String] = Nil
    def send(request: HttpRequest): Http.BytesResponse =
      requested = requested :+ request.uri.toString
      pdfs
        .get(request.uri.toString)
        .fold(Http.BytesResponse(404, Array.emptyByteArray))(Http.BytesResponse(200, _))

  private def fetch(
      pages: Map[String, String],
      pdfs: Pdfs,
      today: LocalDate = Today
  ): List[SamplingPoint] =
    val client = InemaBaWaterQualityClient(Page, () => today)
    Http.withTransport(Pages(pages)) {
      Http.withBinaryTransport(pdfs)(Sync.Unsafe.evalOrThrow(client.samplingPoints))
    }

  test("the listing page yields the seven dated regional bulletins, and not the organogram") {
    val found = InemaBaWaterQualityClient.bulletins(pageHtml)
    assertEquals(found.size, 7)
    assertEquals(found.map(_.date).distinct, List(LocalDate.of(2026, 10, 2)))
    assert(found.exists(_.url == SalvadorBulletin), found)
    assert(!found.exists(_.url.contains("ORGANOGRAMA")), found)
  }

  test("an older upload name dates by its dd_mm_yyyy too; one with no year is skipped") {
    val html =
      """<a href="https://x/2026-06/Boletim_Salvador_05_06_2026.pdf">a</a>
        |<a href="https://x/2026-04/Balneabilidade_Salvador_17_ABR.pdf">b</a>""".stripMargin
    assertEquals(
      InemaBaWaterQualityClient.bulletins(html).map(_.date),
      List(LocalDate.of(2026, 6, 5))
    )
  }

  test("samples carry the bulletin's issue date, not the day they were fetched") {
    val points = fetch(Map(Page -> pageHtml), Pdfs(Map(SalvadorBulletin -> bulletinPdf)))
    assert(points.nonEmpty)
    assertEquals(
      points.flatMap(_.samples.map(_.sampledOn)).distinct,
      List(LocalDate.of(2026, 10, 2))
    )
    val p = points.find(_.id == "SSA IN 100").getOrElse(fail("no SSA IN 100"))
    assertEquals(p.beachName, "São Tomé de Paripe")
    assertEquals(p.latest.map(_.condition), Some(BathingCondition.Proper))
  }

  test("a regional bulletin that fails costs only its own points") {
    val pdfs = Pdfs(Map(SalvadorBulletin -> bulletinPdf))
    val points = fetch(Map(Page -> pageHtml), pdfs)
    assertEquals(pdfs.requested.size, 7, "every fresh bulletin is tried; six 404 here")
    assert(points.exists(_.id == "SSA IN 100"))
  }

  test("a bulletin past the 45-day rule is never downloaded") {
    val pdfs = Pdfs(Map(SalvadorBulletin -> bulletinPdf))
    assertEquals(fetch(Map(Page -> pageHtml), pdfs, today = LocalDate.of(2026, 11, 17)), Nil)
    assertEquals(pdfs.requested, Nil)
  }

  test("a listing page that cannot be fetched gives no points, never a pinned bulletin") {
    val pdfs = Pdfs(Map(SalvadorBulletin -> bulletinPdf))
    assertEquals(fetch(Map.empty, pdfs), Nil)
    assertEquals(pdfs.requested, Nil)
  }

  test("a point absent from the curated coordinate table is dropped, not defaulted") {
    val points = InemaBaWaterQualityClient.toSamplingPoints(bulletinPdf, LocalDate.of(2026, 10, 2))
    // The fixture parses 38 rows (InemaPdfParserSpec); the curated table only covers 28.
    assert(
      points.size < 38,
      s"expected fewer than 38 points (some codes uncurated), got ${points.size}"
    )
    assert(points.size > 0, "expected at least one real match")
  }

  test("coversOrigin: Salvador yes, Florianópolis no") {
    assert(InemaBaWaterQualityClient.coversOrigin(Coordinates(-12.9777, -38.5016)))
    assert(!InemaBaWaterQualityClient.coversOrigin(Coordinates(-27.6733, -48.4700)))
  }
