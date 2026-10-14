#!/usr/bin/env bash
# Shared helpers for the production scripts (sourced, not executed).
# Paths follow DEPLOYMENT.md section 3.3. Override with environment variables for testing.

RQ_ROOT="${RQ_ROOT:-/opt/rq}"
COMPOSE_DIR="${COMPOSE_DIR:-${RQ_ROOT}/compose}"
COMPOSE_FILE="${COMPOSE_FILE:-${COMPOSE_DIR}/docker-compose.yml}"
ENV_FILE="${ENV_FILE:-${COMPOSE_DIR}/.env}"
BACKUP_DIR="${BACKUP_DIR:-${RQ_ROOT}/backups}"
PREVIOUS_TAG_FILE="${PREVIOUS_TAG_FILE:-${COMPOSE_DIR}/.previous_api_tag}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# Read one KEY=value line from .env without sourcing it (no code execution from the file).
# Prints the value, or an empty string if the key is absent.
env_get() {
  local key="$1"
  [ -r "$ENV_FILE" ] || die "cannot read ${ENV_FILE}"
  grep -E "^${key}=" "$ENV_FILE" | tail -n 1 | cut -d '=' -f 2- || true
}

# Like env_get but fails if the value is empty.
env_require() {
  local value
  value="$(env_get "$1")"
  [ -n "$value" ] || die "$1 is not set in ${ENV_FILE}"
  printf '%s' "$value"
}

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

# Smoke check: GET https://$API_DOMAIN/v1/config must answer 2xx within ~150 s.
# X-App-Version is required by the API on /v1/** (API_SPEC.md); 9999.0.0 is always above the minimum.
smoke_check() {
  local domain i
  domain="$(env_require API_DOMAIN)"
  for i in $(seq 1 30); do
    if curl -fsS --max-time 5 -H 'X-App-Version: 9999.0.0' -o /dev/null "https://${domain}/v1/config"; then
      log "smoke check passed (attempt ${i})"
      return 0
    fi
    sleep 5
  done
  log "smoke check failed" >&2
  return 1
}

# Image tags may only contain characters that are safe in sed and in Docker references.
validate_tag() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || die "invalid image tag: $1"
}

set_api_tag() {
  local tag="$1"
  validate_tag "$tag"
  if grep -qE '^API_TAG=' "$ENV_FILE"; then
    sed -i "s/^API_TAG=.*/API_TAG=${tag}/" "$ENV_FILE"
  else
    printf 'API_TAG=%s\n' "$tag" >> "$ENV_FILE"
  fi
}
