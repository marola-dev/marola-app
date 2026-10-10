# Forecast experiment cycle

`marola.experiment.Main`, the forecast experiment's entry point (MIP-0083 §5.6, §5.8): `sbt
"experiment/run <command>"`, or `java -jar` on `sbt experiment/assembly`'s jar.

| Command | What it does |
|---|---|
| `cycle` | Reads each provider's newest run and any it missed, the METAR and INMET reports of the last three days, writes them to the lake, scores those three days again, and logs one MLflow run. Exits 1 when a fetch failed |
| `rescore [from until]` | Rebuilds the scores of `[from, until)` (ISO dates, default the same three days) from the lake's samples alone, writes them back and to `rescore.json` |
| `predictions [from until]` | Writes `predictions.csv` for `[from, until)` (default the same three days) from the lake alone: every forecast at a ground-truth point beside its observation |
| `export` | Writes `export.json`, the `Scorecard`, or logs why it did not: nothing is exported until a ground-truth point is `scored` |

`just experiment-cycle` runs a cycle against the MLflow of `just mlflow-up`; `just
experiment-rescore` then checks that `rescore.json` equals the cycle's `scores.json`.

## On GitHub Actions

`.github/workflows/experiment.yml` runs `cycle` at minute 17 of every fourth hour and on dispatch,
in the `oods-lake` concurrency group, against an MLflow 3.16.0 it starts on localhost. Until the
B2 lake lands, `.tmp/experiment` (the lake, `mlflow.db`) is restored from and saved to the Actions
cache, which evicts an entry unused for 7 days: v0's record is not durable yet.

MLflow's artifacts go to `s3://<B2_BUCKET>/mlflow` on Backblaze B2 when the secrets `B2_KEY_ID`
and `B2_APPLICATION_KEY` and the variables `B2_BUCKET` and `B2_S3_ENDPOINT` (the bucket's S3
endpoint, `https://s3.<region>.backblazeb2.com`) are all set; otherwise they stay in the cache and
the run says so. Each run's `out/` and `mlflow.db` are also kept as the Actions artifact
`experiment-<run id>` for 14 days: download it from the run's page, then `mlflow ui
--backend-store-uri sqlite:///mlflow.db` shows the runs (their artifacts stay in the cache or B2).

The **Cycle** step ends with one line saying what the cycle stored: runs by state, forecast
values, observations, and the predictions at ground-truth points with how many have an
observation. A `failed: …` line names each fetch that failed.

## The predictions table

`predictions.csv`, written by every cycle for the three days it scores, has one row per forecast
at a ground-truth point and valid time: `valid_time`, `point`, `instrument` (the ground-truth id),
`variable`, `provider`, `run_init`, `lead_h`, `forecast` (the members' mean), `members`, and
`observed` and `error` (forecast − observed), both blank when no observation matched (the
matcher's tolerance: 10 minutes for METAR, the hour for INMET). The matched rows are exactly the
pairs `scores.json` counts.

## Settings

Read once at start, from a system property or its environment variable (kyo-config's
`StaticFlag`):

| Variable | Default | Effect |
|---|---|---|
| `MAROLA_EXPERIMENT_FLAGS_LAKE` | `.tmp/experiment/lake` | The lake: a DuckLake directory, or a plain DuckDB file when the path ends in `.duckdb` |
| `MAROLA_EXPERIMENT_FLAGS_OUT` | `.tmp/experiment/out` | Where `scores.json`, `run_index.json`, `predictions.csv`, `rescore.json` and `export.json` go |
| `MAROLA_EXPERIMENT_FLAGS_MLFLOWURI` | empty | The MLflow server; empty, nothing is logged |
| `MAROLA_EXPERIMENT_FLAGS_DRYRUN` | `false` | `true` writes to a throwaway lake and logs nothing |
| `INMET_TOKEN` | unset | INMET's hourly route; unset, INMET is skipped with a warning ([ground truth](4-reference_ground-truth.md)) |

## What a cycle logs

One run of the experiment `forecast-benchmark-v<protocol version>` per cycle:

- **Params**: providers, cadence, window, the run counts (`sampled`, `backfilled`, `missing`), each
  failure as `failure.<n>`, `app_sha` (`GITHUB_SHA`), the latest Niño-3.4 week and anomaly, and the
  git blob sha of each bundled file (`ground-truth.json`, `sampling-points.json`, `protocol.json`,
  `providers.json`).
- **Metrics**, on the first cycle after 00 UTC only: `rmse`, `bias` and `crps` over the window as
  `<metric>.<variable>.<window>d.<point>.<provider>`, with the lead in hours as the step, at the
  scorecard days' leads.
- **Artifacts**: `scores.json` (every cell of the three days scored), `run_index.json` (this
  cycle's run rows) and, once exported, `export.json`.

A failed fetch is a `missing` run row with its reason and a `FAILED` run, never an exception. A
failed metadata read leaves no row, so the next cycle backfills that run.

Every point is scored from its first sample, for the record; only `scored` points reach the
export.
