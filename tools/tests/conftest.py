"""Shared fixtures: synthetic logger sessions written to tmp_path (no real data)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Callable

import numpy as np
import pandas as pd
import pytest

from traces.geo import destination_point

# Synthetic origin. Not a real trip; any plausible coordinate works.
BASE_LAT = 18.5
BASE_LON = 73.8

SessionFactory = Callable[..., Path]


def synthetic_path(n_fixes: int, step_m: float) -> tuple[np.ndarray, np.ndarray]:
    """An L-shaped path: north for the first half of the fixes, then east."""
    lat = np.empty(n_fixes)
    lon = np.empty(n_fixes)
    lat[0], lon[0] = BASE_LAT, BASE_LON
    for i in range(1, n_fixes):
        bearing = 0.0 if i <= n_fixes // 2 else 90.0
        la, lo = destination_point(lat[i - 1], lon[i - 1], bearing, step_m)
        lat[i], lon[i] = float(la), float(lo)
    return lat, lon


def write_session(
    root: Path,
    *,
    n_fixes: int = 201,
    step_m: float = 10.0,
    fix_period_ms: float = 1000.0,
    acc_hz: float = 100.0,
    gyro_offset_ms: float = 3.0,
    grav_gap: tuple[float, float] | None = None,
    tags: list[tuple[float, str, str]] | None = None,
    meta: dict | None = None,
) -> Path:
    """Write a synthetic session directory and return its path.

    Fix i is at t = i * fix_period_ms with speed_mps = i (so sample-and-hold is
    easy to check). Gyro x = t_ms / 1000 (linear, so interpolation is exact).
    """
    root.mkdir(parents=True, exist_ok=True)
    duration_ms = (n_fixes - 1) * fix_period_ms
    lat, lon = synthetic_path(n_fixes, step_m)

    t_acc = np.arange(0.0, duration_ms + 1e-9, 1000.0 / acc_hz) + 0.25  # fractional ms like the logger
    t_gyro = t_acc + gyro_offset_ms
    t_grav = np.arange(0.0, duration_ms + 1e-9, 20.0)
    if grav_gap is not None:
        t_grav = t_grav[(t_grav < grav_gap[0]) | (t_grav > grav_gap[1])]

    frames = [
        pd.DataFrame({"t_ms": t_acc, "sensor": "acc", "x": 0.1, "y": 0.2, "z": 9.81, "w": np.nan}),
        pd.DataFrame({"t_ms": t_gyro, "sensor": "gyro", "x": t_gyro / 1000.0, "y": 0.0, "z": 0.0, "w": np.nan}),
        pd.DataFrame({"t_ms": t_grav, "sensor": "grav", "x": 0.0, "y": 0.0, "z": 9.81, "w": np.nan}),
        pd.DataFrame({"t_ms": t_acc[::2], "sensor": "rot", "x": 0.0, "y": 0.0, "z": 0.0, "w": 1.0}),
    ]
    sensors = pd.concat(frames).sort_values("t_ms", kind="stable")
    sensors.to_csv(root / "sensors.csv", index=False, na_rep="")

    t_fix = np.arange(n_fixes) * fix_period_ms
    pd.DataFrame(
        {
            "t_ms": t_fix,
            "wall_ms": 1_700_000_000_000 + t_fix,
            "lat": lat,
            "lon": lon,
            "speed_mps": np.arange(n_fixes, dtype=float),
            "accuracy_m": 5.0,
            "bearing_deg": 0.0,
            "altitude_m": 560.0,
        }
    ).to_csv(root / "location.csv", index=False)

    tag_rows = tags if tags is not None else []
    with (root / "tags.csv").open("w", encoding="utf-8") as fh:
        fh.write("t_ms,wall_ms,type,severity\n")
        for t, kind, sev in tag_rows:
            fh.write(f"{t},{1_700_000_000_000 + t},{kind},{sev}\n")

    meta_doc = {
        "vehicleType": "scooter",
        "phonePlacement": "mount",
        "device": {"manufacturer": "AcmePhones", "model": "SecretModel X1"},
        "samplingPeriodUs": 10000,
        "sensors": ["acc", "gyro", "grav", "rot"],
        "startWallMs": 1_700_000_000_000,
        "endWallMs": 1_700_000_000_000 + int(duration_ms),
        "note": "leaving home on Elm street",
    }
    if meta is not None:
        meta_doc.update(meta)
    (root / "meta.json").write_text(json.dumps(meta_doc), encoding="utf-8")
    return root


@pytest.fixture
def make_session(tmp_path: Path) -> SessionFactory:
    """Factory fixture: ``make_session(**kwargs)`` writes a synthetic session under tmp_path."""
    counter = {"n": 0}

    def _make(**kwargs) -> Path:
        counter["n"] += 1
        return write_session(tmp_path / f"session_{counter['n']}", **kwargs)

    return _make
