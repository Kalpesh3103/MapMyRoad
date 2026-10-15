# Tasks

**Product:** MapMyRoad
**Status:** Draft v0.1, nothing started yet
**Related:** [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), [PROGRESS.md](PROGRESS.md), [PRD.md](PRD.md), [AGENTS.md](AGENTS.md)

This is the working task list. Task IDs come from IMPLEMENTATION_PLAN.md. **This file is the source of truth for task status and for IDs added after the plan** (section 11). The plan describes why and in what order. This file tracks what is done.

---

## How to use this file

**Status values:** `[ ]` todo, `[~]` in progress, `[x]` done, `[!]` blocked (say why in PROGRESS.md), `[-]` dropped (say why in DECISIONS.md or PROGRESS.md).

**Sizes:** S 1 to 2 days, M 3 to 5, L 6 to 10, XL 10 or more (focused days, including tests). These are rough guesses.

**Priority:** P0 must have, P1 should have, P2 nice to have (from the PRD). Blank means P0.

**Rules**
1. Work on one task at a time. Move it to `[~]` when you start and `[x]` when it meets the definition of done (IMPLEMENTATION_PLAN.md section 5).
2. A task is not done until tests exist and docs are updated.
3. After finishing, add a line to PROGRESS.md.
4. If a task turns out to be bigger than L, split it and add the new IDs here (for example `M2-1a`, `M2-1b`).
5. Do not start a milestone's tasks before the previous milestone's exit gate unless this file says they can run in parallel.

**Current focus:** first build on the Windows machine for M0, M1-1 and the M2 code (M2 started before the M1 exit gate, see PROGRESS.md 2026-10-14)

M0-3 build-fix status (2026-10-14): app Kotlin compilation, app ktlint checks, debug unit tests and Android Lint pass on Windows after the map bounds API and formatting fixes. Full assembly and device verification remain pending.

---

## M0: Foundations

Exit gate: the app builds in CI, shows a map, and an internal-test build installs from Play.

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [x] | M0-1 | Choose app name and Android package ID (MapMyRoad, `com.mapmyroad`, ADR-0017) | S | |
| [x] | M0-2 | Create the repository with the agreed structure, put docs in `docs/`, add the root `AGENTS.md` | S | M0-1 |
| [~] | M0-3 | Android skeleton: Compose, Hilt, Room, version catalog, lint, signing config | M | M0-2 |
| [~] | M0-4 | GitHub Actions: build and test Android, lint, secret scan | S | M0-3 |
| [~] | M0-5 | Firebase project with Crashlytics and Analytics | S | M0-3 |
| [ ] | M0-6 | Play Console listing, app signing, internal testing track | S | M0-1 |
| [~] | M0-7 | Privacy policy page on GitHub Pages (first version) | S | M0-1 |
| [~] | M0-8 | MapLibre map screen with base map and OSM attribution | S | M0-3 |
| [ ] | M0-9 | Try the Oracle Cloud Always Free signup, check `arm64` images for Valhalla and PostGIS | S | |
| [~] | M0-10 | PR template, issue templates, branch protection, Dependabot or Renovate | S | M0-2 |

## M1: Detection data spike

Exit gate: findings doc with parameters and measured precision and recall, a decision on v1 detector scope.

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [~] | M1-1 | Minimal debug-only logger app (sensors, GPS, tag button, CSV output) | M | M0-3 |
| [ ] | M1-2 | Choose and pre-survey 2 or 3 Pune test routes | S | |
| [ ] | M1-3 | Record drives on 2 vehicle types, 2 phone placements, 2 phones | M | M1-1, M1-2 |
| [ ] | M1-4 | Analyze in Python and Jupyter: orientation, filtering, detection, speed effect, double-impulse | L | M1-3 |
| [ ] | M1-5 | Define thresholds per vehicle and speed normalization, write the findings doc | M | M1-4 |
| [ ] | M1-6 | Measure precision and recall against manual tags, list false-positive sources | S | M1-4 |
| [ ] | M1-7 | Measure battery and CPU of sampling and filtering (30-minute drive) | S | M1-1 |
| [ ] | M1-8 | Save anonymized traces and labels as detector test fixtures | S | M1-3 |

## M2: v1 build, personal mapper

