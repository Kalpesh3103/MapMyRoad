#!/usr/bin/env bash
# Restore a database backup (DEPLOYMENT.md sections 9.3 and 9.4).
#
#   restore.sh drill <backup.dump.age> <age-identity-file>
#       Monthly restore drill. Restores into a THROWAWAY PostGIS container (no network, removed afterwards),
#       prints row counts and the time taken. Production is not touched. Record the result in PROGRESS.md.
#
#   restore.sh production <backup.dump.age> <age-identity-file>
#       DESTRUCTIVE. Stops the API, drops and recreates the roadquality database from the backup, starts the
#       API again. Asks for typed confirmation. Use only for disaster recovery or a failed migration.
#
# Fetch a backup first, for example:
#   rclone copy mmrbackup:<bucket>/db/daily/rq_<timestamp>.dump.age /tmp/restore/
# The age identity is the OFFLINE private key. Bring it to the machine only for the restore, then delete it.
# Run drills on a local machine or a temporary container, never copy production data to a dev machine
# beyond the drill itself.
set -euo pipefail

# shellcheck source=common.sh
. "$(dirname "$0")/common.sh"

usage() { sed -n '2,17p' "$0" >&2; exit 2; }
[ "$#" -eq 3 ] || usage
MODE="$1"; BACKUP_FILE="$2"; IDENTITY="$3"
[ -r "$BACKUP_FILE" ] || die "cannot read backup file"
[ -r "$IDENTITY" ] || die "cannot read age identity file"
command -v age >/dev/null || die "age is not installed"

umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
DUMP="${WORK}/restore.dump"

START=$(date +%s)
log "decrypting"
age -d -i "$IDENTITY" -o "$DUMP" "$BACKUP_FILE"
[ -s "$DUMP" ] || die "decrypted dump is empty"

case "$MODE" in
  drill)
    # Same image as production. TODO(M4-4): keep in sync with docker-compose.yml (arm64 note there).
    DRILL_IMAGE="${DRILL_IMAGE:-postgis/postgis:16-3.4}"
    NAME="mmr-restore-drill-$$"
    PW="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
    log "starting throwaway container ${NAME}"
    docker run -d --rm --name "$NAME" --network none \
      -e POSTGRES_USER=drill -e POSTGRES_PASSWORD="$PW" -e POSTGRES_DB=roadquality \
      "$DRILL_IMAGE" >/dev/null
    trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT
    for _ in $(seq 1 60); do
      docker exec "$NAME" pg_isready -U drill -d roadquality >/dev/null 2>&1 && break
      sleep 2
    done
    docker exec "$NAME" pg_isready -U drill -d roadquality >/dev/null || die "drill database did not start"
    log "restoring"
    # --no-owner: roles from production (rq_app, rq_migrator) do not exist in the throwaway container.
    docker exec -i "$NAME" pg_restore -U drill -d roadquality --no-owner --exit-on-error < "$DUMP"
    log "checks: Flyway history and approximate row counts"
    docker exec "$NAME" psql -U drill -d roadquality -c \
      "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
    docker exec "$NAME" psql -U drill -d roadquality -c \
      "SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY relname;"
    # TODO(M4-4): add a few spatial sanity queries once the schema exists (DEPLOYMENT.md 9.3 step 3).
    ;;
  production)
    printf 'This REPLACES the production database with %s.\nType "restore production" to continue: ' \
      "$(basename "$BACKUP_FILE")" >&2
    read -r answer
    [ "$answer" = "restore production" ] || die "aborted"
    log "stopping api"
    compose stop api
    log "recreating database"
    # Variables expand inside the container. Terminate connections, then drop and recreate.
    compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
      -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"'"'roadquality'"'"' AND pid <> pg_backend_pid();" \
      -c "DROP DATABASE IF EXISTS roadquality;" \
      -c "CREATE DATABASE roadquality;"'
    log "restoring"
    compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d roadquality --exit-on-error' < "$DUMP"
    log "starting api"
    compose up -d api
    smoke_check || die "API did not become healthy after restore, investigate (docker compose logs api)"
    ;;
  *)
    usage
    ;;
esac

log "restore (${MODE}) finished in $(( $(date +%s) - START )) s"
