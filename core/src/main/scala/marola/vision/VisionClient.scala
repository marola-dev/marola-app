package marola.vision

import kyo.*

/**
 * Analyzes a user-submitted photo (`docs/1-design_integrations.md` "Photo analysis") for a Telegram
 * bot that can receive images ("is this jellyfish?", "how's visibility right now?").
 */
trait VisionClient:
  def describe(imageBytes: Array[Byte]): String < Sync
