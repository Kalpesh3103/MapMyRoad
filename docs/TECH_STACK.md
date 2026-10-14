# Tech Stack

**Product:** MapMyRoad
**Status:** Draft v0.1
**Related:** [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md), [PRD.md](PRD.md), [ARCHITECTURE.md](ARCHITECTURE.md), [API_SPEC.md](API_SPEC.md), [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)

This document lists every technology the project uses, why it was chosen, and what was rejected. Exact versions are deliberately not pinned here. Pin them in the build files (Gradle version catalog, Maven or Gradle BOM) at project start and update them with Dependabot or Renovate.

---

## 1. Selection principles

1. **Free first.** Everything must be free or open source until a measured upgrade trigger is hit (see ARCHITECTURE.md, section 14).
2. **Native where it matters.** Sensing, foreground services and background behavior are Android-specific, so the app is native.
3. **Familiar on the server.** The backend uses Java and Spring Boot to keep the learning curve on the Android and geospatial parts.
4. **Few moving parts.** One backend application, one database, one routing engine, one reverse proxy.
5. **Open data, open tooling.** OpenStreetMap, MapLibre, Valhalla and PostGIS avoid per-request billing and restrictive map terms.
6. **Replaceable pieces.** Tile host, geocoder and navigation SDK sit behind thin interfaces so they can be swapped.

## 2. Summary by layer

| Layer | Choice |
|---|---|
| Android language and UI | Kotlin, Jetpack Compose |
| Android architecture | MVVM, coroutines and Flow, Hilt |
| Local data | Room (SQLite), DataStore |
| Background work | Foreground service (type `location`), WorkManager |
| Sensing and location | SensorManager, Fused Location Provider, Activity Recognition API |
| Map | MapLibre Native Android |
| Navigation (v3) | Ferrostar, Android TextToSpeech |
| Networking | Retrofit, OkHttp, kotlinx.serialization |
| Backend | Java (current LTS), Spring Boot |
| Database | PostgreSQL with PostGIS |
| Routing and map-matching | Valhalla |
| Geocoding | Photon (public API with caching first, self-hosted later) |
| Map tiles | OpenFreeMap, with Protomaps (PMTiles) as the self-hosted fallback |
| Reverse proxy | Caddy |
| Containers | Docker, Docker Compose |
| CI | GitHub Actions |
| Monitoring | Firebase Crashlytics and Analytics, Spring Boot Actuator, free uptime monitor |
| Analysis and ML tooling | Python, Jupyter, pandas, NumPy, SciPy, scikit-learn, LiteRT (formerly TensorFlow Lite) later |

## 3. Android

### 3.1 Platform and build

| Item | Choice | Notes |
|---|---|---|
| Language | Kotlin | Jetpack Compose requires Kotlin. Coming from Java, the learning curve is small. |
| Min SDK | API 26 (Android 8.0) | Proposed. Revisit with real device data. |
| Target and compile SDK | The level Google Play requires at submission time | Re-check before each release. |
| Build system | Gradle (Kotlin DSL) with a version catalog (`libs.versions.toml`) | |
| Code generation | KSP | For Room and Hilt. |
| Static checks | ktlint (Gradle plugin) and Android Lint | Run in CI. detekt deferred (decided in M0-3). |

### 3.2 Libraries

| Purpose | Library | Phase | Notes |
|---|---|---|---|
| UI | Jetpack Compose (BOM), Material 3 | v1 | |
| Navigation | Navigation Compose | v1 | |
| Async | Kotlin Coroutines, Flow | v1 | Sensor callbacks hand off to a dedicated dispatcher. |
| DI | Hilt | v1 | |
| Local DB | Room | v1 | Export schemas to Git for migration checks. |
| Settings | Jetpack DataStore | v1 | Tokens are stored encrypted with a Keystore-backed key. |
| Background uploads | WorkManager | v2 | Unmetered network constraint by default. |
| Location | Play Services Location (Fused Location Provider) | v1 | |
| Activity recognition | Play Services Location (Activity Recognition API) | v1 (opt-in) | |
| Map | MapLibre Native Android SDK | v1 | Optionally wrapped in a small Compose helper. |
| HTTP | Retrofit, OkHttp, kotlinx.serialization | v2 | Gzip request bodies for uploads. |
| App attestation | Play Integrity API client | v2 | |
| Crash and analytics | Firebase BoM: Crashlytics, Analytics | v1 | |
| Logging | Timber | v1 | No precise location in release logs. |
| Navigation SDK | Ferrostar Android libraries | v3 | Spike first, see IMPLEMENTATION_PLAN.md. |
| Voice | Android TextToSpeech | v3 | Verify Hindi and Marathi voice availability. |
| Photos | System camera intent (no `CAMERA` permission needed) | v3 | |
| ML runtime | LiteRT (formerly TensorFlow Lite) | later | Replaces thresholds with a small classifier. |
| Leak detection | LeakCanary | v1 (debug only) | |
| Testing | JUnit, MockK, Turbine, Compose UI tests, Espresso | v1 | |

