package marola.experiment

import scala.io.Source
import scala.util.Using

import kyo.*

import marola.experiment.schema.{Protocol, Provider}

/**
 * `providers.json` and `protocol.json` (MIP-0083 §4.4, §4.8): values a commit changes, not code.
 */
object Protocols:

  enum Invalid derives CanEqual:
    case Malformed(file: String, detail: String)
    case DuplicateId(file: String, id: String)
    case BadProvider(id: String)
    case BadProtocol(detail: String)

  def providers(text: String)(using Frame): Either[Vector[Invalid], Chunk[Provider]] =
    decode[Chunk[Provider]]("providers.json", text).flatMap { ps =>
      val bad = ps.toVector.collect {
        case p
            if p.runsUtc.isEmpty || p.runsUtc.exists(h => h < 0 || h > 23) ||
              p.members < 1 || p.maxLeadH < 1 =>
          Invalid.BadProvider(p.id)
      }
      checked(duplicates("providers.json", ps.map(_.id)) ++ bad, ps)
    }

  def protocol(text: String)(using Frame): Either[Vector[Invalid], Protocol] =
    decode[Protocol]("protocol.json", text).flatMap { p =>
      val leads = p.leadsH.toVector
      checked(
        Vector(
          Option.when(leads.isEmpty || leads.head < 1 || leads.zip(leads.tail).exists(_ >= _))(
            Invalid.BadProtocol("leads_h must be positive and increasing")
          ),
          Option.when(!p.scorecardDays.forall(d => leads.contains(d * 24)))(
            Invalid.BadProtocol("every scorecard day must be a lead")
          ),
          Option.when(Seq(p.windowDays, p.cadenceH, p.ensembleK, p.minN).exists(_ < 1))(
            Invalid.BadProtocol("window, cadence, K and min_n must be positive")
          )
        ).flatten,
        p
      )
    }

  def bundledProviders(using Frame): Either[Vector[Invalid], Chunk[Provider]] =
    bundled("providers.json").flatMap(providers)

  def bundledProtocol(using Frame): Either[Vector[Invalid], Protocol] =
    bundled("protocol.json").flatMap(protocol)

  private[experiment] def bundled(name: String): Either[Vector[Invalid], String] =
    Using(Source.fromResource(s"experiment/$name"))(_.mkString).toEither.left
      .map(e => Vector(Invalid.Malformed(name, e.toString)))

  // An unknown route or kind is a decode failure (Labeled), so it lands here as Malformed.
  private[experiment] def decode[A: Schema](file: String, text: String)(using
      Frame
  ): Either[Vector[Invalid], A] =
    Json.decode[A](text).toEither.left.map(e => Vector(Invalid.Malformed(file, e.getMessage)))

  private[experiment] def duplicates(file: String, ids: Chunk[String]): Vector[Invalid] =
    ids.toVector.diff(ids.toVector.distinct).distinct.map(Invalid.DuplicateId(file, _))

  private[experiment] def checked[A](errors: Vector[Invalid], a: A): Either[Vector[Invalid], A] =
    if errors.isEmpty then Right(a) else Left(errors)
end Protocols
