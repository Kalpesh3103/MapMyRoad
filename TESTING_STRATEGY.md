# Testing Strategy

**Product:** Road Quality Mapper (working title)
**Status:** Draft v0.1
**Related:** [PRD.md](PRD.md), [ARCHITECTURE.md](ARCHITECTURE.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), [CODING_STANDARDS.md](CODING_STANDARDS.md), [SECURITY.md](SECURITY.md)

Targets and thresholds are **initial values** tied to PRD section 10 and will be revisited with real data after Milestone 1 (M1) and the pilot.

---

## 1. Goals

1. Prove that detection is trustworthy enough to publish (precision, recall, false alarms).
2. Keep tracking reliable on real phones, including those with aggressive battery managers.
3. Keep battery impact low.
4. Protect privacy: prove trimming, retention and deletion work.
5. Keep the API, schema and docs in agreement.
6. Keep releases safe with fast automated checks and a short manual checklist.

## 2. Test pyramid and tooling

| Level | Share of effort | Tools | Runs |
|---|---|---|---|
| Unit | Largest | JUnit 5, MockK (Android), AssertJ, Turbine (Flow) | Every commit |
| Integration | Medium | Room in-memory and migration tests, Testcontainers (PostGIS), WireMock (Valhalla, Photon), Spring `@SpringBootTest` slices | Every commit (backend), on PR (Android) |
| Contract | Small | API_SPEC examples as tests, OpenAPI diff | Every commit |
| UI | Small | Compose UI tests, Espresso for flows, screenshot tests (optional) | On PR, nightly |
| Field and device | Few but critical | Real drives, device matrix | Per milestone and release |
| Manual exploratory | Few | Checklists | Per release |

Because the detector is pure Kotlin, most of the highest-risk logic can run as fast JVM unit tests on recorded traces.

## 3. Detector testing (highest priority)

### 3.1 Trace fixtures

Fixtures are anonymized CSV files recorded in M1 and later drives.

```
# fixture: pune_route_a_scooter_phone_mount_01.csv
t_ms, ax, ay, az, gx, gy, gz, grav_x, grav_y, grav_z, lat, lon, speed_mps, accuracy_m
```

Each fixture has a labels file with manually tagged events:

```json
{
  "fixture": "pune_route_a_scooter_phone_mount_01",
  "vehicleType": "scooter",
  "phonePlacement": "mount",
  "labels": [
    { "tMs": 183400, "type": "pothole", "severity": 3 },
    { "tMs": 204100, "type": "speed_breaker" },
    { "tMs": 251900, "type": "rough_patch", "durationMs": 2500 }
  ]
}
```

Rules for fixtures:
- Anonymize: shift coordinates by a fixed random offset, trim to the middle of the trip, never include home or work locations. Store in `android/detector/src/test/resources/traces/`.
- Keep the files small (a few minutes each, downsampled only where it does not change behavior).
- A fixture is never edited after it is committed. New observations make new fixtures.

### 3.2 Metrics and tolerances

A detection counts as matched when it is within a time window of a labeled event (initial: plus or minus 1.5 seconds, and within 15 m of the label position when GPS is used).

| Metric | Definition | Initial target (PRD section 10) |
|---|---|---|
| Recall (severe) | Matched severe labels divided by all severe labels | at least 70% |
| Precision (severe) | Matched severe detections divided by all severe detections | at least 70% |
| Speed-breaker false alarms | Severe events at labeled speed breakers per pass | fewer than 1 in 10 passes (TS-5), and under 1 per 10 km overall |
| Severity agreement | Detected severity within one band of the label | at least 80% of matched events |

An automated test computes these over the fixture set and **fails the build if a metric drops below the checked-in baseline** by more than a tolerance (for example 2 percentage points). Improving a metric means updating the baseline in the same PR with an explanation. This prevents silent regressions when thresholds change.

### 3.3 Detector unit tests

- Orientation normalization with synthetic rotations.
- Filter behavior on synthetic signals (step, impulse, sine at known frequencies).
- Gating: low speed, poor accuracy, handling (large rotation without heading change).
- Refractory period and double-count prevention.
- Speed normalization monotonicity (same defect at higher speed should not produce a lower severity).
- Position interpolation between GPS fixes, including gaps and pauses.
- Roughness window length and ordering.
- Determinism: same trace twice gives the same output.
- Property-style tests: random noise at rest never produces severe events.

### 3.4 Calibration protocol (M1, repeated when vehicles or phones change)

