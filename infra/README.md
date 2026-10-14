# infra/

Docker Compose stack, Caddyfile, Valhalla configuration and operational scripts (backups,
deploy, rollback) for the v2 backend.

**Not started.** Work begins in milestone **M4** (M4-1 onward). Do not scaffold files here before then.

- Plan: [`docs/IMPLEMENTATION_PLAN.md`](../docs/IMPLEMENTATION_PLAN.md) (M4) and [`docs/TASKS.md`](../docs/TASKS.md)
- Infrastructure, Compose sketch, backups, runbooks: [`docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md)
- API served by the stack: [`docs/API_SPEC.md`](../docs/API_SPEC.md)
- Database (PostGIS) schema: [`docs/DATABASE_SCHEMA.md`](../docs/DATABASE_SCHEMA.md)
- Server hardening and secrets: [`docs/SECURITY.md`](../docs/SECURITY.md) sections 9 and 10

Related open decision: the backend build tool, Maven or Gradle ([`docs/PROGRESS.md`](../docs/PROGRESS.md)
section 5, open question 4), affects the API image build. Secrets never go in this directory:
commit only `.env.example` with placeholders.
