# UI and UX Guidelines

**Product:** Road Quality Mapper (working title)
**Status:** Draft v0.1
**Related:** [PRD.md](PRD.md), [ARCHITECTURE.md](ARCHITECTURE.md), [API_SPEC.md](API_SPEC.md), [DECISIONS.md](DECISIONS.md)

Colors, sizes and timings below are **initial values**. Validate them on real devices, in sunlight and at night, and with a color-blindness simulator before release. Brand name, logo and final palette are open (PRD open question 1).

---

## 1. Design principles

1. **Safe first.** People use this app while riding or driving. The app never asks for touch input while the vehicle is moving, and everything during a trip is glanceable or spoken.
2. **Passive by default.** The best interaction is none. Start, forget, review later.
3. **Honest about data.** Show how much data stands behind a score. Never present a weak guess as a fact.
4. **Never color alone.** Road quality and severity always carry a second cue (shape, pattern, label).
5. **Calm and trustworthy.** Plain language, no alarmism, no nagging.
6. **Works for the real context.** Bright sun, vibration, gloves, one hand, mid-range phones, patchy networks, three languages.
7. **Privacy is visible.** Users can always see what is collected, what is shared and how to delete it.

## 2. Users and context

| Persona | Context | Design consequence |
|---|---|---|
| Commuter Chetan | Scooter, phone in a mount or pocket, daily route | One-tap start (or auto-start), trip review after arrival |
| Delivery Divya | Motorcycle, all-day riding, low battery tolerance | Minimal screen use, tiny battery footprint, quick start |
| Civic Charu | Mostly stationary, reviews data | Rich map, filters, evidence export (v4) |

Context rules:
- **Two-wheelers:** the screen is often off or the phone is in a pocket. Audio and haptics matter more than visuals.
- **Cars:** a mounted phone gives a glance of 1 to 2 seconds at most.
- **Local law:** phone handling while driving is restricted in India. Do not design anything that encourages it.

## 3. Information architecture

```
Bottom navigation (4 tabs)
  Home     Start trip, vehicle, last trip, tips
  Map      My trips | Community (v2), search and routes (v2)
  Trips    History list, trip summary, delete
  Settings Vehicle, contribution, auto-start, language, privacy, about
```

| Screen | ID | Phase |
|---|---|---|
| Onboarding (welcome, vehicle, permissions) | S1 | v1 |
| Home | S2 | v1 |
| Live trip | S3 | v1 |
| Trip summary | S4 | v1 |
| Trips list | S5 | v1 |
| Map (personal) | S6 | v1 |
| Hazard detail sheet | S7 | v1 (own hazards), v2 (community) |
| Search and route candidates | S8 | v2 |
| Navigation | S9 | v3 |
| Settings | S10 | v1 |
| Privacy and data | S11 | v1, expanded v2 |
| Post-trip hazard review | S12 | v2 |
| Report and complaint | S13 | v3, v4 |
| Area report card | S14 | v4 |

## 4. Visual system

### 4.1 Color roles

Use Material 3 color roles with a custom scheme. Light and dark themes are both required (follow the system setting).

| Role | Light (initial) | Dark (initial) |
|---|---|---|
| Primary | `#1F5FA8` | `#A9C7FF` |
| On primary | `#FFFFFF` | `#00315F` |
| Surface | `#FAFAFA` | `#121316` |
| On surface | `#1A1C1E` | `#E3E2E6` |
| Error | `#B3261E` | `#F2B8B5` |

### 4.2 Road quality scale

Scores are 0 to 100, higher is smoother (API_SPEC: tile `score`). Three bands, each with a distinct lightness so they separate even in grayscale.

| Band | Score | Label | Color (initial) | Second cue |
|---|---|---|---|---|
| Good | 70 to 100 | Smooth | `#1B7F79` (deep teal) | Solid line |
| Fair | 40 to 69 | Rough | `#E0A100` (amber) | Dashed line |
| Poor | 0 to 39 | Very rough | `#B3261E` (deep red) | Dotted, thicker line |
| No data | none | No data yet | `#9AA0A6` at 40% opacity | Thin line |

