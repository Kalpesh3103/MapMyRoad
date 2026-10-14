# MapMyRoad tools

Python tools for milestone M1 (detection data spike):

- `traces/`: load raw sensor-logger sessions and convert them into anonymized detector
  fixtures (M1-8, format in `docs/TESTING_STRATEGY.md` section 3.1).
- `analysis/`: exploratory scripts for M1-4 (orientation normalization, filtering, label peaks).
- `tests/`: pytest tests on synthetic sessions only. No real data in this folder.

The Kotlin detector is the reference implementation. The Python code exists for exploration;
when thresholds change, update both and the findings doc (`docs/CODING_STANDARDS.md` section 6).

## Setup

Python 3.11 or newer.

```bash
cd tools
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
pytest
```

All commands below are run from the `tools/` directory.

## Converting a logger session into a fixture

```bash
python -m traces.convert_session path/to/session_dir --out path/to/private/fixtures \
    --name pune_route_a_scooter_phone_mount_01
```

Options:

| Option | Default | Meaning |
|---|---|---|
| `--trim-m` | 500 | Meters of travel dropped at each end of the trip (privacy). Do not go lower. |
| `--start-ms`, `--end-ms` | none | Narrow the window further, in session `t_ms` (the logger timeline). Can never re-include trimmed parts. |
| `--seed` | OS entropy | Seed for the coordinate offset, for reproducible drafts. Never store it next to the fixture. |
| `--downsample-hz` | none | Decimate (no anti-alias filter). This can change detector behaviour; compare with the full-rate output first. |
| `--force` | off | Overwrite existing output (for drafts only). |

What the converter does (details in the `traces/convert_session.py` docstring):

1. Drops the first and last 500 m of travel (haversine along GPS fixes).
2. Writes one row per accelerometer sample. Gyroscope and gravity are linearly interpolated at
   accelerometer timestamps when the bracketing samples are at most 100 ms apart, otherwise left empty.
   `lat, lon, speed_mps, accuracy_m` are sample-and-hold from the latest fix at or before the row
   (not interpolated), empty before the first fix.
3. Shifts all coordinates by one random offset of 1 to 5 km in a random direction.
4. Re-bases `t_ms` to 0 at the window start, integer milliseconds (round half to even).
5. Drops wall-clock times, bearing, altitude, the rotation vector, device info and the note.
   The labels file keeps only `vehicleType` and `phonePlacement`.
6. Turns tags into labels. `rough_patch_start`/`rough_patch_end` pairs become one `rough_patch`
   with `durationMs`; unmatched starts or ends are dropped with a warning.

It prints rows, duration, distance kept and label counts. It never prints coordinates.

Session input requirements: `sensors.csv` with `acc` samples, `location.csv` with enough fixes
to cover more than twice the trim distance, and `meta.json` with valid `vehicleType` and
`phonePlacement`. `tags.csv` is optional.

## Exploring a session or fixture

```bash
python -m analysis.explore_session path/to/session_dir        # raw session (private, local only)
python -m analysis.explore_session path/to/fixture.csv        # fixture (+ sibling .labels.json)
```

It saves `out/<name>_overview.png` (vertical acceleration raw and band-passed, speed, tag markers)
and prints the peak |filtered vertical acceleration| within plus or minus 1.5 s of each label and
the speed at the label. Filter cut-offs are initial values from `docs/ARCHITECTURE.md` section 5,
to be tuned in M1-4/M1-5.

Prefer scripts over notebooks. If you use a notebook, clear all outputs before committing and move
reusable logic into modules here, with tests.

## Where fixtures go

Once the detector module exists (M2-1), committed fixtures live in
`android/detector/src/test/resources/traces/` (`docs/TESTING_STRATEGY.md` section 3.1).
Until then, keep converted fixtures in the agreed private storage, not in Git.

## Privacy rules

- Never commit raw sessions. `raw/`, `sessions/`, `out/` and `*.zip` are git-ignored here, but keep
  raw data outside the repository anyway.
- Only converted, reviewed fixtures are committed. Check that the trip does not start or end near
  a home or work location even after trimming; trim more or pick a window if in doubt.
- Do not print, log or paste precise coordinates (including in issues and PRs).
- A fixture is never edited after it is committed. New observations make new fixtures.
