# Configuration reference

marola-app is configured by environment variables only. `AppConfig.fromEnv` reads all but the
three under [Outside AppConfig](#outside-appconfig). An unset or unparsable value gets the
default. The `config ->` line a recommendation prints shows
`MAROLA_TELEGRAM_BOT_TOKEN` and `MAROLA_MLFLOW_TRACKING_URI` only as `<set>` or `unset`.

Put local overrides in `.env` at the repo root (gitignored;
[`.env.example`](https://github.com/marola-dev/marola-app/blob/main/.env.example) lists the
runtime variables). direnv loads it through `.envrc`, and Docker Compose through `env_file`;
`nix develop` alone does not. `just docker-run` forwards every `MAROLA_*` variable of the calling
shell into the container.

## Models and the corpus

| Variable | Default | Effect |
|---|---|---|
| `MAROLA_LOCAL_LLM_BASE_URL` | `http://localhost:11434/v1` | Ollama's OpenAI-compatible base for the LLM and vision calls (`<base>/chat/completions`). The embedder calls the same server's native `/api/embed`, at this URL minus `/v1` |
| `MAROLA_LOCAL_LLM_MODEL` | `llama3.2` | The model for `--summarize`, `--ask`, `--benchmark`, the chat server and the MCP `ask_ocean_question` |
| `MAROLA_LOCAL_EMBED_MODEL` | `llama3.2` | The embedding model. Current Ollama refuses the default, a chat model: see [Choosing the embedder](#choosing-the-embedder) |
| `MAROLA_LOCAL_VISION_MODEL` | `llava` | The model for `--analyze-photo`; it must be multimodal |
| `MAROLA_KNOWLEDGE_DIR` | `./knowledge` | The corpus: the marola-corpus release's `knowledge` directory. The recipes set it to `.tmp/knowledge`, where `just corpus-fetch` unpacks the pin in `corpus.version`; in the image the default resolves to its own corpus, `/app/knowledge` |
| `MAROLA_KNOWLEDGE_INDEX_PATH` | `./data/knowledge-index.json` | The embedding index, rebuilt when a corpus file or the embed model changes |
| `MAROLA_ASK_FALLBACK` | `general` | `strict` (any case) makes `--ask` abstain when no passage is relevant; anything else answers from the model's general knowledge with an "unsourced" label |
| `MAROLA_ASK_MIN_SCORE` | `0.0` | The cosine score under which a passage counts as irrelevant, for `--ask` and `--benchmark` |
| `MAROLA_SEA_LORE` | on | `off` (any case) or `0` drops the sea-lore paragraph, as `--no-lore` does |

The chat server and the MCP server ignore `MAROLA_ASK_FALLBACK` and `MAROLA_ASK_MIN_SCORE`
([CLI reference](4-reference_cli.md#mcp-server)).

### Choosing the embedder

| `MAROLA_LOCAL_EMBED_MODEL` | Size | Dimensions | When |
|---|---|---|---|
| `llama3.2` (default) | already pulled | 3072 | Fails on current Ollama, which no longer embeds with a chat model: `/api/embed` answers HTTP 501 ([#12](https://github.com/marola-dev/marola-app/issues/12)). Where it still works, retrieval is serviceable, not sharp |
| `nomic-embed-text` | 274 MB | 768 | Sharper retrieval, but RAG with it trails the plain prompt today (marola-dev/marola-ml#4) |
| `all-minilm` | 45 MB | 384 | The fastest re-index; use it while editing the corpus a lot |

Changing the model changes the index fingerprint, so the next `--ask` re-embeds. `just ollama-up
llama3.2 nomic-embed-text` pulls both models; `just benchmark` measures what a switch buys.

## Where and what

| Variable | Default | Effect |
|---|---|---|
| `MAROLA_ORIGIN_LAT`, `MAROLA_ORIGIN_LON` | unset | A fixed origin for the CLI, after `--lat`/`--lon` and `--location-url` ([origin order](4-reference_cli.md#recommendation-the-default)). Both or neither: one alone is ignored with a warning |
| `MAROLA_BEACH_SEARCH_RADIUS_KM` | `15` | The CLI's search radius. An IP-geolocated origin widens it to at least 20 km. The MCP tools take `radius_km`, and `--site` each area's `radius_km` |
| `MAROLA_WATER_QUALITY_PROVIDER` | auto | `ima-sc`, `inema-ba` or `inea-rj` (or with `_`, or no separator) forces an agency; `none` or `off` disables water quality. Anything else picks by the origin: Santa Catarina, Bahia, then Rio de Janeiro, each a bounding box ([Data sources](4-reference.md#bathing-water-quality)) |
| `MAROLA_FACILITIES` | Overpass | `off` skips the facilities query: every beach gets "no data" |
| `MAROLA_LOCAL_SIGHTING_STORE_PATH` | `./data/sightings.jsonl` | Where `--report-sighting` appends |
| `MAROLA_TELEGRAM_BOT_TOKEN` | unset | Read, but used by nothing yet: the bot is Phase 1 ([Telegram setup](https://docs.marola.dev/1-Using-marola/TELEGRAM-SETUP/)) |

Water-quality fetches are cached per agency under `data/water-cache/`, so an agency outage serves
the last good fetch; samples older than 45 days still read "no data".

## Observability

| Variable | Default | Effect |
|---|---|---|
| `MAROLA_MLFLOW_TRACKING_URI` | unset | An MLflow server (`just mlflow-up` starts one at `http://127.0.0.1:5000`). Unset, nothing is logged or traced and no MLflow call is made |
| `MAROLA_MLFLOW_EXPERIMENT` | `marola` | The experiment prefix: benchmark runs go to `<prefix>/benchmark`, traces to `<prefix>/traces` |
| `MAROLA_TRACES` | `off` | `mlflow` traces each recommendation and `--ask` over OTLP to `<tracking uri>/v1/traces`; it needs the tracking URI, and an unreachable server prints a warning and traces nothing |
| `MAROLA_TRACE_CONTENT` | off | `1` or `true` records prompts and replies in the LLM spans. Off by default because the prompt carries the swimmer's coordinates |

## Outside AppConfig

| Variable | Read by | Effect |
|---|---|---|
| `MAROLA_BEACHES_DIR` | `BeachFinder` | A directory of beach lists, one file per origin, radius and limit: read instead of Overpass when the file exists, and written after an Overpass query that found beaches. Unset, `$PWD/site/beaches` when that directory exists, else none |
| `MAROLA_CORPUS_URL` | `scripts/corpus-fetch.sh` | The marola-corpus release base URL (default `https://github.com/marola-dev/marola-corpus/releases/download`); its self-test points it at `file://` fixtures |
| `MAROLA_E2E_SKIP_LLM` | `E2ESpec` | Set to anything, `just e2e` skips its LLM test |

CI also uses the `MAROLA_CROSS_REPO_PAT` secret, which no code reads. `MAROLA_LLM_PROVIDER`, which
older notes mention, is read nowhere.