Rules:
- Always show the legend on map screens, collapsible.
- For routes (v2), show the band as a segmented bar under the ETA, not only as route line color.
- A comfort score (0 to 100) is always paired with its data coverage when coverage is `partial` or `none` (API_SPEC route response), for example "Comfort 81 · limited data".

### 4.3 Hazard severity markers

| Severity | Shape | Color | Label |
|---|---|---|---|
| Minor (1) | Circle | Amber fill | Minor bump |
| Moderate (2) | Triangle | Orange fill | Pothole |
| Severe (3) | Octagon with `!` | Red fill, white glyph | Severe pothole |

Minimum marker size is 24 dp with a 48 dp touch target. Cluster markers when zoomed out and show the highest severity in the cluster. Confidence (community hazards) is shown as marker opacity (low confidence is lighter) and as text in the detail sheet.

### 4.4 Typography

- System default sans (Roboto on most Android phones) with Devanagari fallback for Hindi and Marathi. Use the Noto Sans Devanagari family if the device font is poor.
- Scale: display 36 sp (live trip numbers), title 22 sp, body 16 sp, label 14 sp, caption 12 sp (never for essential info).
- Support system font scaling up to 200% without clipping. Test layouts at 100%, 130% and 200%.

### 4.5 Shape, spacing, touch

- 8 dp spacing grid. Corner radius 12 dp for cards, 28 dp for primary buttons.
- Minimum touch target 48 dp. Primary actions (Start, Stop) are 64 dp tall or larger.
- Keep the primary action reachable with the thumb, in the lower third.

### 4.6 Iconography

Material Symbols for system actions. Custom icons only for hazard severity, vehicle types and the app icon. All icons need content descriptions.

### 4.7 Motion

Short (150 to 250 ms), purposeful, and respect the system "remove animations" setting. No animation on the live trip screen except the recording indicator.

## 5. Screen specifications

### S1 Onboarding

Three steps, skippable only where noted:

1. **What it does:** one illustration and two sentences. "Your phone feels the road. We turn bumps into a map."
2. **Vehicle:** four large cards (scooter, motorcycle, car, auto-rickshaw). Required, changeable later.
3. **Permissions:** each permission has its own screen with a plain explanation, shown just before the system prompt (PV-4).

Permission order: location while using the app, then notifications (Android 13 and later). Activity recognition and background location are asked only if the user turns on **Auto-start** in Settings or in an optional final step ("Start trips automatically?"). Declining must never block manual use.

Permission rationale copy (English, to translate):

| Permission | Headline | Body |
|---|---|---|
| Location | See where the road is rough | "We use your location during trips to place bumps on the map. It is only recorded while a trip is running." |
| Notifications | Know when a trip is recording | "Android requires a notification while we record. It also gives you a Stop button." |
| Activity | Start trips automatically | "We use motion detection to notice when you start driving. You can turn this off any time." |
| Background location | Record even when the app is closed | "To start a trip automatically, we need location access when the app is closed. Your location is never shared without your consent." |

### S2 Home

```
+--------------------------------------+
|  Good morning                  [gear]|
|                                      |
|   Vehicle:  [ Scooter v ]            |
|                                      |
|        +--------------------+        |
|        |                    |        |
|        |    START TRIP      |        |
|        |                    |        |
|        +--------------------+        |
|                                      |
|   Last trip                          |
|   12.4 km · Smoothness 72 · 3 bumps  |
|   [ View summary ]                   |
|                                      |
| [Home] [Map] [Trips] [Settings]      |
+--------------------------------------+
```

- If Auto-start is on, show a small status line: "Auto-start is on".
- If battery optimization may stop tracking (detected or reported), show a dismissible card linking to the battery guidance screen.
- Empty state (no trips yet): a short explainer and the Start button, no empty chart.

