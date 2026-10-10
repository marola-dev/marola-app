package marola.experiment

import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.util.Locale

import scala.util.Try
import scala.util.control.NoStackTrace

import kyo.*

import marola.experiment.schema.{ObservationSample, Variable}
import marola.http.Http
import marola.json.JsonValue
import marola.log.Log

/**
 * MIP-0083 §4.5: what the ground-truth instruments measured. The averaging labels and the INMET
 * token are documented in docs/4-reference_ground-truth.md, "Observation sources".
 */
object Observations:

  /** §5.5: below this observed speed a wind direction is noise, so it is not kept. */
  val MinDirectionSpeedMs = 2.0

  /** International knot: 1852 m per hour. */
  def knotsToMs(knots: Double): Double = knots * 1852.0 / 3600.0

  // Loose physical bounds: they catch a unit slip or a garbage value, not a bad reading.
  private[experiment] def plausible(variable: Variable, value: Double): Boolean = variable match
    case Variable.WindSpeed10m | Variable.WindSpeed850hPa => value >= 0 && value <= 80
    case Variable.WindDirection10m                        => value >= 0 && value <= 360
    case Variable.Temperature2m                           => value >= -40 && value <= 55
    case Variable.Precipitation24h                        => value >= 0 && value <= 1000

  /**
   * One report's samples; a direction is kept only when the speed reaches `MinDirectionSpeedMs`.
   */
  private[experiment] def reading(
      instrument: String,
      at: Instant,
      speedMs: Option[Double],
      directionDeg: Option[Double],
      temperatureC: Option[Double],
      windAveraging: String,
      temperatureAveraging: String
  ): Vector[ObservationSample] =
    def s(v: Variable, avg: String)(x: Double) =
      ObservationSample(instrument, at, v, x, avg, plausible(v, x))
    Vector(
      speedMs.map(s(Variable.WindSpeed10m, windAveraging)),
      directionDeg
        .filter(_ => speedMs.exists(_ >= MinDirectionSpeedMs))
        .map(s(Variable.WindDirection10m, windAveraging)),
      temperatureC.map(s(Variable.Temperature2m, temperatureAveraging))
    ).flatten

/**
 * aviationweather.gov's data API: no key, 100 requests a minute, the last 15 days only; `Http`
 * already sends marola's own User-Agent, which the API asks for.
 */
object MetarClient:

  val Base = "https://aviationweather.gov/api/data/metar"

  // ICAO Annex 3: a METAR's wind is the mean of the 10 minutes before the report.
  val WindAveraging = "10min_mean"
  val TemperatureAveraging = "instant"

  def url(stations: Vector[String], hours: Int): String =
    s"$Base?ids=${stations.mkString(",")}&format=json&hours=$hours"

  /**
   * `instruments`: ICAO code to ground-truth point id; a report from any other station is dropped.
   */
  def parse(body: String, instruments: Map[String, String]): Vector[ObservationSample] =
    JsonValue.parse(body).arr.flatMap { report =>
      for
        icao <- report("icaoId").str.toVector
        instrument <- instruments.get(icao).toVector
        obsTime <- report("obsTime").num.toVector
        // `wdir` is a number, or the string "VRB" for a variable direction.
        sample <- Observations.reading(
          instrument,
          Instant.ofEpochSecond(obsTime.toLong),
          report("wspd").num.map(Observations.knotsToMs),
          report("wdir").num,
          report("temp").num,
          WindAveraging,
          TemperatureAveraging
        )
      yield sample
    }

  def fetch(points: Vector[GroundTruth.Point], hours: Int): Vector[ObservationSample] < Sync =
    val metars = points.filter(_.kind == GroundTruth.Kind.Metar)
    if metars.isEmpty then Vector.empty
    else
      Http
        .getString(url(metars.map(_.station), hours), retries = 2)
        .map(parse(_, metars.map(p => p.station -> p.id).toMap))

/**
 * INMET's automatic stations through `apitempo.inmet.gov.br`. Hourly data is served only on the
 * token route; the token is requested from INMET, so without `INMET_TOKEN` the client skips.
 */
