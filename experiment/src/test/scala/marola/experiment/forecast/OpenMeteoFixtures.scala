package marola.experiment.forecast

import java.net.http.HttpRequest
import java.time.{Instant, LocalDate}

import scala.collection.mutable
import scala.io.Source
import scala.util.Using

import kyo.*

import marola.experiment.Protocols
import marola.experiment.schema.*
import marola.http.Http

/**
 * Responses recorded live on 2026-10-09/10 at sc-sbfl (Florianópolis airport): metadata, the
 * forecast API's latest run and the single-run API, for `ecmwf_ifs` and `ncep_gfs013`.
 */
object OpenMeteoFixtures:

  given AllowUnsafe = AllowUnsafe.embrace.danger

  val sbfl: SamplingPoint = SamplingPoint(
    "sc-sbfl",
    -27.671,
    -48.547,
    PointKind.Station,
    Some("sc-sbfl"),
    LocalDate.parse("2026-10-09"),
    None
  )

  val ifsRun: Instant = Instant.parse("2026-10-09T12:00:00Z")
  val gfsRun: Instant = Instant.parse("2026-10-09T18:00:00Z")
  val gfsEarlier: Instant = Instant.parse("2026-10-09T12:00:00Z")

  val protocol: Protocol = Protocols.bundledProtocol.toOption.get
  private val providers = Protocols.bundledProviders.toOption.get
  val ifs: Provider = providers.find(_.id == "ifs").get
  val gfs: Provider = providers.find(_.id == "gfs").get

  def read(name: String): String =
    Using.resource(Source.fromResource(s"fixtures/open-meteo/$name"))(_.mkString)

  /** The metadata fixture with its run init replaced: a run that moved on. */
  def metaWithInit(model: String, init: Instant): String =
    read(s"meta-$model.json").replaceAll(
      "\"last_run_initialisation_time\":\\d+",
      s"\"last_run_initialisation_time\":${init.getEpochSecond}"
    )

  /**
   * Answers the first route whose predicate matches the URL with its next response, repeating the
   * last; anything unrouted is a 404. Records every URL and when it was sent.
   */
  final class Replay(routes: (String => Boolean, Seq[Http.Response])*) extends Http.Transport:
    private val served = mutable.Map.empty[Int, Int]
    val sent: mutable.ArrayBuffer[(Long, String)] = mutable.ArrayBuffer.empty

    def send(request: HttpRequest): Http.Response = synchronized {
      val url = request.uri.toString
      sent += java.lang.System.nanoTime() -> url
      routes.indexWhere(_._1(url)) match
        case -1 => Http.Response(404, s"no fixture for $url")
        case i =>
          val n = served.getOrElse(i, 0)
          served(i) = n + 1
          val responses = routes(i)._2
          responses(math.min(n, responses.size - 1))
    }

    def urls: Seq[String] = synchronized(sent.map(_._2).toSeq)
  end Replay

  def ok(body: String): Seq[Http.Response] = Seq(Http.Response(200, body))

  def isMeta(model: String)(url: String): Boolean = url.contains(s"/data/$model/static/meta.json")

  def isLatest(model: String)(url: String): Boolean =
    url.startsWith(OpenMeteoForecasts.ForecastBase) && url.contains(s"models=$model&")

  def isSingleRun(model: String, run: String)(url: String): Boolean =
    url.startsWith(OpenMeteoForecasts.SingleRunBase) && url.contains(s"models=$model&") &&
      url.contains(s"run=$run&")

  /** The recorded IFS and GFS routes: metadata, latest run and the single runs on file. */
  def recorded: Seq[(String => Boolean, Seq[Http.Response])] = Seq(
    isMeta("ecmwf_ifs") -> ok(read("meta-ecmwf_ifs.json")),
    isMeta("ncep_gfs013") -> ok(read("meta-ncep_gfs013.json")),
    isLatest("ecmwf_ifs") -> ok(read("forecast-ecmwf_ifs-2026100912-sc-sbfl.json")),
    isLatest("ncep_gfs013") -> ok(read("forecast-ncep_gfs013-2026100918-sc-sbfl.json")),
    isSingleRun("ecmwf_ifs", "2026-10-09T12:00") ->
      ok(read("single-run-ecmwf_ifs-2026100912-sc-sbfl.json")),
    isSingleRun("ncep_gfs013", "2026-10-09T12:00") ->
      ok(read("single-run-ncep_gfs013-2026100912-sc-sbfl.json")),
    isSingleRun("ncep_gfs013", "2026-10-09T18:00") ->
      ok(read("single-run-ncep_gfs013-2026100918-sc-sbfl.json")),
    ((url: String) => url.startsWith(OpenMeteoForecasts.SingleRunBase)) ->
      Seq(Http.Response(400, read("single-run-not-available.json")))
  )

  val fast: Schedule = Schedule.fixed(1.millis).take(3)

  def source(provider: Provider, meter: Meter): ForecastSource =
    OpenMeteoForecasts(provider, protocol, meter, backoff = fast)

  /** Runs `body` against `transport` with an unthrottled meter, failing the test on any error. */
  def run[A](
      transport: Http.Transport,
      meter: => Meter < (Sync & Scope) = Meter.initRateLimiter(1000, 1.millis)
  )(body: Meter => A < (Async & Abort[FetchError])): A =
    outcome(transport, meter)(body) match
      case Result.Success(a) => a
      case other             => throw AssertionError(s"Unexpected: $other")

  /** `body`'s outcome, a `FetchError` included, against `transport` and `meter`. */
  def outcome[A](transport: Http.Transport, meter: => Meter < (Sync & Scope))(
      body: Meter => A < (Async & Abort[FetchError])
  ): Result[FetchError, A] =
    Http.withTransport(transport) {
      KyoApp.Unsafe
        .runAndBlock(60.seconds)(meter.map(m => Abort.run[FetchError](body(m))))
        .getOrThrow
    }

  def withoutFetchTime(samples: Chunk[ForecastSample]): Chunk[ForecastSample] =
    samples.map(_.copy(fetchedAt = Instant.EPOCH))
end OpenMeteoFixtures