Exit gate: all P0 v1 acceptance criteria (PRD section 11) pass, internal build used on real commutes.

### Sensing and detection

| Status | ID | Task | Size | Pri | Depends on |
|---|---|---|---|---|---|
| [~] | M2-1 | Pure-Kotlin detector module with JVM tests on recorded traces | L | | M1-5, M1-8 |
| [~] | M2-2 | `TripTrackingService` (foreground, type `location`), notification, sensor and location registration with batching | L | | M0-3 |
| [~] | M2-3 | Event position interpolation between GPS fixes | S | | M2-1 |
| [~] | M2-4 | Roughness windows (about 50 m) on device | M | | M2-1 |
| [~] | M2-5 | Vehicle profile selection and per-vehicle calibration factors | S | | M2-1 |
| [~] | M2-6 | Auto-pause when stationary, stop at low battery | S | P1 | M2-2 |

### Storage and domain

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [~] | M2-7 | Room schema (DATABASE_SCHEMA.md Part B), DAOs, repositories, migration tests | M | M0-3 |
| [~] | M2-8 | Trip summary computation (distance, duration, smoothness, counts) | S | M2-7 |

### UI

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [~] | M2-9 | Onboarding with permission rationale screens | M | M0-3 |
| [~] | M2-10 | Home screen with Start trip and live trip view | M | M2-2 |
| [~] | M2-11 | Personal map: trips colored by smoothness, hazard markers, attribution | M | M0-8, M2-7 |
| [~] | M2-12 | Trip summary and history, delete trip, delete all data | M | M2-8 |
| [~] | M2-13 | Settings: vehicle, auto-start toggle, language, battery guidance screen | S | M2-9 |

### Optional in v1

| Status | ID | Task | Size | Pri | Depends on |
|---|---|---|---|---|---|
| [ ] | M2-14 | Automatic trip start and stop with Activity Recognition (opt-in, background location rationale) | L | P1 | M2-15 |
| [ ] | M2-15 | Spike: verify foreground-service start from the background on the target API level | S | | M2-2 |

### Quality

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [ ] | M2-16 | Field test on 2 vehicle types and 3 phone brands | M | M2-10 |
| [ ] | M2-17 | Crashlytics and analytics events (PRD section 9, no precise location) | S | M2-10 |
| [ ] | M2-18 | Accessibility pass: font scaling, contrast, TalkBack | S | M2-12 |
| [~] | M2-19 | Android hardening: `allowBackup=false`, data extraction rules, network security config, R8 (SECURITY.md section 8) | S | M0-3 |
| [~] | M2-20 | Detector regression test in CI (metric baseline, TESTING_STRATEGY.md section 3.2) | S | M2-1 |

## M3: v1 closed test and release

