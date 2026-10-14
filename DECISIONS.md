# Decision Log (ADRs)

**Product:** Road Quality Mapper (working title)
**Status:** Living document
**Related:** [ARCHITECTURE.md](ARCHITECTURE.md) (section 17 holds the short version of the first decisions), [TECH_STACK.md](TECH_STACK.md), [PROGRESS.md](PROGRESS.md), [AGENTS.md](AGENTS.md)

Architecture decision records capture **why** a significant choice was made, what else was considered and what it costs. Entries are never rewritten. To change a decision, add a new ADR that supersedes the old one and update the old status.

---

## How to use this file

**Write an ADR when:** a choice affects architecture, data, privacy, cost, a public API, a dependency with long-term impact, or scope. When in doubt, write a short one.

**Statuses:** `Proposed` (under discussion), `Accepted`, `Superseded by ADR-NNNN`, `Rejected`, `Deprecated`.

**Entries before implementation** are marked `Accepted (planning)`: they were proposed during planning and adopted as the working plan. Reconfirm them at the milestone where they first matter, and supersede them if reality disagrees.

**Template**

```
## ADR-NNNN: Title
- Status: Proposed | Accepted | Superseded by ADR-NNNN | Rejected | Deprecated
- Date: YYYY-MM-DD
- Decided by: name or role
- Related: tasks, docs

### Context
What problem or force makes this decision necessary? What constraints apply?

### Options considered
1. Option A: pros, cons
2. Option B: pros, cons

### Decision
What we chose, in one or two sentences.

### Consequences
What becomes easier, what becomes harder, what we must do or watch. When we should revisit.
```

## Index

| ADR | Title | Status | Date |
|---|---|---|---|
| 0001 | Native Android with Kotlin and Jetpack Compose | Accepted (planning) | 2026-10-13 |
| 0002 | MapLibre and OpenStreetMap instead of Google Maps | Accepted (planning) | 2026-10-13 |
| 0003 | Valhalla for routing and map-matching | Accepted (planning) | 2026-10-13 |
| 0004 | Comfort routing by candidate re-ranking | Accepted (planning) | 2026-10-13 |
| 0005 | Spring Boot modular monolith backend | Accepted (planning) | 2026-10-13 |
| 0006 | PostGIS as the only data store at first | Accepted (planning) | 2026-10-13 |
| 0007 | Anonymous install identity with Play Integrity, no accounts | Accepted (planning) | 2026-10-13 |
| 0008 | On-device detection, upload events only | Accepted (planning) | 2026-10-13 |
| 0009 | Manual trip start first, automatic start opt-in | Accepted (planning) | 2026-10-13 |
| 0010 | Offline-first v1 with no backend | Accepted (planning) | 2026-10-13 |
| 0011 | Free-tier-first infrastructure with defined upgrade triggers | Accepted (planning) | 2026-10-13 |
| 0012 | Manual reports and photos are private (v3) | Accepted (planning) | 2026-10-14 |
| 0013 | Hazard feedback is never requested while the vehicle is moving | Accepted | 2026-10-14 |
| 0014 | No certificate pinning initially | Proposed | 2026-10-14 |
| 0015 | Actuator on a separate internal management port | Accepted | 2026-10-14 |
| 0016 | Phase v1 ships without a backend and without in-app navigation | Accepted (planning) | 2026-10-13 |

---

## ADR-0001: Native Android with Kotlin and Jetpack Compose
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: Kalpesh (with planning assistant)
- Related: TECH_STACK.md section 3, M0-3

### Context
The product's value comes from continuous sensing in a foreground service, location handling, battery behavior and Play Store permission rules, all Android-specific. The developer's main experience is Java and Spring Boot.

### Options considered
1. **Kotlin and Compose (native):** first-class access to sensors and services, modern UI, strong tooling. New language and UI paradigm to learn, but Kotlin is close to Java.
2. **Java and XML views:** familiar language, but older UI toolkit and weaker support for new libraries (MapLibre, Ferrostar examples are Kotlin-first).
3. **Flutter or React Native:** cross-platform, but background sensing and foreground services need native plugins and are harder to debug.

### Decision
Native Android in Kotlin with Jetpack Compose. Java remains the language of the backend.

### Consequences
Faster progress on the risky Android parts, a learning curve for Kotlin and Compose in M0 to M2, no iOS version without a rewrite (accepted, out of scope). Revisit only if iOS becomes a goal.

