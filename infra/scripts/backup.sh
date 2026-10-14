#!/usr/bin/env bash
# Nightly encrypted database backup (DEPLOYMENT.md section 9.2, SECURITY.md section 9 "Data").
#
#   /opt/rq/scripts/backup.sh
#
# Schedule with cron or a systemd timer at a quiet hour, for example:
#   15 3 * * *  root  /opt/rq/scripts/backup.sh >> /var/log/rq-backup.log 2>&1
# Alert if it fails or if the newest backup is older than 26 hours (BACKUP_HEARTBEAT_URL helps).
#
# Requirements on the VM: docker compose, age, rclone, curl.
# Flow: pg_dump -Fc inside the db container -> encrypt with the age PUBLIC key -> upload with rclone
# -> delete local copies -> prune remote copies (7 daily, 4 weekly, 3 monthly).
# Not backed up (rebuildable): Valhalla tiles, OSM extracts, osm2pgsql reference tables, tile caches.
set -euo pipefail

# shellcheck source=common.sh
. "$(dirname "$0")/common.sh"

command -v age >/dev/null || die "age is not installed"
command -v rclone >/dev/null || die "rclone is not installed"

BACKUP_AGE_RECIPIENT="$(env_require BACKUP_AGE_RECIPIENT)"
BACKUP_BUCKET="$(env_require BACKUP_BUCKET)"
BACKUP_HEARTBEAT_URL="$(env_get BACKUP_HEARTBEAT_URL)"

# Configure the rclone remote "mmrbackup" from environment variables only, so no rclone.conf with keys
# is written to disk. The variables exist only in this process.
export RCLONE_CONFIG_MMRBACKUP_TYPE=s3
RCLONE_CONFIG_MMRBACKUP_PROVIDER="$(env_get BACKUP_S3_PROVIDER)"
export RCLONE_CONFIG_MMRBACKUP_PROVIDER="${RCLONE_CONFIG_MMRBACKUP_PROVIDER:-Other}"
RCLONE_CONFIG_MMRBACKUP_ENDPOINT="$(env_require BACKUP_ENDPOINT)"
RCLONE_CONFIG_MMRBACKUP_ACCESS_KEY_ID="$(env_require BACKUP_KEY_ID)"
RCLONE_CONFIG_MMRBACKUP_SECRET_ACCESS_KEY="$(env_require BACKUP_KEY)"
export RCLONE_CONFIG_MMRBACKUP_ENDPOINT RCLONE_CONFIG_MMRBACKUP_ACCESS_KEY_ID RCLONE_CONFIG_MMRBACKUP_SECRET_ACCESS_KEY
REMOTE="mmrbackup:${BACKUP_BUCKET}/db"

umask 077                      # dumps are readable by root only
mkdir -p "$BACKUP_DIR"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${BACKUP_DIR}/rq_${TS}.dump"
ENC="${OUT}.age"
# Never leave a plaintext dump behind, even on failure.
trap 'rm -f "$OUT" "$ENC"' EXIT

log "dumping database"
# $POSTGRES_USER is expanded inside the container (single quotes), so the password never touches this host's
# process list. pg_dump connects over the local socket.
compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -Fc roadquality' > "$OUT"
[ -s "$OUT" ] || die "dump is empty"

log "encrypting"
age -r "$BACKUP_AGE_RECIPIENT" -o "$ENC" "$OUT"
rm -f "$OUT"

NAME="$(basename "$ENC")"
log "uploading ${NAME}"
rclone copyto "$ENC" "${REMOTE}/daily/${NAME}"
# Weekly copy on Sundays, monthly copy on the 1st (UTC). Server-side copies, no re-upload.
if [ "$(date -u +%u)" = "7" ]; then
  rclone copyto "${REMOTE}/daily/${NAME}" "${REMOTE}/weekly/${NAME}"
fi
if [ "$(date -u +%d)" = "01" ]; then
  rclone copyto "${REMOTE}/daily/${NAME}" "${REMOTE}/monthly/${NAME}"
fi
rm -f "$ENC"

# Retention (DEPLOYMENT.md 9.1): 7 daily, 4 weekly, 3 monthly. Ages include a small margin.
log "pruning old remote backups"
rclone delete --min-age 8d "${REMOTE}/daily/"
rclone delete --min-age 29d "${REMOTE}/weekly/"
rclone delete --min-age 93d "${REMOTE}/monthly/"

date -u +%Y-%m-%dT%H:%M:%SZ > "${BACKUP_DIR}/last_success"
if [ -n "$BACKUP_HEARTBEAT_URL" ]; then
  curl -fsS --max-time 10 -o /dev/null "$BACKUP_HEARTBEAT_URL" || log "heartbeat ping failed" >&2
fi
log "backup done: ${NAME}"
