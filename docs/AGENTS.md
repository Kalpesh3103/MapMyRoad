# AGENTS.md

Instructions for AI coding assistants (Claude, GitHub Copilot and others) and for any human new to the project. Put a copy or symlink of this file at the repository root. Some tools look for a different filename (for example `CLAUDE.md`), so add a one-line pointer file where needed.

**Project:** MapMyRoad (Android package `com.mapmyroad`), an Android app that detects potholes passively with phone sensors, builds a crowdsourced road-quality map and recommends smoother routes. Free and open-source tools only until growth justifies paid services.

---

## 1. Read first, in this order

1. `docs/PROJECT_OVERVIEW.md`: what and why
2. `docs/PRD.md`: requirements and acceptance criteria
3. `docs/ARCHITECTURE.md`: components and flows
4. `docs/TASKS.md` and `docs/PROGRESS.md`: what to do now and where things stand
5. The detail doc for your task: `DATABASE_SCHEMA.md`, `API_SPEC.md`, `UI_UX_GUIDELINES.md`, `TECH_STACK.md`, `SECURITY.md`, `CODING_STANDARDS.md`, `TESTING_STRATEGY.md`, `DEPLOYMENT.md`, `DECISIONS.md`

If documents disagree, the order of authority is: `DECISIONS.md` (latest accepted ADR), then `PRD.md` for product behavior, `API_SPEC.md` and `DATABASE_SCHEMA.md` for contracts, then the rest. Report the conflict instead of guessing.

## 2. Where we are

Implementation has started (M0, M1-1 and the v1 app in M2). `PROGRESS.md` holds the live state. The Android modules are `:app`, `:detector` (pure Kotlin) and `:sensorlogger` (debug field tool). Backend and infra start in M4. Do not invent build files that contradict `TECH_STACK.md` and `ARCHITECTURE.md`.

## 3. Repository layout

```
android/    Android app (Kotlin, Compose): :app, :detector (pure Kotlin), :sensorlogger (debug tool)
backend/    Spring Boot service (Java), Flyway migrations
infra/      Docker Compose, Caddyfile, Valhalla config, scripts
docs/       Planning documents (this set), research notes, incident notes
tools/      Python analysis scripts and notebooks, trace converters
```

## 4. Commands

| Task | Command |
|---|---|
| Android build | `cd android && ./gradlew assembleDebug` |
| Android unit tests | `cd android && ./gradlew testDebugUnitTest :detector:test` (the detector is a plain Kotlin module) |
| Android lint and format | `cd android && ./gradlew ktlintCheck lintDebug` (`ktlintFormat` to fix). detekt is deferred (PROGRESS.md open question 5). |
| Sensor logger (M1 field tool) | `cd android && ./gradlew :sensorlogger:installDebug` |
| Android instrumented tests | `cd android && ./gradlew connectedDebugAndroidTest` (needs a device or emulator) |
| Backend build and tests | `cd backend && ./gradlew build` (`gradlew.bat build` on Windows, needs JDK 21; Testcontainers tests need Docker) |
| Backend format | Not configured yet (Spotless planned) |
| Local stack | `cd infra && docker compose -f docker-compose.dev.yml up -d` |
| Python tools | `cd tools && python -m venv .venv && pip install -r requirements.txt` |

On Windows use `gradlew.bat` instead of `./gradlew`. Android commands exist since M0-3. Backend commands become real in M4.

Always run the relevant checks before saying a task is done. If a command does not exist yet, say so rather than inventing output.

## 5. How to work on a task

1. Pick a task ID from `TASKS.md` (the human assigns it, or choose the first unblocked one in the current milestone). Work on **one task at a time**.
2. Read the relevant requirement IDs in the PRD and the matching sections of the detail docs.
3. State your plan in a few lines before large changes: files to touch, tests to add, docs to update.
4. Write code and tests together. Make small commits using Conventional Commits and reference the task ID.
5. Run lint and tests. Fix failures. Do not disable tests to make CI pass.
6. Update docs in the same change (API, schema, architecture, UI) when behavior changes.
7. Update `TASKS.md` (status) and add an entry to `PROGRESS.md`.
8. If the work reveals a decision (technology, trade-off, change of approach), draft an ADR in `DECISIONS.md` and flag it for the human.

## 6. Hard rules (never break these)

**Privacy and security**
- Never log, print, persist in analytics or put into exception messages: precise coordinates, tokens, device IDs, request bodies.
- Raw sensor streams and full GPS traces never leave the phone. Uploads contain only events and roughness windows, with trip start and end trimmed by 500 m.
- Do not add accounts, email, phone number or any personal identifier.
- No secrets in code, configuration committed to Git, Docker images, tests or docs. Use environment variables and `.env.example` placeholders.
- All SQL uses named parameters. Never build SQL from strings containing user input.
- Do not weaken auth, rate limits, validation, retention or deletion without an ADR.

**Product safety**
- No UI that requires touch input while the vehicle is moving. No dialogs, toasts or prompts during a live trip. Hazard feedback is never requested while moving (`UI_UX_GUIDELINES.md` section 6).
- Never use color as the only signal.

