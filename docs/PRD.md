# Product Requirements Document (PRD)

**Product:** MapMyRoad
**Platform:** Android (Google Play)
**Status:** Draft v0.1
**Related:** [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md), [ARCHITECTURE.md](ARCHITECTURE.md)

Priorities: **P0** must have, **P1** should have, **P2** nice to have. Phases: **v1** Personal mapper, **v2** Community and route choice, **v3** Navigation and alerts, **v4** Civic impact.

---

## 1. Background

Poor road surfaces cost people comfort, time, vehicle wear and safety, but road condition is invisible to mainstream navigation apps, which optimize for travel time. Existing reporting channels depend on manual complaints. Modern phones carry the sensors needed to measure road roughness passively while people travel.

## 2. Goals and non-goals

### Goals
1. Detect potholes and rough road passively, with trustworthy severity ratings.
2. Give users value immediately, even when no other user has contributed data.
3. Build a crowdsourced road-condition map and use it to recommend smoother routes.
4. Protect user privacy by design.
5. Run on free tools until growth justifies paid services.

### Non-goals (for now)
- Competing with Google Maps on fastest-arrival routing or live traffic.
- Business listings, ratings, reviews or general place discovery.
- iOS, web app or wearables.
- Monetization (deferred).
- Official certification-grade road surveys. Roughness here is a comfort proxy, not an engineering measurement.

## 3. Personas

| Persona | Context | Needs |
|---|---|---|
| **Commuter Chetan** | Rides a scooter 15 km each way in a city | Avoid the worst stretches, know how his daily route compares with alternatives |
| **Delivery Divya** | Rides a motorcycle all day | Save the vehicle and her back, avoid damaged roads, low battery drain |
| **Civic Charu** | Active in a residents' association | Credible, location-accurate evidence to file complaints and publish area rankings |

## 4. User stories

**Trips and detection**
- As a rider, I start a trip with one tap and the app records road quality in the background.
- As a user, I choose my vehicle type so detection fits how my vehicle feels on bumps.
- As a user, I can opt in to automatic trip start so I never forget to record.

**Personal insights**
- As a commuter, I see my route on a map colored by smoothness and the hazards I hit.
- As a commuter, I see a trip summary (distance, smoothness score, hazards by severity).
- As a user, I can delete any trip and all my data.

**Community**
- As a contributor, I share anonymous hazard data so the map improves for everyone.
- As a driver, I see a shared map of road condition and known hazards around me.
- As a driver, I confirm or dismiss hazards I passed, after the trip or while stopped (never while moving, ADR-0013).

**Routing**
- As a commuter, I enter a destination and see the fastest, smoothest and balanced routes with their trade-offs.
- As a commuter, I decide how much extra time I accept for a smoother road.
- As a driver, I get spoken navigation and a warning before a severe patch (v3).

**Civic**
- As a resident, I generate a ready-to-send complaint with location, severity and detection evidence (v4).
- As a journalist or NGO member, I download open road-condition data (v4).

## 5. Functional requirements

### 5.1 Trip recording and detection (TS)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| TS-1 | Start and stop a trip manually from the home screen. A persistent notification shows while recording. | P0 | v1 |
| TS-2 | Vehicle profile selection (motorcycle, scooter, car, auto-rickshaw) that sets detection thresholds and routing mode. | P0 | v1 |
| TS-3 | Collect accelerometer, gyroscope and GPS data during a trip, processed on the device. Raw sensor streams are not stored or uploaded. | P0 | v1 |
| TS-4 | Detect bump events and classify severity as minor, moderate or severe, normalized for speed and vehicle type. | P0 | v1 |
| TS-5 | Suppress known false sources: low speed, poor GPS accuracy, phone being handled, speed breakers and railway crossings (using OSM tags when available and repeat-location patterns). | P1 | v1, improved in v2 |
| TS-6 | Automatic trip start and stop using Activity Recognition, opt-in only. | P1 | v1 |
| TS-7 | Auto-pause when stationary for a configurable time and stop recording at a low-battery threshold. | P1 | v1 |
| TS-8 | Full offline operation. Trips and events are stored locally and uploaded later (v2). | P0 | v1 |
| TS-9 | Segment roughness index computed over rolling distance windows (about 50 m) in addition to discrete events. Computed on the device from v1 (needed for PM-1 smoothness coloring), uploaded from v2. | P0 | v1 |

