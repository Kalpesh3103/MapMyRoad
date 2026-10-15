#!/usr/bin/env bash
# One-shot local setup of map data and demo data (DEPLOYMENT.md section 15, TESTING_STRATEGY.md section 13).
#
#   infra/scripts/load-dev-data.sh               tiny dev extract -> osm2pgsql import -> Valhalla tiles -> seed
#   infra/scripts/load-dev-data.sh --seed-only   only the synthetic seed data (no download, no Valhalla)
#   infra/scripts/load-dev-data.sh --offline     reuse the last download instead of fetching a new one
#
# Needs the dev db running and migrated (docker compose -f docker-compose.dev.yml up -d db, then start the
# API once). The first full run downloads the regional Geofabrik file (hundreds of MB, see infra/osm/README.md).
set -euo pipefail

S="$(cd "$(dirname "$0")" && pwd)"
export MMR_ENV=dev

SEED_ONLY=0
DL_OPT=()
for arg in "$@"; do
  case "$arg" in
    --seed-only) SEED_ONLY=1 ;;
    --offline) DL_OPT=(--offline) ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

if [ "$SEED_ONLY" -eq 0 ]; then
  "${S}/osm-download.sh" --area dev ${DL_OPT[@]+"${DL_OPT[@]}"}
  "${S}/osm-import.sh" --area dev
  "${S}/valhalla-build.sh" all --area dev
fi
"${S}/seed-dev-data.sh"
