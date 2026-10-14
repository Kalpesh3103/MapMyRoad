# Coding Standards

**Product:** MapMyRoad
**Status:** Draft v0.1
**Related:** [ARCHITECTURE.md](ARCHITECTURE.md), [TECH_STACK.md](TECH_STACK.md), [TESTING_STRATEGY.md](TESTING_STRATEGY.md), [SECURITY.md](SECURITY.md), [AGENTS.md](AGENTS.md)

These rules apply to humans and AI assistants alike. When a rule conflicts with the code you are touching, follow the rule in new code and mention the inconsistency in the PR. Exceptions go in the PR description with a reason.

---

## 1. General principles

1. **Clarity over cleverness.** A new contributor (or you in six months) should understand the code without the author.
2. **Small, focused changes.** One task ID per PR where possible.
3. **Privacy and safety are code-level concerns.** Location data is sensitive in memory, in logs and in tests.
4. **Tests are part of the change.** Logic without tests is unfinished (see TESTING_STRATEGY.md).
5. **Docs move with code.** API, schema and architecture changes update the matching document in the same PR.
6. **Boring technology.** Add a dependency only when it clearly pays for itself.
7. **Measure before optimizing,** except in the sensor hot path (section 3.6), where allocation discipline is a requirement.

## 2. Repository and workflow

### 2.1 Structure

```
MapMyRoad/
  android/    Android app (Kotlin, Compose)
  backend/    Spring Boot service
  infra/      docker-compose, Caddyfile, Valhalla config, scripts
  docs/       The 16 planning documents, research notes, incident notes
  tools/      Trace converters, analysis notebooks and scripts
  AGENTS.md   Instructions for AI coding assistants (symlink or copy at the root)
```

### 2.2 Branching and commits

- Trunk-based development. Branch from `main`, keep branches short-lived, merge through pull requests (also when working alone, so CI always runs).
- Branch names: `feature/M2-4-roughness-windows`, `fix/M2-6-autopause-crash`, `docs/api-update`, `chore/deps-update`.
- Conventional Commits: `feat(detector): add speed normalization`, `fix(upload): handle 429 with Retry-After`, `docs(api): add coverage field`, `test(detector): add speed-breaker fixtures`, `chore(deps): bump maplibre`.
- Reference the task ID in the PR title or body (`M2-4`).
- Squash merge to keep history linear. The squash message follows Conventional Commits.
- Never commit generated files, secrets, build outputs, local config or real recorded traces (anonymized fixtures only, in the agreed location).

### 2.3 Pull requests

PR template (required sections): **What and why**, **Task ID**, **How tested**, **Docs updated**, **Privacy and security impact**, **Screenshots** (UI changes), **Dependencies added or changed (with reason)**.

Keep PRs under about 400 changed lines excluding generated code and fixtures. Split larger work.

Review checklist (self-review before merging):
- [ ] Builds and tests pass in CI.
- [ ] New logic has tests, including edge cases.
- [ ] No precise coordinates, tokens or device IDs in logs or analytics.
- [ ] Strings externalized, accessibility labels added.
- [ ] Docs and `PROGRESS.md` updated, `TASKS.md` status changed.
- [ ] No unrelated reformatting.

### 2.4 Definition of done

See IMPLEMENTATION_PLAN.md section 5. In short: merged, CI green, tests, strings externalized, no sensitive logging, privacy mapping updated, docs updated.

### 2.5 TODOs

`TODO` and `FIXME` must carry a task ID or issue number: `// TODO(M2-6): stop recording on low battery`. CI fails on bare TODOs in release branches (optional rule).

## 3. Android (Kotlin)

### 3.1 Style and tooling

- Kotlin official style. `ktlint` (with `ktlint_official` rules from `android/.editorconfig`) and Android Lint run in CI. Run `ktlintFormat` before committing.
- Prefer immutable data (`val`, `data class`, immutable collections). Avoid platform types leaking from Java APIs.
- No `!!` in production code. Use explicit handling or `requireNotNull` with a message at boundaries.
- Public APIs of modules have KDoc where the behavior is not obvious (units, ranges, thread requirements).

### 3.2 Architecture

