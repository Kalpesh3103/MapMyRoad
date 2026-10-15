#!/usr/bin/env bash
# Build Valhalla routing tiles from the trimmed extract in a NEW dated directory, switch the valhalla
# service to it and smoke test it (M4-2, DEPLOYMENT.md 5.1 step 3 and 5.2 steps 2, 5 and 6, runbook 12.3).
#
#   infra/scripts/valhalla-build.sh [all] [--area pilot|dev] [--pbf FILE]   build + activate + smoke (default)
#   infra/scripts/valhalla-build.sh build [--area pilot|dev] [--pbf FILE]   build only, logs "built: <dir>"
#   infra/scripts/valhalla-build.sh activate DIR                            point the service at DIR and restart
#   infra/scripts/valhalla-build.sh smoke                                   route + trace_attributes checks
#   infra/scripts/valhalla-build.sh rollback                                return to the previous directory
#   infra/scripts/valhalla-build.sh cleanup [DAYS]                          delete dated dirs older than DAYS (7)
#
# Zero-downtime layout: <VALHALLA_ROOT>/<YYYYMMDD-HHMMSS>/ per build (dev: infra/data/valhalla, prod:
# /opt/rq/data/valhalla). Tiles are built by a throwaway container while the running service keeps serving
# the old directory. The compose files mount ${VALHALLA_DATA_DIR} (set in .env by "activate") at
# /custom_files; the previous directory is remembered in <VALHALLA_ROOT>/.previous_dir for rollback.
#
# Relies on the gis-ops "docker-valhalla" image conventions (VERIFY against the image README for the tag
# you pin): every *.osm.pbf in /custom_files is built; env vars serve_tiles, force_rebuild,
# use_tiles_ignore_pbf, build_admins, build_time_zones, build_elevation, build_tar, server_threads
# ("True"/"False" strings); with serve_tiles=False the container exits after building; it runs as uid 59999.
# Output prints only success or failure, HTTP status and timing. The sample locations below are fixed
# public places in central Pune (no user data).
set -euo pipefail

# shellcheck source=osm-common.sh
. "$(dirname "$0")/osm-common.sh"

DEFAULT_VALHALLA_IMAGE="ghcr.io/gis-ops/docker-valhalla/valhalla:3.5.1"   # VERIFY tag and arm64 build
VALHALLA_IMAGE="${VALHALLA_IMAGE:-$(env_get VALHALLA_IMAGE)}"
VALHALLA_IMAGE="${VALHALLA_IMAGE:-$DEFAULT_VALHALLA_IMAGE}"
PREV_FILE="${VALHALLA_ROOT}/.previous_dir"

# Shivajinagar -> Deccan Gymkhana -> Swargate (all inside DEV_BBOX and PILOT_BBOX).
ROUTE_LOCATIONS='[{"lat":18.5308,"lon":73.8475},{"lat":18.5167,"lon":73.8410},{"lat":18.5018,"lon":73.8636}]'

usage() { sed -n '2,25p' "$0"; }

compose_valhalla() { compose --profile valhalla "$@"; }