### S3 Live trip

Designed for a 1-second glance. Dark theme by default regardless of system theme, to reduce glare at night.

```
+--------------------------------------+
|  Recording            0:23:41        |
|                                      |
|   12.4 km                            |
|                                      |
|   Bumps   minor 5  rough 2  severe 0 |
|                                      |
|   GPS: good                          |
|                                      |
|   [ Pause ]          [ STOP ]        |
+--------------------------------------+
```

- No map, no scrolling, no text under 16 sp.
- The screen may turn off normally. Recording continues, and the notification carries Pause and Stop.
- State line shows "Recording", "Paused (stopped)" or "Waiting for GPS".
- Haptic tick on trip start and stop. No sound unless the user enables it.

### S4 Trip summary

- Header: date, duration, distance, vehicle.
- **Smoothness score** (0 to 100) with the band label and color, plus a text label (not color alone).
- Map of the trip, line colored by roughness band, with severity markers.
- Counts by severity.
- Actions: Delete trip, Share summary image (P2).
- v2: if contribution is on, a line "Shared anonymously" or "Waiting for Wi-Fi to share".
- v2: a **Review hazards** card (see S12).

### S5 Trips list

Chronological cards: date, route start and end area names when available, distance, score band. Swipe or overflow menu to delete, with a confirmation and undo for 5 seconds. Empty state explains how trips appear here.

### S6 Map

- Segmented toggle: **My trips** (v1) and **Community** (v2).
- Legend (collapsible), center-on-me button, attribution always visible (PV-7).
- Search field at the top in v2 (S8).
- Zoom-dependent detail: lines only at low zoom, markers from zoom 13 and above (API_SPEC tile layers).
- When Community data is thin in the viewed area, show an unobtrusive banner "Limited community data here" instead of an empty-looking map.

### S7 Hazard detail sheet

Bottom sheet from marker tap.

| Element | Content |
|---|---|
| Title | Severity label, for example "Severe pothole" |
| Facts | First seen, last seen, number of passes (own trips) or "Seen by N devices" (community) |
| Confidence (community) | Low, medium or high, with a one-line explanation |
| Actions (v2) | "Still there" and "Fixed", enabled only under the rules in section 6 |
| v4 | "Create complaint" |

### S8 Search and route candidates (v2)

```
+--------------------------------------+
| [ Where to?                       ]  |
|   recent / results                   |
+--------------------------------------+
| Map with up to 3 routes              |
+--------------------------------------+
| (o) Smoothest   27 min   10.1 km     |
|     Comfort 81  [====good======]     |
|     0 severe · 4 rough               |
| ( ) Fastest     23 min    9.2 km     |
|     Comfort 62  [==fair=|=poor=]     |
|     3 severe · 7 rough               |
|  Comfort <--------o------> Speed     |
|  [ Open in Google Maps ] [ Start ]   |
+--------------------------------------+
```

- Candidates are cards with ETA, distance, comfort score, hazard counts and the segmented quality bar.
- A route labeled both smoothest and balanced is shown once with both labels.
- Show the trade-off in words: "4 min slower, far fewer severe potholes" (computed, localized).
- `coverage` of `partial` or `none` shows "Limited data on this route" and softens the comfort display.
- ETA is labeled "typical" because there is no live traffic. Add a one-time explainer.
- **Open in Google Maps** is always available (RT-4). **Start** appears only when in-app navigation exists (v3).

### S9 Navigation (v3)

- Large next-maneuver banner (top), distance to maneuver, street name, ETA and remaining distance (bottom).
- Voice on by default, with a clear mute button.
- Hazard alerts follow section 7.
- Off-route: say "Rerouting" once, then show the new route. No modal dialogs.
- Exit navigation requires a deliberate action while stopped, or a long press while moving.

### S10 Settings