Layering from ARCHITECTURE.md section 4.1: `ui` then `domain` then `data`, with `sensing` and `map` as separate modules. Dependencies point inward only.

- UI uses MVVM with unidirectional data flow: `ViewModel` exposes `StateFlow<UiState>`, the UI sends events up, one-off effects use a `Channel` or `SharedFlow` that is consumed once.
- ViewModels never hold `Context` or UI references.
- Repositories expose `Flow` for observable data and `suspend` functions for one-shot actions.
- Hilt provides dependencies. No service locators, no singletons by hand.
- The **detector module is pure Kotlin** (no Android imports) so it can run in JVM tests and in analysis tools.

### 3.3 Compose

- Stateless composables: take state and callbacks, hoist state to the ViewModel or a state holder.
- Every screen composable has a preview with realistic fake data, including dark mode and 200% font scale previews for key screens.
- No business logic in composables. No `remember`-based state that must survive configuration change without `rememberSaveable` or the ViewModel.
- Keys on `LazyColumn` items. Avoid unstable parameters that cause recomposition storms (use immutable models).
- Content descriptions and semantics for every interactive element (UI_UX_GUIDELINES.md section 11).

### 3.4 Coroutines and Flow

- Inject dispatchers (`@IoDispatcher`, `@DefaultDispatcher`) so tests can replace them. Never hardcode `Dispatchers.IO` in classes under test.
- Structured concurrency only. No `GlobalScope`. Services use their own scope tied to the lifecycle and cancelled in `onDestroy`.
- Collect flows with `repeatOnLifecycle` or `collectAsStateWithLifecycle`.
- Handle `CancellationException` correctly (rethrow it).
- Sensor callbacks do minimal work and hand data to a channel (section 3.6).

### 3.5 Error handling

- Model expected failures as sealed results (`Result`-like types) at repository boundaries. Do not use exceptions for control flow.
- Map network errors to the problem-detail `code` values (API_SPEC.md section 3) in one place (the API client layer).
- Never swallow exceptions silently. Log with context (no sensitive data) and surface a user-facing state.
- Foreground service code is defensive: a crash in the service must not lose the trip. Persist incrementally and recover on restart.

### 3.6 Sensing and detector code

- **No allocation in the hot path.** Sensor callbacks run at 50 to 100 Hz. Reuse arrays and ring buffers, avoid boxing, no logging per sample, no string formatting.
- Use monotonic sensor timestamps for signal processing. Convert to wall-clock only when storing events.
- Keep one writer for shared state or use immutable snapshots. Document the threading model at the top of each class.
- Units are part of names (section 3.8). Never mix m/s and km/h silently.
- Every threshold or constant lives in a named config object with a comment citing the findings doc or a TODO to calibrate. No magic numbers.
- Detector classes are deterministic: same input trace, same output. Randomness is not allowed without an injected seed.

### 3.7 Location privacy in code

- Use a dedicated type for precise coordinates, for example `GeoPoint(lat, lon)` with a `toString()` that **redacts** (`GeoPoint(***)`). Log through helpers that never print coordinates in release builds.
- Trimming of trip starts and ends happens in one function (`TripTrimmer`) with unit tests. No other code builds upload payloads from raw trips.
- Analytics events accept only an allowlisted set of parameters (a typed event class per event), so coordinates cannot be passed by accident.
- Never include coordinates in exception messages.

### 3.8 Naming and units

| Concept | Convention | Example |
|---|---|---|
| Distances | suffix `M` or `Km` | `distanceM`, `lengthM` |
| Speeds | suffix `Mps` or `Kmh` | `speedMps`, `speedKmh` |
| Time points | `At` with epoch millis `Long` | `startedAtMs` |
| Durations | suffix `Ms`, `S` or `Min` | `durationS` |
| Angles | `Deg` | `headingDeg` |
| Coordinates | `lat`, `lon` (never `lng`) | `startLat` |
| Booleans | `is`, `has`, `should` prefix | `isUploaded` |

Packages follow ARCHITECTURE.md. Files are named after their main class. Composable functions use PascalCase. Test names describe behavior: `severeEvent_isSuppressed_nearKnownSpeedBreaker`.

### 3.9 Resources and i18n

