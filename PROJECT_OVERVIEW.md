# Road Quality Mapper: Project Overview

> **Working title.** Replace it once a final app name and Android package ID are chosen.
> **Status:** Draft v0.1, planning stage. Related docs: [PRD.md](PRD.md), [ARCHITECTURE.md](ARCHITECTURE.md).

## 1. Summary

Road Quality Mapper is an Android app that passively detects potholes and rough roads using the phone's motion sensors while the user travels. It builds a crowdsourced road-condition map and helps people choose smoother routes. Later versions add in-app navigation, hazard alerts and tools that turn the collected evidence into municipal complaints.

The app is built for publication on Google Play by a solo developer, using free and open-source tools only until the user base justifies paid services.

## 2. Problem

- Bad roads cost drivers and riders comfort, time, vehicle wear and safety, yet potholes and broken surfaces are reported manually or not at all.
- Navigation apps optimize for arrival time, not road condition, so people have no way to compare routes by smoothness.
- Residents and civic groups lack structured, location-accurate evidence to push for repairs.

## 3. Solution

1. **Passive detection.** Phone accelerometer and gyroscope data, combined with GPS, are processed on the device to detect bumps and estimate their severity.
2. **Crowdsourced map.** Anonymous events from many phones are aggregated into a road-condition heatmap and a confidence-scored hazard layer.
3. **Comfort-aware routing.** Route candidates are scored by road quality, so users can choose the fastest, the smoothest, or a balance.
4. **Civic impact (later).** Ready-to-send complaints, area report cards and open data exports.

## 4. Target users

| Group | What they need |
|---|---|
| Daily commuters on two-wheelers and cars (launch focus: Pune and Maharashtra) | Smoother routes, fewer jolts, a record of their commute quality |
| Delivery riders and drivers | Less vehicle wear and rider fatigue |
| Civic groups, journalists, NGOs | Credible location-based evidence on road condition |

## 5. What makes it different

- **Passive.** No manual reporting needed for the core data.
- **Comfort-based routing.** Routes are compared on road quality, not just time.
- **Useful from day one.** The first version works with zero other users (personal trip map and summaries), so launch is not blocked by a cold start.
- **Privacy-first.** Anonymous by default, no account required, trip start and end trimmed, deletion supported.
- **Free and open stack.** MapLibre, OpenStreetMap, Valhalla and PostGIS rather than paid map APIs.

## 6. Scope by phase

| Phase | Name | Main contents |
|---|---|---|
| **v1** | Personal mapper | Trip recording, bump and pothole detection, severity scoring, vehicle profiles, personal map, trip summary and history. Works fully offline, no backend. |
| **v2** | Community and route choice | Backend, shared heatmap and hazard layer, confirm or dismiss feedback, place search, route candidates with comfort scores, "Open in Google Maps" hand-off, Hindi and Marathi UI. |
| **v3** | Navigation and alerts | In-app turn-by-turn with voice, hazard proximity alerts, monsoon mode, photo reports. |
| **v4** | Civic impact | One-tap complaint generator, ward report cards, open data export, fixed-hazard tracking. |

Phases are ordered by dependency, not by dates. Each phase should be releasable on its own.

## 7. Principles and constraints

- **Free tools first.** Upgrade a component only when a measured trigger is hit (see ARCHITECTURE.md, section 14).
- **Solo developer.** Prefer a simple monolith and boring technology over clever infrastructure.
- **Privacy by design.** Process sensor data on the device, upload only events, keep raw location traces off the server.
- **Play Store compliance from the start.** Foreground-only tracking is the default. Background location is requested only for users who opt in to automatic trip start.
- **Honest scope.** The app is a best-road tool, not a fastest-arrival tool, because there is no free live-traffic data.

## 8. Technology at a glance

| Layer | Choice |
|---|---|
| Android app | Kotlin, Jetpack Compose, Room, WorkManager, Hilt, Retrofit |
| Sensing and location | SensorManager, Fused Location Provider, Activity Recognition API, foreground service |
| Map and navigation | MapLibre Native, Ferrostar (v3), Android TextToSpeech |
| Backend | Spring Boot, Spring Data JPA with Hibernate Spatial, Flyway |
| Database | PostgreSQL with PostGIS |
| Routing and map-matching | Valhalla (self-hosted) on an OpenStreetMap India extract |
| Place search | Photon (public API with caching at first, self-hosted later) |
| Hosting | Oracle Cloud Always Free VM (or a local machine in development), Docker Compose, Caddy |
| Monitoring | Firebase Crashlytics and Analytics, Spring Boot Actuator |

## 9. Success metrics (initial hypotheses)

Detailed targets live in the PRD. In short:

- Detection quality on a surveyed test route is good enough to trust (precision and recall targets in the PRD).
- Battery impact stays low enough that users keep tracking enabled.
- Crash-free sessions of at least 99%.
- A pilot in Pune produces enough mapped kilometers to make the heatmap useful on common commute corridors.

## 10. Key risks

| Risk | Impact | Mitigation |
|---|---|---|
| Detection produces too many false positives (speed breakers, phone handling) | Users lose trust | Speed and GPS gating, OSM traffic-calming tags, repeat-location logic, field calibration per vehicle type |
| Aggressive battery management on some Android phones kills the tracking service | Missing trips | Foreground service, in-app guidance for battery settings, clear notification |
| OSM data gaps in India hurt search and last-mile routing | Weak navigation | Keep "Open in Google Maps" fallback, delay in-app navigation to v3 |
| No live traffic data | ETAs less accurate than Google Maps | Position as best-road tool, show ETA as typical rather than live |
| Cold start for the community map | Empty heatmap | v1 is useful alone, seed through a Pune pilot and testers |
| Play review of location permissions | Delayed release | Foreground-first design, background location only for opt-in, prepare the declaration and demo video early |
| Privacy and legal exposure from location data | Compliance and trust issues | Data minimization, trimming, deletion, written privacy policy, legal review before v2 |
| Single free VM has no redundancy | Server outages | App keeps working offline, backups, uptime monitoring |

## 11. Immediate next steps

- [ ] Choose the app name and Android package ID.
- [ ] Set up the repository (Android app, backend, infra, docs).
- [ ] Record sensor traces on 2 to 3 real drives with manually tagged potholes, to calibrate detection.
- [ ] Build the v1 tracking service and detector, then test on at least two vehicle types.
- [ ] Create the Play Console listing, privacy policy page (GitHub Pages) and start recruiting closed-test testers early (Play currently requires a closed test with at least 12 testers for 14 continuous days for new personal accounts; verify the current rule).
- [ ] Try signing up for an Oracle Cloud Always Free VM early, since capacity can be limited in some regions.

## 12. Open questions

- App name, icon and branding.
- Whether to support raw-sensor sharing as an optional "research mode" for improving the detector.
- Which city municipal complaint channels to support first (v4).
- Monetization: deferred. The app stays free while the user base is built.
- Legal review of the privacy policy and of OpenStreetMap (ODbL) obligations before publishing open data exports.

## 13. Document index

| Document | Purpose |
|---|---|
| [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) | This file: what and why |
| [PRD.md](PRD.md) | Product requirements: users, features, acceptance criteria |
| [ARCHITECTURE.md](ARCHITECTURE.md) | System design: components, data model, flows, deployment |
