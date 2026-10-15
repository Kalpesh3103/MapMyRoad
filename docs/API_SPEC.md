# API Specification

**Product:** MapMyRoad
**Status:** Draft v0.1
**Related:** [ARCHITECTURE.md](ARCHITECTURE.md), [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md), [PRD.md](PRD.md)

This is the human-readable contract between the Android app and the backend. springdoc-openapi generates the machine-readable `openapi.json`, and CI compares the two. Numeric limits are **initial values** to tune.

Phase tags: **v2** community and route choice, **v3** navigation and alerts, **v4** civic impact. Nothing here is needed for v1, which has no backend.

---

## 1. Conventions

| Item | Rule |
|---|---|
| Base URL | `https://api.example.com/v1` (placeholder domain) |
| Format | JSON, UTF-8, `application/json`. Tiles use `application/vnd.mapbox-vector-tile`. |
| Field names | camelCase |
| Timestamps | ISO-8601 in UTC, for example `2026-10-13T08:30:00Z` |
| Coordinates | `lat` and `lon` in decimal degrees (WGS 84). GeoJSON uses `[lon, lat]` order as the standard requires. |
| IDs | UUIDs. Events and windows use client-generated UUIDs. |
| Compression | Request bodies over 1 KB should be gzip-compressed (`Content-Encoding: gzip`). Responses are gzip-compressed when the client sends `Accept-Encoding: gzip`. |
| Required headers | `X-App-Version` (for example `1.0.3`) on every request except `/v1/tiles/**`, which the map library fetches on its own. `Authorization: Bearer <token>` where marked. |
| Optional headers | `X-Request-Id` (client-generated, echoed in responses and logs), `Accept-Language` |
| Region | The service accepts data only inside the configured supported region (see `GET /v1/config`). |

### 1.1 Enumerations

| Name | Values |
|---|---|
| `vehicleType` | `motorcycle`, `scooter`, `car`, `auto_rickshaw` |
| `severity` | `1` minor, `2` moderate, `3` severe |
| `hazardStatus` | `active`, `stale`, `fixed` |
| `feedbackAction` | `confirm`, `dismiss` |
| `routeLabel` | `fastest`, `smoothest`, `balanced` |
| `reportCategory` (v3) | `pothole`, `open_manhole`, `missing_signage`, `broken_divider`, `waterlogging`, `other` |

## 2. Authentication

The app is anonymous. Each install registers once and receives short-lived tokens bound to a random device ID.

1. App asks for a **challenge** (a one-time nonce).
2. App requests a **Play Integrity** token using that nonce.
3. App posts the integrity token to `POST /v1/devices`. The server verifies the verdict (genuine app installed from Google Play, genuine device) and creates the device.
4. Server returns an **access token** (JWT, about 1 hour) and a **refresh token** (opaque, about 30 days, stored hashed on the server).
5. When the access token expires, the app calls `POST /v1/devices/token` with the refresh token.

JWT claims: `sub` (device ID), `iat`, `exp`, `scope` (`device`). No personal data is in the token.

Integrity enforcement can be switched off with a server setting for development builds, never in production.

| Endpoint group | Auth |
|---|---|
| Config, tiles, TileJSON, challenge, registration | Public (rate limited per IP) |
| Everything else | Bearer access token |

## 3. Errors

Errors use RFC 9457 problem details (`application/problem+json`) with an extra stable `code` field for the app to branch on.

