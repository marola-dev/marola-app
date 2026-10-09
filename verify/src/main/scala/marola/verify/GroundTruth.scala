package marola.verify

import java.time.LocalDate

import marola.json.JsonValue

/**
 * The instruments every forecast is scored against (MIP-0083 §5.6,
 * docs/4-reference_ground-truth.md): a ground-truth point is an anemometer, never a model or a
 * reanalysis.
 */
final case class GroundTruth(
    thresholds: GroundTruth.Thresholds,
    points: Vector[GroundTruth.Point],
    deviations: Vector[GroundTruth.Deviation]
):
  def scored: Vector[GroundTruth.Point] = points.filter(_.status == GroundTruth.Status.Scored)

object GroundTruth:

  enum Kind(val label: String) derives CanEqual:
    case Metar extends Kind("metar")
    case Inmet extends Kind("inmet")

  object Kind:
    def fromLabel(s: String): Option[Kind] = values.find(_.label == s)

  enum Status(val label: String) derives CanEqual:
    case Candidate extends Status("candidate")
    case Qualified extends Status("qualified")
    case Rejected extends Status("rejected")
    case Scored extends Status("scored")
    case Retired extends Status("retired")

  object Status:
    def fromLabel(s: String): Option[Status] = values.find(_.label == s)

  /**
   * marola-dev/marola#723's strong-wind rule; `frozen` stays empty while the numbers are
   * placeholders.
   */
  final case class Thresholds(
      historyYears: Int,
      strongMs: Double,
      strongHoursPerYear: Int,
      galeMs: Double,
      galeDaysPerYear: Int,
      frozen: Option[LocalDate]
  )

  /** Where the coordinates were confirmed: a URL and the date it was read. */
  final case class Checked(source: String, on: LocalDate)

  final case class Point(
      id: String,
      state: String,
      name: String,
      kind: Kind,
      station: String,
      lat: Option[Double],
      lon: Option[Double],
      elevationM: Option[Double],
      anemometerM: Option[Double],
      exposure: String,
      status: Status,
      checked: Option[Checked],
      note: String
  )

  final case class Deviation(on: LocalDate, text: String)

  enum Invalid derives CanEqual:
    case Malformed(detail: String)
    case DuplicateId(id: String)
    case BadStationCode(id: String, station: String, kind: Kind)
    case UnknownState(id: String, state: String)
    case OutsideBrazil(id: String)
    case UncheckedCoordinates(id: String)
    case ThresholdsNotFrozen(id: String)
    case TooManyScored(state: String, kind: Kind)

  /**
   * #723 step 3: one airport METAR and one INMET station per state, on the coast of SC, RJ and BA.
   */
  val States: Set[String] = Set("SC", "RJ", "BA")

  def parse(text: String): Either[Vector[Invalid], GroundTruth] =
    try validate(read(JsonValue.parse(text)))
    catch
      case e: JsonValue.JsonParseException => Left(Vector(Invalid.Malformed(e.getMessage)))
      case e: MalformedField               => Left(Vector(Invalid.Malformed(e.getMessage)))

  def bundled: Either[Vector[Invalid], GroundTruth] =
    val in = getClass.getResourceAsStream("/forecast-benchmark/ground-truth.json")
    if in == null then Left(Vector(Invalid.Malformed("ground-truth.json not on the classpath")))
    else
      try parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
      finally in.close()

  def validate(gt: GroundTruth): Either[Vector[Invalid], GroundTruth] =
    val ids = gt.points.map(_.id)
    val duplicates = ids.diff(ids.distinct).distinct.map(Invalid.DuplicateId(_))
    val perPoint = gt.points.flatMap { p =>
      val chosen = p.status == Status.Qualified || p.status == Status.Scored
      Vector(
        Option.when(!stationCodeOk(p.kind, p.station))(
          Invalid.BadStationCode(p.id, p.station, p.kind)
        ),
        Option.when(!States.contains(p.state))(Invalid.UnknownState(p.id, p.state)),
        Option.when(!inBrazil(p))(Invalid.OutsideBrazil(p.id)),
        Option.when(chosen && (p.checked.isEmpty || p.lat.isEmpty || p.lon.isEmpty))(
          Invalid.UncheckedCoordinates(p.id)
        ),
        Option.when(chosen && gt.thresholds.frozen.isEmpty)(Invalid.ThresholdsNotFrozen(p.id))
      ).flatten
    }
    val crowded = gt.scored
      .groupBy(p => (p.state, p.kind))
      .collect { case ((state, kind), ps) if ps.size > 1 => Invalid.TooManyScored(state, kind) }
      .toVector
      .sortBy(_.toString)
    val errors = duplicates ++ perPoint ++ crowded
    if errors.isEmpty then Right(gt) else Left(errors)

  private val IcaoBrazil = "SB[A-Z]{2}".r
  private val InmetAutomatic = "[A-Z][0-9]{3}".r

  private def stationCodeOk(kind: Kind, code: String): Boolean = kind match
    case Kind.Metar => IcaoBrazil.matches(code)
    case Kind.Inmet => InmetAutomatic.matches(code)

  // A loose box around Brazil and its islands: it catches a swapped or unsigned coordinate, not a
  // wrong station.
  private def inBrazil(p: Point): Boolean = (p.lat, p.lon) match
    case (Some(lat), Some(lon)) => lat >= -34.0 && lat <= 6.0 && lon >= -74.0 && lon <= -28.0
    case (None, None)           => true
    case _                      => false

  // Only ever thrown inside `read` and turned into `Invalid.Malformed` by `parse`.
  final private class MalformedField(msg: String)
      extends Exception(msg)
      with scala.util.control.NoStackTrace

  private def read(json: JsonValue): GroundTruth =
    val t = json("thresholds")
    GroundTruth(
      thresholds = Thresholds(
        historyYears = int(t, "history_years"),
        strongMs = num(t, "strong_ms"),
        strongHoursPerYear = int(t, "strong_hours_per_year"),
        galeMs = num(t, "gale_ms"),
        galeDaysPerYear = int(t, "gale_days_per_year"),
        frozen = t("frozen").str.map(date(_, "thresholds.frozen"))
      ),
      points = json("points").arr.map(readPoint),
      deviations = json("deviations").arr.map(d =>
        Deviation(date(str(d, "on"), "deviations.on"), str(d, "text"))
      )
    )

  private def readPoint(p: JsonValue): Point =
    val id = str(p, "id")
    Point(
      id = id,
      state = str(p, "state"),
      name = str(p, "name"),
      kind = label(Kind.fromLabel, str(p, "kind"), s"$id.kind"),
      station = str(p, "station"),
      lat = p("lat").num,
      lon = p("lon").num,
      elevationM = p("elevation_m").num,
      anemometerM = p("anemometer_m").num,
      exposure = str(p, "exposure"),
      status = label(Status.fromLabel, str(p, "status"), s"$id.status"),
      checked = p("checked") match
        case JsonValue.JNull => None
        case c => Some(Checked(str(c, "source"), date(str(c, "on"), s"$id.checked.on"))),
      note = p("note").str.getOrElse("")
    )

  private def str(j: JsonValue, key: String): String =
    j(key).str.getOrElse(throw MalformedField(s"missing string '$key'"))

  private def num(j: JsonValue, key: String): Double =
    j(key).num.getOrElse(throw MalformedField(s"missing number '$key'"))

  private def int(j: JsonValue, key: String): Int = num(j, key).toInt

  private def date(s: String, where: String): LocalDate =
    try LocalDate.parse(s)
    catch case _: java.time.format.DateTimeParseException => throw MalformedField(s"$where: '$s'")

  private def label[E](from: String => Option[E], s: String, where: String): E =
    from(s).getOrElse(throw MalformedField(s"$where: '$s'"))
