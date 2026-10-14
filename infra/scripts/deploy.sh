#!/usr/bin/env bash
# Deploy a backend image tag (DEPLOYMENT.md section 6.2).
#
#   /opt/rq/scripts/deploy.sh <api-tag>        for example: deploy.sh 0.2.0
#
# Steps: back up the database -> remember the current tag -> pull and start the new image (Flyway runs on
# startup) -> smoke check GET /v1/config -> roll back automatically if the check fails.
# Migrations must be backward compatible with the previous release (DEPLOYMENT.md 6.3), otherwise the
# automatic rollback cannot help and the pre-deploy backup must be restored (restore.sh production).
set -euo pipefail

# shellcheck source=common.sh
. "$(dirname "$0")/common.sh"

[ "$#" -eq 1 ] || die "usage: deploy.sh <api-tag>"
TAG="$1"
validate_tag "$TAG"

CURRENT="$(env_get API_TAG)"
if [ "$CURRENT" = "$TAG" ]; then
  log "API_TAG is already ${TAG}; redeploying the same tag"
fi

log "backing up before deploy"
"$(dirname "$0")/backup.sh"

# Remember the running tag for rollback.sh (only if it differs, so a redeploy keeps the real previous tag).
if [ -n "$CURRENT" ] && [ "$CURRENT" != "$TAG" ]; then
  printf '%s\n' "$CURRENT" > "$PREVIOUS_TAG_FILE"
  chmod 600 "$PREVIOUS_TAG_FILE"
fi

set_api_tag "$TAG"
log "pulling and starting api ${TAG}"
compose pull api
compose up -d api

if smoke_check; then
  log "deploy of ${TAG} healthy"
  exit 0
fi

log "deploy of ${TAG} failed the smoke check, rolling back" >&2
compose logs --tail=100 api >&2 || true
"$(dirname "$0")/rollback.sh"
exit 1
