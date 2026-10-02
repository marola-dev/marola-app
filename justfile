set shell := ["bash", "-euo", "pipefail", "-c"]
set allow-duplicate-recipes

# sbt's launcher needs a writable XDG_RUNTIME_DIR to create its boot server socket
# (sbt.internal.BootServerSocket).
export XDG_RUNTIME_DIR := justfile_directory() + "/.tmp/sbt-runtime"

# Where the app's recipes read the corpus: the release `corpus-fetch` unpacks, unless overridden
# (a corpus checkout, e.g. MAROLA_KNOWLEDGE_DIR=../marola-corpus/knowledge). Not exported:
# `docker-run` forwards every MAROLA_* variable, and the image's corpus is /app/knowledge.
knowledge := env("MAROLA_KNOWLEDGE_DIR", ".tmp/knowledge")

# The devkit's shared recipes (uprd, pr, stack, issue-*, ...), from the tree `nix develop` links.
import? '.devkit/devkit.just'

default:
    @just --list

build:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt compile

test: corpus-fetch
    mkdir -p "$XDG_RUNTIME_DIR" && MAROLA_KNOWLEDGE_DIR="{{ knowledge }}" sbt test

# Statement coverage across core/local/cli (sbt-scoverage, project/plugins.sbt).
coverage: corpus-fetch
    mkdir -p "$XDG_RUNTIME_DIR" && MAROLA_KNOWLEDGE_DIR="{{ knowledge }}" sbt clean coverage test coverageReport coverageAggregate

fmt:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll

# Every gate CI runs. `just quality-fix` applies the auto-fixable ones.
quality: quality-scala quality-other

# scalafmt + scalafix (semantic lint).
quality-scala:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtCheckAll "scalafixAll --check"