- No hardcoded user-visible strings. Use `strings.xml` with plurals. Translations in `values-hi` and `values-mr`.
- Colors and dimensions from the theme tokens (UI_UX_GUIDELINES.md section 4), not literals in composables.
- Support dark theme and font scaling from the first screen.

### 3.10 Persistence

- Room entities mirror DATABASE_SCHEMA.md Part B. Schema export is on and committed.
- Every schema change has a migration and a migration test. No destructive migrations in release builds.
- Batch writes in transactions. No database access on the main thread.

### 3.11 Dependencies

- Use the version catalog. No dynamic versions (`+`).
- Adding a dependency needs a PR note: what it does, license, size and maintenance status, alternatives considered. Prefer the platform or existing libraries.

## 4. Backend (Java and Spring Boot)

### 4.1 Layout

```
com.mapmyroad.backend
  api/        Controllers, request and response DTOs, exception handlers
  service/    Business logic (ingest, hazards, segments, routing, geocode, device)
  repo/       Repositories and SQL (JPA for CRUD, JdbcTemplate/native SQL for spatial)
  domain/     Entities and value objects
  config/     Spring configuration, properties
  job/        Scheduled jobs (matching, aggregation, retention)
  integration/ Clients for Valhalla and Photon
```

Flow: controller then service then repository. Controllers contain no business logic and no SQL. Services do not depend on controllers.

### 4.2 Style