1. Pick a known route and pre-survey it on foot or video, noting potholes, speed breakers and rough stretches with positions.
2. Mount or place the phone as specified (mount, pocket, bag) and record the placement.
3. Ride or drive at several speeds (for example 20, 30, 40 km/h) across the same features.
4. Use the in-app tag button or a voice note for each event felt.
5. Run the analysis notebook, tune parameters on half the data, **evaluate on the other half** (avoid overfitting to the tuning routes).
6. Record results and parameters in the findings doc (M1-5, M1-6).

## 4. Android testing

### 4.1 Unit and integration

| Area | Tests |
|---|---|
| ViewModels | State transitions with fake repositories and Turbine |
| Repositories | Room tests (in-memory), mapping, cascade deletes |
| Room migrations | `MigrationTestHelper` for every version step, using exported schemas |
| Trip summary | Distance, duration, smoothness and counts against known inputs (PM-3 within 5%) |
| Trimming | `TripTrimmer` removes the first and last 500 m, handles short trips, loops and gaps (PV-2) |
| Upload worker (v2) | Batching, backoff, idempotency (same IDs on retry), unmetered constraint, 401 refresh, 426 handling |
| Settings | DataStore defaults, opt-in flows, deletion clears everything |
| Analytics | Only allowlisted parameters can be constructed |

### 4.2 Service and background behavior (instrumented, real devices)

| Scenario | Pass criteria |
|---|---|
| Start trip, turn screen off for 30 minutes | Service still running, trip complete |
| Switch between apps, open camera and maps | No gaps in recording beyond GPS loss |
| App swiped from recents (manual trip) | Behavior documented and consistent (foreground service keeps running on most devices) |
| Low battery threshold | Trip stops cleanly and is saved |
| Process death and restart | Trip recovers from incremental saves |
| Airplane mode for the entire trip | Trip appears in history with route and hazards (TS-8) |
| Permission revoked mid-trip | Graceful stop, user informed on next launch |
| Auto-start (if shipped) | Starts within a reasonable time of entering a vehicle, never starts when walking, stops after the configured stationary time |
| Doze and app standby | No unexpected stops during a long trip |

### 4.3 UI tests

- Onboarding flow, including denial paths for every permission (app stays usable).
- Home, live trip, summary, trips list, settings, privacy and delete flows.
- Large font (200%), dark mode, and Hindi and Marathi strings from v2 (no clipped text).
- TalkBack: critical flows can be completed (manual pass each release, automated checks with Accessibility Test Framework).
- Safety rule checks: no dialogs or toasts appear during a live trip (a UI test starts a trip in a test harness and asserts the screen shows only the live trip UI).

### 4.4 Map tests

- Layers render for sample data, with no crashes on empty or huge inputs.
- Attribution visible on every map screen.
- Marker clustering at different zoom levels (screenshot or logic tests).

## 5. Backend testing

### 5.1 Unit tests

- Validation rules for uploads (every rejection reason in API_SPEC section 5.3).
- Clustering radius and matching rules.
- Confidence formula, recency decay, feedback weighting (DATABASE_SCHEMA.md A9).
- Comfort score calculation with fixed hazard sets, including coverage levels.
- Rate limit buckets.
- Token creation, expiry, refresh rotation and reuse detection.

### 5.2 Integration tests (Testcontainers with PostGIS)

- Flyway migrations apply from scratch and from the previous release version.
- Spatial queries with synthetic data around a test route: nearest hazard, hazards near a route, roughness along a route, vector tile output (decode the MVT and assert layers and properties).
- Ingest flow end to end: upload, matching (with a WireMock Valhalla response), aggregation, tile visibility rules.
- Retention job: device IDs nulled at 90 days, trip tokens cleared, deletion cascade (A10).
- Delete my data: recompute queue processes affected hazards and the contributor's influence disappears.

### 5.3 External service tests

- WireMock stubs for Valhalla (`route`, `trace_attributes`) and Photon (search, reverse), including timeouts, 5xx and malformed responses.
- Routing integration against a real local Valhalla on a tiny fixed OSM extract in a separate slower job (nightly): candidate counts, ordering, motorcycle versus car costing.

### 5.4 Contract tests

- Every request and response example in API_SPEC.md is executed as a test.
- CI compares the generated `openapi.json` with the checked-in copy.
- Error shapes are asserted for each documented `code`.

## 6. Data and privacy tests