### 3.3 Manifest permissions and declarations

| Permission or declaration | Needed for | When |
|---|---|---|
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Trip recording | v1 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, service type `location` | Tracking service | v1 |
| `POST_NOTIFICATIONS` (Android 13 and later) | Foreground service notification | v1 |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Map tiles, API | v1 |
| `ACTIVITY_RECOGNITION` | Automatic trip start | v1, opt-in |
| `ACCESS_BACKGROUND_LOCATION` | Starting trips while the app is closed | v1, opt-in only. Needs the Play permission declaration and demo video. |
| `HIGH_SAMPLING_RATE_SENSORS` | Not needed | Sampling stays at or below 200 Hz. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Not used | Google Play restricts it. Open the system battery settings screen with an intent and explain the steps instead. |

### 3.4 Sensor and detection notes

- Sensors: accelerometer, gyroscope, gravity or game rotation vector.
- Sampling at about 50 to 100 Hz with batching (`maxReportLatencyUs`) to let the CPU sleep.
- Detector code is plain Kotlin with no Android dependencies, so it can be unit-tested on the JVM against recorded traces.

## 4. Backend

### 4.1 Runtime and build

| Item | Choice | Notes |
|---|---|---|
| Language | Java, current LTS (21 or newer) | |
| Framework | Spring Boot 4.1.1 | Spring Framework 7, Security 7.1, Jackson 3 (ADR-0018). |
| Build | Gradle (Kotlin DSL), own 9.2.1 wrapper in `backend/` | ADR-0018. Spring Boot 4 needs Gradle 8.14 or later. |
| Concurrency | Virtual threads enabled | Suits IO-bound proxying to Valhalla and Photon. |
| Packaging | Docker image built in CI | |

### 4.2 Libraries

| Purpose | Library | Notes |
|---|---|---|
| Web | Spring Web (MVC), Bean Validation | |
| Data access | NamedParameterJdbcTemplate | Plain JDBC with named parameters for everything (ADR-0018). |
| Spatial types | PostGIS functions in SQL | No Hibernate Spatial: coordinates go in and out as plain numbers or GeoJSON (ADR-0018). |
| Migrations | Flyway (`flyway-core` plus the PostgreSQL module) | |
| Auth | Spring Security OAuth2 resource server (JWT) | Self-issued short-lived tokens, HS256 with a rotating secret. |
| App attestation | Google API client for the Play Integrity verdict endpoint | Needs a Google Cloud project and service account. |
| Rate limiting | In-house token bucket on Caffeine | Single instance only (ADR-0018). |
| Caching | Caffeine, plus a DB table for geocoding | |
| HTTP clients | Spring `RestClient` | Explicit timeouts. Resilience4j circuit breaker optional. |
| API docs | springdoc-openapi | Generates `openapi.json`, checked against API_SPEC.md in CI. |
| Metrics | Micrometer with Prometheus registry | |
| Logging | Logback with JSON encoder | |
| Scheduling | Spring `@Scheduled` | Single instance, so no distributed lock needed at first. |
| Testing | JUnit 5, AssertJ, Testcontainers (PostGIS image), WireMock (Valhalla and Photon), Awaitility | |

Vector tiles are generated in SQL (`ST_AsMVT`), so no tile library is needed in Java.

## 5. Data, maps and geo services

