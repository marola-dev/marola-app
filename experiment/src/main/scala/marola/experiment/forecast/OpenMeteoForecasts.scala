package marola.experiment.forecast

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit.HOURS
import java.time.{Instant, ZoneOffset}

import kyo.*

import marola.experiment.schema.*
import marola.http.Http
import marola.json.JsonValue

/**
 * The `open_meteo` route (MIP-0083 §4.1, §5.5, §5.14): one client for every model Open-Meteo
 * serves, built from a `providers.json` row. The latest run comes from the forecast API between two
 * metadata reads; any other run, or a latest run that changed under the fetch, from the single-run
 * API by its init time.
 */
object OpenMeteoForecasts:

  private given CanEqual[Instant, Instant] = CanEqual.derived

  val ForecastBase = "https://api.open-meteo.com/v1/forecast"
  val SingleRunBase = "https://single-runs-api.open-meteo.com/v1/forecast"

  /** Recorded live 2026-10-09 for `ecmwf_ifs` and `ncep_gfs013` (MIP-0083 §4.1's ⚠). */
  def metadataUrl(modelId: String): String =
    s"https://api.open-meteo.com/data/$modelId/static/meta.json"

  /** Open-Meteo's own advice: read a run 10 minutes after `last_run_availability_time`. */
  val SettleTime: java.time.Duration = java.time.Duration.ofMinutes(10)

  /** The variables this route can ask for; a protocol variable outside it is not requested. */
  val Served: Chunk[Variable] =
    Chunk(Variable.WindSpeed10m, Variable.WindDirection10m, Variable.Temperature2m)

  /** Free tier: 600 calls a minute and 10,000 a day; one cycle is ~2 calls per point and model. */
  def rateLimiter(using Frame): Meter < (Sync & Scope) = Meter.initRateLimiter(5, 1.second)

  val Backoff: Schedule =
    Schedule.exponentialBackoff(initial = 2.seconds, factor = 2, maxBackoff = 30.seconds).take(4)

  def apply(
      provider: Provider,
      protocol: Protocol,
      meter: Meter,
      concurrency: Int = 4,
      backoff: Schedule = Backoff
  ): ForecastSource = Impl(provider, protocol, meter, concurrency, backoff)

  final case class Metadata(init: Instant, availableAt: Instant) derives CanEqual

  def parseMetadata(url: String, body: String): Either[FetchError, Metadata] =
    val json = parseJson(url, body)
    json.flatMap { j =>
      (j("last_run_initialisation_time").num, j("last_run_availability_time").num) match
        case (Some(init), Some(available)) =>
          Right(
            Metadata(Instant.ofEpochSecond(init.toLong), Instant.ofEpochSecond(available.toLong))
          )
        case _ => Left(FetchError.Malformed(url, "no last_run_initialisation_time"))
    }

  /** The leads this provider is sampled at: the protocol's, up to the model's range. */
  def leads(provider: Provider, protocol: Protocol): Chunk[Int] =
    protocol.leadsH.filter(_ <= provider.maxLeadH)

  private val hourFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC)

  private def query(provider: Provider, protocol: Protocol, point: SamplingPoint): String =
    val hourly = protocol.variables.filter(Served.contains).map(_.label).mkString(",")
    s"latitude=${point.lat}&longitude=${point.lon}&models=${provider.modelId}&hourly=$hourly" +
      "&cell_selection=nearest&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT"

  /** The forecast API's window is pinned to the run, so a late fetch asks for the same hours. */
  def latestUrl(
      provider: Provider,
      protocol: Protocol,
      point: SamplingPoint,
      run: Instant
  ): String =
    val ls = leads(provider, protocol)
    s"$ForecastBase?${query(provider, protocol, point)}" +
      s"&start_hour=${hourFormat.format(run.plus(ls.head, HOURS))}" +
      s"&end_hour=${hourFormat.format(run.plus(ls.last, HOURS))}"

  /** The single-run API refuses `start_hour`; its series starts at the run's init. */
  def singleRunUrl(
      provider: Provider,
      protocol: Protocol,
      point: SamplingPoint,
      run: Instant
  ): String =
    s"$SingleRunBase?${query(provider, protocol, point)}" +
      s"&run=${hourFormat.format(run)}&forecast_hours=${leads(provider, protocol).last + 1}"

  /**
   * One point's samples. `startsAt` is the first hour the request asked for: a response starting
   * elsewhere is not the run asked for (§5.14 rule 2).
   */
  def parse(
      provider: Provider,
      protocol: Protocol,
      point: SamplingPoint,
      run: Instant,
      startsAt: Instant,
      url: String,
      body: String,
      fetchedAt: Instant
  ): Either[FetchError, Chunk[ForecastSample]] =
    parseJson(url, body).flatMap { json =>
      val times =
        json("hourly")("time").arr.flatMap(_.num).map(t => Instant.ofEpochSecond(t.toLong))
      (json("latitude").num, json("longitude").num, times.headOption) match
        case (Some(cellLat), Some(cellLon), Some(first)) if first == startsAt =>
          val wanted = leads(provider, protocol).toSet
          val variables = protocol.variables.filter(Served.contains)
          val samples = for
            (validTime, i) <- times.zipWithIndex
            lead = java.time.Duration.between(run, validTime).toHours.toInt
            if wanted.contains(lead) && run.plus(lead, HOURS) == validTime
            variable <- variables
            value <- json("hourly")(variable.label).arr.lift(i).flatMap(_.num)
          yield ForecastSample(
            provider.id,
            run,
            fetchedAt,
            point.id,
            validTime,
            lead,
            variable,
            None,
            value,
            cellLat,
            cellLon,
            url
          )
          Right(Chunk.from(samples))
        case (_, _, Some(first)) =>
          Left(FetchError.Malformed(url, s"series starts at $first, expected $startsAt"))
        case _ => Left(FetchError.Malformed(url, "no hourly series or cell coordinates"))
    }

  /**
   * What was read, not when or from which endpoint: a backfill of the same run hashes the same
   * (§5.14 rule 2).
   */
  def contentSha(samples: Chunk[ForecastSample]): String =
    val lines = samples
      .map(s =>
        s"${s.point}|${s.validTime.getEpochSecond}|${s.leadH}|${s.variable.label}|" +
          s"${s.member.getOrElse(-1)}|${s.value}|${s.cellLat}|${s.cellLon}"
      )
      .toVector
      .sorted
    val digest = MessageDigest.getInstance("SHA-256").digest(lines.mkString("\n").getBytes(UTF_8))
    digest.map(b => f"$b%02x").mkString

  /** The provider's run inits in `(after, upTo]`. */
  def expectedRuns(provider: Provider, after: Instant, upTo: Instant): Chunk[Instant] =
    val first = after.truncatedTo(HOURS).plus(1, HOURS)
    Chunk.from(
      Iterator
        .iterate(first)(_.plus(1, HOURS))
        .takeWhile(!_.isAfter(upTo))
        .filter(t => provider.runsUtc.contains(t.atZone(ZoneOffset.UTC).getHour))
        .toSeq
    )

  private def parseJson(url: String, body: String): Either[FetchError, JsonValue] =
    try
      val json = JsonValue.parse(body)
      json("error").bool match
        case Some(true) =>
          Left(FetchError.Malformed(url, json("reason").str.getOrElse(body.take(200))))
        case _ => Right(json)
    catch case e: JsonValue.JsonParseException => Left(FetchError.Malformed(url, e.getMessage))

  /** A failure worth another attempt: a 429, a 5xx, or no response at all. */
  final private case class Transient(status: Maybe[Int], url: String, detail: String)

  final private case class Impl(
      provider: Provider,
      protocol: Protocol,
      meter: Meter,
      concurrency: Int,
      backoff: Schedule
  ) extends ForecastSource:

    private def now(using Frame): Instant < Sync = Clock.now.map(_.toJava)

    // Http.getString throws; inside the meter's fiber the throw arrives as a panic, not a failure.
    private def attempt(
        url: String
    )(using Frame): String < (Async & Abort[Transient | FetchError]) =
      Abort.run[Throwable](Abort.catching[Throwable](meter.run(Http.getString(url)))).map {
        case Result.Success(body) => body
        case Result.Failure(e)    => classify(url, e)
        case Result.Panic(e)      => classify(url, e)
      }

    private def classify(url: String, e: Throwable)(using
        Frame
    ): Nothing < Abort[Transient | FetchError] = e match
      case e: Http.HttpError if e.status == 429 || e.status >= 500 =>
        Abort.fail(Transient(Maybe(e.status), url, e.bodySnippet))
      case e: Http.HttpError => Abort.fail(FetchError.Status(e.status, url, e.bodySnippet))
      // A connect or TLS failure before any response: seen live from this route on 2026-10-09.
      case e: java.io.IOException => Abort.fail(Transient(Maybe.empty, url, e.toString))
      case e                      => Abort.panic(e)

    private def get(url: String)(using Frame): String < (Async & Abort[FetchError]) =
      Abort.recover[Transient] { (t: Transient) =>
        Abort.fail(
          t.status.fold(FetchError.Unreachable(url, t.detail))(FetchError.Status(_, url, t.detail))
        )
      }(Retry[Transient](backoff)(attempt(url)))

    def metadata(using Frame): Metadata < (Async & Abort[FetchError]) =
      val url = metadataUrl(provider.modelId)
      get(url).map(body => Abort.get(parseMetadata(url, body)))

    def latestRun(using Frame): Maybe[Instant] < (Async & Abort[FetchError]) =
      metadata.map(m => Maybe(m.init))

    private def points(
        run: Instant,
        points: Chunk[SamplingPoint],
        url: SamplingPoint => String,
        startsAt: Instant
    )(using Frame): Chunk[ForecastSample] < (Async & Abort[FetchError]) =
      now.map { fetchedAt =>
        Async
          .foreach(points, concurrency) { p =>
            val u = url(p)
            get(u).map(body =>
              Abort.get(parse(provider, protocol, p, run, startsAt, u, body, fetchedAt))
            )
          }
          .map(_.flatten)
      }

    def fetch(run: Instant, pts: Chunk[SamplingPoint])(using
        Frame
    ): Chunk[ForecastSample] < (Async & Abort[FetchError]) =
      points(run, pts, singleRunUrl(provider, protocol, _, run), run)

    /** Kept only if the metadata names the same run after the fetch as before it. */
    private def latest(meta: Metadata, pts: Chunk[SamplingPoint])(using
        Frame
    ): Collected < (Async & Abort[FetchError]) =
      val run = meta.init
      val startsAt = run.plus(leads(provider, protocol).head, HOURS)
      for
        samples <- points(run, pts, latestUrl(provider, protocol, _, run), startsAt)
        after <- metadata
        _ <- Abort.when(after.init != run)(FetchError.RunChanged(run, after.init))
      yield Collected(
        Chunk(
          RunIndexRow(
            provider.id,
            run,
            Some(meta.availableAt),
            Some(contentSha(samples)),
            RunState.Sampled,
            None,
            samples.headOption.map(_.fetchedAt)
          )
        ),
        samples
      )
    end latest

    def backfill(run: Instant, pts: Chunk[SamplingPoint], earlier: Chunk[RunIndexRow])(using
        Frame
    ): Collected < (Async & Abort[FetchError]) =
      Abort.run[FetchError](fetch(run, pts)).map {
        case Result.Success(samples) =>
          val sha = contentSha(samples)
          val differs = earlier.find(r => r.runInit == run && r.contentSha.exists(_ != sha))
          now.map { at =>
            Collected(
              Chunk(
                RunIndexRow(
                  provider.id,
                  run,
                  None,
                  Some(sha),
                  RunState.Backfilled,
                  differs.map(r =>
                    s"content_sha differs from the ${r.state.label} copy ${r.contentSha.getOrElse("")}"
                  ),
                  Some(at)
                )
              ),
              samples
            )
          }
        case Result.Failure(e) =>
          now.map(at =>
            Collected(
              Chunk(
                RunIndexRow(
                  provider.id,
                  run,
                  None,
                  None,
                  RunState.Missing,
                  Some(e.reason),
                  Some(at)
                )
              ),
              Chunk.empty
            )
          )
        case panic: Result.Panic => Abort.error(panic)
      }

    def collect(known: Chunk[RunIndexRow], pts: Chunk[SamplingPoint])(using
        Frame
    ): Collected < (Async & Abort[FetchError]) =
      val mine = known.filter(_.provider == provider.id)
      val after = mine
        .map(_.runInit)
        .maxOption
        .getOrElse(provider.addedOn.atStartOfDay(ZoneOffset.UTC).toInstant.minusSeconds(1))
      for
        meta <- metadata
        at <- now
        settled = !at.isBefore(meta.availableAt.plus(SettleTime))
        expected = expectedRuns(provider, after, meta.init).filter(r => settled || r != meta.init)
        sampled <-
          if expected.contains(meta.init) then Abort.run[FetchError](latest(meta, pts))
          else Kyo.lift(Result.succeed[FetchError, Collected](Collected(Chunk.empty, Chunk.empty)))
        _ <- sampled match
          case panic: Result.Panic => Abort.error(panic)
          case _                   => Kyo.unit
        // A latest run that failed or changed under the fetch is read again by its init time.
        rest = sampled match
          case Result.Success(c) if c.runs.nonEmpty => expected.filter(_ != meta.init)
          case _                                    => expected
        filled <- Kyo.foreach(rest)(backfill(_, pts, mine))
      yield
        val all = sampled.toMaybe.toChunk ++ filled
        Collected(all.flatMap(_.runs).sortBy(_.runInit), all.flatMap(_.samples))
      end for
    end collect
  end Impl
end OpenMeteoForecasts