```json
{
  "type": "https://api.example.com/problems/rate-limited",
  "title": "Too many requests",
  "status": 429,
  "detail": "Retry after 120 seconds.",
  "code": "RATE_LIMITED",
  "retryAfterSec": 120
}
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `INVALID_REQUEST` | Malformed JSON or missing or invalid fields. `errors` lists field problems. |
| 401 | `UNAUTHORIZED` | Missing or invalid token |
| 401 | `TOKEN_EXPIRED` | Access token expired, refresh and retry |
| 403 | `INTEGRITY_FAILED` | Play Integrity verdict not acceptable |
| 404 | `NOT_FOUND` | Resource does not exist |
| 413 | `PAYLOAD_TOO_LARGE` | Body exceeds the limit |
| 422 | `UNSUPPORTED_REGION` | Coordinates outside the supported region |
| 422 | `TOO_FAR` | Feedback location too far from the hazard |
| 422 | `NO_ROUTE` | No route found between the points |
| 426 | `APP_UPDATE_REQUIRED` | `X-App-Version` below the minimum supported version |
| 429 | `RATE_LIMITED` | Rate limit hit. `Retry-After` header is set. |
| 500 | `INTERNAL` | Unexpected error |
| 503 | `UPSTREAM_UNAVAILABLE` | Valhalla or Photon unreachable. Safe to retry with backoff. |

## 4. Rate limits (initial values)

| Endpoint | Limit |
|---|---|
| `POST /devices/challenge`, `POST /devices` | 10 per hour per IP |
| `POST /devices/token` | 30 per hour per IP (the refresh call carries no access token, so the device is not known before the token is checked) |
| `POST /events` | 60 per hour per device |
| `GET /tiles/**` | 600 per minute per IP |
| `GET /hazards` | 60 per minute per device |
| `POST /hazards/{id}/feedback` | 30 per hour per device |
| `GET /geocode` | 5 per second burst, 300 per hour per device |
| `POST /routes` | 60 per hour per device |
| `POST /reports` (v3) | 20 per day per device |

## 5. Endpoints

### 5.1 Config (v2)

`GET /v1/config` (public)

Returns client-relevant settings so the app adapts without a new release.

```json
{
  "minSupportedAppVersion": "1.0.0",
  "supportedRegion": { "bbox": [72.6, 17.9, 75.1, 19.6] },
  "tileJsonUrl": "https://api.example.com/v1/tiles/tilejson.json",
  "baseMapStyleUrl": "https://tiles.openfreemap.org/styles/liberty",
  "retentionDays": 90,
  "limits": { "maxEventsPerRequest": 500, "maxWindowsPerRequest": 1000 },
  "features": { "navigation": false, "reports": false, "areas": false }
}
```

`bbox` is `[minLon, minLat, maxLon, maxLat]`. The values above are illustrative.

### 5.2 Devices (v2)

#### `POST /v1/devices/challenge` (public)

Response `200`:

```json
{ "nonce": "Yk3x...base64url...", "expiresInSec": 300 }
```

#### `POST /v1/devices` (public)

Request:

```json
{
  "integrityToken": "<Play Integrity token>",
  "nonce": "Yk3x...base64url...",
  "appVersion": "1.0.3",
  "platform": "android",
  "osApiLevel": 34
}
```

Response `201`:

```json
{
  "deviceId": "6f1f7a52-1e0e-4f0d-9d3c-1b2f6d9b8a10",
  "accessToken": "<jwt>",
  "accessExpiresInSec": 3600,
  "refreshToken": "<opaque>",
  "refreshExpiresInSec": 2592000
}
```

Errors: `403 INTEGRITY_FAILED`, `429 RATE_LIMITED`.

#### `POST /v1/devices/token` (refresh)

Request: `{ "refreshToken": "<opaque>" }`. Response `200`: new `accessToken`, `accessExpiresInSec`, and a rotated `refreshToken`. A reused or revoked refresh token returns `401 UNAUTHORIZED` and the app registers again.

#### `DELETE /v1/devices/me` (auth)

Deletes everything tied to the install: device record, refresh tokens, feedback, reports and raw events. Aggregated hazards and road statistics are recomputed shortly afterwards, without this device's contribution.

Response `204`. After this call the access token is invalid. The app clears local data and its stored tokens.

### 5.3 Event upload (v2)

#### `POST /v1/events` (auth)

Uploads detected events and road-roughness windows from one trip.

Request:

```json
{
  "tripToken": "0b7a4c35-3a2e-4b8f-a6f3-8d4f1f1c7e11",
  "vehicleType": "scooter",
  "events": [
    {
      "id": "d1f2c1c2-8b1e-4c2a-9a5b-1a0f9d8a7b61",
      "lat": 18.5204,
      "lon": 73.8567,
      "observedAt": "2026-10-13T08:30:12Z",
      "severity": 2,
      "speedKmh": 32.5,
      "peakScore": 2.8,
      "headingDeg": 187
    }
  ],
  "windows": [
    {
      "id": "a7c1b9d4-5f0e-4d8e-8c6b-2e7f3a9d1c20",
      "startLat": 18.5201,
      "startLon": 73.8565,
      "endLat": 18.5206,
      "endLon": 73.8568,
      "observedAt": "2026-10-13T08:30:10Z",
      "lengthM": 52,
      "roughness": 0.42,
      "speedKmh": 28.0
    }
  ]
}
```

Field notes:

| Field | Notes |
|---|---|
| `tripToken` | Random per trip. Used by the server only to group points for map-matching, then cleared. Never reused across trips. |
| `peakScore` | Unitless normalized value (already adjusted for speed and vehicle). Higher means a stronger jolt. |
| `roughness` | Unitless normalized roughness index for the window, 0 or greater. |
| `headingDeg` | 0 to 359, optional but recommended for map-matching. |
| `windows` | Consecutive roughness windows of about 50 m, ordered by time. |

Client rules:
- Trim the first and last **500 m** of every trip before uploading. Trips shorter than about 1 km upload nothing.
- Split large trips into several requests that share the same `tripToken`.
- Only upload when the user has opted in to contribute.

Server validation (any failure rejects that item, not the whole request):

| Check | Rule |
|---|---|
| Location | Inside the supported region |
| Speed | 0 to 200 km/h, and at least about 10 km/h for events |
| Timestamps | Not more than 5 minutes in the future, not older than 14 days |
| Severity | 1 to 3 |
| Window length | 20 to 120 m |
| Batch size | At most 500 events and 1000 windows per request, body at most 256 KB after decompression |

Response `202` (accepted for processing, map-matching and aggregation happen asynchronously):

```json
{
  "accepted": { "events": 12, "windows": 340 },
  "duplicates": { "events": 0, "windows": 0 },
  "rejected": [
    { "id": "d1f2c1c2-8b1e-4c2a-9a5b-1a0f9d8a7b61", "kind": "event", "reason": "IMPLAUSIBLE_SPEED" }
  ]
}
```

Rejection reasons: `OUT_OF_REGION`, `IMPLAUSIBLE_SPEED`, `FUTURE_TIMESTAMP`, `TOO_OLD`, `INVALID_SEVERITY`, `INVALID_WINDOW`.

Idempotency: items with an `id` that already exists are counted as duplicates and ignored, so retries are safe.

### 5.4 Tiles (v2)

#### `GET /v1/tiles/tilejson.json` (public)

Standard TileJSON describing the layers, zoom ranges and tile URL template, so MapLibre can add the source with one URL.

#### `GET /v1/tiles/{z}/{x}/{y}.mvt` (public)

A single tile that contains both layers below. Empty tiles return `204`.

| Layer | Geometry | Zoom range | Properties |
|---|---|---|---|
| `roads` | LineString | 12 to 16 (overzoomed above) | `score` (0 to 100, higher is smoother), `samples`, `devices`, `ageDays` (days since last observation) |
| `hazards` | Point | 13 to 18 | `id`, `sev` (1 to 3), `conf` (0 to 1), `status`, `seenDays` (days since last seen) |

Visibility rules enforced by the server (one implementation shared by tiles and the hazards API):
- Road segments appear only with at least 3 samples from at least 2 different devices, and at least 3 devices on `residential`, `service`, `living_street` and `unclassified` ways or when the way is unknown (SECURITY.md T2).
- Ways tagged `access=private` or `access=no` are excluded (SECURITY.md 6.4).
- Hazards appear only with confidence of at least 0.3, no suppression reason (for example, a known speed breaker), and the same device thresholds as road segments.
- Below zoom 12 tiles are always empty (`204`). The `roads` layer is also served above zoom 16. Hazard points are snapped to a 0.00001 degree grid.
- Fixed and stale hazards are excluded from the default tiles.

Default styling suggestion for the client: score 70 or above green, 40 to 69 yellow, below 40 red, with severity also shown by icon shape so color is never the only cue.

Caching: `Cache-Control: public, max-age=300` and an `ETag`. A CDN can be put in front later without changes.

### 5.5 Hazards (v2)

#### `GET /v1/hazards` (auth)

Query parameters:

| Parameter | Notes |
|---|---|
| `bbox` | Required. `minLon,minLat,maxLon,maxLat`. Maximum area about 0.1 by 0.1 degrees. |
| `minSeverity` | Optional, default 1 |
| `status` | Optional, default `active` |
| `limit` | Optional, default 200, maximum 500 |

Response `200` is a GeoJSON FeatureCollection:

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": { "type": "Point", "coordinates": [73.8567, 18.5204] },
      "properties": {
        "id": "4b1e9c5a-2d7f-4a39-9f55-0c3d7a2e8b14",
        "severity": 3,
        "confidence": 0.78,
        "status": "active",
        "firstSeen": "2026-09-02T07:10:00Z",
        "lastSeen": "2026-10-12T18:45:00Z",
        "confirmCount": 4,
        "dismissCount": 0
      }
    }
  ]
}
```

Used for hazard details and proximity alerts (v3). Map drawing uses tiles instead.

Precision rules (SECURITY.md 6.4): coordinates have 5 decimals (about 1 m), `firstSeen` and `lastSeen` are truncated to midnight UTC (for example `2026-09-02T00:00:00Z`), and `confidence` has 2 decimals. The `bbox` area is at most 0.01 square degrees (about 0.1 by 0.1).

#### `GET /v1/hazards/{id}` (auth)

Returns a single GeoJSON Feature as above. `404 NOT_FOUND` if unknown.

### 5.6 Hazard feedback (v2)

#### `POST /v1/hazards/{id}/feedback` (auth)

Request:

```json
{
  "action": "confirm",
  "lat": 18.52045,
  "lon": 73.85672,
  "observedAt": "2026-10-13T09:02:00Z"
}
```

Rules:
- The reported location must be within about 50 m of the hazard, otherwise `422 TOO_FAR`.
- One vote per device per hazard. A new vote replaces the previous one.
- `observedAt` may be at most 5 minutes in the future and at most 14 days old (`400` otherwise). The reported location is used only for the distance check and is not stored.
- Hazards that are hidden by the privacy rules return `404`. A hazard slightly below the display confidence, or marked fixed in the last 14 days, can still receive feedback.
- The response returns the stored values. Confidence is recomputed asynchronously within a few minutes.

Response `200`:

```json
{ "id": "4b1e9c5a-2d7f-4a39-9f55-0c3d7a2e8b14", "confidence": 0.81, "status": "active" }
```

### 5.7 Geocoding (v2)

#### `GET /v1/geocode` (auth)

| Parameter | Notes |
|---|---|
| `q` | Required, at least 3 characters |
| `lat`, `lon` | Optional bias point (usually the user's location) |
| `limit` | Optional, default 5, maximum 10 |
| `lang` | Optional, for example `en`, `hi`, `mr` |

Response `200`:

```json
{
  "results": [
    {
      "name": "Shaniwar Wada",
      "displayName": "Shaniwar Wada, Kasba Peth, Pune, Maharashtra",
      "lat": 18.5195,
      "lon": 73.8553,
      "type": "attraction"
    }
  ],
  "cached": true
}
```

Results are cached on the server. Errors: `503 UPSTREAM_UNAVAILABLE` when Photon cannot be reached.

Rules (M4-13):
- `q` is trimmed and whitespace runs are collapsed, then must be 3 to 200 characters. `limit` is 1 to 10. `lat` and `lon` are given together or not at all. `lang` is a language code such as `en` or `en-IN` (only the primary subtag is used). Violations give `400 INVALID_REQUEST` with `errors`.
- Results are restricted to the supported region (`GET /v1/config`). A bias point outside the region is ignored.
- The bias is rounded to 2 decimals (about 1 km) before it is used, so the exact user location is never sent upstream or stored. Answers are cached for 7 days, empty answers for 1 hour. `type` may be `null`.
- A `lang` the geocoder does not support is ignored and local names are returned.
- Rate limits are shared with `GET /v1/geocode/reverse` (section 4).

#### `GET /v1/geocode/reverse` (auth)

Parameters `lat` and `lon` (required) and optional `lang`. Returns the same shape as search, `{"results": [...], "cached": ...}`, with at most one entry (empty when nothing is nearby), used to name a dropped pin. The point is rounded to 4 decimals (about 11 m) before lookup. Points outside the supported region give `422 UNSUPPORTED_REGION`.

### 5.8 Route candidates (v2)

#### `POST /v1/routes` (auth)

Request:

```json
{
  "origin": { "lat": 18.5204, "lon": 73.8567 },
  "destination": { "lat": 18.5590, "lon": 73.7868 },
  "vehicleType": "scooter",
  "preference": 0.5
}
```

`preference` is from 0 (prefer fastest) to 1 (prefer smoothest) and decides which candidate is marked `recommended`.

Response `200`:

```json
{
  "requestId": "e8a3c0de-7b4a-4f55-8b62-0b2e4c1d9a77",
  "candidates": [
    {
      "id": "c1",
      "labels": ["fastest"],
      "durationSec": 1380,
      "distanceM": 9200,
      "comfortScore": 62,
      "hazards": { "severe": 3, "moderate": 7, "minor": 12 },
      "geometry": "<encoded polyline, precision 6>",
      "recommended": false
    },
    {
      "id": "c2",
      "labels": ["smoothest", "balanced"],
      "durationSec": 1620,
      "distanceM": 10100,
      "comfortScore": 81,
      "hazards": { "severe": 0, "moderate": 4, "minor": 9 },
      "geometry": "<encoded polyline, precision 6>",
      "recommended": true
    }
  ],
  "coverage": { "level": "partial", "fractionWithData": 0.64 }
}
```

Notes:
- A candidate can carry more than one label when the same route wins several categories.
- `coverage` tells the app how much of each route has road-quality data, so it can show a "limited data on this route" notice. A comfort score on a route with little data is not meaningful. `level` is `none`, `partial` or `good`.
- ETA is based on typical speeds, not live traffic.
- At most three candidates are returned. The server asks Valhalla for alternates, so sometimes only one or two distinct routes exist. Near-identical alternates (same shape, or distance and duration within 1%) are merged.
- `preference` is optional and defaults to 0.5. Origin and destination closer than 50 m return `400 INVALID_REQUEST`.
- Each candidate also carries its own `coverage` object. The top-level `coverage` is the coverage of the recommended candidate. `fractionWithData` has 2 decimals. `durationSec` and `distanceM` are integers.

Errors: `422 UNSUPPORTED_REGION`, `422 NO_ROUTE`, `503 UPSTREAM_UNAVAILABLE`.

The server does not store origin or destination coordinates of route requests.

### 5.9 Navigation route (v3)

#### `POST /v1/navigation/routes` (auth)

Returns a single route in a navigation-friendly format (Valhalla output in OSRM-compatible format, including banner and voice instructions) for the Ferrostar navigation SDK.

Request:

```json
{
  "mode": "new",
  "requestId": "e8a3c0de-7b4a-4f55-8b62-0b2e4c1d9a77",
  "candidateId": "c2",
  "origin": { "lat": 18.5204, "lon": 73.8567 },
  "heading": 187,
  "destination": { "lat": 18.5590, "lon": 73.7868 },
  "vehicleType": "scooter",
  "preference": 0.5,
  "language": "en"
}
```

| Field | Notes |
|---|---|
| `mode` | `new` for the first route after the user picks a candidate, `reroute` after leaving the route |
| `requestId`, `candidateId` | Used with `new` to return exactly the chosen candidate. The server keeps candidates for about 15 minutes. |
| `heading` | Used with `reroute` to prefer a route that continues in the direction of travel |

For `reroute`, the server recomputes candidates from the new position, applies the same scoring and returns the one that best matches `preference`. It may steer around severe hazards using Valhalla location or polygon exclusion, falling back to a normal route if exclusion makes the route impossible.

The response body is the Valhalla route in OSRM format, wrapped as `{ "route": { ... }, "comfortScore": 81, "hazards": { ... } }`.

### 5.10 Manual reports (v3)

#### `POST /v1/reports` (auth, `multipart/form-data`)

Parts:
- `metadata` (JSON): `{ "category": "open_manhole", "lat": 18.52, "lon": 73.85, "note": "No cover, near bus stop" }`
- `photo` (optional, JPEG, at most 2 MB)

Response `201`: `{ "id": "<uuid>", "visibility": "private" }`.

Reports and photos are **private to the reporter**. They are used as evidence when generating a complaint (v4) and are never shown on the public map. This avoids the moderation burden of public user-generated photos. A report may also feed the hazard layer as a low-trust observation.

`GET /v1/reports` returns the caller's own reports. `DELETE /v1/reports/{id}` removes one.

### 5.11 Areas and report cards (v4)

#### `GET /v1/areas` (auth)

Parameters: `lat`, `lon` (areas containing the point), or `bbox`. Returns a list of `{ id, name, level, parentId }`.

#### `GET /v1/areas/{id}/report-card` (auth)

Parameter `month` (for example `2026-10`, default is the latest complete month).

```json
{
  "area": { "id": "6a0d5c0e-3b3d-4c6a-8c0d-9a7b7e1d2f33", "name": "Ward X", "level": "ward" },
  "month": "2026-10",
  "roadKmCovered": 182.4,
  "averageComfortScore": 66,
  "activeHazards": { "severe": 21, "moderate": 64, "minor": 150 },
  "fixedHazards": 17,
  "medianDaysToFix": 23,
  "worstRoads": [
    { "name": "Example Road", "score": 31, "severeHazards": 6, "kmCovered": 2.3 }
  ],
  "dataQuality": { "distinctDevices": 48, "sufficient": true }
}
```

When data is too thin (fewer than a configured number of distinct devices or too little road covered), `sufficient` is `false` and ranking fields are omitted, so weak data is not presented as fact.

### 5.12 Civic channels and exports (v4)

- `GET /v1/civic-channels?areaId=...` returns curated complaint channels for an area: `{ name, kind, url, notes, verifiedAt }`. The app uses it to build the complaint draft and a link to the right portal. The complaint text itself is generated on the device from hazard data.
- `GET /v1/exports` lists pre-generated data files (for example monthly GeoJSON or CSV of hazards and road scores) with `url`, `layer`, `format`, `rowCount`, `createdAt` and license and attribution text. Files are served as static downloads, not generated per request.

### 5.13 Health

Spring Boot Actuator runs on a separate management port (8081) reachable only inside the Docker network. It is never published or proxied and is not part of the public API (ADR-0015). External uptime monitors use `GET /v1/config`.

## 6. Client behavior requirements

| Topic | Requirement |
|---|---|
| Retries | Exponential backoff with jitter for `5xx`, `429` (honor `Retry-After`) and network errors. No retry on other `4xx`. |
| Token handling | On `401 TOKEN_EXPIRED`, refresh once and retry the request. If refresh fails, register again. |
| Upload timing | Wi-Fi only by default, user can allow mobile data. |
| Upload size | Keep each request under the documented limits. Split larger trips. |
| Trimming | Apply the 500 m trim before building the request, not on the server. |
| Update gate | On `426 APP_UPDATE_REQUIRED`, show an update screen. |
| Offline | The app never blocks recording or viewing local data on network failure. |
| Logging | Do not log precise coordinates or tokens in release builds. |

## 7. Versioning and compatibility

- The path version (`/v1`) changes only for breaking changes.
- Adding optional fields or new endpoints is not breaking. Clients must ignore unknown fields.
- Deprecations are announced through `GET /v1/config` (`minSupportedAppVersion`) and a `Sunset` response header on deprecated endpoints, with at least one release cycle of overlap.

## 8. Contract testing

- Backend tests assert every example in this document against the real controllers (using MockMvc or RestAssured), so docs and code cannot drift silently.
- CI compares the generated `openapi.json` with a checked-in copy and fails on unreviewed changes.
- WireMock stubs stand in for Valhalla and Photon in tests.
