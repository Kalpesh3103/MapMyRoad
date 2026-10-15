#!/usr/bin/env bash
# Shared helpers for the OSM pipeline scripts (sourced, not executed): osm-download.sh, osm-import.sh,
# valhalla-build.sh, osm-refresh.sh, seed-dev-data.sh. See infra/osm/README.md.
#
# Environment selection (MMR_ENV):
#   dev   paths under infra/ (infra/data/osm, infra/data/valhalla), infra/docker-compose.dev.yml, infra/.env
#   prod  paths under /opt/rq (DEPLOYMENT.md section 3.3), /opt/rq/compose/docker-compose.yml and .env
#   auto  (default) prod when /opt/rq/compose/docker-compose.yml exists, otherwise dev
# Layout on the VM: scripts in /opt/rq/scripts/, the contents of infra/osm/ in /opt/rq/osm/.

OSM_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${OSM_SCRIPTS_DIR}/.." && pwd)"   # repo infra/ locally, /opt/rq on the VM
OSM_CONF_DIR="${OSM_CONF_DIR:-${INFRA_DIR}/osm}"

# Git Bash on Windows rewrites arguments that look like POSIX paths (/data, /custom_files) before they reach
# docker. Disable that; harmless everywhere else.
export MSYS_NO_PATHCONV=1

MMR_ENV="${MMR_ENV:-auto}"
if [ "$MMR_ENV" = "auto" ]; then
  if [ -f /opt/rq/compose/docker-compose.yml ]; then MMR_ENV=prod; else MMR_ENV=dev; fi
fi

case "$MMR_ENV" in
  dev)
    COMPOSE_DIR="${COMPOSE_DIR:-${INFRA_DIR}}"
    COMPOSE_FILE="${COMPOSE_FILE:-${INFRA_DIR}/docker-compose.dev.yml}"
    ENV_FILE="${ENV_FILE:-${INFRA_DIR}/.env}"
    OSM_DATA_ROOT="${OSM_DATA_ROOT:-${INFRA_DIR}/data/osm}"
    VALHALLA_ROOT="${VALHALLA_ROOT:-${INFRA_DIR}/data/valhalla}"
    # Smoke requests go to the loopback port published by the dev compose file.
    VALHALLA_SMOKE_URL="${VALHALLA_SMOKE_URL:-http://127.0.0.1:8002}"
    ;;
  prod)
    RQ_ROOT="${RQ_ROOT:-/opt/rq}"
    OSM_DATA_ROOT="${OSM_DATA_ROOT:-${RQ_ROOT}/data/osm}"
    VALHALLA_ROOT="${VALHALLA_ROOT:-${RQ_ROOT}/data/valhalla}"
    # Valhalla publishes no port in production: smoke requests run inside the container (empty URL).
    VALHALLA_SMOKE_URL="${VALHALLA_SMOKE_URL:-}"
    ;;
  *)
    printf 'MMR_ENV must be dev, prod or auto (got %s)\n' "$MMR_ENV" >&2
    exit 1
    ;;
esac

# shellcheck source=common.sh
. "${OSM_SCRIPTS_DIR}/common.sh"

AREA_FILE="${AREA_FILE:-${OSM_CONF_DIR}/pilot-area.env}"
OSM_TOOLS_BASE="${OSM_TOOLS_BASE:-debian:bookworm-slim}"
OSM_TOOLS_IMAGE="${OSM_TOOLS_IMAGE:-mmr-osm-tools:${OSM_TOOLS_BASE##*:}}"
# UID of the "valhalla" user in the gis-ops image (verify against the image README). The build directory
# must be writable by it on Linux hosts. Docker Desktop (macOS, Windows) maps ownership itself.
VALHALLA_UID="${VALHALLA_UID:-59999}"

# Value from pilot-area.env, unless the same name is set in the environment. Never sources the file.
area_get() {
  local key="$1" value
  value="${!key:-}"
  if [ -z "$value" ]; then
    [ -r "$AREA_FILE" ] || die "cannot read ${AREA_FILE}"
    value="$(grep -E "^${key}=" "$AREA_FILE" | tail -n 1 | cut -d '=' -f 2- || true)"
  fi
  printf '%s' "$value"
}