| Requirement | Test |
|---|---|
| PV-2 trimming | Property test: for random trips, no uploaded point lies within 500 m (along the path) of the original start or end |
| PV-3 deletion | After `DELETE /devices/me`, queries find zero rows tied to the device in all tables, and affected aggregates are recomputed |
| Retention | Time-travel test with inserted rows aged 91, 366 days, expected state after the job |
| Visibility thresholds | Segments below the device and sample thresholds never appear in tiles, including the stricter rule for low-traffic road types |
| No identifiers in public responses | Automated scan of tile, hazards and report-card responses for device IDs and trip tokens |
| Logging | Test appenders assert that logs from an upload request contain no coordinates or tokens |
| Analytics | No precise location in any event |

## 7. Routing and navigation tests

| Stage | Tests |
|---|---|
| v2 routing | Fixed origin and destination pairs against a local Valhalla: at least two distinct candidates when alternatives exist, labels correct, comfort ordering matches seeded hazards, coverage reported correctly, `NO_ROUTE` handled |
| Preference slider | Moving from 0 to 1 changes the recommended candidate when scores differ |
| v3 navigation | GPX replay through the app: correct maneuver order and timing, off-route detection, rerouting, voice prompts, no repeated alerts |
| Hazard alerts | Replay a route with seeded hazards, assert lead time, frequency cap, priority against turn prompts |
| Real drives | At least five different routes, including missed turns and tunnels. Record any unsafe or confusing instruction as a blocking defect. |

## 8. Non-functional testing

### 8.1 Battery protocol

1. Fully charge the phone, same screen brightness, same mount, same temperature conditions as far as possible.
2. Baseline: 30 minutes of a normal drive with the app closed (and the navigation app of choice if used).
3. Test: 30 minutes with the app tracking.
4. Compare percent drop, and record phone model, Android version and vehicle type.
5. Also record with `adb shell dumpsys batterystats` or Android Studio Energy Profiler for CPU wakeups and sensor usage.
6. Target: about 5% per hour or less added by the app (PRD NFR). Investigate anything above.

### 8.2 Performance

- Startup time and memory on a low-end device.
- Map scrolling with maximum markers.
- Backend ingestion: synthetic load (for example 500 events and 1000 windows per request) to find the single-VM limit (M6-2), including p95 latency for tiles and routes.
- Tile generation time at common zoom levels with a realistic hazard count.
- Database growth projections validated against measured row sizes (DATABASE_SCHEMA.md A12).

### 8.3 Reliability

- Kill and restart each backend container during uploads, no data loss or duplicate rows (idempotent IDs).
- Valhalla down: routes return `503`, ingestion continues, matching job retries later.
- Disk nearly full on the VM: alerts fire, uploads degrade safely.
- Clock skew on the client: server rejects future timestamps correctly.

### 8.4 Accessibility and localization

- TalkBack walkthrough of onboarding, trip start and stop, summary, delete data.
- Accessibility Scanner on all screens.
- Color-blindness simulation on the map legend, route cards and severity markers.
- Hindi and Marathi: string length, line breaks, font rendering, plural forms.

## 9. Security and abuse tests

Run before v2 (see SECURITY.md section 16).

| Test | Expected |
|---|---|
| Registration without a valid integrity token | `403 INTEGRITY_FAILED` |
| Nonce reuse or expiry | Rejected |
| Expired token | `401 TOKEN_EXPIRED`, refresh works once |
| Refresh token replay after rotation | Revoked, re-registration required |
| Upload with impossible speed, future time, out-of-region point | Items rejected with documented reasons |
| Oversized body, gzip bomb | `413`, no memory spike |
| Parameter tampering on every numeric and text input | `400`, no SQL error leaks |
| Feedback from far away | `422 TOO_FAR` |
| Burst of uploads from one device or IP | `429` with `Retry-After` |
| Many devices reporting a fake hazard in a quiet street | Does not reach display confidence unless corroborated by enough independent devices |
| Scraping hazards and tiles at high rate | Rate limits hold |
| Geocode input with URLs or odd encodings | No SSRF, no upstream calls beyond the configured host |
| Backup restore into a clean environment | Works within the documented time |

## 10. Field testing and the pilot

### 10.1 Field test checklist (per release candidate)

- [ ] Two vehicle types, three phone brands (include at least one with aggressive battery management).
- [ ] Morning and evening commute, at least one rain day for v3 monsoon mode.
- [ ] Phone in mount, pocket and bag (document which are supported).
- [ ] Battery protocol run on at least two devices.
- [ ] Complete a trip with no network.
- [ ] Verify trip summary against a known route distance.
- [ ] Review all detected severe events on the map and record false positives with notes.
- [ ] No unexpected notifications or dialogs during a trip.

### 10.2 Device matrix (minimum)