Exit gate: closed test completed with acceptable stability, v1 submitted to production.

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [ ] | M3-1 | Recruit closed-test testers (start early, verify Play's current requirement) | S | M0-6 |
| [ ] | M3-2 | Store listing: icon, graphics, screenshots, descriptions, content rating | S | M2-12 |
| [ ] | M3-3 | Data safety form consistent with PRD section 8.1 | S | M0-7 |
| [ ] | M3-4 | If auto-start ships: background location declaration and demo video | S | M2-14 |
| [ ] | M3-5 | Run the closed test, collect feedback and crashes, fix issues | M | M3-1, M2-16 |
| [ ] | M3-6 | Apply for production access and release v1 | S | M3-5 |
| [ ] | M3-7 | Review the Play pre-launch report and fix blocking findings | S | M3-5 |

## M4: v2 backend

Exit gate: with synthetic uploads, data flows from `POST /events` through matching and aggregation to tiles and route scoring on a local deployment. Can start while the M3 closed test runs.

### Infrastructure

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [~] | M4-1 | VM or local Docker Compose: Caddy, API, PostGIS, Valhalla (`arm64` images) | M | M0-9 |
| [~] | M4-2 | OSM extract for the pilot area, build Valhalla tiles (Git Bash paths fixed; dev extract, tiles and route/matching smoke verified; pilot run pending) | M | M4-1 |
| [x] | M4-3 | osm2pgsql import of `osm_way` and `osm_calming_point` | M | M4-1 |
| [~] | M4-4 | Backups to object storage with tested restore, uptime monitor, Actuator metrics | S | M4-1 |
| [~] | M4-20 | Server hardening pass (SECURITY.md section 9) and deployment workflow (DEPLOYMENT.md) | M | M4-1 |

### Backend application

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [x] | M4-5 | Spring Boot skeleton, Flyway migrations V1 to V6 | M | M4-1 |
| [x] | M4-6 | Config endpoint | S | M4-5 |
| [x] | M4-7 | Device challenge, registration with Play Integrity, tokens, refresh, deletion | L | M4-5 |
| [x] | M4-8 | `POST /v1/events` with validation, idempotent insert, rejection reasons | M | M4-7 |
| [x] | M4-9 | Map-matching job with Valhalla `trace_attributes`, way and bucket assignment, token cleanup | L | M4-2, M4-3, M4-8 |
| [x] | M4-10 | Aggregation jobs: clustering, confidence, suppression, stale and fixed, segment statistics | L | M4-9 |
| [x] | M4-11 | Tiles endpoint (`ST_AsMVT`), TileJSON, visibility rules | M | M4-10 |
| [x] | M4-19 | Stricter visibility rule (3 devices) for residential, service and unclassified ways (SECURITY.md T2) | S | M4-11 |
| [x] | M4-12 | Hazards, hazard detail and feedback endpoints | M | M4-10 |
| [x] | M4-13 | Geocoding proxy with cache and rate limiting | M | M4-5 |
| [x] | M4-14 | Route candidates: Valhalla alternates, comfort scoring, coverage | L | M4-10, M4-2 |
| [x] | M4-15 | Rate limiting, problem-detail errors, request IDs | M | M4-5 |
| [x] | M4-16 | Retention and deletion jobs, recompute queue | M | M4-10 |
| [~] | M4-17 | Tests: Testcontainers, WireMock, contract tests against API_SPEC examples | L | ongoing |
| [ ] | M4-18 | Spike: Ferrostar with a self-hosted Valhalla (OSRM format, banner and voice) | M | M4-2 |

## M5: v2 Android, community and routes

Exit gate: a tester can contribute a real trip, see it on the shared map, get route candidates, and delete their data.

| Status | ID | Task | Size | Pri | Depends on |
|---|---|---|---|---|---|
| [~] | M5-1 | API client (Retrofit), token handling, retry rules, update gate | M | | M4-7 |
| [~] | M5-2 | Device registration with Play Integrity | M | | M5-1 |
| [~] | M5-3 | Contribution opt-in screen and settings | S | | M5-2 |
| [~] | M5-4 | Upload worker: trimming, batching, backoff, unmetered default | L | | M4-8, M5-3 |
| [~] | M5-5 | Shared map layers from tiles with accessible styling | L | | M4-11 |
| [~] | M5-6 | Hazard detail sheet and post-trip hazard review (feedback only when stopped, UI_UX_GUIDELINES.md section 6) | M | | M4-12, M5-5 |
| [~] | M5-7 | Destination search UI with debounce and caching | M | | M4-13 |
| [~] | M5-8 | Route candidates screen: three options, scores, hazards, slider, coverage notice | L | | M4-14 |
| [~] | M5-9 | "Open in Google Maps" hand-off | S | | M5-8 |
| [~] | M5-10 | "Delete my data" end to end | M | | M4-7 |
| [ ] | M5-11 | Hindi and Marathi strings, native-speaker review | M | | |
| [ ] | M5-12 | Vehicle health hints and contribution stats | S | P2 | M5-4 |

## M6: v2 hardening and release

Exit gate: PRD v2 release criteria met.

| Status | ID | Task | Size | Depends on |
|---|---|---|---|---|
| [ ] | M6-1 | Abuse tests (TESTING_STRATEGY.md section 9) | M | M5-4 |
| [ ] | M6-2 | Load test of ingestion and tiles on the single VM | M | M4-11 |
| [ ] | M6-3 | Pilot data review: matching accuracy, clustering, speed-breaker false alarms | M | M5-4 |
| [ ] | M6-4 | Privacy and legal review of the policy and data practices | M | |
| [ ] | M6-5 | Update Data safety form, store listing, permission declarations | S | M6-4 |
| [ ] | M6-6 | Gate the public heatmap by minimum coverage | S | M6-3 |
| [ ] | M6-7 | Release v2 through closed test then production | S | M6-1 to M6-6 |
| [ ] | M6-8 | Security checklist run and incident runbook rehearsal (SECURITY.md sections 13 and 17) | M | M4-20 |

## M7: v3 navigation and alerts

Exit gate: turn-by-turn matches or beats the "Open in Google Maps" fallback on the test routes, with no unsafe instructions.

| Status | ID | Task | Size | Pri | Depends on |
|---|---|---|---|---|---|
| [ ] | M7-1 | `POST /v1/navigation/routes` (new and reroute) in OSRM format | M | | M4-14 |
| [ ] | M7-2 | Ferrostar integration on MapLibre | XL | | M4-18, M7-1 |
| [ ] | M7-3 | Voice guidance and language handling, test Hindi and Marathi voices | M | | M7-2 |
| [ ] | M7-4 | Hazard proximity alerts with tuning (UI_UX_GUIDELINES.md section 7) | M | | M7-2 |
| [ ] | M7-5 | Reroute with hazard avoidance and safe fallback | M | | M7-1 |
| [ ] | M7-6 | Monsoon mode | M | P2 | M6-7 |
| [ ] | M7-7 | Photo reports: camera intent, private storage, EXIF stripping, report list | L | | M6-7 |
| [ ] | M7-8 | Real-drive navigation testing on several routes | L | | M7-2 |

## M8: v4 civic tools

Exit gate: a user can generate a complaint for a real hazard, and a report card is published for an area with sufficient data.

| Status | ID | Task | Size | Pri | Depends on |
|---|---|---|---|---|---|
| [ ] | M8-1 | Import ward or city boundaries | M | | |
| [ ] | M8-2 | Monthly area statistics job and report-card endpoint with sufficiency rules | M | | M8-1 |
| [ ] | M8-3 | Civic channels data for the first city, curated and verified | M | | |
| [ ] | M8-4 | Complaint generator on the device | M | | M8-3 |
| [ ] | M8-5 | Shareable report-card image or link | S | | M8-2 |
| [ ] | M8-6 | Pre-generated open data exports with license text, review ODbL obligations first | M | | M8-2 |
| [ ] | M8-7 | "Time to fix" tracking | S | P2 | M4-10 |

---

## 9. Backlog (not scheduled)

Ideas and deferred items. Move into a milestone only through an entry in DECISIONS.md or PROGRESS.md.

- ML classifier (LiteRT) to replace thresholds, once enough labeled data exists.
- Optional research mode: share raw sensor snippets with consent to improve detection.
- Self-hosted Photon or Pelias with an India index.
- CDN in front of tiles.
- GraphHopper custom models for in-engine comfort costs, if candidate re-ranking proves too weak.
- App lock for the trip history.
- Offline basemap packs.
- More languages.
- Public API or partner data access.
- Voice confirmation of hazards for hands-free feedback.
- iOS version.

## 10. Open questions to resolve (from the PRD)

| # | Question | Resolve by |
|---|---|---|
| 1 | Logo and branding (name resolved in M0-1: MapMyRoad) | M3-2 |
| 2 | Research mode for raw sensor snippets | After M1 |
| 3 | First municipal complaint channels | M8-3 |
| 4 | How to gate the public heatmap | M6-6 |
| 5 | ODbL obligations for derived exports | M8-6 |
| 6 | Hindi and Marathi translation review | M5-11 |

## 11. IDs added after IMPLEMENTATION_PLAN.md

These came out of SECURITY.md, UI_UX_GUIDELINES.md and DEPLOYMENT.md. They are already included in the tables above.

| ID | Why added |
|---|---|
| M0-10 | Repository hygiene and supply-chain basics (SECURITY.md section 11) |
| M2-19 | Android hardening checklist (SECURITY.md section 8) |
| M2-20 | Detector metric regression gate (TESTING_STRATEGY.md section 3.2) |
| M3-7 | Play pre-launch report review |
| M4-19 | Stricter visibility on low-traffic roads (SECURITY.md T2) |
| M4-20 | Server hardening and deployment workflow |
| M6-8 | Security checklist and incident rehearsal |
