# Libraries

Every library and pinned tool this repo uses, why, at which version, and what else was weighed.
Versions are read from `build.sbt`, `project/`, `flake.nix`, the `Dockerfile`,
`docker-compose.yml` and `ci.yml`. scala-steward opens the bump PRs for the first three tables,
dependabot for the workflows' actions; the devkit tag moves by hand
([Development](3-development.md#ci-workflows)). What to adopt from Scala 3 and the JDK themselves
is the [Scala 3 and JDK review](2-libraries_scala3-jdk.md).

## Language and build

| Tool | Version | Pinned in | Why |
|---|---|---|---|
| Scala | 3.9.0 | `build.sbt` | The LTS line; Kyo's recommended strict flags (`-Wvalue-discard`, `-Wnonunit-statement`, `-language:strictEquality`) are set there |
| JDK | 25 | `flake.nix` (`jdk25`), CI's `setup-java`, the Dockerfile's Temurin 25 images | Kyo 1.0.0-RC7's class files are version 69, so both sbt and the runtime need 25 or later |
| sbt | 1.13.0 | `project/build.properties` | The build; `flake.nix` rebuilds nixpkgs' sbt on JDK 25 because its wrapper hardcodes its own `JAVA_HOME` |
| scalafmt | 3.8.3 | `.scalafmt.conf` | Formatting, checked in CI |

## Libraries

| Library | Version | Module | Why | Alternatives |
|---|---|---|---|---|
| Kyo: `kyo-core`, `kyo-direct`, `kyo-combinators` | 1.0.0-RC7 | all | The effect system at the I/O boundary (`Sync`, `Async`, `Abort`); `kyo-direct` for the direct-style syntax. Pre-1.0 with no version-specific docs, so the pinned jar is the reference (`.claude/agents/jar-verifier.md`) | Every other RC7 module was reviewed: [Kyo modules at 1.0.0-RC7](#kyo-modules-at-100-rc7) |
| Kyo: `kyo-schema-json` (all), `kyo-mcp` (cli) | 1.0.0-RC7 | all, cli | On the classpath ahead of [MIP-0077](https://github.com/marola-dev/marola/pull/675)'s tasks; nothing imports them yet. They pull `kyo-schema` and `kyo-jsonrpc` | — |
| JDK `java.net.http` | (the JDK) | core | `Http`, a thin client wrapper with one `Transport` seam that the golden spec replays fixtures through | `kyo-http`, deferred ([below](#kyo-modules-at-100-rc7)) |
| Hand-rolled JSON (`marola.json`) | — | core | Every shape read or written is plain nested objects, arrays, strings and numbers. 375 `JsonValue` references across 29 main files navigate them by hand | `kyo-schema-json`, promoted ([MIP-0077](https://github.com/marola-dev/marola/pull/675)) |
| `logback-classic` | 1.6.5 | all | The SLF4J backend; `cli`'s `logback.xml` sends every logger to stderr, because stdout is the MCP server's JSON-RPC channel. `marola.log.Log` wraps SLF4J directly | scala-logging, rejected: its only Scala 3 release is 4.0.0-RC1. `kyo-logging-slf4j`, rejected ([below](#kyo-modules-at-100-rc7)) |
| MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`) | 2.0.1 | cli | `SwimConditionsMcpServer`'s stdio transport and tool registry. It finds its JSON-schema validator through `ServiceLoader`, which is why the assembly concatenates `META-INF/services` | `kyo-mcp`, promoted ([MIP-0077](https://github.com/marola-dev/marola/pull/675)) |
| OpenTelemetry `opentelemetry-sdk`, `opentelemetry-exporter-otlp` (`opentelemetry-sdk-testing` in tests) | 1.66.0 | local | Traces to MLflow, which ingests them over OTLP/HTTP only (MIP-0010) | `kyo-stats-otlp`, deferred ([below](#kyo-modules-at-100-rc7)) |
| Apache PDFBox | 3.0.8 | local | The agencies publish bulletins only as PDFs; a JVM parser keeps the image free of native tools (MIP-0031 §4.3) | bundling `pdftotext` in the image, rejected |
| munit | 1.3.6 | all (tests) | The test framework; its tags keep `E2E` suites out of `just test` | `kyo-test-*`, deferred ([below](#kyo-modules-at-100-rc7)) |

## sbt plugins

All in `project/plugins.sbt`.

| Plugin | Version | Why |
|---|---|---|
| sbt-assembly | 2.5.0 | The fat jar the image runs (`cli/assembly`); its merge strategy keeps `META-INF/services` and `META-INF/native-image` |
| sbt-scalafmt | 2.6.2 | `just fmt`, `quality-scala` |
| sbt-scalafix | 0.14.9 | Semantic lint (`.scalafix.conf`: unused code, import order, banned syntax); needs SemanticDB, enabled in `build.sbt` |
| sbt-native-image | 0.5.0 | `sbt cli/nativeImage`, run by `just native-image` with nixpkgs' GraalVM |
| sbt-scoverage | 2.4.4 | `just coverage` and the README's coverage badge |

## Pinned tools and images

| Tool | Version | Pinned in | Why |
|---|---|---|---|
| marola-devkit | v0.4.1 | `flake.nix`, every devkit `uses:` and `devkit-ref:` and the docs-lint clone in `.github/workflows/`, the plugin marketplace `ref` in `.claude/settings.json` | The shared recipes, git hooks, reusable workflows and lint tools, `docs-lint` included |
| nixpkgs | `nixos-unstable`, locked | `flake.lock` | What `nix develop` and the `dev` image resolve; CI's `flake-lock` job fails when the lock is stale |
| ruff, actionlint, hadolint | 0.16.5, 1.7.12, 2.14.0 | `ci.yml` inputs | The versions CI lints with; locally they come from the devkit |
| `sbtscala/scala-sbt` | `eclipse-temurin-25.0.4_7_1.13.0_3.8.4` | `Dockerfile` (`builder`) | A JDK 25 build image; sbt itself still runs at `build.properties`' version |
| `eclipse-temurin` | `25-jre-alpine` | `Dockerfile` (`jvm`, `corpus`) | A small JRE for the `jvm` image |
| `ghcr.io/graalvm/native-image-community` | 25.0.2 | `Dockerfile` (`native-build`) | Pinned because the floating `:25` moved to a release that rejects a launcher flag in the image's `Args` |
| `gcr.io/distroless/base-debian12` | `nonroot` | `Dockerfile` (`native`) | glibc, CA certificates and tzdata with no shell; `libz` comes from `debian:12.12-slim` |
| `nixos/nix` | 2.35.2 | `Dockerfile` (`dev`) | `nix develop` in an image |
| `ollama/ollama` | 0.33.3 | `docker-compose.yml` | The `ollama` profile's model server |
| `ghcr.io/mlflow/mlflow` | v3.16.0 | `docker-compose.yml` | The `mlflow` profile; traces need MLflow 3.6 or later, whose OTLP endpoint takes an experiment id |

## Reviewed, not adopted

### Kyo modules at 1.0.0-RC7

Every `io.getkyo` module published for Scala 3 at 1.0.0-RC7 (Maven Central, lastUpdated
2026-09-28) that could replace or simplify something in `core`, `local`, `cli` or the `oods` code
of [MIP-0075](https://docs.marola.dev/6-MIPs/MIP-0075-water-quality-store-r2/) (the DuckDB/JDBC
store, the HTTP `Throttle`, `OodsS3Config`, `marola.oods.Main`'s arguments, the JSON exports).
marola-site, marola-oods and marola-ml run this repo's image and count through that code. Reviewed
2026-10-06 (#55) against the RC7 jars and sources jars, read with JDK 25 `javap`
(`.claude/agents/jar-verifier.md`), not against getkyo.io. "Pulls" is what the module adds to
marola's current `kyo-core` + `kyo-direct` + `kyo-combinators` set (`cs resolve`); none of them
pulls netty. The promoted three are planned in
[MIP-0077](https://github.com/marola-dev/marola/pull/675), one marola-app PR each;
nothing here changes code.

| Module | marola today, and where | Verdict | Why |
|---|---|---|---|
| `kyo-schema` + `kyo-schema-json` | `marola.json` (`core/.../json/Json.scala`, 200 lines), 375 `JsonValue` references in 29 main files | **promote** | `Json.decode[A]` returns a pure `Result[DecodeException, A]`, so decoding stays outside the effect boundary. `derives Schema`, `@rename`, `Option` and `Map[String, V]` codecs, unknown fields skipped by default. The sources have no reflection or `ServiceLoader`, so it should suit the native `cli` (MIP-0077 task 1 proves it). Pulls only `kyo-schema` and `kyo-system` |
| `kyo-mcp` + `kyo-jsonrpc` | MCP Java SDK 2.0.0 (`cli/.../agent/SwimConditionsMcpServer.scala`), with its `AllowUnsafe.embrace.danger` boundary | **promote** | `McpHandler.tool[In]` derives each tool's JSON Schema from `Schema[In]` and runs a Kyo handler, so the unsafe boundary goes. `JsonRpcTransport.stdioWith` diverts `Console` output to stderr. Removes Jackson 3.0.3, Reactor 3.7.0, `json-schema-validator`, snakeyaml-engine. The server is a JVM-only main class; the line-delimited stdio wire runs on `Console`, not on kyo-net |
| `kyo-case-app` | `args.indexOf` parsing in `cli/.../Main.scala`; nothing yet for `marola.oods.Main` | **promote** (`oods` only) | MIP-0075's six `oods` subcommands, with repeated, enumerated and numeric flags, become case classes under `KyoCommand`/`CommandsEntryPoint` (case-app 2.1.0) instead of a third hand parser. `oods` is JVM-only. `cli`'s positional-optional grammar stays as it is |
| `kyo-http` (client and server) | `marola.http.Http` on `java.net.http` (`core`); `ChatServer` on `com.sun.net.httpserver` (`cli`) | **defer** | Its own HTTP/1.1 stack on `kyo-net`: Panama (FFM) io_uring/epoll backends and BoringSSL/OpenSSL bindings, with an NIO and JDK-TLS floor. It has no HTTP proxy option, so MIP-0075 §4.6's Brazil proxy (`-Dhttps.proxyHost`) would be ignored. `Http.withTransport` has no counterpart, and FFM under native-image is unproven. **Revisit** when it supports a proxy and `just native-image` builds and runs with it |
| `kyo-stats-otlp` | `opentelemetry-sdk` + `-exporter-otlp` 1.66.0 (`local/.../MlflowTracing.scala`) | **defer** | `OTLPTraceExporter.init(OTLPConfig)` exports OTLP/JSON through kyo-http and traces through Kyo's own `Trace` API, not OTel spans. Whether MLflow's OTLP endpoint takes JSON with `x-mlflow-experiment-id` is unverified. **Revisit** after kyo-http, with one live MLflow run |
| `kyo-stats-otel` | as above | **reject** | Last published at 1.0-RC1 (2025-07), not at RC7 |
| `kyo-ai` | `LlmClient` + `LocalLlmClient` (`core`, `local`, ~60 lines), `VisionClient` | **defer** | `Config.apiUrl` can point its OpenAI-compatible backend at Ollama's `/v1`, but it pulls kyo-http, kyo-mcp and kyo-actor into the native `cli` for a 60-line client. **Revisit** after kyo-http, or when marola needs typed structured output or tool calling from the model |
| `kyo-llm` | as above | **reject** | Latest is 0.9.0 (2024-03), pre-1.0. kyo-ai is its successor. The earlier "no `kyo-ai` on Maven Central" note was wrong from RC6 on |
| `kyo-logging-slf4j`, `kyo-logging-jpl` | `marola.log.Log` (22 lines over SLF4J), `logback-classic` | **reject** | One-file bridges that route Kyo's effectful `Log` to SLF4J or `System.Logger`. marola's `Log` is deliberately not effectful, and logback stays either way |
| `kyo-config` | `AppConfig.fromEnv` (`cli`); `OodsS3Config` (MIP-0075) | **reject** | Already on the classpath through kyo-core. Its flags are global objects whose env name comes from the JVM class name (`marola.X.y` → `MAROLA_X_Y`), resolved once. marola's env names are fixed contracts, and `.claude/rules/scala.md` prefers an injected `String => Option[String]` |
| `kyo-system` | `java.nio.file`, `sys.env` | **reject** (as a direct dependency) | It comes with kyo-schema-json anyway. `System.env[A]` would make config effectful without removing code |
| `kyo-sql`, `kyo-sql-*` | MIP-0075's `org.duckdb:duckdb_jdbc` 1.5.6.0 | **reject** | It has its own wire drivers (`kyo-sql-postgres`, `-mysql`, `-sqlite`, `-dolt`) and no JDBC bridge or DuckDB backend (0 `duckdb`/`jdbc` entries in the jar). **Revisit** if a DuckDB backend ships |
| `kyo-cache` | `CachedWaterQualityClient` (an on-disk cache, `local`) | **reject** | Last published at 1.0-RC1. kyo-core RC7 ships `kyo.Cache` itself, and marola's cache is a file, not memory |
| `kyo-stm`, `kyo-actor` | none: one `AtomicReference` (`Http`'s transport) | **reject** | marola has no shared transactional state and no actors |
| `kyo-flow` | none | **reject** | A durable workflow engine with an HTTP server and a store. The pipeline is one-shot, and MIP-0075 records each run in `fetch_run`. Same reasoning as workflows4s below |
| `kyo-markdown` | `Corpus.chunkDocument` splits on `# ` headings | **reject** | It renders to `kyo-ui` (it pulls kyo-ui and kyo-http). marola only splits text |
| `kyo-parse` | line regexes in the PDF parsers (`local/.../water/`) | **reject** | There is no grammar to parse; the regexes are short and covered by fixtures |
| `kyo-test-api`, `-runner`, `-prop`, `-snapshot` | munit 1.3.6, a `Sync.Unsafe.evalOrThrow` helper per spec | **defer** | Published at RC7 (`kyo.test.runner.SbtFramework`, `kyo.test.prop.Gen`), but moving ~40 suites removes nothing. **Revisit** with the first property test (`.claude/rules/scala.md`: `Swimability.score` stays within 0 to 100), where kyo-test-prop competes with ScalaCheck. The old `kyo-test` (0.15.1) is pre-1.0: rejected |
| `kyo-reactive-streams` | Reactor, only inside the MCP SDK | **reject** | kyo-mcp removes the only reactive-streams user |
| `kyo-tapir`, `kyo-sttp` | none | **reject** | Last published at 1.0-RC1, not at RC7. marola uses neither tapir nor sttp |
| `kyo-slack` | none (marola's bot is Telegram) | **reject** | No Slack integration |
| `kyo-playwright` | none | **reject** | Last published at 1.0.0-RC2. No browser automation here |

Already pinned, no new module: kyo-core RC7 has `Meter.initRateLimiter`/`initSemaphore`,
`Retry[E](Schedule)` and `kyo.Cache`, which fit MIP-0075's `Throttle` and could replace
`Http.sendRetrying`'s `Thread.sleep` loop.

The jar lines each promote and defer row relies on (`javap -cp <RC7 jar>`):

```text
kyo-schema-json  kyo.Json$: public <A> java.lang.Object decode(java.lang.String, int, int, kyo.Json, kyo.Schema<A>, java.lang.String);
kyo-schema       kyo.schema.rename: public kyo.schema.rename(java.lang.String);
kyo-mcp          kyo.McpServer$package$McpServer$: public java.lang.Object init(kyo.JsonRpcTransport, scala.collection.immutable.Seq<kyo.McpHandler<?, ?, ?>>, java.lang.String);
kyo-mcp          kyo.McpHandler$: public <In> java.lang.String tool$default$2();   (tool is an inline def)
kyo-jsonrpc      kyo.JsonRpcTransport$: public <A, S> java.lang.Object stdioWith(kyo.JsonRpcFramer, kyo.Schema<kyo.JsonRpcEnvelope>, scala.Function1<kyo.JsonRpcTransport, java.lang.Object>, java.lang.String);
kyo-case-app     kyo.KyoCommand: public abstract class kyo.KyoCommand<T> extends caseapp.core.app.Command<T> …
kyo-http         kyo.HttpClient$package$HttpClient$: public <A> java.lang.Object getJson(java.io.Serializable, java.lang.Object, scala.collection.immutable.Seq, kyo.Schema<A>, java.lang.String);
kyo-http         kyo.HttpClientConfig: baseUrl() timeout() connectTimeout() followRedirects() maxRedirects() retrySchedule() retryOn() transportConfig() tls() …  (no proxy; `jar tf | grep -ci proxy` = 0)
kyo-net          jar tf: kyo/net/internal/posix/IoUringBindingsImpl.class, kyo/net/internal/posix/EpollBindingsImpl.class, kyo/net/internal/BoringSslBindingsImpl.class, kyo/net/internal/NioTransport.class
kyo-stats-otlp   kyo.stats.otlp.OTLPTraceExporter$: public kyo.stats.otlp.OTLPTraceExporter init(kyo.stats.otlp.OTLPConfig, kyo.AllowUnsafe);
kyo-ai           kyo.ai.Config: public kyo.ai.Config apiUrl(java.lang.String);
kyo-test-runner  jar tf: kyo/test/runner/SbtFramework.class
kyo-test-prop    kyo.test.prop.Gen$: public kyo.test.prop.Gen<java.lang.Object> int();
kyo-core         kyo.Meter$: public java.lang.Object initRateLimiter(int, long, boolean, java.lang.String);
```

History: kyo-http's client (`getJson`, `postJson`, `getText`, `postBinary`, …) was first
confirmed by decompiling the 1.0.0-RC5 jar, the version pinned when the `kyo-http and kyo-schema`
review was written. The pin has since moved to RC7, and the table above re-reads it. That review's
plan, migrating one integration at a time with `OpenMeteoClient` first and deleting `marola.json`
only when nothing uses it, is MIP-0077's task order.

### workflows4s

[workflows4s](https://github.com/business4s/workflows4s) (0.6.2, checked 2026-10-04) composes
long-running, stateful processes (approval chains, sagas) with event-sourcing semantics and BPMN
rendering, and is not tied to one effect system. The pipeline here
(`Recommender.bestPerBeachTomorrow`, then the draft, then `Reviewer.review`) is a single-shot call
chain with no state to persist and no human step, so it has nothing to orchestrate. **Revisit**
if a stateful flow appears: sightings moderated before they are trusted, a daily digest that must
remember what it sent, or a bounded draft, review, revise loop. Its sibling
[decisions4s](https://github.com/business4s/decisions4s), a decision-table engine, is worth a look
if the jellyfish and whale heuristics outgrow a handful of thresholds; it was not evaluated in
depth.

### neotypes and Iron

[neotypes](https://github.com/neotypes/neotypes) is a type-safe, effect-agnostic Scala driver for
Neo4j. **Not a fit:** marola has no graph data model.

[Iron](https://github.com/Iltotore/iron) (3.x) adds refined types, constraints checked at compile
time or at construction (`Double :| Interval.Closed[0, 100]`). **A real fit, not adopted yet:**
`Swimability.score`'s 0–100 range is a runtime clamp that nothing else enforces, and
`Coordinates(lat, lon)` accepts any `Double`. A handful of type aliases in `model/Models.scala`
would make both illegal states unrepresentable, with no architecture change. It pairs with the
opaque unit types in the [Scala 3 and JDK review](2-libraries_scala3-jdk.md#21-opaque-types-for-units-and-ranges-highest-payoff),
and is a smaller first step than the kyo-schema-json migration (MIP-0077).