## ADR-0002: MapLibre and OpenStreetMap instead of Google Maps
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: Kalpesh (free tools requirement), planning assistant
- Related: TECH_STACK.md section 5, M0-8

### Context
The project must run on free tools until the user base grows, and routing, tiles and data need to be ours to customize (comfort scores, hazard layers). Google Maps and Directions have billing, terms that restrict mixing results with non-Google maps, and no offline use.

### Options considered
1. **MapLibre Native with OSM data:** free, open, customizable styles. Data quality in India is uneven for small lanes, and tile hosting must be arranged.
2. **Google Maps SDK:** best polish and place data, but billing at scale and restrictive terms.
3. **Mapbox:** polished navigation SDK, paid at scale, data not ours to tune.

### Decision
MapLibre Native for rendering, OpenStreetMap as the data source, OpenFreeMap (with a Protomaps fallback) for base tiles.

### Consequences
No billing surprises and full control of the layers. Search and last-mile addresses are weaker than Google at first (see ADR-0016 for the fallback). OSM attribution is required everywhere. Revisit if data quality blocks the product.

## ADR-0003: Valhalla for routing and map-matching
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: planning assistant, confirmed by Kalpesh
- Related: ARCHITECTURE.md section 9, M4-2, M4-9, M4-14

### Context
We need route candidates for several vehicle types and, critically, map-matching to snap uploaded GPS windows to roads.

### Options considered
1. **Valhalla:** open source, motorcycle, scooter and auto costing, alternates, built-in map-matching (`trace_attributes`), navigation-friendly output. No native per-road custom costs.
2. **GraphHopper:** strong Java ecosystem and custom models, map matching is available but separate. Good fallback.
3. **OSRM:** very fast, but fewer profiles and less flexible for our needs.
4. **Google Directions:** terms and billing rule it out (ADR-0002).

### Decision
Valhalla, self-hosted, built on an OSM extract of the pilot area.

### Consequences
One engine covers routing and matching. Memory and tile build time must be planned on the ARM VM. Comfort scoring happens outside the engine (ADR-0004). Revisit with GraphHopper if alternates are too similar to be useful.

## ADR-0004: Comfort routing by candidate re-ranking
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: planning assistant
- Related: ARCHITECTURE.md section 9.1, API_SPEC.md section 5.8, M4-14

### Context
The differentiator is choosing smoother routes. Valhalla does not accept arbitrary per-road comfort scores in its standard costing.

### Options considered
1. **Re-rank Valhalla alternates** using hazards and roughness from PostGIS. Simple, uses the stock engine. Candidates may be similar.
2. **Custom edge costs inside the engine** (GraphHopper custom models or a forked Valhalla). Stronger effect but much more work.
3. **Exclude hazard areas** as hard constraints. Can make routes impossible and is crude.

### Decision
Request alternates, score each candidate against hazard and roughness data, label them fastest, smoothest and balanced, and report data coverage. Use exclusions only for rerouting around severe clusters with a safe fallback.

### Consequences
Fast to build. The smoothest route may not differ much on some trips, which the UI states honestly (coverage and similarity notices). Revisit if user feedback shows weak value.

## ADR-0005: Spring Boot modular monolith backend
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: Kalpesh
- Related: ARCHITECTURE.md section 6, CODING_STANDARDS.md section 4

### Context
One developer, existing Java and Spring Boot expertise, small initial scale, and a single small server.

### Options considered
1. **Modular monolith:** one deployable, simple operations.
2. **Microservices:** independent scaling, but operational overhead is not justified.
3. **Serverless or Firebase-only:** low ops but poor fit for spatial queries and map-matching pipelines.

### Decision
A single Spring Boot application with clear modules (ingest, hazards, segments, routing, geocode, tiles, device, ops).

### Consequences
Simple deploys and debugging. Modules must keep boundaries so parts can be split later if needed.

## ADR-0006: PostGIS as the only data store at first
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: planning assistant
- Related: DATABASE_SCHEMA.md, M4-5

### Context
The data is geospatial (points, lines, tiles, proximity queries) and relational (devices, feedback, aggregates).

### Options considered
1. **PostgreSQL with PostGIS only:** one system, strong spatial support, `ST_AsMVT` for tiles.
2. **Add Redis, Kafka or MongoDB early:** more components to run and secure.
3. **A managed database:** costs and data location questions.

