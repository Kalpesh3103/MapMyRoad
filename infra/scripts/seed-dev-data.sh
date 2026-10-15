#!/usr/bin/env bash
# Load SYNTHETIC demo data (infra/osm/seed-dev-data.sql) into the LOCAL dev database
# (TESTING_STRATEGY.md section 13, DEPLOYMENT.md section 15). Never for production.
#
#   infra/scripts/seed-dev-data.sh
#
# Refuses to run unless all of these hold:
#   - MMR_ENV is dev (or auto on a machine without /opt/rq/compose/docker-compose.yml)
#   - the compose file is the dev stack (Compose project name "mapmyroad-dev")
#   - DB_URL in the env file points at localhost or 127.0.0.1
# The SQL itself also refuses databases with more than 100 non-synthetic devices.
# Requires the Flyway migrations (start the API once against the dev database first). Idempotent.
set -euo pipefail

MMR_ENV="${MMR_ENV:-dev}"
[ "$MMR_ENV" = "dev" ] || { echo "seed-dev-data.sh: refusing, MMR_ENV=${MMR_ENV} (dev only)" >&2; exit 1; }
export MMR_ENV

# shellcheck source=osm-common.sh
. "$(dirname "$0")/osm-common.sh"

[ "$(basename "$COMPOSE_FILE")" = "docker-compose.dev.yml" ] \
  && grep -qE '^name:[[:space:]]*mapmyroad-dev[[:space:]]*$' "$COMPOSE_FILE" \
  || die "refusing: ${COMPOSE_FILE} is not the dev compose stack (mapmyroad-dev)"
case "$ENV_FILE" in
  /opt/rq/*) die "refusing: ${ENV_FILE} is the production env file" ;;
esac
DB_URL_VALUE="$(env_get DB_URL)"
[[ "$DB_URL_VALUE" =~ ^jdbc:postgresql://(localhost|127\.0\.0\.1)(:[0-9]+)?/ ]] \
  || die "refusing: DB_URL in ${ENV_FILE} does not point at localhost"

require_docker
compose ps --status running -q db | grep -q . || die "the dev db service is not running"
db_psql -tA -c "SELECT to_regclass('public.hazard') IS NOT NULL AND to_regclass('public.osm_way') IS NOT NULL" | grep -q '^t$' \
  || die "tables missing: start the API once so Flyway creates the schema"

log "loading synthetic seed data into the dev database"
db_psql -v i_know_this_is_dev=yes < "${OSM_CONF_DIR}/seed-dev-data.sql"
log "seed done"
