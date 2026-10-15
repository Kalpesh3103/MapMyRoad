# infra/osm: OSM data pipeline

Milestones M4-2 (pilot extract and Valhalla tiles) and M4-3 (osm2pgsql import of `osm_way` and
`osm_calming_point`), plus synthetic seed data for local demos (TESTING_STRATEGY.md section 13).
Design background: DEPLOYMENT.md section 5, DATABASE_SCHEMA.md A5 and A7, TECH_STACK.md section 5,
ARCHITECTURE.md 12.2, SECURITY.md 6.4 and 11.

```
Geofabrik regional .osm.pbf (+ .md5)                     scripts/osm-download.sh
   | md5sum -c (refuse on mismatch)
   | osmium extract -b PILOT_BBOX --strategy smart
   v
<date>/pilot.osm.pbf (or dev.osm.pbf) ----------------+
   |                                                  |
   | osm2pgsql --create, flex style                   | copy into a NEW dated dir, throwaway
   v                                                  v container builds tiles (old ones keep serving)
osm_import.ways / osm_import.calming (staging)     valhalla/<YYYYMMDD-HHMMSS>/   scripts/valhalla-build.sh
   | swap.sql: TRUNCATE + INSERT, one transaction     |
   v                                                  |
osm_way / osm_calming_point (Flyway-owned)            |
   | reconcile.sql: drop unmappable segment stats     |
   v                                                  v
road_segment_stat                               VALHALLA_DATA_DIR in .env -> recreate valhalla -> smoke test
```

`scripts/osm-refresh.sh` runs all of it in order (monthly, DEPLOYMENT.md 5.2). `scripts/load-dev-data.sh`
does the local version with the tiny dev extract and then loads the synthetic seed data.

## Files

| File | Purpose |
|---|---|
| `pilot-area.env` | `PILOT_BBOX` (same as `SUPPORTED_REGION_BBOX`), `GEOFABRIK_URL`, `DEV_BBOX` (a few km of central Pune) |
| `tools.Dockerfile` | Local throwaway image with Debian's `osmium-tool` and `osm2pgsql` (built on first use) |
| `flex/mapmyroad.lua` | osm2pgsql flex style, writes only the staging schema `osm_import` |
| `swap.sql` | Staging to `osm_way` / `osm_calming_point` in one transaction, computes `length_m`, fills `access` if that column exists |
| `reconcile.sql` | Deletes `road_segment_stat` rows that no longer map to a way after a refresh |
| `seed-dev-data.sql` | Synthetic ways, devices, hazards and segment statistics around central Pune (dev only) |
| `../scripts/osm-common.sh` | Shared helpers: dev or prod paths (`MMR_ENV`), tool image, psql in the db container |
| `../scripts/osm-download.sh` | Download, MD5 check, `osmium extract` |
| `../scripts/osm-import.sh` | osm2pgsql into staging, then swap and reconcile |
| `../scripts/valhalla-build.sh` | `build`, `activate`, `smoke`, `rollback`, `cleanup` (default: build, activate and smoke) |
| `../scripts/osm-refresh.sh` | Monthly orchestration, prints a line for `docs/PROGRESS.md` |
| `../scripts/seed-dev-data.sh` | Runs `seed-dev-data.sql`, refuses anything that is not the local dev stack |
| `../scripts/load-dev-data.sh` | Dev one-shot: dev extract, import, Valhalla, seed (`--seed-only`, `--offline`) |

Data lives outside Git: `infra/data/osm/<YYYY-MM-DD>/` and `infra/data/valhalla/<YYYYMMDD-HHMMSS>/` locally
(git-ignored), `/opt/rq/data/osm/` and `/opt/rq/data/valhalla/` on the VM. None of it is backed up, because
it can be rebuilt from Geofabrik (DEPLOYMENT.md 9.2). The `osm_way` and `osm_calming_point` tables do end up
in the nightly `pg_dump`, which is harmless.

## Data license (ODbL)