Grouped list: **Trips** (vehicle, auto-start, pause behavior), **Sharing** (contribute anonymously, Wi-Fi only), **Language**, **Privacy and data** (S11), **Help** (battery guidance, how detection works), **About** (version, OSM attribution, licenses).

### S11 Privacy and data

- Plain statements of what is collected (mirror the Data safety table in PRD section 8.1).
- Toggle: contribute anonymously (default off until the user opts in on first run).
- Button: **Delete my data**, with a confirmation that states exactly what will be deleted (local trips and shared data tied to this install). After deletion, show confirmation and return to onboarding.
- Link to the privacy policy.

### S12 Post-trip hazard review (v2)

A short, optional list shown after a trip ends, on the summary screen: "You passed 3 hazards we know about. Still there?" Each row has a map thumbnail and **Still there** or **Fixed** buttons, plus **Skip all**. This is the primary way to collect feedback (section 6).

### S13 Report and complaint (v3, v4)

- Report (v3): category chips, optional note (280 characters), optional photo via the system camera, location prefilled. Reports are private to the user.
- Complaint (v4): prefilled text with location, severity, number of detections and date range, an optional photo, and a link to the right municipal channel. The user reviews and sends from their own app. Nothing is submitted automatically.

### S14 Area report card (v4)

Area name, month, average comfort score, counts by severity, median days to fix, worst roads (only when `sufficient` is true), data-quality note, share button.

## 6. Safety rules for interaction

These are requirements, not suggestions.

1. **No touch input is required while moving.** Trip start and stop happen when stationary or through the notification. During navigation, all controls remain optional.
2. **Hazard feedback is never requested while the vehicle is moving.** The "still there or fixed" prompt (PRD CM-4) is delivered through the post-trip review (S12), or as a prompt that appears only when the vehicle has been stopped for several seconds. Voice-based confirmation is a possible later addition. This refines PRD CM-4 (see DECISIONS.md, ADR-0013).
3. **No pop-ups, dialogs or toasts during a trip.** The only visible change is the live trip screen. Errors become a persistent status line, not an interruption.
4. **Voice and haptics are short.** Maximum one short phrase at a time, never over a navigation instruction.
5. **Don't gamify driving.** Contribution stats and badges appear only in post-trip and profile areas, never as live scores that could encourage risky behavior (no "speed" or "streak" pressure).
6. **Passenger mode is not a loophole.** Do not add features that need interaction because "a passenger could do it".

## 7. Hazard alerts (v3)

| Parameter | Initial value |
|---|---|
| Trigger | Severe hazards (and moderate on request) with confidence at or above 0.5 |
| Lead time | Based on time, not distance: about 6 to 8 seconds ahead, minimum 80 m, maximum 250 m |
| Format | Short phrase ("Severe pothole ahead") plus a haptic pulse. Optional chime. |
| Frequency cap | No more than one alert per 15 seconds, no repeat for the same hazard within a trip |
| Priority | Never interrupts a navigation instruction. Queue and drop if stale. |
| Control | Settings toggle for voice, haptic and minimum severity, plus a one-tap mute on the navigation screen |

Tune with real drives to avoid alert fatigue. False alerts cost trust.

## 8. States and feedback

| State | Treatment |
|---|---|
| Loading | Skeleton placeholders, never a blank screen. Map shows the base map immediately. |
| Empty | One sentence on why, one action to fix it |
| Offline | Quiet "Offline" chip. Recording and local data keep working. Network features show retry options. |
| Permission denied | Explain what stops working and how to enable it, with a button to the system settings. Manual features remain available. |
| Permission permanently denied | Same, with the system settings shortcut |
| GPS weak | Live trip shows "Waiting for GPS". Events are not recorded without a good fix. |
| Service killed (detected) | On next launch: "Recording stopped unexpectedly" with a link to battery guidance. Save what was recorded. |
| Upload pending (v2) | Small "Waiting for Wi-Fi" status in the trip summary |
| Update required | Full screen with an Update button (API `426`) |
| Errors | Say what happened and what to do, no codes in the main message. Include a "Copy details" link for support. |

