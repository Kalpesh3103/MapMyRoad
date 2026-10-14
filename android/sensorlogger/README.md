# MMR Sensor Logger (task M1-1)

> **Debug-only field tool. Never publish this app on Google Play or any store.**
> It records raw sensor streams and precise GPS traces (SECURITY.md section 8).

A minimal Android app for the M1 calibration drives. It records accelerometer, gyroscope,
gravity, game rotation vector and GPS to CSV files, with large manual tag buttons, so the
detector can be calibrated and evaluated in M1-4 to M1-8.

Application ID: `com.mapmyroad.sensorlogger`. minSdk 26, targetSdk 35.

## Install

- Android Studio: select the `sensorlogger` run configuration and run it on a test phone.
- Command line (from `android/`): `gradlew :sensorlogger:installDebug` (Windows) or
  `./gradlew :sensorlogger:installDebug`.

On first start, grant precise location (required) and notifications (Android 13+, optional:
without it the recording notification and its Stop action are hidden, recording still works).

## Recording protocol (summary)

Full protocol: [TESTING_STRATEGY.md section 3.4](../../docs/TESTING_STRATEGY.md).

1. Pre-survey the route (potholes, speed breakers, rough stretches, positions).
2. In the app, pick the vehicle type and the phone placement (mount, pocket, bag, other). Add a
   note such as the route name.
3. Keep "Keep CPU awake" ON for calibration drives. Turn it OFF for battery measurement runs
   (M1-7, [TESTING_STRATEGY.md section 8.1](../../docs/TESTING_STRATEGY.md)), since the wake lock
   distorts the result.
4. Start recording, ride at several speeds (for example 20, 30, 40 km/h) across the same features.
5. **Tagging: use only when safe. A passenger should tag.** Tap the button for each event felt:
   POTHOLE Minor / Moderate / Severe, SPEED BREAKER, ROUGH START / ROUGH END, OTHER.
   The product rule "no touch input while moving" still applies to the main app.
6. Stop with the Stop button in the app or the notification.

## Output files

One directory per session:
`<external files dir>/sessions/<yyyyMMdd_HHmmss>_<vehicle>_<placement>/`
(local phone time; `<vehicle>` may itself contain an underscore, for example `auto_rickshaw`, so
read vehicle and placement from `meta.json`, not from the name). If a name already exists, `_2`,
`_3`, ... is appended.

### Time base

- `t_ms`: milliseconds since session start on the **elapsed-realtime** clock, written with exactly
  3 decimals (microsecond resolution), for example `1234.567`. Kept internally as integer
  microseconds.
- Assumption: `SensorEvent.timestamp`, `Location.elapsedRealtimeNanos` and
  `SystemClock.elapsedRealtimeNanos()` share the same elapsed-realtime base. This holds on modern
  devices. `meta.json` records `firstEventLagMs` per sensor (receive time minus event timestamp of
  the first event); a value far from a few milliseconds means the assumption is broken on that phone.
- Samples or fixes timestamped before the session start (for example a cached GPS fix) are dropped
  and counted in `meta.json` `droppedBeforeStart`.
- Wall clock: `meta.json` has `startWallMs` and `endWallMs` (epoch ms); `location.csv` and
  `tags.csv` carry `wall_ms` per row.

### sensors.csv

```
t_ms,sensor,x,y,z,w
```

| Column | Meaning |
|---|---|
| `sensor` | `acc` (TYPE_ACCELEROMETER, m/s^2, includes gravity), `gyro` (TYPE_GYROSCOPE, rad/s), `grav` (TYPE_GRAVITY, m/s^2), `rot` (TYPE_GAME_ROTATION_VECTOR, unitless quaternion) |
| `x,y,z` | Device axes. For `rot`: x*sin(θ/2), y*sin(θ/2), z*sin(θ/2) |
| `w` | Only for `rot` (cos(θ/2)); empty for other sensors |

Sampling period 10 000 us (100 Hz requested; actual rate depends on the phone). Not
SENSOR_DELAY_FASTEST, to stay under the 200 Hz limit that needs HIGH_SAMPLING_RATE_SENSORS.
`maxReportLatencyUs = 0` (no batching) for fidelity. Rows of different sensors are interleaved in
arrival order; sort by `t_ms` per sensor if needed.

### location.csv

```
t_ms,wall_ms,lat,lon,speed_mps,accuracy_m,bearing_deg,altitude_m
```

Fused Location Provider, PRIORITY_HIGH_ACCURACY, 1 s interval. `wall_ms` is `Location.getTime()`.
`speed_mps`, `accuracy_m`, `bearing_deg`, `altitude_m` are empty when the fix has no such value.

### tags.csv

```
t_ms,wall_ms,type,severity
```

`type` in `pothole`, `speed_breaker`, `rough_patch_start`, `rough_patch_end`, `other`.
`severity` is `1` minor, `2` moderate, `3` severe for potholes, empty otherwise. `t_ms` is the
moment the button was tapped. Tags are flushed to disk immediately.

### Number format

All numbers use `.` as decimal separator regardless of phone locale. Float values are written in
Java `Float.toString` form, which can use scientific notation for very small values (for example
`1.0E-4`); Python `float()` and pandas parse this. Line endings are `\n`, encoding UTF-8.

### meta.json

Written at start, rewritten every 3 seconds while recording and at stop (atomically via
`meta.json.tmp`). Fields: `schemaVersion`, `sessionId`, `appId`, `appVersionName`,
`appVersionCode`, `deviceManufacturer`, `deviceModel`, `androidSdkInt`, `androidRelease`,
`vehicleType`, `phonePlacement`, `note`, `keepCpuAwake`, `samplingPeriodUs`, `maxReportLatencyUs`,
`locationIntervalMs`, `timeBase`, `startElapsedRealtimeNanos`, `startWallMs`, `endWallMs` (null
until stopped), `durationMs`, `isComplete` (false if the recording did not stop normally),
`sensors` (per stream: `stream`, `available`, `registered`, `name`, `vendor`, `type`,
`resolution`, `maxRange`, `minDelayUs`, `firstEventLagMs`), `missingSensors`, `counts` (`acc`,
`gyro`, `grav`, `rot`, `location`, `tags`), `droppedBeforeStart` (`sensors`, `location`),
`locationRequestFailed`.

### Robustness

Files are flushed every 3 seconds, so if the system kills the app, all but the last few seconds
are kept (`isComplete` stays false). The service is not restarted automatically.

## Retrieve sessions

- In the app: "Recorded sessions", then Share (zips the session and opens the share sheet).
- Over USB: `adb pull /sdcard/Android/data/com.mapmyroad.sensorlogger/files/sessions`

## Privacy rules

- Session files contain **precise location** and can reveal home and work places.
- **Never commit raw sessions to Git.** Keep them on the analysis machine only.
- Convert them to anonymized fixtures with `tools/traces/convert_session.py` (coordinate shift,
  trim to the middle of the trip, TESTING_STRATEGY.md section 3.1) before anything is committed.
- Delete sessions from the phone and from shared drives when the analysis is done.
- The app has `allowBackup="false"` and excludes all data from backup and device transfer.
