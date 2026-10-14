package marola.experiment

import java.nio.file.Path
import java.time.{Instant, LocalDate}

import kyo.*

import marola.experiment.schema.*

/**
 * The lake tables of MIP-0083 §5.6. Every write replaces the rows sharing its natural key, so a
 * re-run cycle adds nothing; every windowed read is a `Stream` fetched one page at a time. Windows
 * are half-open: `from` inclusive, `until` exclusive.
 */
trait SampleStore:
  def writePoints(points: Chunk[SamplingPoint]): Unit < Sync
  def writeForecasts(samples: Chunk[ForecastSample]): Unit < Sync
  def writeObservations(samples: Chunk[ObservationSample]): Unit < Sync
  def writeRuns(rows: Chunk[RunIndexRow]): Unit < Sync
  def writeScoreCells(cells: Chunk[ScoreCell]): Unit < Sync
  def writePairCells(cells: Chunk[PairCell]): Unit < Sync

  def points: Chunk[SamplingPoint] < Sync

  /** By `valid_time`. */
  def forecasts(from: Instant, until: Instant): Stream[ForecastSample, Sync]

  /** By `valid_time`. */
  def observations(from: Instant, until: Instant): Stream[ObservationSample, Sync]

  /** By `run_init`. */
  def runs(from: Instant, until: Instant): Stream[RunIndexRow, Sync]
  def scoreCells(from: LocalDate, until: LocalDate): Stream[ScoreCell, Sync]
  def pairCells(from: LocalDate, until: LocalDate): Stream[PairCell, Sync]

object SampleStore:
  /** The v0 local lake; the B2 store is MIP-0075 task 5's follow-up. */
  def apply(catalog: LakeCatalog, pageSize: Int = 10_000): SampleStore < (Scope & Sync) =
    LakeSamples.open(catalog, pageSize)

/** Where the local lake's catalog lives. */
enum LakeCatalog:
  /**
   * MIP-0075 §4.5's format: metadata in `dir/lake.ducklake`, Parquet under `dir/data/`. Needs the
   * `ducklake` extension, which DuckDB downloads from extensions.duckdb.org on first use.
   */
  case DuckLake(dir: Path)

  /** The same tables in one DuckDB file, for where that download is not possible. */
  case DuckDbFile(file: Path)
