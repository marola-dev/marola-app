# Development

How this repo is built, tested, packaged and released, and what it costs. How every marola repo
works (branches, reviews, the PR flow, the org's CI rules) is the umbrella's
[Ways of working](https://docs.marola.dev/3-Ways-of-working/DEV-FLOW/) and
[CI/CD](https://docs.marola.dev/3-Ways-of-working/CI-CD/); this page covers what is specific to
marola-app. Flags and variables are in the [CLI](4-reference_cli.md) and
[configuration](4-reference_config.md) references.

## Build and gates

`nix develop` gives JDK 25, sbt, the lint tools and the devkit's recipes. Before calling a change
done:

```bash
just build && just test && just quality
```

`just quality` is `quality-scala` (scalafmt and scalafix through sbt) plus `quality-other`: ruff,
shellcheck, the `scripts/*` self-tests, actionlint, hadolint, agents-check, the devkit's
`docs-lint` (stale recipes, paths and links in `README.md` and `docs/`) and, when Docker is
installed, `docker compose config` across every profile. A missing tool fails the recipe rather
than skipping it. `just quality-fix` applies what can be fixed automatically. The devkit's git hooks
run `just precommit` (staged Scala must compile, staged workflows must pass actionlint) and
`just prepush` (`quality-other` always, `quality-scala` when Scala or the build changed).

## Testing

`just test` unpacks the corpus release pinned in `corpus.version` into `.tmp/knowledge`, then runs
`sbt test` with no network and no Ollama. Suites run one at a time: `Http.withTransport` is a
process-wide switch, so two suites replaying fixtures in parallel would see each other's transport
(`build.sbt`).

### The golden spec

[`PipelineGoldenSpec`](../cli/src/test/scala/marola/PipelineGoldenSpec.scala) replays real
responses recorded on 2026-09-05 through the unchanged production code: Overpass around Campeche,
Open-Meteo weather and marine for two beaches, and IMA/SC's points, all under
[`cli/src/test/resources/fixtures/`](../cli/src/test/resources/fixtures/). It asserts the ranking,
the water-quality verdicts, the tide turns and the exact number of HTTP calls per host, and that an
IMA outage or a stale feed degrades to "no data" instead of failing. `SummarizeFlowSpec` and
`RagOfflineSpec` do the same for the LLM and RAG plumbing with a scripted model and a bag-of-words
embedder; `RagOfflineSpec` reads the corpus from `MAROLA_KNOWLEDGE_DIR`, or `.tmp/knowledge`.

### Re-recording the fixtures

When an upstream format changes, re-record; the fixture diff is the change report.

```bash
# Overpass: the query BeachFinder builds, 15 km around Campeche
q='[out:json][timeout:45];(node["natural"="beach"]["name"](around:15000,-27.6733,-48.47);way["natural"="beach"]["name"](around:15000,-27.6733,-48.47);relation["natural"="beach"]["name"](around:15000,-27.6733,-48.47););out center 500;'
curl -s --data-urlencode "data=$q" https://overpass-api.de/api/interpreter > cli/src/test/resources/fixtures/overpass-campeche.json
# Open-Meteo: one weather and one marine response per fixture beach, with OpenMeteoClient's variables
# IMA/SC: curl -s -X POST https://balneabilidade.ima.sc.gov.br/relatorio/mapa, trimmed to the points near Campeche
```

Then move `fixedToday` in `PipelineGoldenSpec` to the day the new forecasts cover. The Open-Meteo
variables are listed in [Data sources](4-reference.md#sea-and-weather-open-meteo).

### Parser and site fixtures

- `local/src/test/resources/`: the agencies' publications as captured, mostly bulletin PDFs and
  HTML pages for IMA/SC, INEA/RJ and INEMA/BA, plus IMA's map JSON (`ima-mapa-sample.json`). Most
  are named for their date (`inea-boletim-niteroi-2026-09-24.pdf`); INEMA's for its bulletin number
  (`inema-boletim-salvador-13-2025.pdf`). The `*PdfParserSpec` and `*WaterQualityClientSpec` suites
  read them. A new bulletin layout gets a new file beside the old ones.
- `core/src/test/resources/fixtures/`: Overpass responses for facilities (`AccessibilitySpec`) and
  trails (`TrailFinderSpec`).
- `cli/src/test/resources/site/`: `areas.json` and `board.json` for `BoardSpec` and
  `SiteBuilderSpec`. `docker.yml` mounts the same `areas.json` into the image for its start-up
  check, and `board.json` ships in the ml resources tarball (below).

### The `oods/` fixtures

`oods/src/test/resources/ima-sc/` holds the Open Ocean Data Store ingest's fixtures (MIP-0056):
IMA/SC's portal index, its municipality, beach and point lists, per-year CSV exports (including a
header-only file and a year with no records), an old bulletin PDF and a Wayback CDX listing. `oods/`
is not an sbt module yet and no test reads these files; the ingest code lands beside them.

### E2E

`just e2e` runs `E2ESpec` against live Overpass, Open-Meteo and IMA/SC, plus Ollama when one
answers on `localhost:11434`. Its suites carry the `E2E` tag, which `build.sbt` excludes from
`just test`; `MAROLA_E2E_SKIP_LLM=1` skips the LLM test. In CI it is the manual
[`marola-e2e.yml`](../.github/workflows/marola-e2e.yml): the network job needs no Ollama, and the
LLM job is opt-in and caches the model.

### Coverage

`just coverage` runs sbt-scoverage across core, local and cli. On `main`, `ci.yml`'s `coverage`
job does the same, turns the aggregate statement rate into a shields.io endpoint JSON (label
`sc-cov`) and pushes it to marola-site's `site-data` branch as `coverage/latest.json`, which the
README badge reads.

## Images

### Tags

Published to `ghcr.io/marola-dev/marola-app` by [`docker.yml`](../.github/workflows/docker.yml),
from `main` only, when the Dockerfile, the build, the sources, `corpus.version` or the workflow
changed. A PR builds the `jvm` image for amd64 and runs its start-up check, but pushes nothing.

| Tag | What | Platforms |
|---|---|---|
| `jvm`, `jvm-<sha>` | the CLI jar on `eclipse-temurin:25-jre-alpine`, with the pinned corpus at `/app/knowledge` | amd64, arm64 |
| `native`, `native-<sha>` | the same CLI compiled by GraalVM native-image, on `gcr.io/distroless/base-debian12:nonroot` | amd64 |
| `dev`, `dev-<sha>` | `nix develop` in an image, for a machine without Nix; only from a manual dispatch with `dev` ticked | amd64 |

The moving tags (`jvm`, `native`) follow `main`; consumers pin a `jvm-<sha>` by digest. Until the
package is made public (MIP-0065 §4.3), pulling needs `docker login ghcr.io` with a token that has
`read:packages`. Running the published image is a user task, covered in
[Docker](https://docs.marola.dev/1-Using-marola/DOCKER/).

The [`Dockerfile`](../Dockerfile) is one multi-stage file: `builder` (sbt `cli/assembly`, built
once on the build platform), `corpus` (fails the build when `.tmp/knowledge` has no documents),
`jvm`, `native-build`, `native` and `dev`; `docker build .` with no target builds `jvm`.
`.dockerignore` is an allowlist, so `.env` never reaches the build context. Locally,
`just docker-build [jvm|native|dev]` builds `marola:<target>` and `just docker-run -- …` runs
`marola:jvm` on the host network with every `MAROLA_*` variable passed through.

### Compose profiles

[`docker-compose.yml`](../docker-compose.yml) runs the `jvm` image (or builds it from this
checkout). It reads `.env` when present and never copies it into the image; the runtime files live
in the `marola-data` volume.

| Profile | Services | For |
|---|---|---|
| none | `marola` | `--brief` and anything without an LLM |
| `ollama` | `ollama` (`ollama/ollama:0.33.3`), `ollama-pull` | the LLM passes; the model (`MAROLA_LOCAL_LLM_MODEL`, default `llama3.2`) is pulled once into the `ollama-models` volume |
| `local` | `marola-local`, `ollama-local` | the `marola-llama3.2` fine-tune, served by marola-ml's `ghcr.io/marola-dev/marola-ml:local` |
| `mlflow` | `mlflow` (`ghcr.io/mlflow/mlflow:v3.16.0`) | the run ledger and traces (below); independent of the others |

```bash
docker compose run --rm marola --brief --lat -27.6733 --lon -48.47
docker compose --profile ollama run --rm marola --summarize --lat -27.6733 --lon -48.47
docker compose --profile local run --rm marola-local --summarize --lat -27.6733 --lon -48.47
```

CI's `static` job renders every profile (`docker compose --profile mlflow --profile ollama
--profile local config --quiet`), so a broken one fails the PR.

### Native build

```bash
just native-image                                            # GraalVM from nixpkgs, sbt cli/nativeImage -> cli/target/marola
just native-run -- --summarize --lat -27.6733 --lon -48.47   # the binary, same flags as `just run`
just docker-build native                                     # the distroless image
```

The native-image arguments and reachability metadata live in
[`cli/src/main/resources/META-INF/native-image/com.marola/marola-cli/`](../cli/src/main/resources/META-INF/native-image/com.marola/marola-cli/),
read from the classpath, so `sbt cli/nativeImage` and the Dockerfile's `native-image -jar` build
the same binary. native-image does not cross-compile, so the image is amd64 only. The binary runs
`Main`; the MCP server is a separate main class and stays on the `jvm` image.

## Observability

Optional and off by default. `just benchmark` always writes its Markdown report under `data/`,
which stays the canonical result. With a tracking URI set, the same run is also logged to a local
MLflow server:

```bash
just mlflow-up                                     # MLflow on http://127.0.0.1:5000, SQLite and artifacts under .tmp/mlflow/
export MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000
just benchmark                                     # the report under data/, plus a run in "marola/benchmark"
just mlflow-down                                   # stop it; .tmp/mlflow/ keeps the history
```

Open `http://127.0.0.1:5000` in a browser to compare runs. Each run carries the params `model`,
`embed_model`, `min_score`, `corpus_sha`, `git_sha` and `questions`, the per-arm metrics
(`<arm>.coverage_all`, `<arm>.cited_pct`, `<arm>.mean_ms`, …) and the report as an artifact.

**Traces.** With `MAROLA_TRACES=mlflow` too, each recommendation is one OpenTelemetry trace sent
over OTLP/HTTP to the same server, in the `marola/traces` experiment: `marola.recommend` with
`bestPerBeachTomorrow`, `trails` and one `llm.<model>` span per LLM call nested under it. LLM spans
carry `gen_ai.request.model`, the message count and the prompt and completion sizes; the text
itself is attached only with `MAROLA_TRACE_CONTENT=1`, because the prompt contains your
coordinates. `--ask` is traced the same way.

```bash
MAROLA_TRACES=mlflow MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000 just run -- --summarize
```

An unreachable server prints `(traces disabled: …)` and the run carries on untraced; with no
tracking URI there is no MLflow call at all. `MAROLA_MLFLOW_EXPERIMENT` (default `marola`) is the
experiment prefix. The server binds `127.0.0.1` only and has no authentication: do not expose the
port.

## CI workflows

All under [`.github/workflows/`](../.github/workflows/), on `ubuntu-latest`. The generic jobs are
the devkit's reusable workflows at the tag `flake.nix` pins; bump every `@v…`, every `devkit-ref:`,
the `docs-lint` clone in `ci.yml`, the flake input and `.claude/settings.json`'s marketplace `ref`
together.

| Workflow | Trigger | What it does |
|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | PR; push to `main` | The merge gates: `build-test` (devkit `scala-ci`: `corpusFetch`, scalafmt, scalafix, compile, test), `python` (ruff and the scripts' self-tests), `static` (actionlint, hadolint, shellcheck, compose config, `docs-lint`), `agents` (the AGENTS.md invariants block) and `flake-lock` (`flake.lock` is current). On `main` only, `coverage` (above) |
| [`docker.yml`](../.github/workflows/docker.yml) | PR and push to `main` touching the image's inputs; dispatch | hadolint and compose config, then the `jvm` and `native` images (and `dev` on request), each with a start-up check; pushes only from `main` |
| [`docker-smoke.yml`](../.github/workflows/docker-smoke.yml) | daily 09:30 UTC; dispatch (`lat`/`lon` or `maps_url`, `model`, `image`) | Runs `--summarize` in the published image against a cached `llama3.2:1b`; `scripts/smoke_record.py` records it as `smoke/` on marola-site's `site-data`, which the map's footer shows as "Last live run". Fails when the pipeline, the model or the reviewer did not answer |
| [`marola-e2e.yml`](../.github/workflows/marola-e2e.yml) | dispatch | `E2ESpec` live (above) |
| [`api-docs.yml`](../.github/workflows/api-docs.yml) | PR; push to `main` | Devkit `api-docs` running `just api-docs`: a check on a PR, force-pushed to the `api-docs` branch from `main` ([API reference](4-reference_api.md)) |
| [`release.yml`](../.github/workflows/release.yml) | `v*` tag | The release asset (below) |
| [`notify-umbrella.yml`](../.github/workflows/notify-umbrella.yml) | push to `main` touching `README.md` or `docs/**` | Dispatches the umbrella's docs rebuild; without the token, a notice and the umbrella's daily build |
| [`pr.yml`](../.github/workflows/pr.yml) | PR events | Devkit `pr-body` fills the description from the commits; devkit `ci-short-circuit` cancels a closed, unmerged PR's runs |
| [`scala-steward.yml`](../.github/workflows/scala-steward.yml) | Mondays 12:00 UTC; dispatch | One PR per newer Scala dependency or sbt plugin |
| [`labels.yml`](../.github/workflows/labels.yml) | dispatch | Devkit `labels-sync`: the org's label manifest; `just labels-sync` does the same locally |

[`dependabot.yml`](../.github/dependabot.yml) watches the workflows' actions only, twice a week,
and leaves the devkit's tag alone because dependabot would bump it in one place of three.

## Code review

Gemini Code Assist on GitHub reviews a PR only when asked, with a `/gemini review` comment
([DEV-FLOW §5](https://docs.marola.dev/3-Ways-of-working/DEV-FLOW/#5-final-review-only-when-asked)).
[`.gemini/config.yaml`](../.gemini/config.yaml) turns off review, summary and help on PR open, keeps
findings at MEDIUM or above (at most ten per review), and skips the test fixtures, the native-image
reachability metadata and `flake.lock`. [`.gemini/styleguide.md`](../.gemini/styleguide.md) is the
subset of `AGENTS.md` and `.claude/rules/` a reviewer can check from a diff. The GitHub app is
installed by a human; the evaluation behind it is
[Gemini Code Assist](https://docs.marola.dev/4-Research-and-plans/GEMINI-CODE-ASSIST/).

## Releases

A `v*` tag is a human's act. It runs `release.yml`, which attaches one asset to the GitHub
release:

| Asset | What | Read by |
|---|---|---|
| `ml-resources-<tag>.tar.gz` | `recommendation_prompt.json`, `review_prompt.json`, `sea_lore.json`, `benchmark_questions.json` and the test `board.json`, flat at the root (`scripts/build-resources-tarball.sh`) | marola-ml, pinned in its `resources.version` |

An asset already attached is never replaced: a re-run that finds one fails instead of changing
what a consumer has fetched. `just resources-tarball <tag>` builds it locally. The
image is not tied to tags; every qualifying `main` push publishes it.

## Secrets

| Secret | Used by | For |
|---|---|---|
| `GITHUB_TOKEN` | `docker.yml`, `docker-smoke.yml`, `release.yml`, `api-docs.yml` | pushing and pulling the image, attaching release assets, pushing the `api-docs` branch |
| `MAROLA_CROSS_REPO_PAT` (org secret) | `ci.yml`, `docker-smoke.yml`, `notify-umbrella.yml` | pushing `coverage/` and `smoke/` to marola-site's `site-data` and dispatching its rebuild; dispatching the umbrella's docs build |
| `STEWARD_GH_TOKEN` | `scala-steward.yml` | opening dependency PRs; the fallback `GITHUB_TOKEN` is refused by the org's Actions policy (marola-dev/marola#496) |

Locally, secrets go in the gitignored `.env`, never in code: direnv's `.envrc` and compose's
`env_file` load it, `nix develop` does not. `.env.example` holds placeholders only
([configuration reference](4-reference_config.md#outside-appconfig)).

## Cost

Nothing here provisions a paid resource. The repo is public, so the hosted runners are free, and a
public package's GHCR storage is free too (MIP-0065 §4.4). The `-<sha>` tags accumulate on every
qualifying push and nothing prunes them; until the package is made public, they count against the
private-package storage quota. The heaviest jobs are the daily smoke test and the
`native` build; both cache what they can (the Ollama model, the GHA build cache). A cloud
deployment is Phase 2 (MIP-0057) and needs a human's go-ahead with its expected cost first.
