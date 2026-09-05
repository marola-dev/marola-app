package marola.sightings

import java.time.Instant

enum SightingKind derives CanEqual:
  case Jellyfish, Whale

final case class Sighting(
    beachName: String,
    kind: SightingKind,
    note: Option[String],
    reportedAt: Instant
)
