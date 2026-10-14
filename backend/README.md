# backend/

Spring Boot service (Java) for the community features: device registration, event and roughness
uploads, hazard map, route scoring and geocoding proxy.

**Not started.** Work begins in milestone **M4** (v2). Do not scaffold code here before then.

- Plan: [`docs/IMPLEMENTATION_PLAN.md`](../docs/IMPLEMENTATION_PLAN.md) (M4) and [`docs/TASKS.md`](../docs/TASKS.md) (M4-5 onward)
- API contract: [`docs/API_SPEC.md`](../docs/API_SPEC.md)
- Database schema and migrations: [`docs/DATABASE_SCHEMA.md`](../docs/DATABASE_SCHEMA.md)
- Deployment and CI: [`docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md)
- Conventions: [`docs/CODING_STANDARDS.md`](../docs/CODING_STANDARDS.md) section 4

**Open decision:** build tool, Maven or Gradle ([`docs/PROGRESS.md`](../docs/PROGRESS.md) section 5,
open question 4, needed by M4-5). Record the choice as an ADR in `docs/DECISIONS.md`, then add the
matching Dependabot ecosystem in `.github/dependabot.yml` and a `backend-ci` workflow.