### Decision
PostgreSQL with PostGIS on the same VM. Add a queue (such as Kafka) only when ingestion outgrows direct writes (upgrade trigger).

### Consequences
Few moving parts. The raw observation table can grow large (DATABASE_SCHEMA.md A12), so retention and partitioning choices are required before scaling.

## ADR-0007: Anonymous install identity with Play Integrity, no accounts
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: planning assistant, aligned with the privacy-first principle
- Related: API_SPEC.md section 2, SECURITY.md sections 4 and 5, M4-7

### Context
Location data is sensitive. Requiring accounts adds friction and personal data. We still need abuse control.

### Options considered
1. **Anonymous install ID plus Play Integrity attestation, rate limits and trust scores.**
2. **Email or phone login:** easier to ban abusers, but collects personal data and reduces adoption.
3. **No identity at all:** impossible to deduplicate or delete data on request.

### Decision
Random per-install device ID, short-lived JWTs, rotating refresh tokens, and Play Integrity at registration.

### Consequences
Low friction and low privacy risk. Abuse control relies on heuristics and attestation, which can fail on unusual devices (accepted with a lower trust score). Revisit if abuse becomes unmanageable.

## ADR-0008: On-device detection, upload events only
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: planning assistant
- Related: ARCHITECTURE.md section 5, SECURITY.md T1 and T3

### Context
Raw sensor streams are large and, combined with GPS, reveal movement patterns.

### Options considered
1. **Detect on the device, upload events and roughness windows.**
2. **Upload raw sensor data** and detect on the server. Easier to improve the detector, but heavy and invasive.
3. **Hybrid with opt-in raw snippets** for research.

### Decision
Detect on the device and upload only events and roughness windows, with trip ends trimmed. An opt-in research mode may be added later (backlog).

### Consequences
Better privacy and bandwidth. The detector cannot be retrained from production data without opt-in samples, so field data collection (M1) matters.

## ADR-0009: Manual trip start first, automatic start opt-in
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: planning assistant
- Related: PRD TS-1, TS-6, M2-14, M2-15

### Context
Auto-start needs background location, which draws stricter Play review and user suspicion, and Android 12 and later restricts starting foreground services from the background.

### Options considered
1. **Manual start by default, auto-start as an opt-in feature.**
2. **Auto-start by default.**
3. **Manual only.**

### Decision
Manual start is always available. Auto-start (Activity Recognition) is optional and requested only when the user turns it on.

### Consequences
Lower review risk and a simpler first release. Some users will forget to start trips, mitigated by auto-start where it works. A spike (M2-15) validates feasibility before building.

## ADR-0010: Offline-first v1 with no backend
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: planning assistant
- Related: PROJECT_OVERVIEW.md section 6, IMPLEMENTATION_PLAN.md strategy

### Context
A community map has a cold-start problem, and running a backend early adds work before the core detection is proven.

### Options considered
1. **v1 personal mapper without a backend,** then add community features.
2. **Backend from day one.**

### Decision
v1 works entirely on the device (trip recording, personal map, summaries). The backend arrives in v2.

### Consequences
Value from the first user, earlier Play testing, less early ops. Data starts flowing only after v2, so the pilot should be planned to seed data.

## ADR-0011: Free-tier-first infrastructure with defined upgrade triggers
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: Kalpesh
- Related: TECH_STACK.md, ARCHITECTURE.md section 14, DEPLOYMENT.md section 14

### Context
The project should not cost money until it has users.

### Options considered
1. **Free tiers and open source, with measurable triggers to upgrade.**
2. **Paid managed services from the start.**

### Decision
Use free tiers (Oracle Always Free VM, Firebase Spark, GitHub, free object storage tiers, public tile and geocoder services with fair use) and upgrade when a trigger fires.

### Consequences
Near-zero running cost. Free tiers can change or run out of capacity, and a single VM has no redundancy. Keep self-hosted fallbacks documented and review limits regularly.

## ADR-0012: Manual reports and photos are private (v3)
- Status: Accepted (planning)
- Date: 2026-10-14
- Decided by: planning assistant
- Related: API_SPEC.md section 5.10, SECURITY.md T13, M7-7

### Context
Public user photos need moderation and can expose people, plates and homes. A solo developer cannot moderate.

