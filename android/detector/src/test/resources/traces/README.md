# Detector trace fixtures

Recorded, anonymized drive traces for the detector regression test (`DetectorRegressionTest`, task M2-20).
The format and rules are in `docs/TESTING_STRATEGY.md` section 3.1. This folder is empty until the M1-8 recordings
exist; until then the regression gate runs only the synthetic scenarios.

Each fixture is a pair:

- `<name>.csv`: optional `#` comment lines, then the header
  `t_ms,ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z,lat,lon,speed_mps,accuracy_m` and one row per sensor sample.
  Leave the `grav_*` columns empty if the phone has no gravity sensor, and the GPS columns empty (or repeated)
  between fixes. A new fix is replayed whenever the GPS columns change.
- `<name>.labels.json`: `{ "fixture": "...", "vehicleType": "scooter", "phonePlacement": "mount", "labels": [ { "tMs": 183400, "type": "pothole", "severity": 3 } ] }`.
  Label types: `pothole` (with `severity` 1 to 3), `speed_breaker`, `rough_patch` (with `durationMs`).

Rules: anonymize coordinates (fixed random offset, trimmed trip ends, no home or work locations), keep files small,
and never edit a committed fixture. When the first fixtures are added, add `fixtures.all.<metric>` keys to
`../detector-baseline.properties` in the same PR, otherwise the regression test fails on purpose.
