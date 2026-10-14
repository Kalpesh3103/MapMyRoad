#!/usr/bin/env bash
# Roll the API back to the previous image tag (DEPLOYMENT.md sections 6.2, 12.1 and 13).
#
#   /opt/rq/scripts/rollback.sh            use the tag saved by deploy.sh
#   /opt/rq/scripts/rollback.sh <tag>      use an explicit tag
#
# This only changes the application image. It does not undo database migrations: if the failed release ran a
# migration that is not backward compatible, restore the pre-deploy backup (restore.sh production).
set -euo pipefail

# shellcheck source=common.sh
. "$(dirname "$0")/common.sh"

if [ "$#" -ge 1 ]; then
  TAG="$1"
else
  [ -r "$PREVIOUS_TAG_FILE" ] || die "no previous tag recorded in ${PREVIOUS_TAG_FILE}; pass a tag explicitly"
  TAG="$(head -n 1 "$PREVIOUS_TAG_FILE")"
fi
validate_tag "$TAG"

log "rolling back api to ${TAG}"
set_api_tag "$TAG"
compose pull api
compose up -d api

if smoke_check; then
  log "rollback to ${TAG} healthy"
  exit 0
fi
die "rollback to ${TAG} is NOT healthy, follow runbook DEPLOYMENT.md section 12.1"
