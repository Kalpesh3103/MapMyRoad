"""Convert a raw logger session into an anonymized detector fixture (M1-8).

Usage (run from the ``tools/`` directory)::

    python -m traces.convert_session <session_dir> --out <dir> --name <fixture_name>
        [--trim-m 500] [--start-ms T] [--end-ms T] [--seed N] [--downsample-hz HZ] [--force]

Produces ``<out>/<name>.csv`` and ``<out>/<name>.labels.json`` in the format of
TESTING_STRATEGY 3.1.

Processing, in order:

1. **Trim** (privacy): drop the first and last ``--trim-m`` meters of travel,
   measured by haversine along the GPS fixes. The kept window runs from the
   first fix at or beyond ``trim`` meters to the last fix at or before
   ``total - trim`` meters, so every written position is at least ``trim``
   meters along the path from the trip start and end.
2. **Optional window**: ``--start-ms`` / ``--end-ms`` are on the *session*
   timeline (the logger's ``t_ms``) and can only narrow the trimmed window,
   never widen it.
3. **Resample onto the accelerometer timeline**: one row per accelerometer
   sample. Gyroscope and gravity are linearly interpolated at each accelerometer
   timestamp when the two bracketing samples are at most ``MAX_INTERP_GAP_MS``
   apart; otherwise (gaps, or outside the sensor's time range) the cells are
   left empty. Location columns use sample-and-hold of the most recent fix at or
   before the row time (no interpolation, no staleness limit); empty before the
   first fix in the window.
4. **Shift coordinates** (privacy): every lat/lon is moved by the same random
   offset of 1 to 5 km in a random direction (a constant lat/lon delta computed
   at the first kept fix, which preserves relative geometry to well under 0.1%).
   The offset is drawn from ``--seed`` if given, else from OS entropy. Neither
   the offset nor the seed is written to any output or printed.
5. **Re-base time**: ``t_ms`` becomes integer milliseconds since the start of the
   kept window, rounded half-to-even (``numpy.rint``). Labels use the same rule.
6. **Drop identifying data**: wall-clock columns, bearing, altitude, the rotation
   vector, device info and the free-text note are not carried over. The labels
   file keeps only ``vehicleType`` and ``phonePlacement``.

Nothing printed by this module contains coordinates.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import pandas as pd

from traces.geo import cumulative_distance_m, destination_point
from traces.labels import Label, crop_and_rebase, tags_to_labels
from traces.session import Session, SessionError, load_session

FIXTURE_COLUMNS: tuple[str, ...] = (
    "t_ms", "ax", "ay", "az", "gx", "gy", "gz", "grav_x", "grav_y", "grav_z",
    "lat", "lon", "speed_mps", "accuracy_m",
)
VEHICLE_TYPES: frozenset[str] = frozenset({"motorcycle", "scooter", "car", "auto_rickshaw"})
PHONE_PLACEMENTS: frozenset[str] = frozenset({"mount", "pocket", "bag", "other"})

DEFAULT_TRIM_M = 500.0
#: Max spacing of the two bracketing gyro/gravity samples used for interpolation.
MAX_INTERP_GAP_MS = 100.0
#: Random coordinate offset range in meters (uniform), direction uniform in [0, 360).
OFFSET_MIN_M = 1_000.0
OFFSET_MAX_M = 5_000.0
#: Fixtures should be "a few minutes"; longer outputs get a warning.
LONG_FIXTURE_WARN_S = 10 * 60

# Output precision (decimal places). 1e-7 degrees is about 1 cm.
SENSOR_DECIMALS = 6
COORD_DECIMALS = 7
SPEED_DECIMALS = 3
ACCURACY_DECIMALS = 2

_NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_]{0,99}$")


class ConversionError(ValueError):
    """Raised when a session cannot be turned into a fixture."""


@dataclass(frozen=True)
class ConvertOptions:
    """Options for :func:`convert`. See the module docstring for semantics."""

    trim_m: float = DEFAULT_TRIM_M
    start_ms: float | None = None
    end_ms: float | None = None
    seed: int | None = None
    downsample_hz: float | None = None
    max_interp_gap_ms: float = MAX_INTERP_GAP_MS


@dataclass(frozen=True)
class ConversionStats:
    """Non-identifying summary of a conversion (safe to print)."""

    rows: int
    duration_s: float
    distance_kept_m: float
    trim_m: float
    labels_by_type: dict[str, int]
    approx_rate_hz: float | None


@dataclass
class ConversionResult:
    """Output of :func:`convert`.

    ``offset_deg`` is the (dlat, dlon) shift applied to coordinates. It exists
    only so tests can verify trimming on un-shifted positions. It must never be
    written, printed or logged.
    """

    name: str
    frame: pd.DataFrame
    labels_doc: dict[str, Any]
    stats: ConversionStats
    offset_deg: tuple[float, float] = field(repr=False)
    warnings: list[str] = field(default_factory=list)


# --------------------------------------------------------------------------- helpers


def validate_fixture_name(name: str) -> str:
    """Return ``name`` if it is a safe fixture name (lowercase letters, digits, underscores)."""
    if not _NAME_RE.match(name):
        raise ConversionError(
            f"invalid fixture name {name!r}: use lowercase letters, digits and underscores, "
            "e.g. pune_route_a_scooter_phone_mount_01"
        )
    return name


def crop_window(fix_t_ms: np.ndarray, cum_m: np.ndarray, trim_m: float) -> tuple[float, float]:
    """Return the (t_start, t_end) session-time window that excludes the first/last ``trim_m`` meters.

    ``t_start`` is the time of the first fix with cumulative distance >= ``trim_m``;
    ``t_end`` the time of the last fix with cumulative distance <= ``total - trim_m``.

    Raises:
        ConversionError: if the trip is too short to keep anything.
    """
    if trim_m < 0:
        raise ConversionError("trim distance must be >= 0")
    if len(fix_t_ms) < 2:
        raise ConversionError("need at least 2 valid GPS fixes to trim the trip; location.csv has fewer")
    total = float(cum_m[-1])
    if total <= 2 * trim_m:
        raise ConversionError(
            f"trip too short: {total:.0f} m of travel, need more than {2 * trim_m:.0f} m "
            f"to trim {trim_m:.0f} m from each end"
        )
    i0 = int(np.searchsorted(cum_m, trim_m, side="left"))
    i1 = int(np.searchsorted(cum_m, total - trim_m, side="right")) - 1
    if i1 <= i0:
        raise ConversionError("no GPS fixes remain between the trimmed start and end; record a longer trip")
    return float(fix_t_ms[i0]), float(fix_t_ms[i1])


def interpolate_gated(
    t_target: np.ndarray, t_src: np.ndarray, values: np.ndarray, max_gap_ms: float
) -> np.ndarray:
    """Linearly interpolate ``values`` (n x k) at ``t_target``.

    A target gets NaN if it lies outside ``[t_src[0], t_src[-1]]`` or if its two
    bracketing source samples are more than ``max_gap_ms`` apart. Exact
    timestamp matches always use the source value.
    """
    m, k = len(t_target), values.shape[1]
    out = np.full((m, k), np.nan)
    if len(t_src) == 0 or m == 0:
        return out
    for j in range(k):
        out[:, j] = np.interp(t_target, t_src, values[:, j])
    hi = np.searchsorted(t_src, t_target, side="left")  # first src >= target
    exact = (hi < len(t_src)) & (t_src[np.minimum(hi, len(t_src) - 1)] == t_target)
    inside = (hi > 0) & (hi < len(t_src))
    gap = np.full(m, np.inf)
    gap[inside] = t_src[hi[inside]] - t_src[hi[inside] - 1]
    ok = exact | (inside & (gap <= max_gap_ms))
    out[~ok] = np.nan
    return out


def sample_and_hold(t_target: np.ndarray, t_src: np.ndarray, values: np.ndarray) -> np.ndarray:
    """For each target time, take the most recent source row at or before it (NaN before the first)."""
    m, k = len(t_target), values.shape[1]
    out = np.full((m, k), np.nan)
    if len(t_src) == 0 or m == 0:
        return out
    idx = np.searchsorted(t_src, t_target, side="right") - 1
    has = idx >= 0
    out[has] = values[idx[has]]
    return out


def random_offset_deg(rng: np.random.Generator, ref_lat: float, ref_lon: float) -> tuple[float, float]:
    """Draw a constant (dlat, dlon) equivalent to a 1-5 km move in a random direction at the reference point."""
    distance = rng.uniform(OFFSET_MIN_M, OFFSET_MAX_M)
    bearing = rng.uniform(0.0, 360.0)
    lat2, lon2 = destination_point(ref_lat, ref_lon, bearing, distance)
    dlon = (float(lon2) - ref_lon + 540.0) % 360.0 - 180.0
    return float(lat2) - ref_lat, dlon


def downsample_indices(t_ms: np.ndarray, hz: float) -> np.ndarray:
    """Indices that keep the first sample in each ``1000/hz`` ms bin (plain decimation, no anti-alias filter)."""
    if hz <= 0:
        raise ConversionError("--downsample-hz must be > 0")
    if len(t_ms) == 0:
        return np.zeros(0, dtype=int)
    bins = np.floor((t_ms - t_ms[0]) / (1000.0 / hz)).astype(np.int64)
    _, first = np.unique(bins, return_index=True)
    return np.sort(first)


def _median_rate_hz(t_ms: np.ndarray) -> float | None:
    if len(t_ms) < 2:
        return None
    dt = float(np.median(np.diff(t_ms)))
    return 1000.0 / dt if dt > 0 else None


def _meta_field(meta: dict[str, Any], key: str, allowed: frozenset[str]) -> str:
    value = meta.get(key)
    if not isinstance(value, str) or value not in allowed:
        raise ConversionError(f"meta.json field '{key}' must be one of {sorted(allowed)}, got {value!r}")
    return value


# --------------------------------------------------------------------------- main API


def convert(session: Session, name: str, options: ConvertOptions | None = None) -> ConversionResult:
    """Convert a loaded session into an anonymized fixture held in memory.

    Raises:
        ConversionError: on missing metadata, too-short trips, empty windows or bad options.
    """
    opts = options or ConvertOptions()
    validate_fixture_name(name)
    warnings: list[str] = list(session.warnings)

    vehicle = _meta_field(session.meta, "vehicleType", VEHICLE_TYPES)
    placement = _meta_field(session.meta, "phonePlacement", PHONE_PLACEMENTS)

    acc = session.sensor("acc")
    if acc.empty:
        raise ConversionError("session has no accelerometer samples (sensor 'acc')")
    gyro = session.sensor("gyro")
    grav = session.sensor("grav")
    if gyro.empty:
        warnings.append("no gyroscope samples; gx/gy/gz will be empty")
    if grav.empty:
        warnings.append("no gravity samples; grav_x/grav_y/grav_z will be empty")

    loc = session.location
    fix_t = loc["t_ms"].to_numpy(dtype=float)
    lat = loc["lat"].to_numpy(dtype=float)
    lon = loc["lon"].to_numpy(dtype=float)
    cum = cumulative_distance_m(lat, lon)

    # 1. privacy trim
    trim_t0, trim_t1 = crop_window(fix_t, cum, opts.trim_m)

    # 2. optional narrower window (session time)
    t0, t1 = trim_t0, trim_t1
    if opts.start_ms is not None:
        if opts.start_ms < trim_t0:
            warnings.append("--start-ms lies inside the trimmed start; clamped to the trimmed window")
        t0 = max(t0, float(opts.start_ms))
    if opts.end_ms is not None:
        if opts.end_ms > trim_t1:
            warnings.append("--end-ms lies inside the trimmed end; clamped to the trimmed window")
        t1 = min(t1, float(opts.end_ms))
    if t1 <= t0:
        raise ConversionError("the requested window is empty after trimming; check --start-ms/--end-ms")

    # 3. accelerometer timeline
    acc_t = acc["t_ms"].to_numpy(dtype=float)
    keep = (acc_t >= t0) & (acc_t <= t1)
    acc_kept = acc.loc[keep].reset_index(drop=True)
    if acc_kept.empty:
        raise ConversionError("no accelerometer samples inside the kept window")
    t = acc_kept["t_ms"].to_numpy(dtype=float)
    native_hz = _median_rate_hz(t)

    if opts.downsample_hz is not None:
        warnings.append(
            f"downsampling to {opts.downsample_hz:g} Hz by decimation (no anti-alias filter); "
            "this can change detector behaviour, compare against the full-rate fixture"
        )
        if native_hz is not None and opts.downsample_hz >= native_hz:
            warnings.append(f"--downsample-hz is not below the native rate (~{native_hz:.0f} Hz); no effect")
        idx = downsample_indices(t, opts.downsample_hz)
        acc_kept = acc_kept.iloc[idx].reset_index(drop=True)
        t = t[idx]

    xyz = ["x", "y", "z"]
    gyro_v = interpolate_gated(t, gyro["t_ms"].to_numpy(dtype=float), gyro[xyz].to_numpy(dtype=float),
                               opts.max_interp_gap_ms)
    grav_v = interpolate_gated(t, grav["t_ms"].to_numpy(dtype=float), grav[xyz].to_numpy(dtype=float),
                               opts.max_interp_gap_ms)
    for label, arr in (("gyroscope", gyro_v), ("gravity", grav_v)):
        missing = int(np.isnan(arr[:, 0]).sum())
        if 0 < missing:
            warnings.append(f"{label}: {missing} of {len(t)} rows left empty (gap > "
                            f"{opts.max_interp_gap_ms:g} ms or outside sensor range)")

    # location: only fixes inside the trimmed window may be held
    in_trim = (fix_t >= trim_t0) & (fix_t <= trim_t1)
    fix_t_w = fix_t[in_trim]
    loc_vals = loc.loc[in_trim, ["lat", "lon", "speed_mps", "accuracy_m"]].to_numpy(dtype=float)
    held = sample_and_hold(t, fix_t_w, loc_vals)

    # 4. shift coordinates
    rng = np.random.default_rng(opts.seed)  # seed=None -> OS entropy
    ref = loc_vals[0]
    dlat, dlon = random_offset_deg(rng, float(ref[0]), float(ref[1]))
    held[:, 0] += dlat
    held[:, 1] += dlon

    # 5. re-base time
    t_out = np.rint(t - t0).astype(np.int64)
    if len(t_out) > 1 and (np.diff(t_out) <= 0).any():
        warnings.append("some rows share the same integer t_ms after rounding (sampling faster than 1 kHz?)")

    frame = pd.DataFrame(
        {
            "t_ms": t_out,
            "ax": acc_kept["x"].to_numpy(dtype=float).round(SENSOR_DECIMALS),
            "ay": acc_kept["y"].to_numpy(dtype=float).round(SENSOR_DECIMALS),
            "az": acc_kept["z"].to_numpy(dtype=float).round(SENSOR_DECIMALS),
            "gx": gyro_v[:, 0].round(SENSOR_DECIMALS),
            "gy": gyro_v[:, 1].round(SENSOR_DECIMALS),
            "gz": gyro_v[:, 2].round(SENSOR_DECIMALS),
            "grav_x": grav_v[:, 0].round(SENSOR_DECIMALS),
            "grav_y": grav_v[:, 1].round(SENSOR_DECIMALS),
            "grav_z": grav_v[:, 2].round(SENSOR_DECIMALS),
            "lat": held[:, 0].round(COORD_DECIMALS),
            "lon": held[:, 1].round(COORD_DECIMALS),
            "speed_mps": held[:, 2].round(SPEED_DECIMALS),
            "accuracy_m": held[:, 3].round(ACCURACY_DECIMALS),
        },
        columns=list(FIXTURE_COLUMNS),
    )

    # labels
    raw_labels, label_warnings = tags_to_labels(session.tags)
    warnings.extend(label_warnings)
    labels, crop_warnings = crop_and_rebase(raw_labels, t0, t1)
    warnings.extend(crop_warnings)
    labels_doc: dict[str, Any] = {
        "fixture": name,
        "vehicleType": vehicle,
        "phonePlacement": placement,
        "labels": labels,
    }

    # stats (no coordinates)
    in_win = (fix_t >= t0) & (fix_t <= t1)
    cum_w = cum[in_win]
    distance_kept = float(cum_w[-1] - cum_w[0]) if len(cum_w) >= 2 else 0.0
    duration_s = float(t_out[-1] - t_out[0]) / 1000.0 if len(t_out) else 0.0
    if duration_s > LONG_FIXTURE_WARN_S:
        warnings.append(
            f"fixture is {duration_s / 60:.1f} min long; fixtures should be a few minutes "
            "(use --start-ms/--end-ms to pick a window)"
        )
    stats = ConversionStats(
        rows=len(frame),
        duration_s=duration_s,
        distance_kept_m=distance_kept,
        trim_m=opts.trim_m,
        labels_by_type=dict(Counter(lab["type"] for lab in labels)),
        approx_rate_hz=_median_rate_hz(t),
    )
    return ConversionResult(
        name=name, frame=frame, labels_doc=labels_doc, stats=stats, offset_deg=(dlat, dlon), warnings=warnings
    )


def write_fixture(result: ConversionResult, out_dir: str | Path, force: bool = False) -> tuple[Path, Path]:
    """Write ``<name>.csv`` and ``<name>.labels.json`` into ``out_dir``. Returns both paths.

    Refuses to overwrite existing files unless ``force`` (fixtures are immutable once committed).
    """
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    csv_path = out / f"{result.name}.csv"
    json_path = out / f"{result.name}.labels.json"
    if not force:
        existing = [p.name for p in (csv_path, json_path) if p.exists()]
        if existing:
            raise ConversionError(f"refusing to overwrite {existing}; fixtures are immutable (use --force for drafts)")
    result.frame.to_csv(csv_path, index=False, na_rep="", lineterminator="\n")
    with json_path.open("w", encoding="utf-8", newline="\n") as fh:
        json.dump(result.labels_doc, fh, indent=2)
        fh.write("\n")
    return csv_path, json_path


def format_summary(result: ConversionResult) -> str:
    """Human-readable, coordinate-free summary of a conversion."""
    s = result.stats
    rate = f"~{s.approx_rate_hz:.0f} Hz" if s.approx_rate_hz else "n/a"
    by_type = ", ".join(f"{k}={v}" for k, v in sorted(s.labels_by_type.items())) or "none"
    return "\n".join(
        [
            f"fixture:        {result.name}",
            f"rows:           {s.rows} ({rate})",
            f"duration:       {s.duration_s:.1f} s",
            f"distance kept:  {s.distance_kept_m:.0f} m (trimmed {s.trim_m:.0f} m from each end)",
            f"labels:         {sum(s.labels_by_type.values())} ({by_type})",
            f"warnings:       {len(result.warnings)}",
        ]
    )


# --------------------------------------------------------------------------- CLI


def build_parser() -> argparse.ArgumentParser:
    """Argument parser for the converter CLI."""
    p = argparse.ArgumentParser(
        prog="python -m traces.convert_session",
        description="Convert a raw logger session into an anonymized detector fixture (TESTING_STRATEGY 3.1).",
    )
    p.add_argument("session_dir", type=Path, help="logger session directory (sensors.csv, location.csv, ...)")
    p.add_argument("--out", type=Path, required=True, help="output directory (keep outside Git until reviewed)")
    p.add_argument("--name", required=True, help="fixture name, e.g. pune_route_a_scooter_phone_mount_01")
    p.add_argument("--trim-m", type=float, default=DEFAULT_TRIM_M,
                   help=f"meters of travel dropped at each end (default {DEFAULT_TRIM_M:g}; do not go below 500)")
    p.add_argument("--start-ms", type=float, default=None, help="optional window start, session t_ms")
    p.add_argument("--end-ms", type=float, default=None, help="optional window end, session t_ms")
    p.add_argument("--seed", type=int, default=None,
                   help="seed for the coordinate offset (default: OS entropy). Never record it next to the fixture.")
    p.add_argument("--downsample-hz", type=float, default=None,
                   help="decimate to this rate (default: keep native rate). Can change detector behaviour.")
    p.add_argument("--force", action="store_true", help="overwrite existing output files")
    return p


def main(argv: Sequence[str] | None = None) -> int:
    """CLI entry point. Returns a process exit code."""
    args = build_parser().parse_args(argv)
    if args.trim_m < 500:
        print(f"warning: --trim-m {args.trim_m:g} is below the 500 m privacy default", file=sys.stderr)
    try:
        name = validate_fixture_name(args.name)
        session = load_session(args.session_dir)
        result = convert(
            session,
            name,
            ConvertOptions(
                trim_m=args.trim_m,
                start_ms=args.start_ms,
                end_ms=args.end_ms,
                seed=args.seed,
                downsample_hz=args.downsample_hz,
            ),
        )
        csv_path, json_path = write_fixture(result, args.out, force=args.force)
    except (SessionError, ConversionError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    for w in result.warnings:
        print(f"warning: {w}", file=sys.stderr)
    print(format_summary(result))
    print(f"wrote:          {csv_path.name}, {json_path.name} -> {csv_path.parent}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