object InmetClient:

  val Base = "https://apitempo.inmet.gov.br/token/estacao"

  // INMET Nota Técnica 001/2011: wind is the 10-minute mean, temperature a 1-minute mean.
  val WindAveraging = "10min_mean"
  val TemperatureAveraging = "1min_mean"

  val NoTokenReason = "INMET skipped: INMET_TOKEN is unset (hourly data needs a token from INMET)"

  private val log = Log.forName(getClass.getName)

  def url(station: String, from: LocalDate, to: LocalDate, token: String): String =
    s"$Base/$from/$to/$station/$token"

  private val Hour = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.ROOT)

  /**
   * One record per station and hour: `DT_MEDICAO` and `HR_MEDICAO` (UTC, "HHMM"); values are
   * decimal strings or null.
   */
  def parse(body: String, instrument: String): Vector[ObservationSample] =
    JsonValue.parse(body).arr.flatMap { hour =>
      val at = for
        date <- hour("DT_MEDICAO").str
        time <- hour("HR_MEDICAO").str
        t <- Try(LocalDateTime.parse(s"$date $time", Hour)).toOption
      yield t.toInstant(ZoneOffset.UTC)
      at.toVector.flatMap {
        Observations.reading(
          instrument,
          _,
          number(hour, "VEN_VEL"),
          number(hour, "VEN_DIR"),
          number(hour, "TEM_INS"),
          WindAveraging,
          TemperatureAveraging
        )
      }
    }

  private def number(j: JsonValue, key: String): Option[Double] =
    j(key).str.flatMap(_.toDoubleOption).orElse(j(key).num)

  def fetch(
      points: Vector[GroundTruth.Point],
      from: LocalDate,
      to: LocalDate,
      token: Option[String] = sys.env.get("INMET_TOKEN").filter(_.nonEmpty)
  ): Vector[ObservationSample] < Sync =
    val stations = points.filter(_.kind == GroundTruth.Kind.Inmet)
    token match
      case None =>
        Sync.defer {
          log.warn(NoTokenReason)
          Vector.empty
        }
      case Some(t) =>
        Kyo.foreach(stations)(p => station(p, from, to, t)).map(_.flatten.toVector)

  // The token is in the path, so a failure is re-raised without the URL `Http.HttpError` carries.
  private def station(
      p: GroundTruth.Point,
      from: LocalDate,
      to: LocalDate,
      token: String
  ): Vector[ObservationSample] < Sync =
    Abort.run(Abort.catching[Throwable](Http.getString(url(p.station, from, to, token)))).map {
      case Result.Success(body)              => parse(body, p.id)
      case Result.Failure(e: Http.HttpError) => throw InmetError(p, from, to, s"HTTP ${e.status}")
      case Result.Failure(e) => throw InmetError(p, from, to, e.getClass.getSimpleName)
      case Result.Panic(e)   => throw InmetError(p, from, to, e.getClass.getSimpleName)
    }

  final case class InmetError(p: GroundTruth.Point, from: LocalDate, to: LocalDate, detail: String)
      extends Exception(s"INMET ${p.station} $from..$to: $detail")
      with NoStackTrace

/**
 * NOAA CPC's weekly Niño-3.4 SST and anomaly against 1991–2020 (`wksst9120.for`): one value for the
 * whole basin, stored beside each cycle, not an instrument reading.
 */
object Nino34Reader:

  val Url = "https://www.cpc.ncep.noaa.gov/data/indices/wksst9120.for"

  /** `week` is the centre of the week CPC averaged. */
  final case class Week(week: LocalDate, sstC: Double, anomalyC: Double) derives CanEqual

  private val Day = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("ddMMMyyyy")
    .toFormatter(Locale.ENGLISH)

  // Fixed-width Fortran columns: a negative anomaly touches the SST before it ("20.6-0.1"), so the
  // line cannot be split on blanks.
  def parse(text: String): Vector[Week] =
    text.linesIterator.toVector.flatMap { line =>
      if line.length < 49 then None
      else
        for
          week <- Try(LocalDate.parse(line.substring(1, 10), Day)).toOption
          sst <- line.substring(41, 45).trim.toDoubleOption
          anomaly <- line.substring(45, 49).trim.toDoubleOption
        yield Week(week, sst, anomaly)
    }

  def latest: Option[Week] < Sync = Http.getString(Url, retries = 2).map(parse(_).lastOption)
