package marola.water

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, ZoneId}

import scala.util.Try

import kyo.*

import marola.http.Http
import marola.log.Log
import marola.model.Coordinates

/**
 * Bahia's INEMA bathing-water bulletins (MIP-0031, #44): the listing page links the latest dated
 * PDF per coastal region, found there rather than pinned, parsed by `InemaPdfParser`.
 */
final class InemaBaWaterQualityClient(
    listingPage: String = InemaBaWaterQualityClient.ListingPage,
    today: () => LocalDate = () => LocalDate.now(ZoneId.of("America/Sao_Paulo"))
) extends WaterQualityClient:

  import InemaBaWaterQualityClient.*

  def name: String = "INEMA/BA"

  def samplingPoints: List[SamplingPoint] < Sync =
    orNothing(listingPage) {
      Http.getString(listingPage).map { html =>
        val found = bulletins(html)
        if found.isEmpty then log.warn(s"INEMA: no dated bulletin link on $listingPage")
        val (current, stale) = found.partition(b => WaterQuality.isFresh(b.date, today()))
        stale.foreach(b => log.info(s"INEMA: skipping ${b.url}, dated ${b.date}, past 45 days"))
        // Only Salvador's points are curated today; the other regions' rows drop on the lookup.
        Kyo.foreach(current)(bulletinPoints).map(_.toList.flatten.distinctBy(_.id))
      }
    }

  private def bulletinPoints(b: Bulletin): List[SamplingPoint] < Sync =
    orNothing(b.url)(Http.getBytes(b.url, timeoutSeconds = 30).map(toSamplingPoints(_, b.date)))

  private def orNothing(what: String)(
      points: => List[SamplingPoint] < Sync
  ): List[SamplingPoint] < Sync =
    Abort.run(Abort.catching[Throwable](points)).map {
      case Result.Success(ps) => ps
      case other =>
        log.warn(s"INEMA: $what failed ($other) — no points from it")
        Nil
    }

object InemaBaWaterQualityClient:

  private val log = Log.forName(getClass.getName)

  val ListingPage: String = "https://www.ba.gov.br/inema/iniciativas/qualidade-das-praias"

  final case class Bulletin(url: String, date: LocalDate)

  private val PdfLink = """href="([^"]+\.pdf)"""".r
  // `... emitido em (02_10_2026).pdf`, and older uploads like `Boletim_Salvador_05_06_2026.pdf`.
  private val IssueDate = """(\d{2})_(\d{2})_(\d{4})""".r

  def bulletins(pageHtml: String): List[Bulletin] =
    PdfLink
      .findAllMatchIn(pageHtml)
      .map(_.group(1))
      .flatMap { url =>
        val name = Try(URLDecoder.decode(url, UTF_8)).getOrElse(url)
        IssueDate.findAllMatchIn(name).toList.lastOption.flatMap { m =>
          Try(LocalDate.of(m.group(3).toInt, m.group(2).toInt, m.group(1).toInt)).toOption
            .map(Bulletin(url, _))
        }
      }
      .toList
      .distinct

  /** Rough bounding box of Bahia's coast — used by `AppConfig` to auto-select this provider. */
  def coversOrigin(origin: Coordinates): Boolean =
    origin.lat >= -18.5 && origin.lat <= -8.5 && origin.lon >= -40.5 && origin.lon <= -37.0

  /** Pure; unit-tested against the real fixture PDF (`InemaBaWaterQualityClientSpec`). */
  def toSamplingPoints(pdfBytes: Array[Byte], bulletinDate: LocalDate): List[SamplingPoint] =
    InemaPdfParser.parseTable(pdfBytes).flatMap(toSamplingPoint(_, bulletinDate))

  private def toSamplingPoint(
      row: InemaPdfParser.Row,
      bulletinDate: LocalDate
  ): Option[SamplingPoint] =
    SamplingPointCoordinates.Bahia.get(row.code).map { coord =>
      SamplingPoint(
        row.code,
        row.pointName,
        row.pointName,
        row.description,
        coord,
        // The bulletin gives a verdict, no enterococci count.
        List(
          WaterSample(bulletinDate, ImaScWaterQualityClient.verdict(row.category), None, None, None)
        )
      )
    }
