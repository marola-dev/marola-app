package marola.sightings

import kyo.*

/**
 * Where user-reported jellyfish/whale sightings go — the missing piece for the calibration feedback
 * loop `ARCHITECTURE.md` §8 describes ("let users report sightings back... accumulate that as real
 * labeled data"). Two implementations, same local-vs-Azure split as `LlmClient`:
 * `LocalFileSightingStore` (default, a JSON-lines file, zero Azure account) and
 * `CosmosDbSightingStore` (a provisioned Cosmos DB container).
 *
 * NOTE on phase discipline (`AGENTS.md`): the natural way to *submit* a sighting is through the
 * Telegram bot, which doesn't exist yet (`ARCHITECTURE.md` §11 Phase 1). `Main`'s
 * `--report-sighting` CLI flag is the stand-in for that today — this store is fully testable end to
 * end without the bot, but the bot is still the missing prerequisite for how a real user would ever
 * call this.
 */
trait SightingStore:
  def record(sighting: Sighting): Unit < Sync
  def recentFor(beachName: String, limit: Int = 5): List[Sighting] < Sync