| Component | Choice | Notes |
|---|---|---|
| Database | PostgreSQL (current supported major) with PostGIS 3.x | |
| OSM source data | Geofabrik regional extract | For the pilot, use a regional extract rather than all of India to cut memory and disk needs. Trim further with `osmium extract` if needed. |
| OSM import for reference tables | osm2pgsql (flex output) | Roads and traffic-calming points only, see DATABASE_SCHEMA.md. |
| Extract tooling | osmium-tool | |
| Routing and map-matching | Valhalla | Costing models `motorcycle`, `motor_scooter`, `auto`. Map-matching through `trace_attributes`. |
| Place search | Photon | Public API fair-use only, cache responses, self-host an index later. |
| Vector tiles for the base map | OpenFreeMap (hosted, free, no API key) | Donation-funded with no guarantee. Keep the fallback ready. |
| Self-hosted tiles fallback | Protomaps PMTiles file on static hosting | Needs a matching style, plus font glyphs and sprites hosted by you. |
| Map style | MapLibre style JSON | Use a published style from the tile host or the Protomaps basemap style. |
| Hazard and road-quality tiles | Generated by the backend (`/v1/tiles/...`) | |
| Polyline format | Encoded polyline, precision 6 | Matches Valhalla's native output. |

## 6. Infrastructure and DevOps

| Need | Choice | Notes |
|---|---|---|
| Server | Oracle Cloud Always Free ARM VM, or a local machine in development | The free VM is **ARM (Ampere)**. Check that every Docker image has an `arm64` build, or build it yourself. |
| Containers | Docker, Docker Compose | |
| Reverse proxy and TLS | Caddy (automatic HTTPS) | |
| Domain | Optional custom domain, otherwise a free DNS name | A privacy policy URL on GitHub Pages is required either way. |
| CI | GitHub Actions | Android build, backend build and tests, lint. |
| Dependency updates | Dependabot or Renovate | |
| Backups | `pg_dump` to Cloudflare R2 or Oracle Object Storage free tiers | Test restores periodically. |
| Uptime monitoring | Free external monitor on the health endpoint | |
| Metrics dashboards | Prometheus and Grafana (optional) | Skip at first if memory is tight. |
| Secrets | Environment variables on the VM, GitHub Actions secrets | Never in the repository or the app. |
| Distribution | Google Play Console (one-time registration fee) | The only unavoidable cost. |

## 7. Developer tools

| Tool | Use |
|---|---|
| Android Studio | Android development, emulator with GPX or KML route playback |
| IntelliJ IDEA | Backend development |
| Git and GitHub | Source control, issues, CI |
| Postman or Bruno | API testing |
| DBeaver or pgAdmin | Database inspection |
| QGIS | Inspecting hazards, segments and routes on a map |
| adb, Perfetto | Debugging services, measuring battery and CPU |
| Firebase Test Lab | Optional device testing within its free quota |
| Python with Jupyter | Sensor trace analysis, detector tuning, later model training |
| Claude, GitHub Copilot | Boilerplate, tests, refactoring. Review sensor and permission code by hand. |

## 8. Licenses to track

| Component | License | Note |
|---|---|---|
| MapLibre Native | BSD-2-Clause | |
| Valhalla | MIT | |
| Spring Boot | Apache-2.0 | |
| PostgreSQL | PostgreSQL License | |
| PostGIS | GPL-2.0-or-later | Used server-side only and not distributed with the app. |
| OpenStreetMap data | ODbL | Attribution required in the app. Review share-alike obligations before publishing derived open data (v4). |
| Photon, Ferrostar, OpenFreeMap styles and data | Check each project's current license and terms | Verify before release. |

This table is a starting checklist, not legal advice. Verify each license before shipping.

## 9. Considered and rejected

| Option | Why not |
|---|---|
| Google Maps SDK and Directions API | Billing, terms restrict mixing results with non-Google maps, no offline use |
| Flutter or React Native | The core value is foreground-service sensing and background behavior, which is Android-specific and easier natively |
| Firebase Firestore or Firebase Auth as the backend | Poor fit for spatial queries, ties data to a vendor, accounts are not wanted |
| MongoDB | Spatial queries and clustering are stronger in PostGIS |
| Kafka, Redis, Kubernetes at the start | Operational weight without need. Add when upgrade triggers are hit. |
| OSRM | No built-in map-matching of the same quality and fewer vehicle profiles out of the box |
| GraphHopper | A good alternative, kept as the fallback if custom comfort costs are needed inside the routing engine |
| Mapbox SDKs | Paid at scale and the data is not yours to tune |
| Self-hosted Nominatim for the pilot | Heavy import and memory use for little early benefit. Use Photon with caching. |

## 10. Version and upgrade policy

- Pin versions through the Gradle version catalog and the backend BOM.
- Update dependencies monthly through automated pull requests, with CI as the gate.
- Re-check the Play target SDK requirement each spring and before every release.
- Track MapLibre, Valhalla and Ferrostar release notes before upgrading. Routing and navigation behavior can change between versions.
- Keep a short "known good versions" list in the repository once the first release ships.
