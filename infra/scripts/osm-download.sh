#!/usr/bin/env bash
# Download the Geofabrik extract, verify its published MD5 and trim it to the pilot (or dev) bbox
# (M4-2, DEPLOYMENT.md sections 5.1 and 5.2, SECURITY.md section 11).
#
#   infra/scripts/osm-download.sh [--area pilot|dev] [--url URL] [--offline]
#
#   --area pilot   trim to PILOT_BBOX -> <dir>/pilot.osm.pbf (default)
#   --area dev     trim to DEV_BBOX   -> <dir>/dev.osm.pbf (a few km of central Pune, for local work and CI)
#   --url          override GEOFABRIK_URL from infra/osm/pilot-area.env (must be https)
#   --offline      do not download; reuse the newest dated directory that already has a verified extract
#
# Output directory: <OSM_DATA_ROOT>/<YYYY-MM-DD>/ (UTC date of the download)
#   dev:  infra/data/osm/<date>/   (git-ignored)
#   prod: /opt/rq/data/osm/<date>/
# Idempotent: an already downloaded and verified file of the same day is not fetched again, the trimmed
# file is rebuilt (osmium --overwrite). Writes EXTRACT_INFO (key=value) next to the files.
set -euo pipefail

# shellcheck source=osm-common.sh
. "$(dirname "$0")/osm-common.sh"

AREA=pilot
URL=""
OFFLINE=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --area) AREA="${2:?--area needs a value}"; shift 2 ;;
    --url) URL="${2:?--url needs a value}"; shift 2 ;;
    --offline) OFFLINE=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

BBOX="$(bbox_for_area "$AREA")"
URL="${URL:-$(area_get GEOFABRIK_URL)}"
[[ "$URL" =~ ^https://[A-Za-z0-9./_-]+\.osm\.pbf$ ]] || die "GEOFABRIK_URL must be an https URL ending in .osm.pbf"
SRC_NAME="$(basename "$URL")"
OUT_NAME="${AREA}.osm.pbf"

ensure_tools_image
command -v curl >/dev/null || die "curl is not installed"

if [ "$OFFLINE" -eq 1 ]; then
  DIR="$(latest_osm_dir)"
  [ -n "$DIR" ] && [ -f "${DIR}/${SRC_NAME}" ] || die "--offline: no previous download of ${SRC_NAME} under ${OSM_DATA_ROOT}"
else
  DIR="${OSM_DATA_ROOT}/$(date -u +%F)"
fi
mkdir -p "$DIR"
DIR="$(cd "$DIR" && pwd)"

# Rough free-space check: the regional file plus the trimmed copy plus headroom.
AVAIL_KB="$(df -Pk "$DIR" | awk 'NR == 2 {print $4}')"
if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt 3000000 ]; then
  log "WARNING: less than 3 GB free in ${DIR}"
fi

verify_md5() {
  # md5sum -c inside the tool image, so the host needs no md5 tool (macOS ships `md5`, not `md5sum`).
  # Geofabrik's .md5 lines look like "<hash>  <file name>", and the file is saved under that same name.
  tools_run -v "$(host_path "$DIR"):/data:ro" -w /data -- md5sum -c --status "${SRC_NAME}.md5"
}

if [ "$OFFLINE" -eq 0 ]; then
  if [ -f "${DIR}/${SRC_NAME}" ] && [ -f "${DIR}/${SRC_NAME}.md5" ] && verify_md5; then
    log "already downloaded and verified today: ${SRC_NAME}"
  else
    log "downloading ${SRC_NAME} (and .md5) into ${DIR}"
    # Path conversion is disabled for Docker; native Windows curl also needs host-form paths.
    curl -fsSL --retry 3 --retry-delay 10 -o "$(host_path "${DIR}/${SRC_NAME}.md5")" "${URL}.md5"
    curl -fL --retry 3 --retry-delay 10 --progress-bar -o "$(host_path "${DIR}/${SRC_NAME}.part")" "$URL"
    mv -f "${DIR}/${SRC_NAME}.part" "${DIR}/${SRC_NAME}"
    if ! verify_md5; then
      rm -f "${DIR}/${SRC_NAME}"
      die "MD5 mismatch for ${SRC_NAME}; the file was deleted (SECURITY.md section 11). Retry later."
    fi
    log "MD5 verified"
  fi
else
  verify_md5 || die "MD5 mismatch for the existing ${DIR}/${SRC_NAME}"
  log "offline: reusing verified ${DIR}/${SRC_NAME}"
fi

# Trim to the bbox. Strategy "smart" keeps complete multipolygon and boundary relations that cross the
# bbox edge, so Valhalla's admin build (build_admins) still sees the India and Maharashtra boundaries
# (left-hand traffic and country access defaults depend on them). Ways are kept complete (no cut ends).
log "extracting ${AREA} bbox ${BBOX} -> ${OUT_NAME}"
START="$(date +%s)"
tools_run -v "$(host_path "$DIR"):/data" -w /data -- \
  osmium extract -b "$BBOX" --strategy smart -S types=multipolygon,boundary \
  --overwrite --no-progress -o "/data/${OUT_NAME}" "/data/${SRC_NAME}"
log "extract done in $(( $(date +%s) - START )) s"

# OSM data timestamp from the Geofabrik file header (may be empty for other sources).
OSM_TS="$(tools_run -v "$(host_path "$DIR"):/data:ro" -- osmium fileinfo -g header.option.osmosis_replication_timestamp "/data/${SRC_NAME}" 2>/dev/null || true)"
SRC_SIZE="$(du -h "${DIR}/${SRC_NAME}" | cut -f 1)"
OUT_SIZE="$(du -h "${DIR}/${OUT_NAME}" | cut -f 1)"
VERSIONS="$(tool_versions)"

INFO="${DIR}/EXTRACT_INFO"
touch "$INFO"
tmp="$(mktemp)"
grep -vE "^(source_url|source_file|source_md5|osm_timestamp|tools|${AREA}_bbox|${AREA}_file)=" "$INFO" > "$tmp" || true
{
  printf 'source_url=%s\n' "$URL"
  printf 'source_file=%s\n' "$SRC_NAME"
  printf 'source_md5=%s\n' "$(cut -d ' ' -f 1 "${DIR}/${SRC_NAME}.md5")"
  printf 'osm_timestamp=%s\n' "${OSM_TS:-unknown}"
  printf 'tools=%s\n' "$VERSIONS"
  printf '%s_bbox=%s\n' "$AREA" "$BBOX"
  printf '%s_file=%s\n' "$AREA" "$OUT_NAME"
} >> "$tmp"
cat "$tmp" > "$INFO"
rm -f "$tmp"

log "source ${SRC_NAME} ${SRC_SIZE}, ${OUT_NAME} ${OUT_SIZE}, OSM data timestamp ${OSM_TS:-unknown}"
log "tools: ${VERSIONS}"
log "output: ${DIR}/${OUT_NAME}"