**Scope and dependencies**
- Do not add paid or key-requiring services (Google Maps, Mapbox, paid geocoders) without an ADR.
- Do not add a dependency without stating what it does, its license, and why existing tools are not enough. Pin versions in the version catalog or BOM.
- Do not edit a Flyway migration that has been released. Add a new one.
- Do not change a public API shape without updating `API_SPEC.md` first and keeping backward compatibility rules.
- Do not make unrelated refactors or formatting changes inside a task PR.

**Honesty**
- Do not fabricate APIs, library versions, benchmark results or test output. Check official documentation or the code. If you are unsure, say so and propose how to verify.
- Do not claim a command succeeded unless you ran it.
- Thresholds, scores and targets in the docs are initial values. Do not present them as measured facts.

## 7. Areas needing extra care (explain your reasoning, expect line-by-line human review)

- Sensor processing, filters, thresholds and the detector.
- Foreground service, permissions (especially background location), battery behavior and OEM restrictions.
- Trimming, upload payload construction, token handling, Play Integrity.
- Anything touching retention, deletion or privacy mapping.
- SQL for spatial queries, clustering, tiles and route scoring.
- Infrastructure, backups and anything that runs on the production VM.

## 8. Architecture boundaries to preserve

- The detector module is **pure Kotlin** with no Android dependencies, deterministic, allocation-free in the hot path.
- The Android app is offline-first: recording and local history never depend on the network.
- Backend is a modular monolith: controller, service, repository. No business logic in controllers. No SQL outside repositories.
- Valhalla, Photon and the tile host are behind thin interfaces so they can be swapped.
- Public responses never include device IDs or trip tokens.

## 9. Code style summary

Details in `CODING_STANDARDS.md`.

- Kotlin official style, no `!!`, inject dispatchers, structured concurrency, units in names (`speedKmh`, `distanceM`, `startedAtMs`).
- Compose: stateless composables, state hoisting, previews, content descriptions, theme tokens only.
- Java: records for DTOs, constructor injection, Bean Validation, `Instant` for time, RFC 9457 problem details with stable `code`.
- Strings in resource files, with plurals. No hardcoded user-visible text.
- Comments explain why. TODOs carry a task ID.

## 10. Testing expectations

Details in `TESTING_STRATEGY.md`.

- New logic ships with unit tests. Detector changes include fixture-based tests and must not lower the checked-in metric baseline.
- Backend persistence and spatial code is tested with Testcontainers and PostGIS.
- API changes include contract tests and an updated `openapi.json`.
- Privacy-relevant changes (trimming, retention, deletion, visibility rules) include tests that would fail if the rule broke.

## 11. Pull request checklist

- [ ] Task ID in the title or body
- [ ] What and why described, with screenshots for UI
- [ ] Tests added and passing, lint clean
- [ ] Docs updated (API, schema, architecture, UI) when affected
- [ ] No sensitive data in logs, analytics or tests
- [ ] New dependencies explained
- [ ] `TASKS.md` status and `PROGRESS.md` entry updated
- [ ] ADR drafted if a decision changed

## 12. When to stop and ask the human

Ask before proceeding when:
- The task conflicts with a document or an accepted ADR.
- A requirement is ambiguous in a way that changes the design (not for naming or small choices).
- You need a paid service, a new permission, or a new category of data.
- The change would alter retention, deletion, auth or what is uploaded.
- A destructive or irreversible operation is involved (data deletion, force pushes, production commands).
- You cannot run the checks needed to verify your work.

For small choices that have a conventional default, pick the default, note it in the PR, and move on.

## 13. Useful prompt patterns

**Implement a task**
> Implement M2-4 (roughness windows). Read PRD TS-9, ARCHITECTURE.md section 5 and DATABASE_SCHEMA.md Part B first. Write the code in the detector module with tests on the fixtures. List the files you will change before you start.

**Review code**
> Review this change against `CODING_STANDARDS.md` and `SECURITY.md`. Look for sensitive data in logs, allocation in the sensor hot path, missing tests, and SQL built from strings.

**Write tests**
> Add unit tests for `TripTrimmer` covering short trips, loops, GPS gaps and exactly 500 m boundaries. Include a property-style test.

**Propose a decision**
> Draft an ADR for [topic] using the template in `DECISIONS.md`: context, options with trade-offs, recommendation. Do not implement it.

## 14. Glossary

| Term | Meaning |
|---|---|
| Hazard | Aggregated pothole or severe bump at a location, built from many events |
| Event | One detected bump from one device |
| Window | A roughness measurement over about 50 m of travel |
| Segment | A road piece keyed by OSM way ID and a distance bucket |
| Comfort score | 0 to 100 route score from hazards and roughness, higher is smoother |
| Coverage | Share of a route that has road-quality data |
| Trim | Removing the first and last 500 m of a trip before upload |
| Trip token | Random per-trip value used only to group points for map-matching |
| ADR | Architecture decision record, kept in `DECISIONS.md` |
