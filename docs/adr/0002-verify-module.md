# ADR-0002: The forecast benchmark is a fourth sbt project, `verify`

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Related** | [ADR-0001](0001-three-sbt-modules.md), [MIP-0083](https://docs.marola.dev/6-MIPs/MIP-0083-forecast-benchmark-job/), [marola-app#63](https://github.com/marola-dev/marola-app/issues/63) |

## Context

MIP-0083 adds a job that archives wind forecasts every 4 hours and scores them against
anemometers. It shares `core`'s HTTP and JSON helpers and, later, `local`'s MLflow ledger, but
nothing on the recommendation path calls it, and it runs from its own scheduled workflow.

## Decision

`build.sbt` gets a fourth project, `verify` (`marola-verify`), aggregated by `root`. It depends on
`core` now and adds `local` when the cycle logs to MLflow (MIP-0083 task 6). `cli` does not depend
on it.

## Consequences

- `sbt test` and `just quality` cover it like the other three projects.
- The benchmark's jar is built from `verify` alone, so the app image does not grow.
- A dependency only the benchmark needs goes in `verify`, by ADR-0001's lowest-module rule.
