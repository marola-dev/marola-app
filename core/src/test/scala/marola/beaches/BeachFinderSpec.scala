package marola.beaches

import java.nio.file.Files

import kyo.*

import marola.http.Http
import marola.model.Coordinates

class BeachFinderSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private val campeche = Coordinates(-27.6733, -48.47)

  private def body(name: String, remark: Option[String] = None) =
    val r = remark.fold("")(m => s""","remark":"$m"""")
    s"""{"elements":[{"tags":{"name":"$name"},"center":{"lat":-27.6733,"lon":-48.47}}]$r}"""

  private val timedOut =
    body("Praia de Jurerê", Some("runtime error: Query timed out in \\\"query\\\" at line 3"))

  final class ByHost(bodies: Map[String, String]) extends Http.Transport:
    var seen = List.empty[String]
    def send(request: java.net.http.HttpRequest): Http.Response =
      val host = request.uri().getHost
      seen = seen :+ host
      Http.Response(200, bodies(host))

  test("a timed-out Overpass answer with a remark is a failure, not a snapshot") {
    val dir = Files.createTempDirectory("marola-beaches")
    val t = ByHost(
      Map("overpass-api.de" -> timedOut, "overpass.private.coffee" -> body("Praia do Campeche"))
    )
    val got = Http.withTransport(t) {
      Sync.Unsafe.evalOrThrow(BeachFinder.nearby(campeche, 15.0, 6, snapshots = Some(dir)))
    }
    assertEquals(t.seen, List("overpass-api.de", "overpass.private.coffee"))
    assertEquals(got.map(_.name), List("Praia do Campeche"))
    assertEquals(
      BeachSnapshot.read(BeachSnapshot.fileFor(dir, campeche, 15.0, 6)).map(_.name),
      List("Praia do Campeche")
    )
  }

  test("when every mirror times out the search fails and nothing is written") {
    val dir = Files.createTempDirectory("marola-beaches")
    val t = ByHost(BeachFinder.Endpoints.map(e => java.net.URI(e).getHost -> timedOut).toMap)
    val result = Http.withTransport(t) {
      Sync.Unsafe.evalOrThrow(
        Abort.run(Abort.catching[Throwable](BeachFinder.nearby(campeche, snapshots = Some(dir))))
      )
    }
    result match
      case Result.Failure(BeachFinder.OverpassIncomplete(_, remark)) =>
        assert(remark.contains("timed out"), remark)
      case other => fail(s"Unexpected: $other")
    assertEquals(Files.list(dir).count(), 0L)
  }

end BeachFinderSpec
