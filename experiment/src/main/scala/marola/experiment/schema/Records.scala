package marola.experiment.schema

import java.time.{Instant, LocalDate}

import kyo.*

// MIP-0083 §5.12. Fields are camelCase in Scala and snake_case on the wire and in the lake
// (lake.sql), so every given renames them; a change to any record is a new protocol version.

private def snake[A](s: Schema[A])(using Frame): Schema[A] =
  s.renameAllFields(Schema.NameCase.SnakeCase)

/** A `providers.json` row: adding an Open-Meteo model is one of these, not code (§4.4). */
final case class Provider(
    id: String,
    route: Route,
    modelId: String,
    runsUtc: Chunk[Int],
    members: Int,
    maxLeadH: Int,
    addedOn: LocalDate
) derives CanEqual

object Provider:
  given Schema[Provider] = snake(Schema.derived[Provider])

final case class Instrument(
    id: String,
    network: Network,
    lat: Double,
    lon: Double,
    heightM: Option[Double],
    averaging: String,
    licence: String
) derives CanEqual

object Instrument:
  given Schema[Instrument] = snake(Schema.derived[Instrument])

final case class SamplingPoint(
    id: String,
    lat: Double,
    lon: Double,
    kind: PointKind,
    instrument: Option[String],
    addedOn: LocalDate,
    retiredOn: Option[LocalDate]
) derives CanEqual

object SamplingPoint:
  given Schema[SamplingPoint] = snake(Schema.derived[SamplingPoint])

final case class Protocol(
    version: Int,
    variables: Chunk[Variable],
    leadsH: Chunk[Int],
    scorecardDays: Chunk[Int],
    windowDays: Int,
    cadenceH: Int,
    ensembleK: Int,
    minN: Int,
    windBinsMs: Chunk[Double]
) derives CanEqual

object Protocol:
  given Schema[Protocol] = snake(Schema.derived[Protocol])

/** Keyed by `runInit`, never by `fetchedAt` (§5.14): `leadH` counts from the run. */
final case class ForecastSample(
    provider: String,
    runInit: Instant,
    fetchedAt: Instant,
    point: String,
    validTime: Instant,
    leadH: Int,
    variable: Variable,
    member: Option[Int],
    value: Double,
    cellLat: Double,
    cellLon: Double,
    sourceUrl: String
) derives CanEqual

object ForecastSample:
  given Schema[ForecastSample] = snake(Schema.derived[ForecastSample])

final case class ObservationSample(
    instrument: String,
    validTime: Instant,
    variable: Variable,
    value: Double,
    averaging: String,
    qcPassed: Boolean
) derives CanEqual

object ObservationSample:
  given Schema[ObservationSample] = snake(Schema.derived[ObservationSample])

/**
 * Every expected run is a row, so a gap is never silent (§5.5); `contentSha` pins what was read
 * (§5.14).
 */
final case class RunIndexRow(
    provider: String,
    runInit: Instant,
    availableAt: Option[Instant],
    contentSha: Option[String],
    state: RunState,
    reason: Option[String],
    fetchedAt: Option[Instant]
) derives CanEqual

object RunIndexRow:
  given Schema[RunIndexRow] = snake(Schema.derived[RunIndexRow])

/** One provider's daily sums; task 3 makes it a monoid, so any window is a fold of days. */
final case class ScoreCell(
    provider: String,
    point: String,
    variable: Variable,
    leadH: Int,
    bin: String,
    day: LocalDate,
    n: Long,
    sumE: Double,
    sumSqE: Double,
    sumAbsE: Double,
    sumCrps: Double,
    sumSqSpread: Double
) derives CanEqual

object ScoreCell:
  given Schema[ScoreCell] = snake(Schema.derived[ScoreCell])

/**
 * Paired sums of two providers on the cells both have (§5.14), for head-to-head and error
 * correlation.
 */
final case class PairCell(
    providerA: String,
    providerB: String,
    point: String,
    variable: Variable,
    leadH: Int,
    day: LocalDate,
    n: Long,
    sumD: Double,
    sumSqD: Double,
    sumEaEb: Double,
    sumSqEa: Double,
    sumSqEb: Double
) derives CanEqual

object PairCell:
  given Schema[PairCell] = snake(Schema.derived[PairCell])

final case class NavyWarning(
    number: Int,
    area: String,
    force: Int,
    gust: Option[Int],
    validFrom: Instant,
    validTo: Instant,
    issuedAt: Instant,
    rawTextSha: String
) derives CanEqual

object NavyWarning:
  given Schema[NavyWarning] = snake(Schema.derived[NavyWarning])

final case class ScorecardRow(
    provider: String,
    variable: Variable,
    day: Int,
    n: Long,
    bias: Double,
    rmse: Double,
    crps: Double,
    lowSample: Boolean
) derives CanEqual

object ScorecardRow:
  given Schema[ScorecardRow] = snake(Schema.derived[ScorecardRow])

/**
 * The export marola-site reads (§5.8); its JSON Schema is checked in as `scorecard.schema.json`.
 */
final case class Scorecard(
    protocolVersion: Int,
    generatedAt: Instant,
    windowDays: Int,
    rows: Chunk[ScorecardRow],
    licences: Chunk[String]
) derives CanEqual

object Scorecard:
  given Schema[Scorecard] = snake(Schema.derived[Scorecard])