# minLon,minLat,maxLon,maxLat with plain decimal numbers only (the value ends up in a command line).
validate_bbox() {
  [[ "$1" =~ ^-?[0-9]+(\.[0-9]+)?(,-?[0-9]+(\.[0-9]+)?){3}$ ]] || die "invalid bbox: $1"
}

# Bbox for an area name: pilot (production region) or dev (tiny extract for local work and CI).
bbox_for_area() {
  local bbox
  case "$1" in
    pilot) bbox="$(area_get PILOT_BBOX)" ;;
    dev)   bbox="$(area_get DEV_BBOX)" ;;
    *)     die "unknown area: $1 (use pilot or dev)" ;;
  esac
  validate_bbox "$bbox"
  printf '%s' "$bbox"
}

# Write KEY=value into $ENV_FILE (replace or append). Values are restricted to path-safe characters.
env_set() {
  local key="$1" value="$2" tmp
  [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || die "invalid env key: $key"
  [[ "$value" =~ ^[A-Za-z0-9._/:-]*$ ]] || die "invalid value for ${key}"
  [ -w "$ENV_FILE" ] || die "cannot write ${ENV_FILE}"
  tmp="$(mktemp)"
  grep -vE "^${key}=" "$ENV_FILE" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  # cat > keeps the original file's owner and mode (600 on the VM).
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
}

# Path form for `docker run -v`. Git Bash on Windows needs C:/Users/... instead of /c/Users/...
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

require_docker() {
  command -v docker >/dev/null || die "docker is not installed or not on PATH"
  docker info >/dev/null 2>&1 || die "the docker daemon is not reachable (is Docker Desktop running?)"
}

# Build the local tool image once (infra/osm/tools.Dockerfile). No build context is sent.
ensure_tools_image() {
  require_docker
  if ! docker image inspect "$OSM_TOOLS_IMAGE" >/dev/null 2>&1; then
    log "building ${OSM_TOOLS_IMAGE} from ${OSM_TOOLS_BASE} (one time)"
    docker build -q --build-arg "BASE=${OSM_TOOLS_BASE}" -t "$OSM_TOOLS_IMAGE" - < "${OSM_CONF_DIR}/tools.Dockerfile" >/dev/null
  fi
}

# Run a command in the tool image: tools_run <docker run options...> -- <command...>
tools_run() {
  local opts=()
  while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do opts+=("$1"); shift; done
  [ "${1:-}" = "--" ] && shift
  # ${opts[@]+...}: bash 3.2 (macOS) treats an empty array as unset under set -u.
  docker run --rm ${opts[@]+"${opts[@]}"} "$OSM_TOOLS_IMAGE" "$@"
}

tool_versions() {
  tools_run -- sh -c 'printf "osmium %s; osm2pgsql %s\n" \
    "$(osmium --version | head -n 1 | awk "{print \$NF}")" \
    "$(osm2pgsql --version 2>&1 | grep -m 1 -oE "[0-9]+\.[0-9]+\.[0-9]+")"'
}

# Newest dated download directory (YYYY-MM-DD names sort chronologically). Empty if none.
latest_osm_dir() {
  local d
  d="$(find "$OSM_DATA_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]' 2>/dev/null | sort | tail -n 1)"
  printf '%s' "$d"
}

# psql inside the db container over the local socket, so the password never appears on this host.
# Usage: db_psql [psql options...] < file.sql
db_psql() {
  compose exec -T db sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d roadquality "$@"' psql "$@"
}

# Docker network the db container is attached to (for the osm2pgsql container).
db_network() {
  local cid net
  cid="$(compose ps -q db)"
  [ -n "$cid" ] || die "the db service is not running (start it with docker compose ... up -d db)"
  net="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$cid" | head -n 1)"
  [ -n "$net" ] || die "cannot determine the db container network"
  printf '%s' "$net"
}

# Current Valhalla data directory as configured in $ENV_FILE (VALHALLA_DATA_DIR). Relative values are
# relative to the compose directory.
valhalla_current_dir() {
  local v
  v="$(env_get VALHALLA_DATA_DIR)"
  [ -n "$v" ] || return 0
  case "$v" in
    /*) printf '%s' "$v" ;;
    *)  printf '%s/%s' "$COMPOSE_DIR" "${v#./}" ;;
  esac
}
