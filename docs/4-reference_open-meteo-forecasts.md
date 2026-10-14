# Open-Meteo forecasts

The forecast experiment's `open_meteo` route
([MIP-0083](https://docs.marola.dev/6-MIPs/MIP-0083-forecast-benchmark-job/) §4.1, §5.14),
`marola.experiment.forecast.OpenMeteoForecasts`. A `providers.json` row with `"route":
"open_meteo"` is all a new model needs: its `model_id` goes into every URL below.

| Call | URL | Used for |
|---|---|---|
| Metadata | `https://api.open-meteo.com/data/<model_id>/static/meta.json` | `last_run_initialisation_time` (the run) and `last_run_availability_time` (a run is read 10 minutes after it) |
| Latest run | `https://api.open-meteo.com/v1/forecast?…&start_hour=<init + first lead>&end_hour=<init + last lead>` | the run the metadata names, kept unless the metadata names a newer run after the fetch (its servers update apart, so an older one is a lagging server) |
| Single run | `https://single-runs-api.open-meteo.com/v1/forecast?…&run=<init>&forecast_hours=<last lead + 1>` | a missed run, or a latest run that changed under the fetch; its series must start at `<init>` |

Both forecast calls send `cell_selection=nearest`, `wind_speed_unit=ms`, `timeformat=unixtime`
and `timezone=GMT`, one request per sampling point, five a second at most, and back off on a 429,
a 5xx or a connection failure. The single-run API refuses `start_hour` (HTTP 400) and answers a
run it does not keep with HTTP 400, "The requested model run is not available"; that run becomes a
`missing` row carrying the reason.

## Checked live

Recorded 2026-10-09/10 at `sc-sbfl` and replayed by `ForecastClientSpec` and `FairnessSpec`
(`experiment/src/test/resources/fixtures/open-meteo/`):

- `ecmwf_ifs` and `ncep_gfs013` both answer the metadata URL above and the forecast API, so the
  GFS id and the metadata URL, ⚠ in MIP-0083 §4.1, are confirmed.
- GFS 18Z and IFS 12Z of 2026-10-09 read from the single-run archive matched the live forecast
  API at every lead from 6 to 360 h, value for value and in the same grid cell: on this overlap a
  backfill hashes like the live run (§5.14 rule 2). One overlap per model is not a guarantee; a
  later mismatch is recorded as a `RunIndexRow` reason, never overwritten.
- The single-run API answered HTTP 429 "Daily API request limit exceeded" from a shared cloud
  address on 2026-10-09; the daily limit is per address, so a runner that shares one can hit it.
