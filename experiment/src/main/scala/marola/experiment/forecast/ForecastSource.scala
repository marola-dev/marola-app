package marola.experiment.forecast

import java.time.Instant

import kyo.*

import marola.experiment.schema.{ForecastSample, Provider, RunIndexRow, SamplingPoint}

/** One per route (MIP-0083 §4.4, §5.3); a new Open-Meteo model is a `providers.json` row. */
trait ForecastSource:
  def provider: Provider

  /** The run pinned by its init time (§5.14 rule 2), whatever is newest now. */
  def fetch(run: Instant, points: Chunk[SamplingPoint])(using
      Frame
  ): Chunk[ForecastSample] < (Async & Abort[FetchError])

  /**
   * A run read by its init time, as `backfilled`; a hash differing from an earlier copy of the same
   * run in `earlier` is kept and said in `reason`, a failure is a `missing` row.
   */
  def backfill(run: Instant, points: Chunk[SamplingPoint], earlier: Chunk[RunIndexRow])(using
      Frame
  ): Collected < (Async & Abort[FetchError])

  /**
   * Every expected run after the newest one in `known`: the latest sampled, the rest backfilled or
   * `missing` with a reason (§5.5). `known` is this provider's run index so far.
   */
  def collect(known: Chunk[RunIndexRow], points: Chunk[SamplingPoint])(using
      Frame
  ): Collected < (Async & Abort[FetchError])
end ForecastSource

final case class Collected(runs: Chunk[RunIndexRow], samples: Chunk[ForecastSample])
    derives CanEqual

enum FetchError derives CanEqual:
  case Status(status: Int, url: String, body: String)
  case Unreachable(url: String, detail: String)
  case Malformed(url: String, detail: String)
  case RunChanged(before: Instant, after: Instant)

  /** The `reason` a `missing` run carries. */
  def reason: String = this match
    case Status(s, url, body)     => s"HTTP $s for $url: $body"
    case Unreachable(url, detail) => s"unreachable $url: $detail"
    case Malformed(url, detail)   => s"malformed response from $url: $detail"
    case RunChanged(b, a)         => s"latest run changed from $b to $a during the fetch"
end FetchError
