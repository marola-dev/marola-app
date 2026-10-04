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
| sbt | 1.10.7 | `project/build.properties` | The build; `flake.nix` rebuilds nixpkgs' sbt on JDK 25 because its wrapper hardcodes its own `JAVA_HOME` |
| scalafmt | 3.8.3 | `.scalafmt.conf` | Formatting, checked in CI |

## Libraries

| Library | Version | Module | Why | Alternatives |
|---|---|---|---|---|
| Kyo: `kyo-core`, `kyo-direct`, `kyo-combinators` | 1.0.0-RC7 | all | The effect system at the I/O boundary (`Sync`, `Async`, `Abort`); `kyo-direct` for the direct-style syntax. Pre-1.0 with no version-specific docs, so the pinned jar is the reference (`.claude/agents/jar-verifier.md`) | Its own `kyo-http` and `kyo-llm` were reviewed (below) |
| JDK `java.net.http` | (the JDK) | core | `Http`, a thin client wrapper with one `Transport` seam that the golden spec replays fixtures through | `kyo-http` (below) |
| Hand-rolled JSON (`marola.json`) | — | core | Every shape read or written is plain nested objects, arrays, strings and numbers, not worth a derivation-macro codec | `kyo-schema` (below) |
| `logback-classic` | 1.5.13 | all | The SLF4J backend; `cli`'s `logback.xml` sends every logger to stderr, because stdout is the MCP server's JSON-RPC channel. `marola.log.Log` wraps SLF4J directly | scala-logging, rejected: its only Scala 3 release is 4.0.0-RC1 |
| MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`) | 2.0.0 | cli | `SwimConditionsMcpServer`'s stdio transport and tool registry. It finds its JSON-schema validator through `ServiceLoader`, which is why the assembly concatenates `META-INF/services` | none reviewed |
| OpenTelemetry `opentelemetry-sdk`, `opentelemetry-exporter-otlp` (`opentelemetry-sdk-testing` in tests) | 1.65.0 | local | Traces to MLflow, which ingests them over OTLP/HTTP only (MIP-0010) | none reviewed |
| Apache PDFBox | 3.0.8 | local | The agencies publish bulletins only as PDFs; a JVM parser keeps the image free of native tools (MIP-0031 §4.3) | bundling `pdftotext` in the image, rejected |
| munit | 1.0.2 | all (tests) | The test framework; its tags keep `E2E` suites out of `just test` | none reviewed |

## sbt plugins

All in `project/plugins.sbt`.

| Plugin | Version | Why |
|---|---|---|
| sbt-assembly | 2.3.0 | The fat jar the image runs (`cli/assembly`); its merge strategy keeps `META-INF/services` and `META-INF/native-image` |
| sbt-scalafmt | 2.5.2 | `just fmt`, `quality-scala` |
| sbt-scalafix | 0.14.8 | Semantic lint (`.scalafix.conf`: unused code, import order, banned syntax); needs SemanticDB, enabled in `build.sbt` |
| sbt-native-image | 0.5.0 | `sbt cli/nativeImage`, run by `just native-image` with nixpkgs' GraalVM |
| sbt-scoverage | 2.4.4 | `just coverage` and the README's coverage badge |

## Pinned tools and images

| Tool | Version | Pinned in | Why |
|---|---|---|---|
| marola-devkit | v0.3.0 | `flake.nix`, every devkit `uses:` and `devkit-ref:` in `.github/workflows/`, the plugin marketplace `ref` in `.claude/settings.json` | The shared recipes, git hooks, reusable workflows and lint tools |
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

### kyo-http and kyo-schema

kyo-http's client (`getJson`, `postJson`, `getText`, `postBinary`, …) was confirmed by decompiling
the 1.0.0-RC5 jar, the version pinned at the time, because getkyo.io documents only the latest
release. Its codec typeclass, `kyo.Schema[A]`, lives in `kyo-schema`, which kyo-http pulls in. Both
are on Maven Central at 1.0.0-RC7, the version pinned now (checked 2026-10-04); RC7's API has not
been re-read.

Migrating would replace `Http` and `marola.json` with `HttpClient.getJson[A]`/`postJson[A, B]` and
`derives Schema` case classes for every shape parsed by hand today (Overpass, Open-Meteo, Ollama,
the agencies' JSON). That means typed models instead of `JsonValue` navigation, and less code. It
was not done because every call site was verified live against the real APIs, and a library with
no version-specific docs risks a quiet difference in timeouts, redirects or pooling.

**Recommendation:** migrate as a dedicated task, one integration at a time (`OpenMeteoClient`
first, the simplest and most called), re-verifying each against live data, and remove `Http` and
`marola.json` only when no call site uses them. `Http.withTransport`, which the golden spec
replays through, needs an equivalent seam first.

### kyo-llm

There is no `kyo-ai` on Maven Central; the nearest is `kyo-llm`, whose last release is 0.9.0 from
March 2024 (checked again 2026-10-04), built against Kyo before its 0.x to 1.0 redesign. It is
unlikely to resolve beside `kyo-core` 1.0.0-RC7, and its idioms predate the rest of this code.
**Not adopted:** `LlmClient` over plain HTTP to Ollama is small, verified live and has no
compatibility risk. Revisit if kyo-llm ships against Kyo 1.x.

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
and is a smaller first step than the kyo-http migration.