### 5.2 Personal map and insights (PM)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| PM-1 | Map showing the user's own trip routes colored by smoothness. | P0 | v1 |
| PM-2 | Hazard markers from the user's own trips, with severity. | P0 | v1 |
| PM-3 | Trip summary: distance, duration, smoothness score, hazard counts by severity. | P0 | v1 |
| PM-4 | Trip history list with the ability to delete a trip. | P0 | v1 |
| PM-5 | Monthly stats and a vehicle-health hint (for example, number of severe bumps this month). | P2 | v2 |
| PM-6 | Contribution stats and badges (kilometers mapped, hazards confirmed). | P2 | v2 |

### 5.3 Community map (CM)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| CM-1 | Opt-in contribution: upload anonymous hazard events and segment statistics, on Wi-Fi by default. | P0 | v2 |
| CM-2 | Shared road-condition layer where road segments are green, yellow or red. | P0 | v2 |
| CM-3 | Hazard layer with a confidence indicator and last-seen time. | P0 | v2 |
| CM-4 | One-tap "still there" or "fixed" feedback on known hazards the user passed, collected in a post-trip review or only after the vehicle has been stopped for several seconds. Never requested while moving (ADR-0013). | P1 | v2 |
| CM-5 | Anti-abuse: rate limits, implausible-value rejection, app attestation, multi-device confirmation before a hazard is shown as high confidence. | P0 | v2 |
| CM-6 | Manual report with photo for hazards sensors cannot catch (open manholes, missing signage). | P2 | v3 |
| CM-7 | Stale hazard handling: hazards not re-observed, or passed over without a bump, are aged out or marked fixed. | P1 | v2 |

### 5.4 Routing and navigation (RT)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| RT-1 | Destination search with autocomplete. | P0 | v2 |
| RT-2 | Show up to three route candidates (fastest, smoothest, balanced) with ETA, distance, comfort score and hazard counts. | P0 | v2 |
| RT-3 | Comfort versus speed preference control. | P1 | v2 |
| RT-4 | "Open in Google Maps" hand-off so the user is never stuck while in-app navigation is unavailable. | P0 | v2 |
| RT-5 | In-app turn-by-turn navigation with voice guidance, off-route detection and rerouting. | P0 | v3 |
| RT-6 | Hazard proximity alerts (voice or haptic, about 100 m before a severe hazard). | P1 | v3 |
| RT-7 | Monsoon mode: flag newly damaged or waterlogged roads. | P2 | v3 |

### 5.5 Civic tools (CV)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| CV-1 | One-tap complaint generator with location, severity, photo (if any) and detection count, with a link to the relevant municipal channel. | P1 | v4 |
| CV-2 | Ward or area report cards ranking the worst roads, shareable as an image or link. | P1 | v4 |
| CV-3 | Open data export (GeoJSON or CSV) with attribution and a documented license. | P2 | v4 |
| CV-4 | "Time to fix" tracking from first report to confirmed repair. | P2 | v4 |

### 5.6 Privacy, settings and onboarding (PV)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| PV-1 | No account required. Contribution is anonymous by default. | P0 | v1 |
| PV-2 | Trim the start and end of every trip (about the first and last 500 m) before any upload or sharing, so home and work locations are not exposed. | P0 | v2 |
| PV-3 | "Delete my data" in the app that removes local data and server-side data tied to the device. | P0 | v2 |
| PV-4 | Plain-language permission rationale screens before every permission prompt. | P0 | v1 |
| PV-5 | Guidance for battery optimization settings on phones that stop background services. | P1 | v1 |
| PV-6 | Languages: English in v1, plus Hindi and Marathi in v2. | P1 | v1, v2 |
| PV-7 | Required OpenStreetMap attribution visible on every map screen. | P0 | v1 |

