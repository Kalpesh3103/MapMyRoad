---
title: Privacy policy
permalink: /privacy/
---

<!--
DRAFT (task M0-7). This document needs legal review before publication (PRD section 8.2,
SECURITY.md section 14), including against India's Digital Personal Data Protection Act, 2023
and its rules as currently in force. It is not legal advice.

Before publishing, verify against the real app build:
- Firebase Analytics configuration (advertising ID collection disabled, no precise location in
  events, PRD section 9) and Crashlytics configuration (no coordinates or IDs in custom keys/logs).
- That the list of third parties matches the Play Data safety form (PRD section 8.1, M3-3).
- That backups are really disabled (allowBackup=false and data extraction rules, M2-19) and that
  a "delete all data" option exists in v1 settings (PRD PM-4 only guarantees per-trip deletion).
- Contact email, effective date and the data controller's name.
Update this policy whenever what the app collects, stores, shares or retains changes.
-->

# MapMyRoad privacy policy

**Draft — not yet in effect.**

Effective date: [EFFECTIVE DATE]

MapMyRoad is an Android app that detects potholes and rough road surfaces using your phone's
motion sensors and GPS while you travel, and shows the road quality of your own trips on a map.
This policy explains, in plain language, what data the app uses, why, where it is kept and how
you can delete it.

This policy covers the current version of the app (version 1, the "personal mapper"). In this
version **there is no MapMyRoad server, no account and no upload of your trips.**

## Summary

- No account. We never ask for your name, email address or phone number.
- Your trips, locations and detected potholes stay **on your phone**. We do not receive them.
- Raw motion sensor data is processed on the phone and is not stored or sent anywhere.
- We use Firebase Crashlytics (crash reports) and Firebase Analytics (app usage, **without your
  precise location**) to keep the app working and improve it.
- Map images are downloaded from OpenFreeMap, which sees your IP address and the map area you view.
- You can delete a single trip or all your data in the app at any time. Uninstalling the app
  also removes it.

## What we collect and why

### Precise location (during trips only)

- **What:** GPS location while a trip is being recorded.
- **Why:** to draw your route, measure distance and speed, and place detected potholes on your
  personal map.
- **Where it is kept:** only in the app's private storage on your phone. It is not sent to us.
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

### Crash reports (Firebase Crashlytics)

- **What:** information about app crashes and errors, such as the error details, app version,
  device model, Android version and a random installation identifier created by Firebase.
- **Why:** to find and fix bugs.
- **What it does not include:** your location, your trips or your sensor data. We do not put
  these into crash reports.

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

## What we do not collect

- No name, email address, phone number or login.
- No contacts, photos, files, microphone or camera access.
- No raw sensor recordings or full GPS traces leave your phone.
- No selling of data, and no advertising.

## How long data is kept

- **Trips, locations and detected potholes:** on your phone until you delete a trip, delete all
  data in the app, or uninstall the app. Uninstalling removes all app data from the phone. App
  backups to your Google account are disabled, so this data is not copied to the cloud.
- **Crash reports and analytics:** kept by Google (Firebase) for the periods set in Firebase. These
  are limited, and we will state the exact periods here before this policy takes effect.
  <!-- TODO(M0-7): fill in the configured Firebase Analytics retention and Crashlytics retention. -->

## Sharing

In this version we do not share or sell your data. The only third parties that receive any data
are the service providers listed above:

| Provider | Purpose | Data they receive |
|---|---|---|
| Google (Firebase Crashlytics) | Crash reporting | Crash details, device and app information, random installation ID |
| Google (Firebase Analytics) | Usage analytics | Usage events, device and app information, random app-instance ID, approximate region from IP |
| Google Play services | Location and optional activity recognition on Android | Handled by Google under its own privacy policy |
| OpenFreeMap | Base map tiles | IP address and requested map area |

We may disclose information if required by law. Because your trips are stored only on your phone,
we do not hold them and cannot disclose them.

## Your choices and deleting your data

- **Delete a trip:** open the trip history in the app and delete it.
- **Delete all data:** use the delete-all option in the app's settings.
- **Uninstall:** removes all app data stored on the phone.
- **Permissions:** you can withdraw location, activity recognition or notification permission at
  any time in Android settings. Recording trips needs location permission.
- **Questions or requests:** contact us at the address below, including if you have uninstalled the
  app and have a question about crash or analytics data.

## Children

MapMyRoad is not directed at children and is intended for people old enough to drive or ride
a vehicle. We do not knowingly collect data from children.

## Security

Trip data is kept in the app's private storage, which other apps cannot read. App backups are
disabled. Connections to the services listed above use encrypted HTTPS. No method of storage or
transmission is perfectly secure, but we design the app so that the most sensitive data, your
location history, never leaves your phone in this version.

## Planned for v2 (community features, opt-in) — NOT YET ACTIVE

*None of this happens in the current version. This section describes what we plan, so you know
in advance. Before any of it starts, this policy will be updated and the app will ask for your
consent first.*

A later version plans to let you **choose** to contribute detected potholes and road-roughness
measurements to a shared, community road-quality map and smoother route suggestions. If you opt
in:

- **Trimmed trips:** about the first and last 500 m of every trip are removed on your phone before
  anything is sent, so your home and work locations are not exposed.
- **Only results, not traces:** only detected events (for example a pothole's location and
  severity) and road-roughness summaries are uploaded. Raw sensor data and full GPS traces still
  never leave your phone.
- **Pseudonymous install ID:** contributions are linked to a random ID created by the app, not to
  your name, phone number or hardware identifiers. You will be able to reset it.
- **Detachment after about 90 days:** individual contributions are separated from the install ID
  after a fixed period (proposed 90 days). Combined, map-level road data remains without any
  identifier.
- **Delete my data:** an in-app option will delete your data on the phone and the server-side data
  linked to your install ID.
- Still **no account, email or phone number**.

## Changes to this policy

We will update this page when the app's data practices change, and change the effective date at
the top. For significant changes (such as starting the v2 community features) we will also tell
you in the app before the change applies.

## Contact

[CONTACT EMAIL, e.g. privacy@example.com]