## 9. Content and tone

- Short, direct, friendly. No jargon like "accelerometer" in the main UI.
- Say "bumps" and "potholes" for users, "events" only in code.
- Avoid blame ("bad roads in your ward") in public-facing text. Use neutral, factual phrases ("Roughness is high on this stretch").
- Numbers: kilometers and meters, km/h, 24-hour or locale clock per system setting.
- All user-facing strings come from string resources (no hardcoded text), with plural forms.

## 10. Localization

- Launch language: English (v1). Hindi and Marathi in v2 (PRD PV-6).
- Allow 30 to 40% text expansion. Test long Marathi and Hindi strings in buttons and chips.
- Use Devanagari digits only if the user's locale prefers them. Otherwise use Western digits.
- Voice guidance language follows the app language when a TTS voice exists. Otherwise fall back to English and say so in Settings.
- Have translations reviewed by native speakers before release.
- No text inside images.

## 11. Accessibility

| Area | Requirement |
|---|---|
| Contrast | At least 4.5:1 for text, 3:1 for large text and map graphics against the base map |
| Color | Never the only cue (sections 4.2 and 4.3) |
| Touch | 48 dp minimum |
| Screen reader | Content descriptions on all controls and markers. Map regions summarized in text ("3 severe potholes in view"). Trip summary readable as text. |
| Font scaling | Up to 200% |
| Motion | Respect reduced motion |
| Focus order | Logical, visible focus for keyboard or switch users |
| Haptics | Not the sole channel for alerts |
| Testing | TalkBack pass, Accessibility Scanner, color-blindness simulation each release |

## 12. Map styling guidelines

- Base map: muted, low-saturation style so quality colors stand out. Light and dark variants.
- Quality lines draw above roads but below labels and markers.
- Line width scales with zoom. Poor roads are slightly thicker.
- Route candidates (v2) use distinct outline styles in addition to color, with the selected route on top.
- OpenStreetMap attribution is always visible and tappable.
- Offline basemap packs are not part of v1 or v2 scope.

## 13. Permissions and trust UX

- Request permissions in context, one at a time, with the rationale screen first.
- Never ask for background location during first-run unless the user opted in to Auto-start.
- A persistent, honest recording notification: "Recording road quality. Tap to open." with Pause and Stop actions.
- Show a one-time explanation of battery impact and the battery guidance screen before suggesting Auto-start.
- Before first upload, a one-time consent screen for contribution, with a plain explanation of trimming and anonymity.

## 14. Visual identity (open)

- Name, logo and tagline are not decided. Use the placeholder name and a neutral app icon until then.
- App icon: simple, readable at small sizes, adaptive icon (foreground and background layers), monochrome layer for themed icons.
- Avoid using road signs or government logos in the icon or marketing images.

## 15. Store listing and screenshots

- Eight screenshots: onboarding value statement, live trip, trip summary, personal map, community map, route candidates, privacy screen, language.
- Use real, plausible data from the pilot city, never fabricated user reviews or testimonials.
- Short description states what is collected and that it is anonymous. Keep it consistent with the Data safety form.

## 16. UX measurement

Events are listed in PRD section 9. UX checks to run during closed testing:
- Time from install to first recorded trip.
- Share of users who grant each permission, and drop-off at each onboarding step.
- Share of trips that end by manual stop, auto-stop or service kill.
- Qualitative: five-person walkthroughs on a real commute, noting any moment the person looked at the screen while moving.

## 17. Design handoff checklist (per screen)

- [ ] All states designed: loading, empty, error, offline, permission denied.
- [ ] Light and dark variants.
- [ ] 200% font scale layout checked.
- [ ] Hindi and Marathi strings fit.
- [ ] TalkBack labels and order defined.
- [ ] No input needed while moving (section 6).
- [ ] Analytics events defined, with no precise location.
- [ ] Strings externalized.