## 6. Non-functional requirements

| Area | Requirement |
|---|---|
| **Battery** | Active tracking should add no more than about 5% battery per hour of driving on a mid-range phone (initial target, validate on real devices). Use sensor batching and low sampling when not in a vehicle. |
| **Reliability** | The tracking service survives screen-off and normal app switching. Recording resumes safely after a crash. No data loss for completed trips. |
| **Offline** | All v1 features work without a network. Uploads are queued and retried. |
| **Performance** | Map interaction stays smooth on mid-range phones. Route candidate response in under about 3 seconds on a normal mobile connection (v2 target). |
| **Privacy** | Raw sensor data stays on the device. Only events and aggregated statistics leave it. See section 8. |
| **Security** | HTTPS only, short-lived device tokens, server-side validation of every field, rate limiting. |
| **Compatibility** | Minimum Android 8.0 (API 26) proposed. Target the API level Google Play requires at submission time. |
| **Accessibility** | Supports system font scaling, sufficient contrast, TalkBack labels on controls. Hazard colors are never the only signal. |
| **Cost** | Stay within free tiers until upgrade triggers are hit. |

## 7. Key user flows

### 7.1 First run
1. Welcome screen explains what the app measures and why.
2. User picks a vehicle type.
3. Permission rationale, then location (while using the app) and motion or activity access as needed.
4. Optional: opt in to automatic trip start (requests background location, with a clear explanation).
5. Home screen with a prominent Start trip button.

### 7.2 Recording a trip
1. User taps Start trip. A foreground notification appears.
2. App records, detects events on the device, and shows a minimal live view (distance and hazards so far).
3. User taps Stop, or the trip ends automatically after a stationary period.
4. Summary screen: route colored by smoothness, hazards by severity, smoothness score.

### 7.3 Choosing a route (v2)
1. User searches a destination.
2. App shows up to three candidates with ETA, distance, comfort score and hazard counts, for example "Route B: 4 min slower, far fewer bad patches."
3. User picks a route and either starts in-app navigation (v3) or opens it in Google Maps (v2).

### 7.4 Giving feedback on a hazard (v2)
1. After a trip ends, the summary lists known hazards the user passed (post-trip review). A hazard can also be opened from the map while not on a trip.
2. User taps "Still there" or "Fixed". The feedback is sent anonymously and weighted by confidence rules.
3. Feedback is never requested while the vehicle is moving (ADR-0013, UI_UX_GUIDELINES.md section 6).

## 8. Data and privacy requirements

### 8.1 Data collected (input for the Play Data safety form)

| Data | Collected? | Purpose | Notes |
|---|---|---|---|
| Precise location | Yes | Place hazards on the map, record trips | Trip start and end trimmed before upload. Raw traces are not uploaded. |
| Motion sensor data | Processed on device | Detect bumps | Raw streams are not stored or uploaded. |
| Pseudonymous install ID | Yes (v2) | Deduplication, abuse prevention, data deletion | Random, not derived from hardware identifiers, resettable by the user. |
| Crash logs and diagnostics | Yes | Stability | Firebase Crashlytics. |
| Usage analytics | Yes | Product improvement | No precise location in analytics events. |
| Photos | Optional (v3) | Manual hazard reports | User-initiated only. |

### 8.2 Requirements
- Anonymous by default, no email, phone number or login in v1 and v2.
- A public privacy policy URL is live before the first closed test.
- Retention: raw hazard events are detached from the install ID after a fixed period (proposed 90 days). Aggregated hazards remain without identifiers.
- Users can delete their data at any time (PV-3).
- Legal review of the privacy policy against applicable Indian law (including the Digital Personal Data Protection Act, 2023) before v2 launch. This document is not legal advice.

## 9. Analytics events

