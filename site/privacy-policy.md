---
title: Privacy policy
permalink: /privacy/
---

<!--
DRAFT (tasks M0-7, updated for v2 in M5-10). This document needs legal review before publication
(PRD section 8.2, SECURITY.md section 14), including against India's Digital Personal Data
Protection Act, 2023 and its rules as currently in force. It is not legal advice.

Before publishing, verify against the real app build and the production server:
- Firebase Analytics configuration (advertising ID collection disabled, no precise location in
  events, PRD section 9) and Crashlytics configuration (no coordinates or IDs in custom keys/logs).
- That the list of third parties matches the Play Data safety form (PRD section 8.1).
- That backups are really disabled on the phone (allowBackup=false and data extraction rules).
- The server retention job (DATABASE_SCHEMA.md A10) runs with the periods stated below, and the
  access log retention configured in Caddy (SECURITY.md section 12, proposed 14 days).
- That "Delete my data" (DELETE /v1/devices/me) removes all server rows tied to the install
  (PRD PV-3 acceptance) and that the aggregated map is recomputed afterwards.
- The hosting provider and backup storage actually used (TECH_STACK.md: Oracle Cloud, Cloudflare R2
  or Oracle Object Storage), their data locations, and whether search uses the public Photon
  service (komoot) or a self-hosted index.
- Contact email, effective date and the data controller's name.
Update this policy whenever what the app collects, stores, shares or retains changes.
-->

# MapMyRoad privacy policy

**Draft — not yet in effect.**

Effective date: [EFFECTIVE DATE]

MapMyRoad is an Android app that detects potholes and rough road surfaces using your phone's
motion sensors and GPS while you travel. It shows the road quality of your own trips on a map, and,
if you choose, lets you contribute to a shared road-quality map and find smoother routes. This
policy explains, in plain language, what data the app uses, why, where it is kept and how you can
delete it.

## Summary

- No account. We never ask for your name, email address or phone number.
- Your trips, full GPS traces and raw motion sensor data stay **on your phone**.
- **Contribute anonymously** is off unless you turn it on. When it is on, the app sends only the
  bumps and road roughness it detected, with their location, after removing about the first and
  last 500 m of every trip.
- Online features (community map, place search, routes, sharing) use a **random install ID**, not
  your identity. Shared data is separated from that ID after 90 days.
- We use Firebase Crashlytics (crash reports) and Firebase Analytics (app usage, **without your
  precise location**) to keep the app working and improve it.
- Map images come from OpenFreeMap, which sees your IP address and the map area you view.
- You can delete a trip, or all your data on the phone and on our server, in the app at any time.

## What we collect and why

### Precise location (during trips only)

- **What:** GPS location while a trip is being recorded.
- **Why:** to draw your route, measure distance and speed, and place detected potholes on your
  personal map.
- **Where it is kept:** in the app's private storage on your phone. Your full route is never sent
  to us. If you contribute anonymously, only the locations of detected bumps and road-roughness
  measurements are sent (see below).
- **When:** only while a trip is recording. If you turn on the optional automatic trip start, the
  app may use location in the background to record trips you did not start by hand. This is off
  by default and the app asks you before enabling it. A notification is always shown while a trip
  is recording.

### Motion sensor data

