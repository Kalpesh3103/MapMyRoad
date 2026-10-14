# Progress

**Product:** MapMyRoad
**Last updated:** 2026-10-14
**Related:** [TASKS.md](TASKS.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), [DECISIONS.md](DECISIONS.md), [AGENTS.md](AGENTS.md)

This file is the running record of where the project stands. Update it after every work session, every merged task and every milestone gate. Keep entries short and factual. Newest entries go at the top of the log.

---

## 1. Current status

| Item | Value |
|---|---|
| Phase | Implementation started (M0, M1-1, M2 code) |
| Current milestone | M0 Foundations |
| Current task | M0-3, M0-8, M1-1 waiting for the first build and device test on the Windows machine |
| Next task | Build and fix (M0-3), then M0-5 Firebase project, M0-6 Play Console, M1-2 test routes |
| Blockers | None |
| Next release target | v1 (personal mapper) after M0 to M3 |

## 2. Milestone tracker

| Milestone | Status | Started | Finished | Exit gate met? | Notes |
|---|---|---|---|---|---|
| Planning docs (16 files) | Done | 2026-10-13 | 2026-10-14 | Yes | See log |
| M0 Foundations | In progress | 2026-10-14 | | No | Code written, not yet built |
| M1 Detection data spike | In progress | 2026-10-14 | | No | M1-1 logger and tools/ converter written |
| M2 v1 build | In progress | 2026-10-14 | | No | Code written ahead of the M1 gate, thresholds are placeholders |
| M3 v1 closed test and release | Not started | | | No | |
| M4 v2 backend | Not started | | | No | Can overlap with M3 |
| M5 v2 Android | Not started | | | No | |
| M6 v2 hardening and release | Not started | | | No | |
| M7 v3 navigation and alerts | Not started | | | No | |
| M8 v4 civic tools | Not started | | | No | |

Task-level status lives in TASKS.md. This table only records milestone-level facts.

## 3. Key metrics (fill in as they become measurable)

| Metric | Target | Latest | Measured on | Notes |
|---|---|---|---|---|
| Detector recall (severe) | at least 70% | not measured | | M1-6 |
| Detector precision (severe) | at least 70% | not measured | | M1-6 |
| Speed-breaker false alarms | under 1 per 10 km | not measured | | |
| Battery per hour of tracking | about 5% or less | not measured | | M1-7 |
| Crash-free sessions | at least 99% | not measured | | |
| Trip loss rate (service killed) | track | not measured | | |
| Mapped km (pilot) | track | 0 | | |
| Active contributors | track | 0 | | |
| Backend p95 latency (tiles, routes) | under about 3 s routes | not measured | | M6-2 |
| Last successful backup restore drill | monthly | never | | |

## 4. Risk watchlist

Review at every milestone gate. Mark each risk Open, Mitigated, Realized or Closed.

| Risk | Status | Signal to watch | Notes |
|---|---|---|---|
| Detector accuracy too low | Open | M1 precision and recall | Decide scope after M1 |
| OEM battery managers kill tracking | Open | Trip loss rate in M2 field tests | |
| Foreground-service start from background blocked (auto-start) | Open | M2-15 spike | |
| Oracle free VM unavailable or ARM image gaps | Open | M0-9 | Fallback: local machine for the pilot |
| Map-matching quality on sparse windows | Open | M6-3 | |
| Alternates too similar for comfort routing | Open | M4-14 and M5-8 testing | |
| Ferrostar and Valhalla integration | Open | M4-18 spike | |
| Play review of location permissions | Open | M3 | Foreground-first design |
| Re-identification on low-traffic roads | Open | Visibility thresholds (M4-19) | |
| Free-tier changes | Open | Provider notices | Keep fallbacks documented |
| Scope creep | Open | Backlog growing faster than it closes | Hold each phase to its exit gate |

## 5. Open questions and decisions needed

