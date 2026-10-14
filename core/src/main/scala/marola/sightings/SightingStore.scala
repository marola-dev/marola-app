package marola.sightings

import kyo.*

/**
 * Where user-reported jellyfish/whale sightings go: the collection half of the heuristics'
 * calibration loop (`docs/1-design_heuristics.md`).
 */
trait SightingStore:
  def record(sighting: Sighting): Unit < Sync
  def recentFor(beachName: String, limit: Int = 5): List[Sighting] < Sync