- **What:** accelerometer and gyroscope readings during a trip.
- **Why:** to detect bumps and potholes and rate how smooth a road is.
- **Where it is kept:** processed on your phone as you travel. Raw sensor readings are **not
  stored and not sent anywhere**. Only the results (for example "moderate pothole at this point of
  your trip" and smoothness scores) are saved with your trip on the phone.

### Physical activity (optional)

If you turn on automatic trip start, the app uses Android's activity recognition to notice when
you start and stop travelling in a vehicle. This is processed on the phone and not sent to us.

### Contributed road data (only when "Contribute anonymously" is on)

"Contribute anonymously" is **off by default**. The app asks for your consent before turning it
on, and you can turn it off at any time in Settings. When it is on:

- **What is sent:** detected bumps and potholes (location, time, severity, speed, direction of
  travel and your vehicle type) and road-roughness measurements over short stretches of road (about
  50 m), from finished trips. Uploads happen in the background, on Wi-Fi only unless you allow mobile data.
- **Trimmed trips:** about the first and last 500 m of every trip are removed **on your phone
  before anything is sent**, so places such as your home and work are not exposed. Trips shorter
  than about 1 km send nothing.
- **What is never sent:** raw sensor data and full GPS traces.
- **Why:** to build a shared map of potholes and road quality and to score routes by smoothness.
  Individual contributions are combined with those of other users; the public map shows only
  combined results, and only where enough different devices agree.

### Pseudonymous install ID

- **What:** a random ID that our server gives the app the first time you use an online feature
  (the community map, place search, routes or sharing). It is not derived from your phone's
  hardware identifiers and is not linked to a name, email, phone number or account. The app stores
  it, with its access tokens, encrypted on your phone.
- **Why:** to prevent duplicates and abuse (for example one phone flooding the map), to apply rate
  limits, and to let you delete your data. To check that requests come from a genuine copy of the
  app, registration uses Google Play Integrity.
- **Detachment:** the install ID is removed from contributed road data after 90 days. A short-lived
  random trip token, used only to match a trip's points to roads, is cleared when matching is done
  and in any case within 24 hours. The public map and route scores never include the install ID.

### Feedback on potholes

When you answer whether a known pothole is still there or fixed (after a trip, never while
moving), your answer is sent with the install ID. The location you report is used only to check
that you were near the pothole and is not stored.

### Place search and routes

- **Search:** what you type is sent to our server, which looks it up with the Photon geocoder.
  Your current location, used to show nearby results first, is rounded to about 1 km before our
  server uses it or passes it on, so your exact position is never sent to the geocoder or stored.
  Search answers are cached on our server for about 7 days. When you name a dropped pin, its
  location is rounded to about 11 m before lookup.
- **Routes:** the start and end points of a route request are sent to our server to compute and
  score routes. **We do not store the start or end of route requests.**

### Crash reports (Firebase Crashlytics)

- **What:** information about app crashes and errors, such as the error details, app version,
  device model, Android version and a random installation identifier created by Firebase.
- **Why:** to find and fix bugs.
- **What it does not include:** your location, your trips, your sensor data or the install ID. We
  do not put these into crash reports.

### Usage analytics (Firebase Analytics)

- **What:** how the app is used, for example that a trip was started or a screen was opened, along
  with app version, device model, Android version, a random app-instance identifier and an
  approximate region (such as city or country) that Google derives from your IP address.
- **Why:** to understand which features work and improve the app.
- **What it does not include:** your precise location, your routes or the location of potholes.

### Map tiles (OpenFreeMap)

To show the base map, the app downloads map images ("tiles") from OpenFreeMap. Like any website,
OpenFreeMap receives your IP address and which map tiles are requested, which reveals the general
area you are viewing. The app does not send your trips or GPS position to OpenFreeMap. Map data
© OpenStreetMap contributors.

### Server access logs

Like most web services, our server keeps short access logs (for example time, requested address
and IP address [VERIFY: shortened or full IP, SECURITY.md section 12]) to keep the service secure
and working. They are set up not to record your location, search text or the install ID
[VERIFY: Caddy log format must drop query strings, since search and tile requests carry them].

## What we do not collect

- No name, email address, phone number or login.
- No contacts, photos, files, microphone or camera access.
- No raw sensor recordings or full GPS traces leave your phone.
- No hardware identifiers and no advertising ID.
- No selling of data, and no advertising.

## How long data is kept

| Data | Where | How long |
|---|---|---|
| Trips, locations and detected potholes | Your phone | Until you delete a trip, use "Delete my data", or uninstall the app. App backups to your Google account are disabled, so this data is not copied to the cloud. |
| Install ID on contributed bumps, roughness measurements and feedback | Our server | Removed after 90 days |
| Trip token used for matching to roads | Our server | Cleared after matching, at most 24 hours |
| Contributed bumps and roughness measurements (raw rows) | Our server | Deleted after 365 days |
| Combined map data (potholes, road quality) | Our server | Kept, without any install ID |
| An install that has not used the server for 12 months | Our server | Deleted, with everything still linked to it |
| Search answers cache | Our server | About 7 days |
| Route start and end points | Our server | Not stored |
| Server access logs | Our server | About 14 days |
| Crash reports and analytics | Google (Firebase) | For the periods set in Firebase |

<!-- TODO(M0-7): fill in the configured Firebase Analytics retention and Crashlytics retention, and confirm the access log period. -->

Encrypted database backups follow the same rules: deleted data disappears from backups when older
backups expire. [BACKUP RETENTION PERIOD]

## Sharing and service providers

We do not sell your data and do not share it for advertising. We use these service providers:

| Provider | Purpose | Data they receive |
|---|---|---|
| [HOSTING PROVIDER, e.g. Oracle Cloud] | Runs our server and database | Everything our server holds, as listed above |
| [BACKUP STORAGE, e.g. Cloudflare R2] | Encrypted database backups | Encrypted backup files only |
| Photon geocoder (komoot, or our own copy later) | Place search | Search text and a location rounded to about 1 km, sent by our server, not by your phone |
| OpenFreeMap | Base map tiles | IP address and requested map area |
| Google (Firebase Crashlytics) | Crash reporting | Crash details, device and app information, random installation ID |
| Google (Firebase Analytics) | Usage analytics | Usage events, device and app information, random app-instance ID, approximate region from IP |
| Google Play services | Location, optional activity recognition, Play Integrity check at registration | Handled by Google under its own privacy policy |

The public road-quality map and route scores are built only from combined data. They never show
individual contributions, times finer than a day, or the install ID.

We may disclose information if required by law, after checking that the request is valid, and
only the minimum required. We hold no names, emails or phone numbers, and contributed data loses
its install ID after 90 days. Your trips are stored only on your phone, so we cannot disclose them.

## Your choices and deleting your data

- **Stop contributing:** turn off "Contribute anonymously" in Settings. Nothing more is uploaded.
- **Delete a trip:** open the trip history in the app and delete it. This deletes it on the phone.
- **Delete my data:** in Settings, Privacy and data. This deletes everything tied to your install
  on our server (contributed bumps and roughness measurements, feedback, the install ID and its
  tokens) and then all trips and settings on your phone. The combined map is recalculated without
  your contributions shortly afterwards. If the app cannot reach our server, nothing is deleted and
  you can try again. You can also choose to delete only the data on the phone; the app then keeps
  its install ID so you can delete the server data later.
- **Uninstall:** removes all app data stored on the phone, including the install ID. Data already
  shared stays on our server until the retention periods above remove it. If you uninstalled the
  app and want us to delete server data, contact us at the address below. Because we do not know
  who you are, we may not be able to find your data without the install ID.
- **Permissions:** you can withdraw location, activity recognition or notification permission at
  any time in Android settings. Recording trips needs location permission.
- **Questions or requests:** contact us at the address below.

## Children

MapMyRoad is not directed at children and is intended for people old enough to drive or ride
a vehicle. We do not knowingly collect data from children.

## Security

Trip data is kept in the app's private storage, which other apps cannot read. App backups are
disabled. The install ID's tokens are stored encrypted with a key kept in Android's secure key
store. All connections use encrypted HTTPS. Our database is not reachable from the internet, and
backups are encrypted. No method of storage or transmission is perfectly secure, but we design the
app so that the most sensitive data, your location history, never leaves your phone.

## Changes to this policy

We will update this page when the app's data practices change, and change the effective date at
the top. For significant changes we will also tell you in the app before the change applies.

## Contact

[CONTACT EMAIL, e.g. privacy@example.com] — also for deletion requests from people who have
uninstalled the app.