| # | Question | Owner | Needed by | Status |
|---|---|---|---|---|
| 1 | Logo and branding (name and package ID resolved: MapMyRoad, `com.mapmyroad`) | Kalpesh | M3-2 | Open |
| 2 | Confirm ADR-0013 (no feedback while moving) and update PRD CM-4 wording | Kalpesh | Before M5 | Open |
| 3 | Confirm ADR-0014 (no certificate pinning initially) | Kalpesh | Before M4-20 | Proposed |
| 4 | Backend build tool: Maven or Gradle | Kalpesh | M4-5 | Open |
| 5 | Static analysis tools for Java and Kotlin (detekt, ktlint, Error Prone, SpotBugs) | Kalpesh | M0-3 (Android), M4-5 (Java) | Android decided in M0-3: ktlint + Android Lint. detekt deferred until it supports the project's Kotlin version cleanly. Java tools open. |
| 6 | Closed-testing requirement for the Play developer account (confirm current rule) | Kalpesh | M3-1 | Open |
| 7 | Research mode for raw sensor snippets | Kalpesh | After M1 | Open |
| 8 | First municipal complaint channels | Kalpesh | M8-3 | Open |
| 9 | Gating rule for the public heatmap | Kalpesh | M6-6 | Open |
| 10 | ODbL obligations for derived exports | Kalpesh | M8-6 | Open |
| 11 | Hindi and Marathi translation review plan | Kalpesh | M5-11 | Open |
| 12 | Legal review of privacy policy | Kalpesh | M6-4 | Open |

## 6. Environment and accounts checklist

Tick off as each is created. Do not write secrets here.

- [ ] GitHub repository (private) with branch protection
- [ ] Google Play Console developer account (one-time registration fee)
- [ ] Firebase project (Crashlytics and Analytics)
- [ ] Oracle Cloud account and Always Free ARM VM
- [ ] Domain or DNS name for the API
- [ ] Object storage bucket for backups (and encryption key stored offline)
- [ ] Uptime monitor account
- [ ] Privacy policy URL (GitHub Pages)
- [ ] Google Cloud project and service account for Play Integrity verification
- [ ] Two-factor authentication on every account above
- [ ] Offline vault for keys and recovery codes

## 7. Data and infrastructure facts (fill in when they exist)

| Item | Value |
|---|---|
| Repository URL | |
| App package ID | `com.mapmyroad` |
| API base URL | |
| VM region and shape | |
| OSM extract used (name and date) | |
| Valhalla version | |
| PostGIS version | |
| Last OSM refresh | |
| Last backup | |
| Last restore drill | |
| Current app version in production | |
| Current API version in production | |

## 8. Log

Newest first. Format: date, what changed, what is next. Link task IDs and ADRs.

### 2026-10-14 (M0-3 build fixes)
- Fixed five app ktlint violations and replaced invalid MapLibre bounds property references with the latitude/longitude properties exposed by SDK 11.5.2.
- Verified on Windows: `:app:ktlintCheck`, `:app:compileDebugKotlin`, `:app:testDebugUnitTest` and `:app:lintDebug` pass. The initial combined run timed out; unit tests and Android Lint completed successfully on rerun.
- Next: full debug assembly and device verification.