| Dimension | Coverage |
|---|---|
| Brands | At least three of: Samsung, Xiaomi/Redmi/Poco, Realme/OPPO/Vivo, Google Pixel or Motorola (near-stock Android) |
| Android versions | 8 (minimum supported), 10, 12, 13 or 14, and the newest stable release |
| Screen and RAM | One small, low-RAM device and one large device |
| Sensors | Phones with different accelerometer quality (budget and mid-range) |

Firebase Test Lab (within its free quota) helps with UI and compatibility. It cannot replace real drives, because sensors and movement are the point.

### 10.3 Pilot (Pune)

- Recruit a small group of testers (also fulfilling the Play closed-testing requirement for v1 if applicable).
- Provide a feedback form and a way to report false alarms.
- Review weekly: events per km, share of severe events, false-alarm reports, battery and crash reports.
- Decide thresholds for opening the public heatmap (PRD open question 4) using the pilot data.

### 10.4 Ground truth for the community map

Choose a few road stretches and survey them independently (walk and photograph). Compare against the heatmap and hazards after enough passes. Record agreement. This gives the first honest estimate of real-world accuracy.

## 11. Release testing checklists

### 11.1 Per Android release

- [ ] CI green (unit, integration, lint, dependency and secret scans).
- [ ] Field test checklist (10.1) done on the release candidate.
- [ ] Upgrade test from the previous release (Room migration with real data).
- [ ] Fresh install and first-run flow, all permission combinations.
- [ ] Play pre-launch report reviewed.
- [ ] Release build checks: not debuggable, logs clean, no debug-only features, `adb backup` extracts nothing.
- [ ] Data safety form and privacy policy match the build.
- [ ] Store listing screenshots match the release.

### 11.2 Per backend release

- [ ] CI green including contract tests and migrations from scratch and from the previous version.
- [ ] Backup taken immediately before deploying.
- [ ] Deploy to a staging compose project or local copy first when the change touches migrations or jobs.
- [ ] Smoke test after deploy: registration, upload, tile, route, delete.
- [ ] Monitors green for an hour after release. Rollback plan ready (DEPLOYMENT.md).

## 12. CI gating

| Stage | Blocks merge |
|---|---|
| Lint and format | Yes |
| Unit tests (Android JVM, backend) | Yes |
| Detector metric regression test | Yes |
| Room migration tests | Yes |
| Backend integration tests (Testcontainers) | Yes |
| Contract tests and OpenAPI diff | Yes |
| Dependency and secret scans | Yes (for high severity and any secret) |
| Compose UI and instrumented tests | Yes for a small smoke set, the rest nightly |
| Nightly Valhalla integration | No, but failures open an issue |
| Field and battery tests | Manual gates for release |

Flaky tests are fixed or quarantined within a week, with an issue tracking each. A quarantined test does not stay ignored without an owner.

## 13. Test data and environments

- **Fixtures:** anonymized sensor traces (section 3.1) and tiny OSM extracts for routing tests.
- **Seed script:** generates synthetic hazards, segments and devices around a test route for local development and demos.
- **No real user data in tests.** Production data is never copied to a development machine.
- **Environments:** local Docker Compose (development and integration), production. A staging copy is optional and can be a second Compose project on the same VM when memory allows (DEPLOYMENT.md).

## 14. Quality metrics tracked

Tracked in PROGRESS.md and reviewed at each milestone exit gate.

| Metric | Source |
|---|---|
| Detector precision, recall, speed-breaker false alarms | Fixture suite and field tests |
| Battery per hour | Battery protocol |
| Crash-free sessions and ANR rate | Crashlytics and Play Console |
| Trip loss rate (trips ended by service kill) | App analytics (`trip_ended` with stop reason) |
| Upload success rate | Server counters |
| Rejected uploads by reason | Server counters |
| Test coverage on detector and trimming code | CI (target high for these modules, no blanket percentage) |
| Time to restore from backup | Restore drills |

## 15. Exit criteria by milestone

| Milestone | Testing exit criteria |
|---|---|
| M1 | Findings doc with measured precision and recall, battery measurement, fixtures saved |
| M2 | All P0 v1 acceptance criteria pass (PRD section 11), field test checklist done, detector regression test in CI |
| M3 | Closed test crash-free sessions at or above 99%, pre-launch report clean |
| M4 | Synthetic uploads flow to tiles and route scoring, contract and integration suites green |
| M5 | End-to-end tests on devices: contribute, view, feedback, route, delete |
| M6 | Abuse tests (section 9), load test, deletion and retention drills, pilot data review |
| M7 | Navigation real-drive tests with no unsafe instructions |
| M8 | Complaint text reviewed on real examples, report-card sufficiency rules tested |
