package marola.agent

import kyo.*
import marola.model.{Beach, BestHour, Coordinates}
import marola.json.JsonValue
import marola.{AppConfig, Recommender}
import marola.beaches.BeachFinder
import io.modelcontextprotocol.server.{McpServer, McpSyncServerExchange}
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import tools.jackson.databind.json.JsonMapper

/**
 * Exposes marola's own pipeline (`BeachFinder`, `Recommender`) as MCP tools — the "Foundry Agent
 * Service + MCP tool-calling" item from `ARCHITECTURE.md` §5b: instead of `Recommender` hardcoding
 * the call order (BeachFinder → OpenMeteoClient → Swimability), an agent (Claude Desktop locally,
 * or an Azure AI Foundry agent once deployed) can decide when/how to call these tools itself.
 *
 * Runs over **stdio** (`StdioServerTransportProvider`) — the simplest MCP transport, and the one
 * that needs zero network exposure: point any local MCP client's config at this jar (`java -cp ...
 * marola.agent.SwimConditionsMcpServer`) and it "just works", no Azure account, no public URL. A
 * Foundry agent's *remote* MCP tool config needs an HTTP-reachable server instead (this SDK also
 * ships `HttpServletSseServerTransportProvider`/`HttpServletStreamableServerTransportProvider` for
 * that — not wired up here, since it needs an actual servlet container and a public endpoint, i.e.
 * real deployment, which nothing in this repo has yet per `AGENTS.md`'s cost-safety rule).
 *
 * Verified: this file compiles and, run manually, correctly registers with and responds to the MCP
 * `tools/list` and `tools/call` methods (checked by piping raw JSON-RPC requests to stdin — see
 * `ARCHITECTURE.md` §5b's Status note). NOT verified against Claude Desktop or a Foundry agent
 * directly — that requires configuring an actual MCP client to launch this process, which wasn't
 * available to test in this environment.
 */
object SwimConditionsMcpServer:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private def runSync[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  private def numberArg(args: java.util.Map[String, Object], key: String): Option[Double] =
    Option(args.get(key)).map {
      case n: java.lang.Number => n.doubleValue()
      case s: String           => s.toDouble
      case other               => other.toString.toDouble
    }

  private def beachToJson(beach: Beach): JsonValue =
    JsonValue.obj(
      "name" -> JsonValue.str(beach.name),
      "lat" -> JsonValue.num(beach.coordinates.lat),
      "lon" -> JsonValue.num(beach.coordinates.lon),
      "distance_km" -> JsonValue.num(beach.distanceKm)
    )

  private def bestHourToJson(best: BestHour): JsonValue =
    JsonValue.obj(
      "beach_name" -> JsonValue.str(best.beach.name),
      "distance_km" -> JsonValue.num(best.beach.distanceKm),
      "hour_local" -> JsonValue.str(best.hour.time.toString),
      "score" -> JsonValue.num(best.score.toDouble),
      "sea_temp_c" -> best.hour.seaTempC.map(JsonValue.num).getOrElse(JsonValue.JNull),
      "wind_kmh" -> best.hour.windSpeedKmh.map(JsonValue.num).getOrElse(JsonValue.JNull),
      "wave_height_m" -> best.hour.waveHeightM.map(JsonValue.num).getOrElse(JsonValue.JNull),
      "jellyfish_risk" -> JsonValue.str(best.jellyfishRisk.toString),
      "whale_sighting_likelihood" -> JsonValue.str(best.whaleSightingLikelihood.toString),
      "notes" -> JsonValue.arr(best.notes.map(JsonValue.str)*)
    )

  private def numberProperty(description: String): java.util.Map[String, Object] =
    java.util.Map.of("type", "number", "description", description)

  private val latLonRadiusSchema: java.util.Map[String, Object] = java.util.Map.of(
    "type",
    "object",
    "properties",
    java.util.Map.of(
      "lat",
      numberProperty("Latitude"),
      "lon",
      numberProperty("Longitude"),
      "radius_km",
      numberProperty("Search radius in km, default 15")
    ),
    "required",
    java.util.List.of("lat", "lon")
  )

  private val findBeachesTool = McpSchema.Tool
    .builder("find_nearby_beaches", latLonRadiusSchema)
    .description("Find named open-water swim beaches near a coordinate, via OpenStreetMap.")
    .build()

  private val recommendationTool = McpSchema.Tool
    .builder("get_swim_recommendation", latLonRadiusSchema)
    .description(
      "Get tomorrow's best swim hour for each beach near a coordinate: sea temperature, wind, " +
        "wave height, a swimability score, jellyfish risk, and whale sighting likelihood."
    )
    .build()

  // `exchange` is unused in both handlers below but required by the MCP SDK's BiFunction
  // signature — kept named (not `_`) so it stays self-documenting at the call site.
  private def findBeachesHandler(
      exchange: McpSyncServerExchange,
      request: McpSchema.CallToolRequest
  ): McpSchema.CallToolResult =
    val args = request.arguments()
    val lat = numberArg(args, "lat").getOrElse(0.0)
    val lon = numberArg(args, "lon").getOrElse(0.0)
    val radiusKm = numberArg(args, "radius_km").getOrElse(15.0)
    val beaches = runSync(BeachFinder.nearby(Coordinates(lat, lon), radiusKm))
    val json = JsonValue.arr(beaches.map(beachToJson)*).render
    McpSchema.CallToolResult.builder().addTextContent(json).build()

  private def recommendationHandler(
      exchange: McpSyncServerExchange,
      request: McpSchema.CallToolRequest
  ): McpSchema.CallToolResult =
    val args = request.arguments()
    val lat = numberArg(args, "lat").getOrElse(0.0)
    val lon = numberArg(args, "lon").getOrElse(0.0)
    val radiusKm = numberArg(args, "radius_km").getOrElse(15.0)
    val config = AppConfig.fromEnv
    val results = runSync(
      Recommender.bestPerBeachTomorrow(
        Coordinates(lat, lon),
        radiusKm,
        distanceRefiner = config.distanceRefiner
      )
    )
    val json = JsonValue.arr(results.map(bestHourToJson)*).render
    McpSchema.CallToolResult.builder().addTextContent(json).build()

  def main(args: Array[String]): Unit =
    val jsonMapper = JacksonMcpJsonMapper(JsonMapper.builder().build())
    val transport = StdioServerTransportProvider(jsonMapper)
    val server = McpServer
      .sync(transport)
      .serverInfo("marola-swim-conditions", "0.1.0")
      .instructions(
        "Tools for finding nearby open-water swim beaches and tomorrow's best swim conditions " +
          "(sea temperature, wind, waves, jellyfish risk, whale sighting likelihood)."
      )
      .toolCall(findBeachesTool, findBeachesHandler(_, _))
      .toolCall(recommendationTool, recommendationHandler(_, _))
      .build()
    // Blocks forever serving stdio requests, per StdioServerTransportProvider's own design — this
    // main is meant to be launched by an MCP client (Claude Desktop, an agent framework), not run
    // interactively.
    Runtime.getRuntime.addShutdownHook(Thread(() => server.closeGracefully()))
