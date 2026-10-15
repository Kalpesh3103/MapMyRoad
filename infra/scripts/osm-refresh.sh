#!/usr/bin/env bash
# Monthly OSM refresh (DEPLOYMENT.md 5.2, scheduled job "OSM refresh" in section 11).
#
#   /opt/rq/scripts/osm-refresh.sh [--area pilot|dev] [--force] [--offline]
#
# Order is chosen so the live system changes only at the end, with the database swap and the Valhalla
# switch back to back (the map-matching job reads way IDs from Valhalla and joins them to osm_way, so the
# two must come from the same extract):
#   1. download the regional extract into a new dated directory, verify MD5, trim to the bbox
#   2. build Valhalla tiles into a new dated directory (old tiles keep serving)
#   3. osm2pgsql into the staging schema osm_import (live tables untouched)
#   4. swap.sql: copy staging into osm_way / osm_calming_point in one transaction
#   5. reconcile.sql: drop segment statistics that no longer map to a way
#   6. point Valhalla at the new directory, restart it, smoke test (rollback of Valhalla on failure)
#   7. delete builds and downloads older than 7 days (the current and previous ones are always kept)
#   8. print a line to paste into docs/PROGRESS.md
# Rollback of the reference tables after a bad refresh: osm-import.sh --pbf <previous dir>/pilot.osm.pbf
# and valhalla-build.sh rollback (DEPLOYMENT.md runbook 12.3).
#
# Suggested schedule (root crontab on the VM, quiet hour, first Sunday of the month):
#   30 2 1-7 * 0  root  /opt/rq/scripts/osm-refresh.sh >> /var/log/rq-osm-refresh.log 2>&1
# (cron ORs day-of-month and day-of-week; if that matters, check the weekday inside a wrapper.)
set -euo pipefail

# shellcheck source=osm-common.sh
. "$(dirname "$0")/osm-common.sh"

AREA=pilot
FORCE_OPT=()
DL_OPT=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --area) AREA="${2:?--area needs a value}"; shift 2 ;;
    --force) FORCE_OPT=(--force); shift ;;
    --offline) DL_OPT=(--offline); shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
KEEP_DAYS="${OSM_KEEP_DAYS:-7}"
S="$OSM_SCRIPTS_DIR"

# One refresh at a time.
LOCK_DIR="${OSM_DATA_ROOT}/.refresh.lock"
mkdir -p "$OSM_DATA_ROOT"
mkdir "$LOCK_DIR" 2>/dev/null || die "another refresh is running (remove ${LOCK_DIR} if it is stale)"
trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT

T0="$(date +%s)"
log "OSM refresh (${MMR_ENV}, area ${AREA}) started"

log "step 1/7: download and extract"
"${S}/osm-download.sh" --area "$AREA" ${DL_OPT[@]+"${DL_OPT[@]}"}
OSM_DIR="$(latest_osm_dir)"

log "step 2/7: build Valhalla tiles in a new directory"
BUILD_OUT="$(mktemp)"
"${S}/valhalla-build.sh" build --area "$AREA" | tee "$BUILD_OUT"
NEW_TILES="$(sed -n 's/.* built: //p' "$BUILD_OUT" | tail -n 1)"
rm -f "$BUILD_OUT"
[ -n "$NEW_TILES" ] && [ -d "$NEW_TILES" ] || die "could not determine the new Valhalla directory"

log "step 3/7: osm2pgsql into the staging schema"
"${S}/osm-import.sh" --area "$AREA" --stage-only

log "step 4-5/7: swap reference tables and reconcile segment keys"
"${S}/osm-import.sh" --swap-only ${FORCE_OPT[@]+"${FORCE_OPT[@]}"}

log "step 6/7: switch Valhalla to the new tiles and smoke test"
"${S}/valhalla-build.sh" activate "$NEW_TILES"
if ! "${S}/valhalla-build.sh" smoke; then
  log "smoke test failed, rolling Valhalla back to the previous tiles" >&2
  "${S}/valhalla-build.sh" rollback || true
  die "refresh incomplete: Valhalla rolled back but osm_way is already on the new extract. Either fix and re-run, or restore the reference tables with: osm-import.sh --pbf <previous dir>/${AREA}.osm.pbf"
fi

log "step 7/7: cleanup (keep ${KEEP_DAYS} days, always keep current and previous)"
"${S}/valhalla-build.sh" cleanup "$KEEP_DAYS"
# Downloads: keep the two newest dated directories regardless of age.
find "$OSM_DATA_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]' | sort | sed '$d' | sed '$d' \
  | while read -r d; do
      if [ -n "$(find "$d" -maxdepth 0 -mtime "+${KEEP_DAYS}")" ]; then
        log "removing old download ${d}"
        rm -rf -- "$d"
      fi
    done

info_get() { grep -E "^$1=" "${OSM_DIR}/EXTRACT_INFO" 2>/dev/null | tail -n 1 | cut -d '=' -f 2- || true; }
COUNTS="$(db_psql -tA -F ' ' -c 'SELECT (SELECT count(*) FROM osm_way), (SELECT count(*) FROM osm_calming_point), (SELECT count(*) FROM road_segment_stat)')"
read -r N_WAYS N_POINTS N_STATS <<< "$COUNTS"
VALHALLA_IMAGE_USED="$(env_get VALHALLA_IMAGE)"

log "OSM refresh finished in $(( ($(date +%s) - T0) / 60 )) min"
echo
echo "Paste into docs/PROGRESS.md:"
printf -- '- %s OSM refresh (%s): source %s, OSM data timestamp %s, bbox %s, %s, Valhalla image %s, tiles %s; osm_way %s rows, osm_calming_point %s rows, road_segment_stat %s rows after reconcile.\n' \
  "$(date -u +%F)" "$MMR_ENV" "$(info_get source_file)" "$(info_get osm_timestamp)" "$(info_get "${AREA}_bbox")" \
  "$(info_get tools)" "${VALHALLA_IMAGE_USED:-ghcr.io/gis-ops/docker-valhalla/valhalla (default tag)}" \
  "$(basename "$NEW_TILES")" "$N_WAYS" "$N_POINTS" "$N_STATS"
