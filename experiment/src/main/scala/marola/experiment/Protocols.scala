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
    case NoGroundTruth(point: String)
    case NotAtInstrument(point: String)
    case RetiredBeforeAdded(point: String)

  def providers(text: String)(using Frame): Either[Vector[Invalid], Chunk[Provider]] =
    decode[Chunk[Provider]]("providers.json", text).flatMap { ps =>
      val dups = duplicates("providers.json", ps.map(_.id))
      if dups.isEmpty then Right(ps) else Left(dups)
    }

  def bundledProviders(using Frame): Either[Vector[Invalid], Chunk[Provider]] =
    bundled("providers.json").flatMap(providers)

  def bundledProtocol(using Frame): Either[Vector[Invalid], Protocol] =
    bundled("protocol.json").flatMap(decode[Protocol]("protocol.json", _))

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
end Protocols
