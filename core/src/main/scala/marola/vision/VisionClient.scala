package marola.vision

import kyo.*

/**
 * Analyzes a user-submitted photo — the differentiator `ARCHITECTURE.md` §4 names for a Telegram
 * bot that can receive images ("is this jellyfish?", "how's visibility right now?"). Same
 * local-vs-Azure split as `LlmClient`/`SightingStore`: `LocalVisionClient` (a multimodal Ollama
 * model, e.g. `llava`/`moondream`, zero Azure account) and `AzureVisionClient` (Azure AI Vision's
 * Image Analysis API — structured captioning/tagging, a genuinely different capability from a
 * conversational multimodal model, not just a redundant path).
 *
 * NOTE on phase discipline, same as `SightingStore`: photos arrive via the Telegram bot, which
 * doesn't exist yet. `Main`'s `--analyze-photo` CLI flag is the local stand-in.
 */
trait VisionClient:
  def describe(imageBytes: Array[Byte]): String < Sync
