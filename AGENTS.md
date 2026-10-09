# AGENTS.md

Instructions for any AI coding agent working in **marola-app**. This is the repo layer
(MIP-0070 §5.1): the workspace rules live in the umbrella's
[AGENTS.md](https://github.com/marola-dev/marola/blob/main/AGENTS.md); this file says what this
repo is and where it differs.

<!-- invariants:start -->
## Org invariants

Non-negotiable in every marola repo; a repo may make these stricter, never looser (MIP-0070 §5.1).

- **Cost and deployment safety**: never provision or deploy a paid cloud resource without explicit human confirmation first ([AGENTS.md](AGENTS.md#cost--deployment-safety-hard-rule)).
- **No secrets in code**: never hardcode a key/connection string/secret; `.env.example` holds placeholders only ([AGENTS.md](AGENTS.md#cost--deployment-safety-hard-rule)).
- **The agent-ready gate**: an agent may only begin implementation on an issue carrying `agent-ready` ([AGENTS.md](AGENTS.md#issue-tracking-hard-rule)).
- **The three commit trailers**: commits carry three trailers and nothing else — `Tested:`, `Cost:`, and `Co-Authored-By: Claude <noreply@anthropic.com>` ([AGENTS.md](AGENTS.md#attribution-and-cost-accounting-hard-rule)).
- **Phase discipline**: work one phase at a time; never start a later phase before the current one is done ([AGENTS.md](AGENTS.md#phase-discipline-hard-rule)).
<!-- invariants:end -->

## What this repo is

The marola product: the Scala 3 / Kyo pipeline that answers "what's the best hour tomorrow to swim
nearby?" from OpenStreetMap beaches, Open-Meteo conditions and a two-pass LLM summary, runnable
entirely locally with a free Ollama model. One sbt multi-project build (`build.sbt`):

- `core/`: pure pipeline logic, shared HTTP/JSON helpers, the traits (`LlmClient`, `VisionClient`,
  `SightingStore`) `local/` implements.
- `local/`: Ollama-backed implementations (LLM, vision) and the local-file sighting store.
- `cli/`: `Main`, `AppConfig` (settings from env vars), the MCP tool server, the benchmark runner,
  `--site`'s board writer. Use `sbt cli/run` / `cli/runMain …`, not `sbt run` at the root (a pure
  aggregate with no source of its own).
- `experiment/`: the forecast experiment (MIP-0083, ADR-0002): its schemas (`schema/`, kyo-schema,
  with the lake DDL and the scorecard's JSON Schema under `src/main/resources/experiment/`) and its
  ground-truth points, `ground-truth.json`, documented in `docs/4-reference_ground-truth.md`.
- `oods/src/test/resources/`: the OODS ingest's test fixtures (MIP-0056); its code lands here too.
- `README.md` (the landing) and `docs/`: this repo's numbered pages (`1-design*`, `2-libraries*`,
  `3-development`, `4-reference*`) and ADRs (`docs/adr/`), mounted by the umbrella at
  docs.marola.dev/5-Repos/marola-app/ (MIP-0074 §5.2). Links stay relative inside the repo and use
  absolute `https://docs.marola.dev/…` URLs for the umbrella's pages; user and system pages (Run
  it locally, Telegram setup, Architecture) live there.

## What it consumes and produces

| Direction | Contract |
|---|---|
| corpus → app | `corpus.version` pins a marola-corpus tag; `scripts/corpus-fetch.sh` (`just corpus-fetch`, sbt's `corpusFetch`) unpacks it into `.tmp/knowledge`, which tests, `just ask` and the image read. A corpus checkout overrides it: `MAROLA_KNOWLEDGE_DIR=<corpus>/knowledge` |
| app → site | `ghcr.io/marola-dev/marola-app:jvm-<sha>` (`docker.yml`, pushed from `main`), which marola-site pins by digest and runs with `--site --areas …`: board data only, against the `board.schema.json` the jar ships |
| app → site `site-data` | `coverage/` (`ci.yml` on `main`) and `smoke/` (`docker-smoke.yml`), pushed with `MAROLA_CROSS_REPO_PAT` |
| app → ml | the same image (`--benchmark`) and `ml-resources-<tag>.tar.gz` on each `v*` release (`release.yml`, `scripts/build-resources-tarball.sh`) |
| ml → app | the compiled-prompt JSON, as a bot PR into `core/src/main/resources/` |
| app → umbrella | `README.md` + `docs/` (`notify-umbrella.yml`); Scaladoc (`just api-docs`) on the `api-docs` branch from every `main` push (`api-docs.yml`) |

No repo reads another's tree, and this repo's CI never builds a consumer (MIP-0070 §5.4).

## Setup & commands

```bash
nix develop          # JDK 25, sbt, scala-cli, coursier, ollama, the lint tools and the devkit's
just                 # list all recipes
just build           # sbt compile
just test            # corpus-fetch, then sbt test
just quality         # quality-scala (scalafmt + scalafix) and quality-other (ruff, shellcheck,
                     # the scripts' self-tests, actionlint, hadolint, agents-check,
                     # docs-lint)
just fmt             # scalafmtAll
just run -- --brief  # the CLI; `just mcp-server` for the MCP tool server
just e2e             # the live E2E test (Overpass/Open-Meteo/Ollama), excluded from `just test`
just coverage        # sbt-scoverage across core/local/cli
```

Always run `just build && just test && just quality` before considering a change done. The
devkit's git hooks (`core.hooksPath .devkit/.githooks`, set by the dev shell) run `just precommit`
and `just prepush`.

**JDK 25 is required, not just "17+".** Kyo's artifacts won't load on an older JVM. See
`.claude/rules/scala.md` (loaded automatically while editing `.scala`/`build.sbt`) for the full
JDK/Kyo-versioning detail and the jar-verification approach (`.claude/agents/jar-verifier.md`) for
Kyo's pre-1.0 API surface.

## Releases

A `v*` tag (a human's act: `git tag` and `gh release` are denied in `.claude/settings.json`) runs
`release.yml`, which attaches `ml-resources-<tag>.tar.gz` and never replaces an asset already
there. The image is not tied to tags: every `main` push publishes
`:jvm` and `:jvm-<sha>`, and consumers pin the digest.

## Phase discipline (hard rule)

The phase list is the umbrella's `docs/PHASES.md`. Do not start Phase 2 (going live on a cloud
backend, GCP per MIP-0057) before Phase 1 (the Telegram bot actually working) is done. If asked to
jump ahead, implement the requested feature but flag which earlier-phase prerequisite is missing.

## Issue tracking (hard rule)

An agent starts work only on an issue carrying `agent-ready`, in this repo (MIP-0070 §5.7).
`just issue-queue` / `just issue-claim <n>` come from the devkit.

## Cost & deployment safety (hard rule)

**Never provision or deploy a paid cloud resource without explicit human confirmation first.**
Propose the change, state the expected cost, wait for a go-ahead. Never hardcode a
key/connection string/secret; `.env.example` holds placeholders only.

## Attribution and cost accounting (hard rule)

Commits carry `Tested:`, `Cost:` and `Co-Authored-By: Claude <noreply@anthropic.com>` and nothing
else, as in the umbrella; `just pr` fills a missing trailer and writes the PR body.

## Code style

Scala style, the Kyo effect boundary and testing discipline are in `.claude/rules/scala.md`.
Prefer `enum` + exhaustive matching over exceptions for expected failure modes, and reproduce a
bug with a failing test before fixing it.

**Comments: write few, and only what the code cannot say**: a why (a non-obvious decision, a
constraint from outside the file), a trap, or a pointer to the MIP or issue. Never restate the
code, narrate a fix's history, or paste a paragraph where a clause works; that belongs in the
commit message.

## Before implementing a feature

Check the umbrella's `docs/MIPs/` and `docs/4-Research-and-plans/FUTURE-WORK.md` first: the idea
may already be designed or decided. A non-trivial change is designed there as a MIP
(`/marola-devkit:mip`) before it is built here.
