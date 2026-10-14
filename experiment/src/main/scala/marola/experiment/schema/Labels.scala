package marola.experiment.schema

import kyo.*

/**
 * A closed set of codes stored as their `label` (MIP-0083 §5.12); an unknown label fails decoding.
 */
trait Labeled:
  def label: String

object Labeled:
  def schema[E <: Labeled](name: String, values: Seq[E])(using Frame): Schema[E] =
    Schema.stringSchema.transform[E](s =>
      values
        .find(_.label == s)
        .getOrElse(throw UnknownVariantException(Seq(name), s))
    )(_.label)

enum Route(val label: String) extends Labeled derives CanEqual:
  case OpenMeteo extends Route("open_meteo")
  case WeatherNext3BigQuery extends Route("weathernext3_bigquery")
  case Monan extends Route("monan")
  case MarolaAnalog extends Route("marola_analog")

object Route:
  given Schema[Route] = Labeled.schema("route", values.toSeq)

enum Variable(val label: String) extends Labeled derives CanEqual:
  case WindSpeed10m extends Variable("wind_speed_10m")
  case WindDirection10m extends Variable("wind_direction_10m")
  case Temperature2m extends Variable("temperature_2m")
  case WindSpeed850hPa extends Variable("wind_speed_850hpa")
  case Precipitation24h extends Variable("precipitation_24h")

object Variable:
  given Schema[Variable] = Labeled.schema("variable", values.toSeq)

// Not `Unit`: that name is Scala's.
enum MeasureUnit(val label: String) extends Labeled derives CanEqual:
  case MetresPerSecond extends MeasureUnit("m/s")
  case Degrees extends MeasureUnit("deg")
  case Celsius extends MeasureUnit("degC")
  case Millimetres extends MeasureUnit("mm")

object MeasureUnit:
  given Schema[MeasureUnit] = Labeled.schema("unit", values.toSeq)

  def of(v: Variable): MeasureUnit = v match
    case Variable.WindSpeed10m | Variable.WindSpeed850hPa => MetresPerSecond
    case Variable.WindDirection10m                        => Degrees
    case Variable.Temperature2m                           => Celsius
    case Variable.Precipitation24h                        => Millimetres

enum Network(val label: String) extends Labeled derives CanEqual:
  case Inmet extends Network("inmet")
  case Metar extends Network("metar")
  case Pnboia extends Network("pnboia")
  case Simcosta extends Network("simcosta")
  case Radiosonde extends Network("radiosonde")
  case Merge extends Network("merge")

object Network:
  given Schema[Network] = Labeled.schema("network", values.toSeq)

enum PointKind(val label: String) extends Labeled derives CanEqual:
  case Station extends PointKind("station")
  case Buoy extends PointKind("buoy")
  case Coast extends PointKind("coast")
  case UpperAir extends PointKind("upper_air")

object PointKind:
  given Schema[PointKind] = Labeled.schema("kind", values.toSeq)

enum RunState(val label: String) extends Labeled derives CanEqual:
  case Sampled extends RunState("sampled")
  case Backfilled extends RunState("backfilled")
  case Missing extends RunState("missing")

object RunState:
  given Schema[RunState] = Labeled.schema("state", values.toSeq)
