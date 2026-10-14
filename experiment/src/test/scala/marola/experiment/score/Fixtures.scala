package marola.experiment.score

import java.time.{Duration, Instant}

import marola.experiment.schema.Variable

object Fixtures:
  val T0: Instant = Instant.parse("2026-07-01T00:00:00Z")

  def at(hours: Long): Instant = T0.plus(Duration.ofHours(hours))

  def matched(
      provider: String,
      runH: Long,
      validH: Long,
      members: Seq[Double],
      observed: Double,
      point: String = "P"
  ): Matched =
    Matched(
      provider,
      at(runH),
      point,
      Variable.WindSpeed10m,
      at(validH),
      (validH - runH).toInt,
      members,
      observed
    )