OpenStreetMap data is © OpenStreetMap contributors and licensed under the Open Database License (ODbL 1.0).
Geofabrik redistributes it under the same license.

- Show "© OpenStreetMap contributors" with a link to https://www.openstreetmap.org/copyright wherever
  OSM-derived data is displayed (map screen, route screen, public tiles, exports). The app's map screen already
  carries OSM attribution (M0-8).
- `osm_way`, `osm_calming_point` and the Valhalla tiles are derived databases of OSM. If we ever distribute
  them, or a database that combines them with our data, publicly (for example the v4 exports), the share-alike
  terms of ODbL may apply. This needs a proper review before M8 exports; it is not decided here.
- The synthetic seed data is not OSM data.

## Sizes and timing (rough estimates, not measured)

These are planning guesses. Replace them with measured values in `docs/PROGRESS.md` after the first run.

| Step | Pilot (PILOT_BBOX, about 2.5 x 1.7 degrees) | Dev (DEV_BBOX) |
|---|---|---|
| Regional download (India western-zone) | a few hundred MB, minutes on a good line | same file |
| `osmium extract` | 1 to 5 min, output tens to low hundreds of MB | seconds, a few MB |
| osm2pgsql import into staging | 2 to 10 min, about 1 to 2 GB RAM | under a minute |
| `swap.sql` (lock held) | seconds to a few tens of seconds | under a second |
| Valhalla tile build | 10 to 60 min on 2 to 4 ARM cores, peak RAM 2 to 4 GB, tiles a few hundred MB to 1 GB | 1 to 3 min |
| Disk per monthly generation | regional file + extract + tiles, plan 3 to 5 GB, two generations kept | well under 1 GB |

Plan the first production build at a quiet hour and check free disk space first (`df -h /opt/rq`).

## Running locally

Prerequisites: Docker (Docker Desktop on macOS or Windows), `curl`, bash, and the dev database migrated:

```sh
cd infra
cp .env.example .env                                 # once
docker compose -f docker-compose.dev.yml up -d db
# start the API once (bootRun or --profile api) so Flyway creates osm_way and friends, then:
./scripts/load-dev-data.sh                           # dev extract, import, Valhalla tiles, seed data
./scripts/load-dev-data.sh --seed-only               # only the synthetic seed data
```

Individual steps (all accept `-h`):

```sh
./scripts/osm-download.sh --area dev                 # infra/data/osm/<date>/dev.osm.pbf
./scripts/osm-import.sh --area dev                   # staging, swap, reconcile; prints row counts
./scripts/valhalla-build.sh all --area dev           # tiles, activate, smoke test on 127.0.0.1:8002
./scripts/seed-dev-data.sh
```

After `valhalla-build.sh activate`, `infra/.env` contains `VALHALLA_DATA_DIR=./data/valhalla/<stamp>` and
`docker compose -f docker-compose.dev.yml up -d db valhalla` brings both back after a reboot. Do not start
the valhalla service before the first build: with an empty data directory the image has nothing to serve.

### Windows