cmd_build() {
  local area=pilot pbf="" stamp new start
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --area) area="${2:?--area needs a value}"; shift 2 ;;
      --pbf) pbf="${2:?--pbf needs a value}"; shift 2 ;;
      *) die "unknown argument: $1" ;;
    esac
  done
  if [ -z "$pbf" ]; then
    local dir
    dir="$(latest_osm_dir)"
    [ -n "$dir" ] || die "no download under ${OSM_DATA_ROOT}; run osm-download.sh first"
    pbf="${dir}/${area}.osm.pbf"
  fi
  [ -f "$pbf" ] || die "missing ${pbf}"

  require_docker
  stamp="$(date -u +%Y%m%d-%H%M%S)"
  new="${VALHALLA_ROOT}/${stamp}"
  mkdir -p "$new"
  new="$(cd "$new" && pwd)"
  cp "$pbf" "${new}/$(basename "$pbf")"
  if [ "$(uname -s)" = "Linux" ]; then
    ensure_tools_image
    tools_run -v "$(host_path "$new"):/custom_files" -- chown -R "${VALHALLA_UID}:${VALHALLA_UID}" /custom_files
  fi

  log "building Valhalla tiles in ${new} with ${VALHALLA_IMAGE} (the running service is not touched)"
  start="$(date +%s)"
  docker run --rm --name "mmr-valhalla-build-${stamp}" \
    --memory "${VALHALLA_BUILD_MEM:-6g}" \
    -v "$(host_path "$new"):/custom_files" \
    -e serve_tiles=False \
    -e force_rebuild=True \
    -e use_tiles_ignore_pbf=False \
    -e build_admins=True \
    -e build_time_zones=True \
    -e build_elevation=False \
    -e build_tar=True \
    -e server_threads="${VALHALLA_THREADS:-2}" \
    "$VALHALLA_IMAGE" >"${new}/build.log" 2>&1 \
    || { tail -n 20 "${new}/build.log" >&2; die "tile build failed, see ${new}/build.log"; }

  if [ ! -s "${new}/valhalla_tiles.tar" ] && [ -z "$(ls -A "${new}/valhalla_tiles" 2>/dev/null)" ]; then
    die "no tiles were produced in ${new} (see build.log)"
  fi
  [ -f "${new}/valhalla.json" ] || die "valhalla.json missing in ${new}"
  log "tiles built in $(( $(date +%s) - start )) s, size $(du -sh "$new" | cut -f 1)"
  BUILT_DIR="$new"
  log "built: ${new}"
}

