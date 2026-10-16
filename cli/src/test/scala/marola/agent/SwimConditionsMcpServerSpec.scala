package marola.agent

import java.time.{LocalDate, ZoneId}

import kyo.*

import marola.Fixtures
import marola.http.Http
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

end SwimConditionsMcpServerSpec
