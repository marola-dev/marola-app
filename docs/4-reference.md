# Data sources

Every external source the app calls, and on what terms. All are free and need no key or account.
The [CLI reference](4-reference_cli.md) and [Configuration reference](4-reference_config.md) say
which mode calls what; [Integrations](1-design_integrations.md) says which class calls each
source and what has been verified against it.

## Beaches, facilities and trails: OpenStreetMap

| Endpoint | Called by | For |
|---|---|---|
| `https://overpass-api.de/api/interpreter`, then `overpass.private.coffee`, then `overpass.kumi.systems` (same path) | `BeachFinder` | Named beaches (`natural=beach`: nodes, ways and relations) around the origin, nearest first |
| `https://overpass-api.de/api/interpreter` | `OverpassAccessibilityClient` | Amenities within 300 m of each beach (MIP-0021); off with `MAROLA_FACILITIES=off` |
| `https://overpass-api.de/api/interpreter` | `TrailFinder` | Named paths and tracks near the beaches (MIP-0030) |

The three beach endpoints are public mirrors of the same data, tried in order. Overpass is
fair-use rate limited ([its policy](https://wiki.openstreetmap.org/wiki/Overpass_API#Introduction)):
fine for a personal run; a public deployment calling it often should self-host or cache.
`MAROLA_BEACHES_DIR` serves beach lists from disk instead.

## Sea and weather: Open-Meteo

| Endpoint | Hourly variables |
|---|---|
| `https://api.open-meteo.com/v1/forecast` | `temperature_2m`, `wind_speed_10m`, `wind_direction_10m`, `uv_index`, `precipitation_probability`, `is_day` |
| `https://marine-api.open-meteo.com/v1/marine` | `wave_height`, `sea_surface_temperature`, `ocean_current_velocity`, `wave_period`, `wave_direction`, `swell_wave_height`, `swell_wave_period`, `sea_level_height_msl` |

`OpenMeteoClient` asks for two forecast days in the beach's own timezone, and retries twice on a
429, 502, 503 or 504 or on a timed-out or refused connection. Tide turns are the local extrema of
`sea_level_height_msl` (`Tides`); there is no tide-table API. Open-Meteo is
[free for non-commercial use](https://open-meteo.com/en/pricing), with no key.

## Bathing-water quality

Each agency publishes its own state's samples, so the provider follows the origin
(`MAROLA_WATER_QUALITY_PROVIDER`).

| Agency | Covers (lat, lon) | Source |
|---|---|---|
| IMA/SC | −29.4 to −25.9, −53.9 to −48.3 | `POST https://balneabilidade.ima.sc.gov.br/relatorio/mapa`, the JSON the portal's map uses: every point with its last samples. When it gives nothing, the newest weekly bulletin, `http://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/<date>`, found from the portal's index and joined by beach and point name with the coordinates of the same feed over plain HTTP, which carries no samples |
| INEMA/BA | −18.5 to −8.5, −40.5 to −37.0 | The bulletin PDF at `http://balneabilidade.inema.ba.gov.br/index.php/relatoriodebalneabilidade/geraBoletim?idcampanha=83453`, placed with the bundled `sampling_points_ba.json` |
| INEA/RJ | −23.4 to −21.0, −44.9 to −40.9 | The latest PDF per zone, found on `https://www.inea.rj.gov.br/rio-de-janeiro/` and `https://www.inea.rj.gov.br/niteroi/`, placed with the bundled `sampling_points_rj.json` |

Every provider sits behind `CachedWaterQualityClient`, which serves the last good fetch from
`data/water-cache/` when the agency is down. A sample older than 45 days reads "no data", cached or
not. INEMA/BA's URL pins one campaign (bulletin 13/2025), so it does not follow newer bulletins.

## Origin: IP geolocation

When no origin is given, `IpGeolocation` asks `https://ipinfo.io/json`, `https://ipwho.is/` and
`http://ip-api.com/json/` and keeps the medoid of their answers. Each is free with a modest rate
limit; ip-api.com's free tier is HTTP-only and non-commercial.

## Local services

| Service | Endpoint | For |
|---|---|---|
| [Ollama](https://ollama.com) | `MAROLA_LOCAL_LLM_BASE_URL` + `/chat/completions`; `/api/embed` on the same server | The LLM, vision and embeddings, on your own hardware |
| [MLflow](https://mlflow.org), optional | `MAROLA_MLFLOW_TRACKING_URI`: its REST API under `/api/2.0/mlflow`, and OTLP at `/v1/traces` | The benchmark ledger and traces |

## Bundled, no network

- The knowledge corpus: the [marola-corpus](https://github.com/marola-dev/marola-corpus) release
  pinned in `corpus.version`, fetched at build time. Each document cites its own source.
- Sea lore: `core/src/main/resources/sea_lore.json`, each entry with its source URL (Wikipedia,
  NOAA).
- The benchmark questions and compiled prompts, on the classpath.

No jellyfish or whale API exists; those two fields come from heuristics. Telegram's Bot API is not
called yet (Phase 1). The base map and satellite layers are marola-site's
([tile policy](https://docs.marola.dev/5-Repos/marola-site/4-reference/#tile-policy)). `tiles` is
still required in the areas file and echoed into `data/areas.json`, but the site no longer reads it.