cmd_activate() {
  local new="${1:?activate needs a directory}" current value
  [ -d "$new" ] || die "not a directory: ${new}"
  new="$(cd "$new" && pwd)"
  current="$(valhalla_current_dir)"
  if [ -n "$current" ] && [ "$current" != "$new" ]; then
    printf '%s\n' "$current" > "$PREV_FILE"
  fi
  # Relative to the compose directory when possible (works on every OS), otherwise absolute.
  case "$new" in
    "${COMPOSE_DIR}"/*) value="./${new#"${COMPOSE_DIR}"/}" ;;
    *) value="$new" ;;
  esac
  env_set VALHALLA_DATA_DIR "$value"
  log "valhalla now uses ${new}; recreating the container"
  compose_valhalla up -d --force-recreate --no-deps valhalla
}

# POST a JSON body (stdin) to a Valhalla endpoint. Writes the body to $2 and prints "<http_code> <seconds>".
vpost() {
  local path="$1" out="$2" raw
  raw="$(mktemp)"
  if [ -n "$VALHALLA_SMOKE_URL" ]; then
    curl -sS --max-time 60 -H 'Content-Type: application/json' --data-binary @- \
      -w '\n%{http_code} %{time_total}' "${VALHALLA_SMOKE_URL}${path}" > "$raw" || true
  else
    # Production: no published port, so curl runs inside the container (verify curl exists in the image).
    compose_valhalla exec -T valhalla curl -sS --max-time 60 -H 'Content-Type: application/json' \
      --data-binary @- -w '\n%{http_code} %{time_total}' "http://127.0.0.1:8002${path}" > "$raw" || true
  fi
  sed '$d' "$raw" > "$out"
  tail -n 1 "$raw"
  rm -f "$raw"
}

vget_ok() {
  if [ -n "$VALHALLA_SMOKE_URL" ]; then
    curl -fsS --max-time 5 -o /dev/null "${VALHALLA_SMOKE_URL}$1"
  else
    compose_valhalla exec -T valhalla curl -fsS --max-time 5 -o /dev/null "http://127.0.0.1:8002$1"
  fi
}

cmd_smoke() {
  local i body res code secs shape failed=0 costing
  require_docker
  log "waiting for valhalla /status"
  for i in $(seq 1 60); do
    if vget_ok /status >/dev/null 2>&1; then break; fi
    [ "$i" -eq 60 ] && { log "smoke: valhalla did not become ready" >&2; return 1; }
    sleep 5
  done
  body="$(mktemp)"

  for costing in motorcycle motor_scooter auto; do
    res="$(printf '{"locations":%s,"costing":"%s","directions_options":{"units":"kilometers"}}' \
      "$ROUTE_LOCATIONS" "$costing" | vpost /route "$body")"
    code="${res%% *}"; secs="${res##* }"
    if [ "$code" = "200" ] && grep -q '"shape":"' "$body"; then
      log "smoke route ${costing}: OK (${secs} s)"
    else
      log "smoke route ${costing}: FAILED (HTTP ${code:-none}, $(grep -oE '"error_code":[0-9]+' "$body" | head -n 1))" >&2
      failed=1
    fi
  done

  # Map-matching check. The trace is the encoded shape of the first leg of the motorcycle route, so it lies
  # on the road network by construction (hand-typed trace points can fall off the road and fail spuriously).
  res="$(printf '{"locations":%s,"costing":"motorcycle"}' "$ROUTE_LOCATIONS" | vpost /route "$body")"
  shape="$(grep -oE '"shape":"[^"]*"' "$body" | head -n 1 | sed -e 's/^"shape":"//' -e 's/"$//')"
  if [ -z "$shape" ]; then
    log "smoke trace_attributes: SKIPPED (no route shape)" >&2
    failed=1
  else
    res="$(printf '{"encoded_polyline":"%s","costing":"motorcycle","shape_match":"map_snap","filters":{"attributes":["edge.way_id","edge.length"],"action":"include"}}' \
      "$shape" | vpost /trace_attributes "$body")"
    code="${res%% *}"; secs="${res##* }"
    if [ "$code" = "200" ] && grep -q '"way_id"' "$body"; then
      log "smoke trace_attributes motorcycle: OK (${secs} s)"
    else
      log "smoke trace_attributes motorcycle: FAILED (HTTP ${code:-none}, $(grep -oE '"error_code":[0-9]+' "$body" | head -n 1))" >&2
      failed=1
    fi
  fi
  rm -f "$body"
  [ "$failed" -eq 0 ]
}

cmd_rollback() {
  local prev
  [ -r "$PREV_FILE" ] || die "no previous Valhalla directory recorded"
  prev="$(cat "$PREV_FILE")"
  [ -d "$prev" ] || die "previous directory is gone: ${prev}"
  log "rolling back Valhalla to ${prev}"
  cmd_activate "$prev"
  cmd_smoke
}

cmd_cleanup() {
  local days="${1:-7}" current prev d
  [[ "$days" =~ ^[0-9]+$ ]] || die "DAYS must be a number"
  current="$(valhalla_current_dir)"
  prev="$(cat "$PREV_FILE" 2>/dev/null || true)"
  find "$VALHALLA_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20[0-9][0-9][0-1][0-9][0-3][0-9]-[0-9]*' -mtime "+${days}" \
    | while read -r d; do
        d="$(cd "$d" && pwd)"
        if [ "$d" = "$current" ] || [ "$d" = "$prev" ]; then continue; fi
        log "removing old Valhalla build ${d}"
        rm -rf -- "$d"
      done
}

CMD="${1:-all}"
case "$CMD" in
  build|activate|smoke|rollback|cleanup|all) if [ "$#" -gt 0 ]; then shift; fi ;;
  --area|--pbf) CMD=all ;;
  -h|--help) usage; exit 0 ;;
  *) usage; die "unknown command: ${CMD}" ;;
esac

case "$CMD" in
  build) cmd_build "$@" ;;
  activate) cmd_activate "$@" ;;
  smoke) cmd_smoke ;;
  rollback) cmd_rollback ;;
  cleanup) cmd_cleanup "$@" ;;
  all)
    BUILT_DIR=""
    cmd_build "$@"
    NEW="$BUILT_DIR"
    cmd_activate "$NEW"
    if ! cmd_smoke; then
      if [ -r "$PREV_FILE" ]; then
        log "smoke test failed on the new tiles; rolling back" >&2
        cmd_rollback || true
      fi
      die "Valhalla smoke test failed for ${NEW}"
    fi
    log "Valhalla ready on ${NEW}"
    ;;
esac
