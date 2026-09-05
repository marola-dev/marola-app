package marola.beaches

import kyo.*
import marola.http.Http
import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * Finds named beaches near a point via OpenStreetMap's Overpass API (overpass-api.de) — free, no
 * API key, no signup. Good enough for a POC; a production deployment calling this more than
 * occasionally should either self-host an Overpass instance or cache results, per Overpass's own
 * fair-use policy (https://wiki.openstreetmap.org/wiki/Overpass_API#Introduction).
 */
object BeachFinder:

  private val OverpassEndpoint = "https://overpass-api.de/api/interpreter"

  def nearby(origin: Coordinates, radiusKm: Double = 15.0, limit: Int = 6): List[Beach] < Sync =
    val radiusM = (radiusKm * 1000).toInt
    val query =
      s"""[out:json][timeout:20];
         |(
         |  node["natural"="beach"](around:$radiusM,${origin.lat},${origin.lon});
         |  way["natural"="beach"](around:$radiusM,${origin.lat},${origin.lon});
         |);
         |out center ${limit * 4};""".stripMargin

    Http.postForm(OverpassEndpoint, Map("data" -> query)).map { body =>
      val elements = JsonValue.parse(body)("elements").arr
      elements
        .flatMap(parseElement(_, origin))
        .groupBy(_.name)
        .values
        .map(_.minBy(_.distanceKm)) // dedupe node+way pairs for the same beach, keep the closer one
        .toList
        .sortBy(_.distanceKm)
        .take(limit)
    }

  private def parseElement(el: JsonValue, origin: Coordinates): Option[Beach] =
    val name = el("tags")("name").str.getOrElse("")
    if name.isEmpty then None
    else
      for
        lat <- el("lat").num.orElse(el("center")("lat").num)
        lon <- el("lon").num.orElse(el("center")("lon").num)
      yield
        val coords = Coordinates(lat, lon)
        Beach(name, coords, haversineKm(origin, coords))

  /**
   * Straight-line ("as the crow flies") distance — cheap, no extra API call, but can rank a beach
   * across a bay/headland as "nearby" when it's actually a long drive or not reachable at all
   * without a boat (confirmed on real data: Icaraí/Camboinhas in Niterói show up within 15km of
   * Arpoador, straight across Guanabara Bay). A real routing-distance/travel-time call (e.g. OSRM,
   * also free/self-hostable) would fix this; not done here to keep the POC to one dependency-free
   * API family. See docs/ARCHITECTURE.md's "Future work".
   */
  private def haversineKm(a: Coordinates, b: Coordinates): Double =
    val earthRadiusKm = 6371.0
    val dLat = math.toRadians(b.lat - a.lat)
    val dLon = math.toRadians(b.lon - a.lon)
    val la1 = math.toRadians(a.lat)
    val la2 = math.toRadians(b.lat)
    val h = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(la1) * math.cos(la2) * math.sin(dLon / 2) * math.sin(dLon / 2)
    2 * earthRadiusKm * math.asin(math.sqrt(h))
