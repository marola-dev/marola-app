package marola.experiment

import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}

import kyo.*

import marola.experiment.schema.{ObservationSample, Variable}
import marola.http.Http

/**
 * The METAR and CPC fixtures were recorded live on 2026-10-09; INMET's is its documented shape, not
 * a recording, until an INMET_TOKEN exists (docs/4-reference_ground-truth.md).
 */
class ObservationClientSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def resource(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"observations/$name")
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  private class Recording(pages: Map[String, String]) extends Http.Transport:
    var requested: List[String] = Nil
    def send(request: HttpRequest): Http.Response =
      requested = requested :+ request.uri.toString
      pages.get(request.uri.toString).fold(Http.Response(404, ""))(Http.Response(200, _))

  private def run[A](transport: Http.Transport)(effect: A < Sync): A =
    Http.withTransport(transport)(Sync.Unsafe.evalOrThrow(effect))

  private def point(id: String, kind: GroundTruth.Kind, station: String) =
    GroundTruth.Point(id, "SC", kind, station, None, None, GroundTruth.Status.Candidate, None)

  private val Sbfl = point("sc-sbfl", GroundTruth.Kind.Metar, "SBFL")
  private val A806 = point("sc-a806", GroundTruth.Kind.Inmet, "A806")
  private val Day = LocalDate.of(2026, 10, 9)

  private def at(samples: Vector[ObservationSample], iso: String): Vector[ObservationSample] =
    samples.filter(_.validTime.equals(Instant.parse(iso)))

  test("metar_knots_to_ms_and_temperature") {
    val body = resource("metar-2026-10-09.json")
    val url = MetarClient.url(Vector("SBFL"), 24)
    val samples = run(Recording(Map(url -> body)))(MetarClient.fetch(Vector(Sbfl), 24))
    assertEquals(samples.map(_.instrument).toSet, Set("sc-sbfl"), "the other airports are dropped")

    // METAR SBFL 092300Z 08004KT ... 21/18: 4 kt is 2.06 m/s, so the direction is kept.
    assertEquals(
      at(samples, "2026-10-09T23:00:00Z").map(s => (s.variable, s.value, s.averaging)),
      Vector(
        (Variable.WindSpeed10m, 4 * 1852.0 / 3600, "10min_mean"),
        (Variable.WindDirection10m, 80.0, "10min_mean"),
        (Variable.Temperature2m, 21.0, "instant")
      )
    )
    // 26003KT: 1.54 m/s is below 2 m/s, so no direction.
    assertEquals(
      at(samples, "2026-10-09T10:00:00Z").map(_.variable),
      Vector(Variable.WindSpeed10m, Variable.Temperature2m)
    )
    // VRB02KT has no direction at all; the SPECI at 00:40 is kept like a METAR.
    assertEquals(
      at(samples, "2026-10-09T19:00:00Z").map(_.variable),
      Vector(Variable.WindSpeed10m, Variable.Temperature2m)
    )
    assertEqualsDouble(
      at(samples, "2026-10-09T00:40:00Z").head.value,
      Observations.knotsToMs(13),
      1e-12
    )
    assert(samples.forall(_.qcPassed))
  }

  test("inmet_hour_parsed") {
    val url = InmetClient.url("A806", Day, Day.plusDays(1), "t0k3n")
    val samples = run(Recording(Map(url -> resource("inmet-a806-shape-unrecorded.json")))) {
      InmetClient.fetch(Vector(A806), Day, Day.plusDays(1), Some("t0k3n"))
    }
    // HR_MEDICAO is UTC: "2300" on 2026-10-09, then "0000" on the 10th; the 01 UTC hour is empty.
    assertEquals(
      samples.map(s => (s.validTime, s.variable, s.value, s.averaging)),
      Vector(
        (Instant.parse("2026-10-09T23:00:00Z"), Variable.WindSpeed10m, 3.1, "10min_mean"),
        (Instant.parse("2026-10-09T23:00:00Z"), Variable.WindDirection10m, 95.0, "10min_mean"),
        (Instant.parse("2026-10-09T23:00:00Z"), Variable.Temperature2m, 20.9, "1min_mean"),
        (Instant.parse("2026-10-10T00:00:00Z"), Variable.WindSpeed10m, 1.4, "10min_mean"),
        (Instant.parse("2026-10-10T00:00:00Z"), Variable.Temperature2m, 20.3, "1min_mean")
      )
    )
    assertEquals(samples.map(_.instrument).toSet, Set("sc-a806"))
  }

  test("inmet_failure_hides_the_token") {
    val failure = intercept[InmetClient.InmetError] {
      run(Recording(Map.empty))(InmetClient.fetch(Vector(A806), Day, Day, Some("s3cr3t")))
    }
    assertEquals(failure.getMessage, "INMET A806 2026-10-09..2026-10-09: HTTP 404")
  }

  test("inmet_without_token_skipped") {
    val transport = Recording(Map.empty)
    val samples = run(transport)(InmetClient.fetch(Vector(A806), Day, Day, None))
    assertEquals(samples, Vector.empty)
    assertEquals(transport.requested, Nil)
  }

  test("nino34_anomaly_parsed") {
    val weeks = Nino34Reader.parse(resource("cpc-wksst9120-2026-10-09.for"))
    assertEquals(weeks.size, 6, "the header lines are not weeks")
    assertEquals(weeks.last, Nino34Reader.Week(LocalDate.of(2026, 9, 30), 29.9, 3.2))
    // A negative anomaly touches the SST before it in CPC's fixed-width columns.
    assertEquals(
      Nino34Reader.parse(" 02SEP1981     20.6-0.1     24.8-0.1     26.5-0.2     28.3-0.3"),
      Vector(Nino34Reader.Week(LocalDate.of(1981, 9, 2), 26.5, -0.2))
    )
  }