| Event | Parameters (no precise location) |
|---|---|
| `trip_started` | vehicle_type, start_mode (manual or auto) |
| `trip_ended` | duration_min, distance_km, hazard_counts_by_severity, stop_reason (manual, auto, low_battery, service_killed) |
| `permission_result` | permission, granted |
| `contribute_opt_in` | enabled |
| `route_requested` (v2) | vehicle_type |
| `route_selected` (v2) | route_type (fastest, smoothest, balanced) |
| `hazard_feedback` (v2) | action (confirm or dismiss) |
| `data_deleted` (v2) | scope |

## 10. Success metrics (initial hypotheses, revisit after the pilot)

| Metric | Target |
|---|---|
| Detection recall on a surveyed route | At least 70% of pre-surveyed severe potholes flagged |
| Detection precision on that route | At least 70% of flagged severe events correspond to a real defect |
| False alarms from speed breakers | Fewer than 1 per 10 km after suppression logic |
| Crash-free sessions | At least 99% |
| Battery impact | About 5% per hour of active tracking or less |
| Pilot coverage (Pune) | Meaningful coverage of the main commute corridors before enabling the public heatmap |
| Retention | Tracked from the first closed test, no target set yet |

## 11. Acceptance criteria for core requirements

| ID | Acceptance criterion |
|---|---|
| TS-1 | Starting a trip shows a foreground notification within 2 seconds. Stopping it saves the trip and shows the summary. |
| TS-4 | On a controlled route with known potholes, event detection meets the recall and precision targets in section 10, separately for each supported vehicle type. |
| TS-5 | Driving over a known speed breaker at normal speed does not produce a severe pothole event in at least 9 of 10 passes. |
| TS-8 | A trip recorded in airplane mode appears in history with its route and hazards. |
| PM-3 | Summary distance is within 5% of a reference measurement for a known route. |
| CM-5 | Uploads from a stationary or implausibly fast device are rejected, and a hazard seen by only one device is not shown as high confidence. |
| RT-2 | For a destination with alternatives, at least two distinct candidates are shown with ETA, distance and comfort score. |
| PV-2 | In uploaded data, no point lies within the trimmed start or end distance of a trip. |
| PV-3 | After deletion, the server holds no events linked to the install ID. |

## 12. Release criteria

### v1 closed test
- [ ] Core requirements marked P0 for v1 meet their acceptance criteria.
- [ ] Field-tested on at least two vehicle types and at least three phone brands.
- [ ] Crash-free sessions at or above 99% during internal testing.
- [ ] Privacy policy published, Data safety form completed, content rating questionnaire done.
- [ ] If automatic trip start uses background location: permission declaration and demo video prepared.
- [ ] Store listing assets ready (icon, feature graphic, screenshots, description).
- [ ] Closed test with the required number of testers running for the required number of days. As currently understood, this is at least 12 testers for 14 continuous days for new personal developer accounts. Verify the current Play policy.
- [ ] OpenStreetMap attribution shown in the app.

### v2 and later
- [ ] Backend deployed with backups and monitoring.
- [ ] Abuse controls active (CM-5).
- [ ] Deletion flow verified end to end (PV-3).
- [ ] Legal review of the privacy policy completed.

## 13. Dependencies and assumptions

- OpenStreetMap data quality for India is good enough on major roads. Small lanes and new areas may be incomplete.
- Free hosting (Oracle Cloud Always Free) is available and sufficient for the pilot.
- Public geocoding services remain usable under fair-use limits with caching.
- Ferrostar and Valhalla integrate as expected for v3. Evaluate with a spike before committing.
- Users on phones with aggressive battery management will follow in-app guidance.

## 14. Open questions

1. Logo and branding. (Name resolved: MapMyRoad, package `com.mapmyroad`, ADR-0017.)
2. Should an optional "research mode" let users share raw sensor snippets to improve detection?
3. Which municipal complaint channels should be supported first in v4?
4. How should the public heatmap be gated at launch (minimum coverage per area)?
5. Are there license obligations for publishing derived open data on top of OpenStreetMap (ODbL)?
6. Hindi and Marathi translations: self-translated, or reviewed by native speakers?
