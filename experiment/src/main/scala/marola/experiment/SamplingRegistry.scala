package marola.experiment

import java.time.LocalDate

import kyo.*

import marola.experiment.Protocols.Invalid
import marola.experiment.schema.{PointKind, SamplingPoint}

/**
 * `sampling-points.json` (MIP-0083 §5.4): where forecasts are sampled. ground-truth.json stays the
 * source of each instrument's status; a point only names one.
 */
object SamplingRegistry:

  def parse(text: String, gt: GroundTruth)(using
      Frame
  ): Either[Vector[Invalid], Chunk[SamplingPoint]] =
    Protocols.decode[Chunk[SamplingPoint]]("sampling-points.json", text).flatMap { ps =>
      val instruments = gt.points.map(i => i.id -> i).toMap
      val perPoint = ps.toVector.flatMap { p =>
        val instrument = p.instrument.flatMap(instruments.get)
        Vector(
          Option.when(p.kind != PointKind.Coast && instrument.isEmpty)(
            Invalid.NoGroundTruth(p.id)
          ),
          // The forecast is sampled where the anemometer is, so the coordinates must be checked ones.
          Option.when(instrument.exists(i => !i.lat.contains(p.lat) || !i.lon.contains(p.lon)))(
            Invalid.NotAtInstrument(p.id)
          ),
          Option.when(p.retiredOn.exists(_.isBefore(p.addedOn)))(Invalid.RetiredBeforeAdded(p.id))
        ).flatten
      }
      val errors = Protocols.duplicates("sampling-points.json", ps.map(_.id)) ++ perPoint
      if errors.isEmpty then Right(ps) else Left(errors)
    }

  def bundled(gt: GroundTruth)(using Frame): Either[Vector[Invalid], Chunk[SamplingPoint]] =
    Protocols.bundled("sampling-points.json").flatMap(parse(_, gt))

  /** A retired point keeps its rows; it is only no longer sampled after `retired_on`. */
  def sampledOn(p: SamplingPoint, day: LocalDate): Boolean =
    !day.isBefore(p.addedOn) && p.retiredOn.forall(!day.isAfter(_))

  def scorable(p: SamplingPoint): Boolean = p.kind != PointKind.Coast && p.instrument.nonEmpty
end SamplingRegistry
