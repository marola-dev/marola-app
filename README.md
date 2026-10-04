# marola-app

<!-- Badge JSON is served by marola.dev from marola-site's site-data branch: coverage/ from this
repo's ci.yml, stats/scala-loc.json from the umbrella's repo-stats job. -->
[![Scala statement coverage (sbt-scoverage)](https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fcoverage%2Flatest.json)](https://github.com/marola-dev/marola-app/actions/workflows/ci.yml)
![Scala lines of code](https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fscala-loc.json)
![Scala 3.9](https://img.shields.io/badge/Scala-3.9_LTS-DC322F?logo=scala&logoColor=white)
![JDK 25](https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white)
![Ollama](https://img.shields.io/badge/runs_on-Ollama_%C2%B7_llama3.2-000000?logo=ollama&logoColor=white)
![MCP](https://img.shields.io/badge/agents-MCP_tools-000000?logo=modelcontextprotocol&logoColor=white)

The marola product: a Scala 3 / Kyo pipeline that finds the beaches near you (OpenStreetMap),
reads tomorrow's sea and weather (Open-Meteo), scores each hour for swimming, and has a local LLM
write the answer while a second pass reviews it. It runs entirely on your machine with a free
Ollama model; no cloud account is needed. The same jar is a CLI, an MCP tool server, a chat server
(`--serve-chat`: `/health` and `/ask` over HTTP, for the map's chat widget), the benchmark runner
and the writer of the boards the [map](https://marola.dev) shows.

It is one of the marola repos under the [umbrella](https://github.com/marola-dev/marola)
(MIP-0070), and its history before the split is marola's, filtered to these files.

**Status:** runs locally end to end as a CLI, an MCP server and a chat server; the image feeds
marola.dev's boards and marola-ml's benchmark. There is no Telegram loop yet: the bot is Phase 1,
and [Telegram setup](https://docs.marola.dev/1-Using-marola/TELEGRAM-SETUP/) only prepares its
credentials.

## Try it

```bash
nix develop
just ollama-up                                   # an Ollama server with llama3.2
just run -- --summarize --lat -27.6733 --lon -48.47
just mcp-server                                  # the MCP tool server
```

Or the published image: `docker run --rm --network host ghcr.io/marola-dev/marola-app:jvm --brief
--lat -27.6733 --lon -48.47`. [Run it locally](https://docs.marola.dev/1-Using-marola/RUN-LOCALLY/)
has every mode, and [Architecture](https://docs.marola.dev/2-Building-marola/ARCHITECTURE/) how
the pipeline fits together.

To build and test:

```bash
just build && just test && just quality
```

`just test` first unpacks the [marola-corpus](https://github.com/marola-dev/marola-corpus) release
pinned in `corpus.version`. JDK 25 is required (Kyo).

## Repo map

| Path | What |
|---|---|
| `core/` | the pure pipeline, the shared HTTP/JSON helpers, the `LlmClient` / `VisionClient` / `SightingStore` traits |
| `local/` | the Ollama-backed implementations and the local-file sighting store |
| `cli/` | `Main`, `AppConfig`, the MCP and chat servers, the benchmark runner, the board writer |
| `oods/src/test/resources/` | the OODS ingest's test fixtures (MIP-0056); its code lands here too |
| `scripts/` | the corpus fetch, the resources tarball, the `site-data` push, Scaladoc post-processing, test fixtures |
| `docs/` | this repo's design, library and reference pages |

## Contracts

| | |
|---|---|
| Consumes | the marola-corpus tag in [`corpus.version`](https://github.com/marola-dev/marola-app/blob/main/corpus.version); compiled prompts from marola-ml, as bot PRs into `core/src/main/resources/` |
| Publishes | the image `ghcr.io/marola-dev/marola-app` (`:jvm`, `:jvm-<sha>`, `:native`) from every `main` push ([`docker.yml`](https://github.com/marola-dev/marola-app/blob/main/.github/workflows/docker.yml)); Scaladoc on the `api-docs` branch, force-pushed from every `main` push (one commit, the latest; [`api-docs.yml`](https://github.com/marola-dev/marola-app/blob/main/.github/workflows/api-docs.yml)); on each `v*` tag, `api-docs.tar.gz` (Scaladoc) and `ml-resources-<tag>.tar.gz` (the prompts, sea lore and benchmark questions marola-ml reads), via [`release.yml`](https://github.com/marola-dev/marola-app/blob/main/.github/workflows/release.yml); `coverage/` and `smoke/` to marola-site's `site-data` branch; `README.md` and `docs/` to docs.marola.dev |
| Pinned by | marola-site, marola-ml and marola-oods pin the image (tag + digest) in `marola-image`; marola-ml also pins the resources tarball in `resources.version` |

## Docs and AGENTS.md

- [Effects map](docs/1-design_effects.md): each module's purity and effect status, and where a
  signature hides an effect.
- [Scala 3 and JDK review](docs/2-libraries_scala3-jdk.md): what to adopt from Scala 3 and the JDK,
  and what to leave alone.
- [API reference](docs/4-reference_api.md): the Scaladoc trees, and marola-ml's pdoc.
- [AGENTS.md](https://github.com/marola-dev/marola-app/blob/main/AGENTS.md): what this repo is and
  where it differs from the umbrella's rules.