### 2026-10-14 (M2 code)
- `:detector` (M2-1, M2-3, M2-4, M2-5, M2-20): filters, gating, event detection, speed normalization, severity bins, event position interpolation, speed-breaker heuristic, 50 m roughness windows, trip scoring. All parameters in `DetectorConfig` marked `TODO(M1-5)`. 89 JVM tests, compiled and passing in the dev session (IntelliJ's bundled Kotlin compiler). The regression baseline is synthetic until M1-8 fixtures exist.
- Data layer (M2-7, M2-8): Room database v1 (trip, trip_point, hazard_event_local, segment_sample_local), DAO, repository, trip summary calculator, DataStore settings.
- Tracking (M2-2, M2-6): `TripTrackingService` (foreground, type location, notification with Pause and Stop), `TripRecorder` on one sensor thread (100 Hz, 1 s batching, 1 Hz GPS), batched writes every 5 s, auto-pause and auto-stop, low-battery stop, recovery of killed trips as `service_killed`.
- Screens (M2-9, M2-10, M2-12, M2-13): onboarding with permission rationale, home, live trip (no dialogs, hold to stop), trip summary, trips list, settings, privacy and delete all data, battery guidance, about.
- Personal map (M2-11): trips colored by band with line styles, severity markers by shape, clustering, hazard sheet, trip route map on the summary.
- Verification in the dev session: pure-Kotlin parts of `:app` (tracking policies, summary math, map segmenting, formatting) compiled with kotlinc and 74 tests passed. Android-specific code (Room, Hilt, Compose, MapLibre, service) was reviewed line by line only. Nothing was built with Gradle.
- Field-test items: no CPU wake lock during trips (relies on 1 Hz GPS to wake the CPU), check with the 30-minute screen-off test (TESTING_STRATEGY.md 4.2).
- Deviation from UI_UX S5: trip delete uses a confirmation dialog instead of 5-second undo (the repository has no restore).

### 2026-10-14 (decision: start M2 before the M1 exit gate)
- Decided by Kalpesh: build the v1 app (M2) in parallel with M1 data collection instead of waiting for the M1 exit gate (TASKS.md rule 5 exception).
- Scope this round: M2-1 detector with placeholder thresholds (every value marked `TODO(M1-5)`), M2-2 to M2-13, M2-19, M2-20 with synthetic fixtures. Not in scope: M2-14 and M2-15 (auto-start, needs the spike), M2-16 (field test), M2-17 (needs Firebase), M2-18 (accessibility pass after the first build).
- Risk: detector thresholds, severity bins and smoothness curve change after M1-5. The detector is isolated in `:detector` with all parameters in `DetectorConfig`, so retuning should not touch the app.
- Schema: added `trip.stop_reason` (DATABASE_SCHEMA.md B1) to support the "Recording stopped unexpectedly" state.

### 2026-10-14 (M0 and M1-1 code)
- M0-2 done: `android/`, `backend/` and `infra/` (placeholders, M4), `tools/`, `site/`, `.github/`, root README, `.gitattributes`.
- M0-3 code: Gradle 8.11.1 wrapper, version catalog (AGP 8.7.3, Kotlin 2.0.21, KSP, Hilt 2.52, Room 2.6.1 plugin with no database yet, Compose BOM 2024.12.01, MapLibre 11.5.2), `:app` with Hilt, Compose theme (UI_UX section 4), four-tab navigation, placeholder screens, backup disabled, cleartext disabled, R8 for release, release signing from `keystore.properties` or `MMR_*` env vars. Versions were chosen offline (no network in the dev session) and must be confirmed on the first sync.
- Static analysis decision (open question 5): ktlint (`ktlint_official`) and Android Lint. detekt deferred.
- M0-5 code: Firebase plugins and libraries are applied only when `app/google-services.json` exists. The Firebase project itself is still to create.
- M0-8 code: MapLibre map tab with OpenFreeMap style and always-visible OSM attribution.
- M0-4 and M0-10: `android-ci` and `secret-scan` workflows, Dependabot, PR and issue templates. Actions use version tags and still need SHA pinning. Branch protection is a manual GitHub setting.
- M0-7: privacy policy draft in `site/` (needs legal review, not published).
- M1-1 code: `:sensorlogger` debug field app (100 Hz accelerometer, gyroscope, gravity, game rotation vector, 1 Hz GPS, tag buttons, CSV and meta.json per session, zip sharing). Never published.
- M1 support: `tools/traces/convert_session.py` converts logger sessions into trimmed, coordinate-shifted fixtures (supports M1-8). `tools/analysis/explore_session.py` is a starter for M1-4. Its 28 tests passed in the dev session on Python 3.9 with a stand-in for pytest. Run them on Python 3.11 with real pytest.
- Nothing was compiled in the dev session (no Android SDK, no network). Both Android modules got a line-by-line compile review.
- Next: first build on Windows (`gradlew.bat assembleDebug testDebugUnitTest ktlintCheck lintDebug`), fix issues, then install the logger and start M1-2 and M1-3.

### 2026-10-14 (M0-1 done)
- M0-1 done: app name MapMyRoad, Android package ID `com.mapmyroad` (ADR-0017). Backend Java package set to `com.mapmyroad.backend`. Logo and branding still open.
- Replaced the "Road Quality Mapper (working title)" headers across all docs.
- Next: M0-2 (repository structure), M0-9 (Oracle Cloud signup) can run in parallel.

### 2026-10-14 (consistency pass)
- Moved the planning docs into `docs/`. Added a root `AGENTS.md` symlink, a `CLAUDE.md` pointer and a `.gitignore` (part of M0-2).
- Applied the alignment edits that the entry below lists but that had not landed: PRD CM-4, user story and flow 7.4 now follow ADR-0013. API_SPEC.md section 5.13 now follows ADR-0015.
- PRD TS-9 changed to P0, v1 (on-device roughness is needed for PM-1 and is scheduled as M2-4). Roughness upload remains v2.
- ARCHITECTURE.md aligned with API_SPEC.md (`/v1/navigation/routes`, `/v1/exports`, uptime on `/v1/config`) and with DATABASE_SCHEMA.md column names. Also: LiteRT naming, regional OSM extract, Play Integrity wording.
- Added `stop_reason` to the `trip_ended` analytics event (used by TESTING_STRATEGY.md section 14). Noted the missing trust-score weighting in the DATABASE_SCHEMA.md confidence sketch. Fixed the "v6" phase typo in SECURITY.md T5. Completed the document index in PROJECT_OVERVIEW.md.
- `road-quality-app-free-stack.xlsx` is not in the repository.

### 2026-10-14
- Wrote the remaining nine planning documents: `UI_UX_GUIDELINES.md`, `SECURITY.md`, `CODING_STANDARDS.md`, `TESTING_STRATEGY.md`, `TASKS.md`, `AGENTS.md`, `DEPLOYMENT.md`, `DECISIONS.md`, `PROGRESS.md`. The full set of 16 documents is complete.
- New decisions recorded: ADR-0013 (no hazard feedback while moving), ADR-0014 proposed (no certificate pinning initially), ADR-0015 (Actuator on an internal port).
- New tasks added: M0-10, M2-19, M2-20, M3-7, M4-19, M4-20, M6-8 (see TASKS.md section 11).
- Small alignment edits made to earlier docs: API_SPEC.md section 5.13 (management port) and PRD CM-4 (feedback not while moving).
- Next: M0-1 (name and package ID), then M0-2 (repository).

### 2026-10-13
- Chose the idea: a passive road-quality mapper for Android, with route comparison by comfort and later in-app navigation, built on free tools.
- Wrote `PROJECT_OVERVIEW.md`, `PRD.md`, `ARCHITECTURE.md`, `TECH_STACK.md`, `API_SPEC.md`, `DATABASE_SCHEMA.md`, `IMPLEMENTATION_PLAN.md`.
- Also produced a spreadsheet of the free tool stack (`road-quality-app-free-stack.xlsx`).
- Next: finish the remaining documents.

## 9. Weekly review template

Copy into the log each week once implementation starts.

```
### YYYY-MM-DD (week review)
- Done: task IDs and one-line outcomes
- In progress: task IDs and state
- Blocked: what and why
- Metrics changed: which and to what
- Risks changed: which and why
- Decisions made: ADR numbers
- Next week: top three tasks
```

## 10. Milestone gate checklist template

Copy into the log when a milestone ends.

```
### Milestone Mx gate (YYYY-MM-DD)
- Exit gate criteria (from IMPLEMENTATION_PLAN.md):  met / not met, evidence
- Acceptance criteria from PRD:                     list and result
- Testing exit criteria (TESTING_STRATEGY.md s15):   met / not met
- Security items (SECURITY.md s17) for this release: met / not met
- Metrics recorded in section 3:                     yes / no
- Risks reviewed in section 4:                       yes / no
- Docs updated (API, schema, architecture, UI):      yes / no
- Go / no-go for the next milestone:                 decision and reason
```
