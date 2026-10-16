package marola.beaches

import java.nio.file.{Files, Path as JPath}

import kyo.*

import marola.MarolaException
import marola.http.Http
import marola.json.JsonValue
import marola.log.Log
import marola.model.{Beach, Coordinates}

/**
 * Finds named beaches near a point via OpenStreetMap's Overpass API (overpass-api.de) — free, no
 * API key, no signup.
 */
object BeachFinder:

  private val log = Log.forName(getClass.getName)

  /**
   * Tried in order. All are public Overpass instances serving the same OSM data through the same
   * API, so this is a mirror rotation, not a fallback to something lesser. Verified 2026-09-09:
   * overpass-api.de answered in 16 s, overpass.private.coffee in 21 s, overpass.kumi.systems
   * returned 504. A single hardcoded endpoint is why a slow day at overpass-api.de failed the whole
   * site build with HttpConnectTimeoutException.
   */
  val Endpoints: List[String] = List(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter"
  )

  /**
   * Upper bound on elements Overpass returns *before* marola sorts them by distance — Overpass's
   * `out N` truncation is in database order, not by distance, so this has to be comfortably larger
   * than the number of named beaches any plausible radius contains, or the nearest ones can be cut
   * arbitrarily.
   */
  private val MaxOverpassElements = 500

  /**
   * Overpass's server-side query budget, and the client-side HTTP timeout kept above it so a slow
   * query surfaces as Overpass's own error rather than a client abort.
   */
  private val OverpassTimeoutSeconds = 45
  private val HttpTimeoutSeconds = 60L

  /**
   * Extra attempts after a 429/5xx from the public instance (`Http.RetryableStatuses`): a 504 under
   * load is routine there and transient — one cost a scheduled site build its second area on 5 Sep
   * 2026.
   */
  private val OverpassRetries = 2

  /**
   * HTTP 200 with a `runtime error` remark: Overpass hit its budget and the elements are partial.
   */
  final case class OverpassIncomplete(endpoint: String, remark: String)
      extends Exception
      with MarolaException:
    def message = s"$endpoint answered with a partial result: $remark"

  def nearby(
      origin: Coordinates,
      radiusKm: Double = 15.0,
      limit: Int = 6,
      // Injected rather than read from the environment inside (`.claude/rules/scala.md`), so a
      // test can point it at a tmpdir and the default still does the right thing in CI.
      snapshots: Option[JPath] = defaultSnapshotDir
  ): List[Beach] < Sync =
    val radiusM = (radiusKm * 1000).toInt
    // All three OSM element types: large beaches are very often mapped as multipolygon
    // *relations*, not ways — confirmed on real data: Praia do Campeche, Joaquina, Armação,
    // Matadeiro and ~40 others around Florianópolis are relations, and a node+way-only query
    // returned just two beaches within 20km of Campeche.
    val around = s"""["natural"="beach"]["name"](around:$radiusM,${origin.lat},${origin.lon})"""
    val query =
      s"""[out:json][timeout:$OverpassTimeoutSeconds];
         |(
         |  node$around;
         |  way$around;
         |  relation$around;
         |);
         |out center $MaxOverpassElements;""".stripMargin

    fromSnapshot(snapshots, origin, radiusKm, limit) match
      case Some(cached) =>
        log.info(s"beaches: ${cached.size} near $origin from the snapshot")
        cached
      case None =>
        queryOverpass(query, Endpoints)
          .map { body =>
            val elements = JsonValue.parse(body)("elements").arr
            elements
              .flatMap(parseElement(_, origin))
              .groupBy(_.name)
              .values
              .map(_.minBy(_.distanceKm)) // one beach per name across node/way/relation
              .toList
              .sortBy(_.distanceKm)
              .take(limit)
          }
          .map { beaches =>
            log.info(s"beaches: ${beaches.size} near $origin from Overpass")
            if beaches.nonEmpty then
              snapshots.foreach(d =>
                BeachSnapshot.write(BeachSnapshot.fileFor(d, origin, radiusKm, limit), beaches)
              )
            beaches
          }

  /**
   * Where committed beach lists live. `MAROLA_BEACHES_DIR` overrides it; unset means `site/beaches`
   * when that directory exists and nothing otherwise, so a plain `just run` from anywhere still
   * works and CI — which has the directory checked in — never calls Overpass.
   */
  def defaultSnapshotDir: Option[JPath] =
    sys.env
      .get("MAROLA_BEACHES_DIR")
      .map(JPath.of(_))
      .orElse(Some(JPath.of("site", "beaches")))
      .filter(Files.isDirectory(_))

  private def fromSnapshot(
      dir: Option[JPath],
      origin: Coordinates,
      radiusKm: Double,
      limit: Int
  ): Option[List[Beach]] =
    dir
      .map(d => BeachSnapshot.read(BeachSnapshot.fileFor(d, origin, radiusKm, limit)))
      .filter(_.nonEmpty)

  /** Each mirror in turn; the last failure propagates so a total outage is still an error. */
  private def queryOverpass(query: String, endpoints: List[String]): String < Sync =
    def post(endpoint: String) =
      Http
        .postForm(endpoint, Map("data" -> query), HttpTimeoutSeconds, retries = OverpassRetries)
        .map { body =>
          JsonValue.parse(body)("remark").str match
            case Some(r) if r.contains("runtime error") => throw OverpassIncomplete(endpoint, r)
            case _                                      => body
        }
    endpoints match
      case Nil             => throw new IllegalStateException("no Overpass endpoint configured")
      case endpoint :: Nil => post(endpoint)
      case endpoint :: rest =>
        Abort
          .run(Abort.catching[Throwable](post(endpoint)))
          .map {
            case Result.Success(body) => body
            case other =>
              log.warn(s"beaches: $endpoint failed ($other) — trying ${rest.head}")
              queryOverpass(query, rest)
          }

  /**
   * OSM has `natural=beach` on playgrounds, clubs and lagoon sand. A denylist rather than "must
   * start with Praia": Jurerê, Mar Grande and Ponta do Capim are real beaches.
   */
  private val GenericNames = Set("praia", "beach", "playa", "plage")
  private val NotABeach = "(?i).*(playgr|parquinho|parque|\\bclube?\\b|condom).*".r
  // Sand on Lagoa Rodrigo de Freitas, not the sea.
  private val NamedJunk = Set("point cap ferrat")

  def isJunkName(name: String): Boolean =
    val n = name.trim.toLowerCase
    n.count(_.isLetter) < 3 || GenericNames(n) || NamedJunk(n) || NotABeach.matches(n)

  private def parseElement(el: JsonValue, origin: Coordinates): Option[Beach] =
    val name = el("tags")("name").str.getOrElse("")
    if name.isEmpty || isJunkName(name) then None
    else
      for
        lat <- el("lat").num.orElse(el("center")("lat").num)
        lon <- el("lon").num.orElse(el("center")("lon").num)
      yield
        val coords = Coordinates(lat, lon)
        Beach(name, coords, origin.distanceKm(coords))
