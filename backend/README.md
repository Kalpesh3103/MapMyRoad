# backend/

Spring Boot service for the community features (v2, milestone M4): device registration, event and
roughness uploads, hazard map, route scoring and geocoding proxy.

## Stack

- Spring Boot 4.1.1, Java 21 (Temurin)
- Gradle 9.2.1 through the wrapper in this directory (`gradlew`, `gradlew.bat`)
- JDBC (`JdbcClient`, named parameters only) and Flyway migrations (`src/main/resources/db/migration`)
- PostgreSQL 16 with PostGIS 3.4
- Actuator on a separate management port, 8081, never published or proxied (ADR-0015)
- Tests: JUnit 5, Spring Boot test slices, Testcontainers (PostGIS)

## Run locally (Windows and macOS)

Requirements: JDK 21 and Docker (Docker Desktop on Windows and macOS).

JDK 21: any Temurin 21 works. Android Studio's bundled `jbr` (JetBrains Runtime 21) also works. Point
`JAVA_HOME` at it:

```sh
# macOS
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

1. Start the database (from the repository root):

   ```sh
   cd infra
   cp .env.example .env          # once; local-only values
   docker compose -f docker-compose.dev.yml up -d db
   ```

2. Run the API with the `local` profile. The API reads `DB_URL`, `DB_USER`, `DB_PASSWORD` and the other
   variables from the environment (defaults in `application.yml` point at `localhost:5432/roadquality`
   with user `roadquality`). Gradle does not read `infra/.env`, so set at least the password to the
   value you put in `infra/.env` (or use an IDE run configuration):

   ```sh
   export DB_PASSWORD=change-me-local-only        # macOS and Linux
   $env:DB_PASSWORD = "change-me-local-only"      # Windows (PowerShell)
   ```

   ```sh
   cd backend
   ./gradlew bootRun --args="--spring.profiles.active=local"       # macOS and Linux
   gradlew.bat bootRun --args="--spring.profiles.active=local"     # Windows
   ```

   The API listens on <http://localhost:8080>. Actuator health:
   <http://localhost:8081/actuator/health> (local only; in Docker it is not published).

3. Optional, the API as a container:

   ```sh
   cd backend && ./gradlew bootJar
   cd ../infra && docker compose -f docker-compose.dev.yml --profile api up -d --build
   ```

## Tests and build

```sh
./gradlew test        # gradlew.bat test on Windows
./gradlew build       # compile, tests, boot jar (what CI runs)
```

Testcontainers tests start a PostGIS container and are skipped when Docker is not running, so a
green run without Docker does not cover persistence code. CI (`.github/workflows/backend-ci.yml`)
always has Docker.

Formatting: Spotless is planned but not configured yet.

## Package layout

```
com.mapmyroad.backend
  api/          controllers, request and response DTOs, auth, rate limiting, error handling
  config/       configuration properties and beans
  ...           feature modules (service and repository per feature) added during M4
```

Rules: controller, service, repository. No business logic in controllers, no SQL outside repositories,
no device IDs or trip tokens in public responses (`docs/CODING_STANDARDS.md` section 4).

## Container image

`Dockerfile` packages the jar built by `./gradlew bootJar` (it does not compile). CI builds and
pushes a multi-arch image to GHCR on tags `backend-v*`. Deployment: [`infra/README.md`](../infra/README.md)
and [`docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md).

## References

- API contract: [`docs/API_SPEC.md`](../docs/API_SPEC.md)
- Database schema and migrations: [`docs/DATABASE_SCHEMA.md`](../docs/DATABASE_SCHEMA.md)
- Conventions: [`docs/CODING_STANDARDS.md`](../docs/CODING_STANDARDS.md) section 4
- Security: [`docs/SECURITY.md`](../docs/SECURITY.md)

## Testing with Postman

Import `tools/postman/MapMyRoad.postman_collection.json` (Postman: Import, then choose the file). Start the backend
with the `local` profile, then run the folders in order. **2 Device** registers a test device and stores the tokens
that the later folders use. Run **1 Basics** first to check the backend is up, and **7 Delete my data** last.
Variables such as `baseUrl` and `appVersion` are on the collection (Variables tab).

## M4 exit gate check (end to end)

`tools/e2e/m4-exit-gate.ps1` checks that synthetic uploads from 3 devices flow through map matching and aggregation
into the hazards API, the vector tiles and route scoring, and that "delete my data" removes the hazard again.
It needs the local stack with Valhalla and the dev extract (`infra/scripts/load-dev-data.sh`) and the backend
running with the `local` profile. It only runs against localhost.

```powershell
powershell -ExecutionPolicy Bypass -File tools\e2e\m4-exit-gate.ps1            # about 10 to 17 minutes
powershell -ExecutionPolicy Bypass -File tools\e2e\m4-exit-gate.ps1 -KeepData  # keep the test data for a look at the map
```

Registration is rate limited to 10 calls per hour per IP (challenge and register count together, so about
5 registrations). The script registers 4 devices. If you get `429`, wait an hour or restart the backend (limits are
kept in memory).