# The JVM-free gates. A missing tool fails, never skips.
quality-other:
    #!/usr/bin/env bash
    set -euo pipefail
    for tool in ruff shellcheck actionlint hadolint agents-check; do command -v "$tool" >/dev/null || { echo "quality-other: $tool not installed — run inside 'nix develop'" >&2; exit 1; }; done
    just --list >/dev/null
    ruff check .
    ruff format --check .
    shellcheck --severity=error scripts/*.sh
    python3 scripts/smoke_record.py --self-test
    python3 scripts/ocr-post.py --self-test
    python3 scripts/strip_external_scripts.py --self-test
    scripts/corpus-fetch.sh --self-test
    scripts/site-data-push.sh --self-test
    scripts/build-resources-tarball.sh --self-test
    actionlint
    hadolint Dockerfile
    agents-check
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then docker compose --profile mlflow --profile ollama --profile local config --quiet && echo "docker compose config: ok"; else echo "docker compose not installed — skipping compose config check"; fi

quality-fix:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll scalafixAll
    ruff check --fix . && ruff format .

# Run by the devkit's pre-commit hook: staged Scala must compile, staged workflows pass actionlint.
precommit:
    #!/usr/bin/env bash
    set -euo pipefail
    staged="$(git diff --cached --name-only --diff-filter=ACM)"
    if grep -qE '\.scala$' <<<"$staged"; then
        mkdir -p "$XDG_RUNTIME_DIR"
        sbt Test/compile
    fi
    if grep -qE '^\.github/workflows/.*\.ya?ml$' <<<"$staged"; then actionlint; fi

# Run by the devkit's pre-push hook: the JVM-free gates always, scalafmt/scalafix when Scala changed.
prepush:
    #!/usr/bin/env bash
    set -euo pipefail
    base="$(git rev-parse -q --verify '@{push}' 2>/dev/null || git merge-base HEAD origin/main 2>/dev/null || true)"
    just quality-other
    if [ -z "$base" ] || grep -qE '\.(scala|sbt)$|^project/|^\.scalafmt\.conf$|^\.scalafix\.conf$' <<<"$(git diff --name-only "$base" HEAD)"; then
        just quality-scala
    fi

# Run marola's CLI (build.sbt's `cli` project).
run *args: corpus-fetch
    mkdir -p "$XDG_RUNTIME_DIR" && MAROLA_KNOWLEDGE_DIR="{{ knowledge }}" sbt "cli/run {{ args }}"

# Run marola's MCP tool server, a separate main class from `run`'s (build.sbt). stdout is the
# JSON-RPC channel: corpus-fetch reports on stderr, and sbt runs with -error.
mcp-server: corpus-fetch
    mkdir -p "$XDG_RUNTIME_DIR" && MAROLA_KNOWLEDGE_DIR="{{ knowledge }}" sbt -error "cli/runMain marola.agent.SwimConditionsMcpServer"

watch:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "~compile"

# Unpack the marola-corpus release pinned in corpus.version into .tmp/knowledge (MIP-0070 §5.4).
corpus-fetch:
    scripts/corpus-fetch.sh

# Ask the corpus a question: local RAG, Ollama embeds and answers. MIP-0001.
ask question: corpus-fetch
    mkdir -p "$XDG_RUNTIME_DIR" && MAROLA_KNOWLEDGE_DIR="{{ knowledge }}" sbt "cli/run -- --ask \"{{ question }}\""

# Force a re-embed of the corpus (normally automatic when a file or the embed model changes).
knowledge-index: corpus-fetch
    mkdir -p "$XDG_RUNTIME_DIR" && MAROLA_KNOWLEDGE_DIR="{{ knowledge }}" sbt "cli/run -- --reindex"

# marola vs a plain prompt on the benchmark questions, 3 arms: writes data/benchmark-*.md.
benchmark: corpus-fetch
    mkdir -p "$XDG_RUNTIME_DIR" && MAROLA_KNOWLEDGE_DIR="{{ knowledge }}" sbt "cli/run -- --benchmark"

# The live E2E test (E2ESpec) against Overpass/Open-Meteo, plus Ollama if reachable.
e2e:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt \
        'set cli/Test/testOptions := Seq(Tests.Argument(new TestFramework("munit.Framework"), "--include-tags=E2E"))' \
        'cli/testOnly marola.E2ESpec'

# Make sure an Ollama server is reachable, starting one if not.
ollama-serve:
    #!/usr/bin/env bash
    set -euo pipefail
    api=http://localhost:11434/api/tags
    if ! curl -sf -m 2 "$api" >/dev/null; then
        mkdir -p .tmp
        nohup ollama serve >.tmp/ollama.log 2>&1 &
        for _ in $(seq 1 30); do curl -sf -m 1 "$api" >/dev/null && break; sleep 1; done
        curl -sf -m 2 "$api" >/dev/null || { echo "ollama: server did not come up — see .tmp/ollama.log" >&2; exit 1; }
    fi

# Make sure an Ollama server is reachable and has `model` pulled.
ollama-up model=env_var_or_default("MAROLA_LOCAL_LLM_MODEL", "llama3.2") embed=env_var_or_default("MAROLA_LOCAL_EMBED_MODEL", "llama3.2"): ollama-serve
    #!/usr/bin/env bash
    set -euo pipefail
    for m in "{{ model }}" "{{ embed }}"; do
        ollama list | awk 'NR>1 {print $1}' | grep -qx "$m" || ollama pull "$m"
    done

# What release.yml attaches to a v* tag: .tmp/ml-resources-<tag>.tar.gz (the app -> ml contract).
resources-tarball tag:
    scripts/build-resources-tarball.sh .tmp/ml-resources-{{ tag }}.tar.gz

# Build the CLI image. target=jvm (default), dev or native. MIP-0008.
docker-build target="jvm": corpus-fetch
    docker build --target {{ target }} -t marola:{{ target }} .

# Run the CLI image with host networking, so a local Ollama on :11434 is reachable.
docker-run *args:
    docker run --rm --network host --env-file <(env | grep '^MAROLA_' || true) marola:jvm {{ args }}

# GraalVM native-image of the CLI → cli/target/marola (MIP-0008 task 3).
native-image:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "$XDG_RUNTIME_DIR"
    nix shell nixpkgs#graalvmPackages.graalvm-ce --command bash -c '
        export GRAALVM_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v native-image)")")")"
        sbt -batch cli/nativeImage'
    ls -la cli/target/marola

native-run *args:
    ./cli/target/marola {{ args }}

# A local MLflow server for the run ledger, http://127.0.0.1:5000. MIP-0010.
mlflow-up:
    mkdir -p .tmp/mlflow && docker compose --profile mlflow up -d --wait mlflow

mlflow-down:
    docker compose --profile mlflow down