- Java current LTS. Use records for DTOs and immutable values. Constructor injection only (no field injection).
- Format with Spotless (Google Java Format or the project's chosen style) and run it in CI. Static analysis with Error Prone, SpotBugs or Checkstyle as the project chooses at M0.
- Keep methods short and named for intent. Prefer `Optional` for return values, never for parameters or fields.
- No `System.out`. No catching `Exception` unless rethrowing or at a top-level handler.

### 4.3 API and validation

- DTOs are validated with Bean Validation, with explicit maximums for all strings and collections.
- Responses and errors follow API_SPEC.md exactly. Error handling is centralized in a `@ControllerAdvice` producing RFC 9457 problem details with the stable `code`.
- Timestamps use `Instant`. Never `Date` or local time. Coordinates use a dedicated value type, not raw doubles scattered around.
- An API change starts in API_SPEC.md, then code, then regenerated `openapi.json` and contract tests.

### 4.4 Database access

- Flyway migrations only. Never edit a migration that has been released. Add a new one.
- Native SQL uses **named parameters**. Never concatenate user input into SQL.
- Spatial queries live in repository classes with comments explaining the geography versus geometry choice (DATABASE_SCHEMA.md section A1).
- Transactions are declared at the service layer. Keep them short. Long-running aggregation jobs work in batches.
- Do not use `SELECT *` in application queries.
- Index every column used in a spatial or frequent filter, and verify new queries with `EXPLAIN` against realistic data volumes.

### 4.5 Concurrency and jobs

- Scheduled jobs are idempotent and safe to run twice. Mark work with `matched_at` style columns rather than relying on timing.
- Jobs record start, end, counts and failures as metrics.
- External calls (Valhalla, Photon) have timeouts, bounded retries and a circuit breaker where useful. Never block request threads without a timeout.

### 4.6 Logging

- SLF4J with structured JSON output. Include the request ID.
- **Never log coordinates, tokens, device IDs, request bodies or the Authorization header.** Use IDs of internal rows only when needed.
- Levels: `ERROR` for failures needing attention, `WARN` for recoverable anomalies, `INFO` for lifecycle events, `DEBUG` off in production.
- Do not log inside tight loops.

### 4.7 Configuration

- Typed `@ConfigurationProperties` classes with validation. No `@Value` scattering.
- Secrets only from environment variables. No defaults for secrets.
- Profiles: `local`, `test`, `prod`. Production-only behavior (for example Play Integrity enforcement) is configuration, never a code path that tests cannot reach.

## 5. SQL and migrations

- Lowercase `snake_case` names, singular tables, explicit constraints (`CHECK`, `NOT NULL`, foreign keys) as in DATABASE_SCHEMA.md.
- One logical change per migration, named `V<n>__<description>.sql`.
- Migrations are forward-only. For risky changes, add a documented manual rollback note in the PR.
- Test migrations on a copy with realistic data volume when the table is large.
- Any change that touches personal data updates the privacy mapping (DATABASE_SCHEMA.md section A11) and retention jobs.

## 6. Python and notebooks (analysis tools)

- Python 3.11 or newer. Dependencies in `requirements.txt` or `pyproject.toml`.
- Notebooks clear their outputs before commit. Large data and real traces stay out of Git (use the agreed private storage), only anonymized fixtures are committed.
- Reusable logic moves from notebooks into modules in `tools/` with tests. The Kotlin detector is the reference implementation, and the Python version exists for exploration. When thresholds change, update both and the findings doc.

## 7. Documentation standards

- Docs are Markdown in `docs/`. Keep the table of contents and cross-links current.
- Decisions go in DECISIONS.md using the template there. Changing a key decision requires a new ADR that supersedes the old one.
- Progress is recorded in PROGRESS.md after each work session or merged milestone task.
- Comments explain **why**, not what. Remove commented-out code.
- Public numbers (thresholds, limits) are documented where defined and where users or integrators see them.

## 8. Security rules in code (summary)

Full details in SECURITY.md.
- Validate everything from clients.
- Parameterized SQL only.
- No sensitive data in logs, analytics, exceptions or test names.
- Secrets never in code, config files in Git or Docker images.
- New endpoints state their auth requirement and rate limit in API_SPEC.md before implementation.
- Changes to auth, token, upload, deletion or retention code need extra care and a deliberate self-review against SECURITY.md sections 5 to 7.

## 9. Performance rules

- Android: avoid work on the main thread, keep the tracking service light (batching, no per-sample logging), measure battery on a real device for any change to sampling or location settings (TESTING_STRATEGY.md section 8).
- Map: limit the number of markers, cluster, simplify geometries, avoid recreating sources and layers on every state change.
- Backend: paginate or bound every list, enforce bbox size limits, use prepared statements and indexes, check query plans for spatial joins.
- Do not add caching without a measured need and a clear invalidation rule.

## 10. Examples

### 10.1 Redacting location type (Kotlin)

```kotlin
@JvmInline
value class GeoPoint private constructor(private val packed: Long) {
    val lat: Double get() = unpackLat(packed)
    val lon: Double get() = unpackLon(packed)
    override fun toString(): String = "GeoPoint(***)"   // never leak into logs
    companion object { fun of(lat: Double, lon: Double): GeoPoint = GeoPoint(pack(lat, lon)) }
}
```

(Illustrative. A plain `data class` with an overridden `toString()` is equally acceptable. The requirement is the redaction.)

### 10.2 Named-parameter spatial query (Java)

```java
private static final String NEAREST_HAZARD = """
    SELECT h.id
    FROM hazard h
    WHERE ST_DWithin(h.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusM)
      AND (:wayId IS NULL OR h.way_id IS NULL OR h.way_id = :wayId)
    ORDER BY ST_Distance(h.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
    LIMIT 1
    """;

Optional<UUID> findNearestHazard(double lat, double lon, double radiusM, Long wayId) {
    var params = new MapSqlParameterSource()
        .addValue("lat", lat).addValue("lon", lon)
        .addValue("radiusM", radiusM).addValue("wayId", wayId);
    return jdbc.query(NEAREST_HAZARD, params, (rs, i) -> rs.getObject("id", UUID.class))
               .stream().findFirst();
}
```

### 10.3 Problem-detail error mapping (Java)

```java
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Retry after " + e.retryAfterSec() + " seconds.");
        pd.setTitle("Too many requests");
        pd.setProperty("code", "RATE_LIMITED");
        pd.setProperty("retryAfterSec", e.retryAfterSec());
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(e.retryAfterSec()))
                .body(pd);
    }
}
```

## 11. Review rubric for AI-generated code

When an assistant writes code, check:
- Does it match the task ID and nothing more?
- Are all library calls real (check the version in the catalog and the official docs)?
- Are sensors, permissions, foreground service and SQL handled correctly (these get a line-by-line review)?
- Are there tests that would fail if the code were wrong?
- Did it add dependencies, change migrations or touch auth without asking?
- Does anything log or store precise location outside the allowed paths?
