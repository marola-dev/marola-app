package marola.agent

import java.time.{LocalDate, ZoneId}

import kyo.*

import marola.Fixtures
import marola.http.Http
import marola.knowledge.{KnowledgeStore, OceanQa, Passage}
import marola.llm.{ChatMessage, LlmClient}
import marola.model.Coordinates
import marola.water.ImaScWaterQualityClient

class SwimConditionsMcpServerSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private val origin = Coordinates(-27.6733, -48.4700)
  private val fixedToday: ZoneId => LocalDate = _ => LocalDate.of(2026, 9, 5)

  test("recommendation includes agency water quality when the origin is covered") {
    val results = Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SwimConditionsMcpServer.recommendationsFor(
          origin,
          radiusKm = 15.0,
          waterQuality = Some(ImaScWaterQualityClient()),
          today = fixedToday
        )
      )
    }
    val campeche = results.find(_.beach.name == "Praia do Campeche").getOrElse(fail("no Campeche"))
    assert(campeche.waterQuality.nonEmpty, campeche.toString)
  }

  test("ask_ocean_question honours MAROLA_ASK_MIN_SCORE") {
    val store = new KnowledgeStore:
      def search(q: String, k: Int): List[Passage] < Sync =
        List(Passage("Rip currents", "https://x", "swim parallel to the shore", 0.3))
    val llm = new LlmClient:
      def complete(messages: List[ChatMessage]): String < Sync = "Swim parallel to the shore [1]."
    val json = Sync.Unsafe.evalOrThrow(
      SwimConditionsMcpServer.askResponse("rip current?", store, llm, OceanQa.Fallback.Strict, 0.9)
    )
    assertEquals(json("answer").str, Some(OceanQa.NoPassagesReply))
    assertEquals(json("sources").arr.size, 0)
  }

end SwimConditionsMcpServerSpec
