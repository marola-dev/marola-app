# marola-app

The Scala app: pipeline, adapters, CLI, MCP server, benchmark runner and image (MIP-0070 §5.2).
Its two doc sections sit at the root of docs.marola.dev, as they did before the split.

| Section | Pages |
|---|---|
| Using marola | [Run it locally](1-Using-marola/RUN-LOCALLY.md), [Telegram setup](1-Using-marola/TELEGRAM-SETUP.md) |
| Building marola | [Architecture](2-Building-marola/ARCHITECTURE.md), [Effects map](2-Building-marola/EFFECTS-MAP.md), [Scala 3 / JDK review](2-Building-marola/SCALA3-JDK-REVIEW.md), [API reference](2-Building-marola/API.md) |

## Contracts

| Artifact | Where |
|---|---|
| The image | `ghcr.io/marola-dev/marola-app:jvm-<sha>`, built by [`docker.yml`](https://github.com/marola-dev/marola-app/blob/main/.github/workflows/docker.yml) on every `main` push |
| Scaladoc | `api-docs.tar.gz` on each `v*` release ([`release.yml`](https://github.com/marola-dev/marola-app/blob/main/.github/workflows/release.yml)) |
| ml resources | `ml-resources-<tag>.tar.gz` on the same release |
| The corpus it reads | the marola-corpus tag in [`corpus.version`](https://github.com/marola-dev/marola-app/blob/main/corpus.version) |
