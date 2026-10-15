#!/usr/bin/env bash
# Import the trimmed extract into the reference tables osm_way and osm_calming_point (M4-3,
# DEPLOYMENT.md 5.1 step 4 and 5.2 steps 3 and 4).
#
#   infra/scripts/osm-import.sh [--area pilot|dev] [--pbf FILE] [--stage-only | --swap-only] [--force]
#
#   --area       which trimmed file of the newest download to use: pilot.osm.pbf (default) or dev.osm.pbf
#   --pbf        explicit .osm.pbf file instead (for example the previous month's file for a rollback)
#   --stage-only run osm2pgsql into the staging schema osm_import only (no change to the live tables)
#   --swap-only  run swap.sql and reconcile.sql on the existing staging tables only
#   --force      pass force=1 to swap.sql and reconcile.sql (skip the "suspiciously small" guards)
#
# Flow: CREATE SCHEMA osm_import -> osm2pgsql --create (flex, infra/osm/flex/mapmyroad.lua) writes
# osm_import.ways and osm_import.calming -> swap.sql copies them into the Flyway tables in one
# transaction -> reconcile.sql drops segment statistics that no longer map to a way.
# Idempotent: re-running with the same file gives the same tables. Prints row counts, never coordinates.
# The Flyway migrations (V2) must already have run, i.e. the API has started once against this database.
set -euo pipefail

# shellcheck source=osm-common.sh
. "$(dirname "$0")/osm-common.sh"

AREA=pilot
PBF=""
STAGE=1
SWAP=1
FORCE=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --area) AREA="${2:?--area needs a value}"; shift 2 ;;
    --pbf) PBF="${2:?--pbf needs a value}"; shift 2 ;;
    --stage-only) SWAP=0; shift ;;
    --swap-only) STAGE=0; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ "$STAGE" -eq 1 ] || [ "$SWAP" -eq 1 ] || die "--stage-only and --swap-only exclude each other"

require_docker
compose ps --status running -q db | grep -q . || die "the db service is not running"

has_table() {
  db_psql -tA -c "SELECT to_regclass('public.$1') IS NOT NULL" | grep -q '^t$'
}
has_table osm_way && has_table osm_calming_point \
  || die "osm_way / osm_calming_point do not exist yet: start the API once so Flyway runs V2"

if [ "$STAGE" -eq 1 ]; then
  if [ -z "$PBF" ]; then
    DIR="$(latest_osm_dir)"
    [ -n "$DIR" ] || die "no download under ${OSM_DATA_ROOT}; run osm-download.sh first"
    PBF="${DIR}/${AREA}.osm.pbf"
  fi
  [ -f "$PBF" ] || die "missing ${PBF}; run osm-download.sh --area ${AREA} first"
  PBF_DIR="$(cd "$(dirname "$PBF")" && pwd)"
  PBF_NAME="$(basename "$PBF")"

  ensure_tools_image
  DB_USER_NAME="$(env_require POSTGRES_USER)"
  # Exported only into this process and passed to docker by name (-e PGPASSWORD), so the password is
  # not part of any command line.
  PGPASSWORD="$(env_require POSTGRES_PASSWORD)"
  export PGPASSWORD
  NET="$(db_network)"

  log "creating schema osm_import (if missing)"
  db_psql -c 'CREATE SCHEMA IF NOT EXISTS osm_import'

  # osm2pgsql >= 1.9 has --schema (also places its osm2pgsql_properties table there instead of public).
  SCHEMA_OPT=()
  if tools_run -- sh -c 'osm2pgsql --help 2>&1 | grep -qE "(^|[[:space:]])--schema"'; then
    SCHEMA_OPT=(--schema=osm_import)
  fi

  log "osm2pgsql: ${PBF_NAME} -> osm_import.ways, osm_import.calming ($(tool_versions))"
  START="$(date +%s)"
  # No --slim: node locations are kept in RAM (--cache MB), no middle tables are written to the database,
  # which is fine because the import is always a full --create (no replication updates).
  tools_run --network "$NET" -e PGPASSWORD \
    -v "$(host_path "$PBF_DIR"):/data:ro" \
    -v "$(host_path "${OSM_CONF_DIR}/flex"):/style:ro" -- \
    osm2pgsql --create --output=flex --style=/style/mapmyroad.lua \
      --database=roadquality --host=db --port=5432 --user="$DB_USER_NAME" \
      ${SCHEMA_OPT[@]+"${SCHEMA_OPT[@]}"} \
      --cache="${OSM2PGSQL_CACHE_MB:-1000}" --number-processes="${OSM2PGSQL_PROCESSES:-2}" \
      "/data/${PBF_NAME}"
  unset PGPASSWORD
  log "osm2pgsql done in $(( $(date +%s) - START )) s"
  db_psql -tA -F ' ' -c "SELECT 'staged ways:', count(*) FROM osm_import.ways" \
                     -c "SELECT 'staged calming points:', count(*) FROM osm_import.calming"
fi

if [ "$SWAP" -eq 1 ]; then
  log "swap: staging -> osm_way, osm_calming_point (one transaction)"
  START="$(date +%s)"
  db_psql -v force="$FORCE" < "${OSM_CONF_DIR}/swap.sql"
  log "swap done in $(( $(date +%s) - START )) s"
  log "reconcile: segment statistics"
  db_psql -v force="$FORCE" < "${OSM_CONF_DIR}/reconcile.sql"
  log "import finished"
fi