The scripts are bash. Use **WSL 2** (recommended: clone the repo inside the WSL file system, enable Docker
Desktop's WSL integration, run the scripts from the WSL shell) or **Git Bash** (the scripts set
`MSYS_NO_PATHCONV=1` and convert bind-mount paths with `cygpath`). In PowerShell the main steps look like
this (run from `infra\`, adjust the date directory):

```powershell
# 1. Tool image, download and MD5 check
Get-Content osm\tools.Dockerfile | docker build -t mmr-osm-tools:bookworm-slim -
$d = "data\osm\$(Get-Date -Format yyyy-MM-dd)"; New-Item -ItemType Directory -Force $d | Out-Null
$url = "https://download.geofabrik.de/asia/india/western-zone-latest.osm.pbf"
Invoke-WebRequest "$url.md5" -OutFile "$d\western-zone-latest.osm.pbf.md5"
Invoke-WebRequest $url -OutFile "$d\western-zone-latest.osm.pbf"
(Get-FileHash -Algorithm MD5 "$d\western-zone-latest.osm.pbf").Hash.ToLower()   # compare with the .md5 file

# 2. Extract the dev area
docker run --rm -v "${PWD}\${d}:/data" mmr-osm-tools:bookworm-slim osmium extract -b 73.82,18.49,73.88,18.54 --strategy smart -S types=multipolygon,boundary --overwrite -o /data/dev.osm.pbf /data/western-zone-latest.osm.pbf

# 3. Import into staging, then swap and reconcile ($env:PGPASSWORD = the POSTGRES_PASSWORD from .env)
docker compose -f docker-compose.dev.yml exec db psql -U roadquality -d roadquality -c "CREATE SCHEMA IF NOT EXISTS osm_import"
docker run --rm --network mapmyroad-dev_default -e PGPASSWORD -v "${PWD}\${d}:/data:ro" -v "${PWD}\osm\flex:/style:ro" mmr-osm-tools:bookworm-slim osm2pgsql --create --output=flex --style=/style/mapmyroad.lua -d roadquality -H db -U roadquality /data/dev.osm.pbf
Get-Content osm\swap.sql | docker compose -f docker-compose.dev.yml exec -T db psql -v ON_ERROR_STOP=1 -U roadquality -d roadquality
Get-Content osm\reconcile.sql | docker compose -f docker-compose.dev.yml exec -T db psql -v ON_ERROR_STOP=1 -U roadquality -d roadquality

# 4. Valhalla tiles into a dated directory, then set VALHALLA_DATA_DIR=./data/valhalla/<stamp> in .env
$v = "data\valhalla\$(Get-Date -Format yyyyMMdd-HHmmss)"; New-Item -ItemType Directory -Force $v | Out-Null
Copy-Item "$d\dev.osm.pbf" $v
docker run --rm -v "${PWD}\${v}:/custom_files" -e serve_tiles=False -e force_rebuild=True -e use_tiles_ignore_pbf=False -e build_admins=True -e build_time_zones=True -e build_elevation=False -e build_tar=True ghcr.io/gis-ops/docker-valhalla/valhalla:3.5.1
docker compose -f docker-compose.dev.yml up -d --force-recreate valhalla
```

The seed data needs `-v i_know_this_is_dev=yes` when run by hand (`Get-Content osm\seed-dev-data.sql | ... psql
-v ON_ERROR_STOP=1 -v i_know_this_is_dev=yes ...`); prefer the bash wrapper, which checks that the target is local.

## Running on the VM

Layout: scripts in `/opt/rq/scripts/` (including `common.sh` and `osm-common.sh`), the contents of `infra/osm/`
in `/opt/rq/osm/`. The scripts detect production through `/opt/rq/compose/docker-compose.yml` (or set
`MMR_ENV=prod`) and use `/opt/rq/compose/.env`.

Initial load (DEPLOYMENT.md 5.1), after the API has started once so Flyway created the tables:

```sh
/opt/rq/scripts/osm-download.sh                 # pilot area
/opt/rq/scripts/valhalla-build.sh all           # build, set VALHALLA_DATA_DIR, start valhalla, smoke test
/opt/rq/scripts/osm-import.sh                   # staging, swap, reconcile
```

Monthly refresh (DEPLOYMENT.md 5.2): `/opt/rq/scripts/osm-refresh.sh`, then paste the printed line into
`docs/PROGRESS.md`. Rollback: `valhalla-build.sh rollback` (tiles) and
`osm-import.sh --pbf /opt/rq/data/osm/<previous date>/pilot.osm.pbf` (reference tables). The previous
generation is kept for 7 days (`OSM_KEEP_DAYS`).

The scripts print counts, timings and file sizes only, never coordinates or credentials. The database
password is passed to the osm2pgsql container by variable name (`-e PGPASSWORD`), never on a command line;
`psql` runs inside the db container over its local socket.

## osm2pgsql and the flex style

- Tool versions (expected, verify with the version line the scripts print): `debian:bookworm-slim` gives
  osmium-tool 1.15 and osm2pgsql 1.8; `OSM_TOOLS_BASE=debian:trixie-slim` gives osmium-tool 1.18 and
  osm2pgsql 2.x. The style only uses API present in both (`define_table` with `schema` and `ids`,
  `object:as_linestring()` / `object:as_point()`, `table:insert()`). With osm2pgsql 1.9+ the script also passes
  `--schema=osm_import`, which keeps the `osm2pgsql_properties` table out of `public`.
- Non-slim mode (node locations in RAM, `OSM2PGSQL_CACHE_MB`, default 1000): no middle tables in the database,
  which is fine because every import is a full `--create`.
- Ways: `highway` in motorway, trunk, primary, secondary, tertiary (and their `_link`), unclassified,
  residential, living_street, service, road; skipped when `area=yes` or `access=no`. `access=private` is kept
  and stored so public tiles and exports can exclude it (SECURITY.md 6.4). Narrower tags such as
  `motor_vehicle=no` are left to Valhalla.
- Calming points: `traffic_calming` = bump, hump, table, cushion, rumble_strip as-is; `traffic_calming=yes`
  becomes `bump` (an untyped calming node in India is almost always a speed breaker, and a wrong "bump" only
  suppresses a hazard there); chicane, choker, island, dip and others are skipped (no vertical jolt);
  `railway=level_crossing` becomes `level_crossing`. Calming tags on ways are ignored.

### Why staging plus TRUNCATE/INSERT instead of renaming shadow tables

Flyway owns `osm_way` and `osm_calming_point`, so osm2pgsql never creates or drops them. DEPLOYMENT.md 5.2
sketches shadow tables that are renamed into place; for Flyway-owned tables that would leave index and
constraint names out of step with the migrations, and any column a later migration adds (for example a V9
`access` column) would have to be recreated on the shadow table by hand. The pilot therefore copies the staging
rows inside one transaction (`swap.sql`):

- Pro: schema stays exactly as Flyway built it; all-or-nothing; readers never see half-filled tables.
- Con: an ACCESS EXCLUSIVE lock for the duration of the copy (estimated seconds to tens of seconds for the
  pilot). Only background jobs read these tables (map-matching, hazard suppression) and they retry.
- Guards: refuses an empty staging table, or one with less than half the current ways (`--force` to override).
  `access` is filled only when `information_schema` shows that `osm_way.access` exists.

### Reconciliation (DATABASE_SCHEMA.md A7)

Segment keys are `(way_id, bucket)` with 50 m buckets. After the swap, `reconcile.sql`:

1. deletes `road_segment_stat` rows whose `way_id` is no longer in `osm_way`;
2. for ways whose length changed by more than 10 m (old lengths are saved by `swap.sql` in
   `osm_import.prev_way_length`), deletes rows for buckets that start beyond the new length;
3. refuses when `osm_way` is empty or more than 25 % of all statistics would go (`--force` to override);
4. reports, without changing them, hazards and observations that point at ways that no longer exist.

Future work: real re-keying (match old segment geometries to the new ways with `ST_LineLocatePoint` and move
statistics instead of dropping them, and re-bucket ways whose geometry moved without a length change).

## Valhalla

Image: the gis-ops "docker-valhalla" scripted image, `ghcr.io/gis-ops/docker-valhalla/valhalla:<tag>`, set by
`VALHALLA_IMAGE` in `.env` (default tag 3.5.1 in the compose files and scripts). **Verify the tag, the arm64
build (Ampere VM) and everything in the table below against the image README before relying on it**; the
environment that wrote these files had no network access to check. The same "scripted" image is also
published by the Valhalla project itself as `ghcr.io/valhalla/valhalla-scripted` (verify; same conventions
expected).

| Env var (as used here) | Value | Meaning (per the image README, verify) |
|---|---|---|
| `serve_tiles` | `False` for builds, `True` for the service | Start `valhalla_service` after the build, or exit |
| `force_rebuild` | `True` for builds, `False` for the service | Rebuild even if tiles exist |
| `use_tiles_ignore_pbf` | `False` for builds, `True` for the service | Use existing tiles without checking the PBF |
| `build_admins` | `True` | Admin areas: country rules such as left-hand traffic in India and access defaults |
| `build_time_zones` | `True` | Time zones for time-dependent routing |
| `build_elevation` | `False` | No elevation data (not needed, saves disk and time) |
| `build_tar` | `True` | Pack tiles into `valhalla_tiles.tar` (faster loading) |
| `server_threads` | 2 (dev), 4 (prod) | Worker threads |
| container user | uid 59999 | Owner needed for the mounted directory on Linux hosts (`VALHALLA_UID`) |

Every `*.osm.pbf` in `/custom_files` is built. `valhalla-build.sh build` copies the trimmed extract into a new
`<VALHALLA_ROOT>/<YYYYMMDD-HHMMSS>/` and runs a throwaway container with `serve_tiles=False` (limit
`VALHALLA_BUILD_MEM`, default 6g) while the running service keeps the old directory. `activate` writes
`VALHALLA_DATA_DIR` into `.env` (the compose files mount `${VALHALLA_DATA_DIR}` at `/custom_files`), remembers the
old directory in `.previous_dir` and recreates the container. `smoke` waits for `/status`, requests a `/route`
Shivajinagar to Deccan to Swargate for `motorcycle`, `motor_scooter` and `auto`, then a `/trace_attributes`
(`map_snap`) of the motorcycle route's own encoded shape, so the trace is on the network by construction
(hand-typed trace points can fall off the road and fail for the wrong reason). It prints OK or FAILED with the
HTTP status and timing only. In production the requests run inside the container with `curl` (verify that the
image has it).

Limits: the image generates `valhalla.json` with Valhalla's defaults, which are generous for a city-scale
pilot (route distance limits of hundreds of km and more, trace limits in the thousands of points). If route or
trace requests hit a limit, edit `service_limits` in `<dir>/valhalla.json` and recreate the container; record
the change in `docs/PROGRESS.md`. Ports: `127.0.0.1:8002` in dev, none in production (internal network only).

## Seed data (dev only)

`seed-dev-data.sql` adds, all clearly synthetic (UUIDs start with `5eed0000-`, OSM ids are negative):

- 5 straight synthetic roads in central Pune (2 primary, 1 secondary, 2 residential), only while `osm_way` has
  no real rows, and one synthetic speed breaker;
- 3 devices with trust 0.5;
- 20 hazards: severities 1 to 3, confidence 0.12 to 0.97, statuses active, stale (1) and fixed (1), two
  suppressed (traffic calming, repeat pattern) and two below the 0.3 confidence visibility rule;
- one `road_segment_stat` row per 50 m bucket (with coverage gaps), scores spread from red to green, some
  rows below the 3 samples / 2 devices visibility rule.

The wrapper refuses unless the target is the local dev compose stack with a localhost `DB_URL`, and the SQL
refuses databases with more than 100 non-synthetic devices. Never run it in production.

## Things to verify before production

1. `GEOFABRIK_URL`: the India sub-region that contains the whole pilot bbox (expected `western-zone`), from
   https://download.geofabrik.de/asia/india.html.
2. Valhalla image tag, arm64 availability, env var names, uid 59999, `curl` in the image, and that
   `serve_tiles=False` makes the container exit after building.
3. osm2pgsql behaviour of the flex style on the version you run (1.8 or 2.x): run `osm-import.sh --area dev`
   once and check the counts; geometry rows that fail `not_null` should simply be skipped.
4. That `osmium extract --strategy smart -S types=multipolygon,boundary` keeps the India boundary complete
   enough for `build_admins` (check left-hand driving in a route's maneuvers).
