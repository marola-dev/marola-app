# marola-app

The marola product: a Scala 3 / Kyo pipeline that finds the beaches near you (OpenStreetMap),
reads tomorrow's sea and weather (Open-Meteo), scores each hour for swimming, and has a local LLM
write the answer while a second pass reviews it. It runs entirely on your machine with a free
Ollama model; no cloud account is needed. The same jar is a CLI, an MCP tool server, the
benchmark runner and the writer of the boards the [map](https://marola.dev) shows.

It is one of the marola repos under the [umbrella](https://github.com/marola-dev/marola)
(MIP-0070), and its history before the split is marola's, filtered to these files.

## Run it

```bash
nix develop
just ollama-up                                   # an Ollama server with llama3.2
just run -- --summarize --lat -27.6733 --lon -48.47
```

Or the published image: `docker run --rm --network host ghcr.io/marola-dev/marola-app:jvm --brief
--lat -27.6733 --lon -48.47`. [docs/1-Using-marola/RUN-LOCALLY.md](docs/1-Using-marola/RUN-LOCALLY.md)
has every mode, and [docs/2-Building-marola/ARCHITECTURE.md](docs/2-Building-marola/ARCHITECTURE.md)
how the pipeline fits together.

## Build and test

```bash
just build && just test && just quality
```

`just test` first unpacks the [marola-corpus](https://github.com/marola-dev/marola-corpus) release
pinned in `corpus.version`. JDK 25 is required (Kyo).

## What it publishes

- The image `ghcr.io/marola-dev/marola-app` (`:jvm`, `:jvm-<sha>`, `:native`), from every `main`
  push. marola-site and marola-ml pin it by digest.
- Scaladoc on the `api-docs` branch, force-pushed from every `main` push (one commit, the latest).
- On each `v*` tag: `api-docs.tar.gz` (Scaladoc, shown on [docs.marola.dev](https://docs.marola.dev/2-Building-marola/API/))
  and `ml-resources-<tag>.tar.gz` (the prompts, sea lore and benchmark questions marola-ml reads).

More in [docs/](docs/index.md) and [AGENTS.md](https://github.com/marola-dev/marola-app/blob/main/AGENTS.md).
