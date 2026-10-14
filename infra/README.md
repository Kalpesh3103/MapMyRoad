# infra/

Docker Compose stacks, Caddy configuration and operational scripts for the v2 backend.
Full design and runbooks: [`docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md). Hardening and secrets:
[`docs/SECURITY.md`](../docs/SECURITY.md) sections 9 to 11.

## Files

| File | Purpose |
|---|---|
| `docker-compose.dev.yml` | Local development: PostGIS (port 5432 on 127.0.0.1 only), optional API container (`--profile api`). Valhalla follows in M4-2. |
| `docker-compose.yml` | Production on the VM (`/opt/rq/compose/`): Caddy (only service with published ports, 80 and 443), API, PostGIS. Valhalla follows in M4-2. |
| `Caddyfile` | TLS, 2 MB request limit, HSTS and `nosniff`, reverse proxy to `api:8080` with an active health check on `/v1/config`, JSON access log kept 14 days. |
| `.env.example` | Every environment variable name with placeholders. Copy to `.env`. |
| `scripts/backup.sh` | `pg_dump`, `age` encryption, `rclone` upload, remote retention (7 daily, 4 weekly, 3 monthly). |
| `scripts/restore.sh` | `drill` (throwaway container, monthly) or `production` (destructive, typed confirmation). |
| `scripts/deploy.sh` | Backup, remember the previous tag, pull and start a new `API_TAG`, smoke check, automatic rollback. |
| `scripts/rollback.sh` | Return to the previous (or a given) `API_TAG` and smoke check. |
| `scripts/common.sh` | Shared helpers sourced by the scripts above. |
| `scripts/load-dev-data.sh` | Placeholder for the tiny OSM extract and seed data (M4-2, M4-3). |

## Local development

```sh
cd infra
cp .env.example .env                                   # local-only values
docker compose -f docker-compose.dev.yml up -d db      # then run the API with bootRun (backend/README.md)
docker compose -f docker-compose.dev.yml --profile api up -d --build   # API as a container (bootJar first)
docker compose -f docker-compose.dev.yml down          # add -v to delete the local database
```

Never copy production data to a development machine.

## Production

On the VM the layout is `/opt/rq/{compose,data,backups,scripts}` (DEPLOYMENT.md section 3.3). Copy
`docker-compose.yml`, `Caddyfile` and a filled-in `.env` to `/opt/rq/compose/`, the scripts to
`/opt/rq/scripts/`, replace `OWNER` in the image name, then use `scripts/deploy.sh <tag>` for releases.

Health checks: the API image has no curl or wget, so Compose probes the internal readiness endpoint
(`:8081/actuator/health/readiness`) with bash's `/dev/tcp`. Docker does not restart unhealthy
containers by itself; Caddy's active check and the external uptime monitor on `GET /v1/config` cover
availability. Caddy and the smoke checks send `X-App-Version`, which the API requires on `/v1/**`.

## Secrets

- `.env` holds all secrets. It is git-ignored (`infra/.gitignore`), mode 600 and root-owned on the VM.
  Only `.env.example` with placeholders is committed.
- The Play Integrity key is mounted read-only as a Compose secret from `secrets/play-integrity-sa.json`
  (git-ignored, readable by uid 10001).
- The backup encryption **private** key stays offline. The VM only has the public `age` recipient.
- Rotation: DEPLOYMENT.md section 12.7 and SECURITY.md section 10.

## Backups

`scripts/backup.sh` runs nightly from cron or a systemd timer, writes a heartbeat (`backups/last_success`
and an optional `BACKUP_HEARTBEAT_URL` ping) and needs `age`, `rclone` and `curl` on the VM. Run a
restore drill monthly with `scripts/restore.sh drill` and record it in `docs/PROGRESS.md`
(DEPLOYMENT.md section 9).

## Images and arm64

The Oracle VM is ARM. The official `postgis/postgis` image has historically been amd64-only; see the
comments in the compose files for multi-arch alternatives. Pin full versions and digests before go-live.