### Options considered
1. **Private reports used as evidence for complaints and as low-trust observations.**
2. **Public photo reports with moderation.**
3. **No manual reports.**

### Decision
Reports and photos are visible only to the reporter, with EXIF removed and server-side re-encoding. They may feed the hazard layer as low-trust observations.

### Consequences
No moderation burden and lower legal risk. The map loses user-submitted photos as public content. Revisit if there is a community and moderation capacity.

## ADR-0013: Hazard feedback is never requested while the vehicle is moving
- Status: Accepted
- Date: 2026-10-14
- Decided by: planning assistant (pending Kalpesh's confirmation in review)
- Related: PRD CM-4 (refined), UI_UX_GUIDELINES.md sections 5 (S12) and 6, M5-6

### Context
PRD CM-4 described a one-tap "still there or fixed" prompt as the user passes a hazard. Prompting a driver or rider in motion is unsafe, can violate local rules, and conflicts with the principle of no input while moving.

### Options considered
1. **Post-trip review list (S12), plus an optional prompt only after the vehicle has been stopped for several seconds.**
2. **Prompt while passing,** as originally written.
3. **Voice confirmation** while moving, which is hands-free but needs speech handling and still distracts.

### Decision
Feedback is collected after the trip or when stopped. CM-4 is refined accordingly. Voice confirmation goes to the backlog for later evaluation.

### Consequences
Safer and more defensible in Play review. Fewer and slightly delayed feedback signals. The "fixed" signal relies more on clean passes measured by sensors (DATABASE_SCHEMA.md A7). Update PRD CM-4 wording to match.

## ADR-0014: No certificate pinning initially
- Status: Proposed
- Date: 2026-10-14
- Decided by: pending
- Related: SECURITY.md T19 and section 18

### Context
Pinning protects against some interception attacks but can lock out all users if certificates or keys change incorrectly. The data sent is anonymous and trimmed, and a solo developer has limited operational slack.

### Options considered
1. **Standard TLS validation only.**
2. **Pin the public key with backup pins,** requiring careful rotation procedures.
3. **Pin via the network security config with an expiration date,** reducing lockout risk.

### Decision (proposed)
Start with standard TLS validation. Reconsider if there is evidence of interception or abuse, or if the data sensitivity increases (for example, accounts or photos).

### Consequences
Lower operational risk, slightly weaker protection against targeted interception on compromised devices or networks. Revisit before v3 (photos) and at each security review.

## ADR-0015: Actuator on a separate internal management port
- Status: Accepted
- Date: 2026-10-14
- Decided by: planning assistant
- Related: DEPLOYMENT.md sections 2 and 4, SECURITY.md section 9, API_SPEC.md section 5.13

### Context
API_SPEC.md said the health endpoint is "restricted by the proxy". Path-based blocking is easy to misconfigure.

### Options considered
1. **Separate management port bound to the Docker network, never published or proxied.**
2. **Expose Actuator under the main port and block paths at Caddy.**
3. **Secret path.**

### Decision
Run Actuator on its own port (8081) reachable only inside the Docker network. Metrics scraping and health checks happen from inside. External uptime monitors use `GET /v1/config`.

### Consequences
Smaller public attack surface. Dashboards need an SSH tunnel or an internal Prometheus. API_SPEC.md section 5.13 is updated to match.

## ADR-0016: Phase v1 ships without a backend and without in-app navigation
- Status: Accepted (planning)
- Date: 2026-10-13
- Decided by: Kalpesh and planning assistant
- Related: PROJECT_OVERVIEW.md section 6, PRD RT-4 and RT-5

### Context
In-app navigation needs reliable routing, search and rerouting, and OSM data in India has gaps. A wrong instruction costs far more trust than a missed hazard. There is also no live traffic data.

### Options considered
1. **Phase it:** route candidates with "Open in Google Maps" in v2, in-app turn-by-turn in v3 after the data and routing prove reliable.
2. **Ship turn-by-turn from the start.**
3. **Never build navigation,** and only hand off to other apps.

### Decision
Phase it as in option 1. The app is positioned as the best-road tool, not the fastest-arrival tool.

### Consequences
A weak navigation experience cannot sink the first releases. Users still switch apps for guidance until v3. Revisit after the Ferrostar spike (M4-18).

---

## Superseded and rejected decisions

None yet.
