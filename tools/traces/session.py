"""Load a sensor-logger session directory into validated pandas DataFrames.

Expected layout (written by the debug ``sensorlogger`` Android app, M1-1)::

    <session_dir>/
        sensors.csv    t_ms,sensor,x,y,z,w        sensor in {acc, gyro, grav, rot}
        location.csv   t_ms,wall_ms,lat,lon,speed_mps,accuracy_m,bearing_deg,altitude_m
        tags.csv       t_ms,wall_ms,type,severity
        meta.json      vehicleType, phonePlacement, device info, ...

``t_ms`` is milliseconds since session start on the elapsed-realtime clock
(float). Only ``sensors.csv`` is strictly required by the loader; callers that
need location or metadata check for it themselves (see ``convert_session``).

Privacy: the loaded data contains precise coordinates and device details. Never
print or log them. Warning messages produced here only contain counts and
column names.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

SENSOR_TYPES: frozenset[str] = frozenset({"acc", "gyro", "grav", "rot"})
TAG_TYPES: frozenset[str] = frozenset({"pothole", "speed_breaker", "rough_patch_start", "rough_patch_end", "other"})

SENSORS_REQUIRED = ("t_ms", "sensor", "x", "y", "z")
LOCATION_REQUIRED = ("t_ms", "lat", "lon")
LOCATION_OPTIONAL = ("wall_ms", "speed_mps", "accuracy_m", "bearing_deg", "altitude_m")
TAGS_REQUIRED = ("t_ms", "type")
TAGS_OPTIONAL = ("wall_ms", "severity")


class SessionError(ValueError):
    """Raised when a session directory is missing files or contains unusable data."""


@dataclass
class Session:
    """A loaded logger session.

    Attributes:
        path: the session directory.
        sensors: long-format samples, columns ``t_ms, sensor, x, y, z, w``, sorted by
            (sensor, t_ms) with duplicate timestamps per sensor removed.
        location: valid fixes only (finite, in-range lat/lon, not 0/0), sorted by t_ms.
        tags: columns ``t_ms, wall_ms, type, severity`` sorted by t_ms (may be empty).
        meta: parsed meta.json (empty dict if absent).
        warnings: human-readable data-quality notes (never contain coordinates).
    """

    path: Path
    sensors: pd.DataFrame
    location: pd.DataFrame
    tags: pd.DataFrame
    meta: dict[str, Any]
    warnings: list[str] = field(default_factory=list)

    def sensor(self, name: str) -> pd.DataFrame:
        """Return samples of one sensor (``acc``, ``gyro``, ``grav``, ``rot``) as ``t_ms, x, y, z[, w]``.

        Returns an empty frame if the sensor is absent.
        """
        if name not in SENSOR_TYPES:
            raise ValueError(f"unknown sensor '{name}', expected one of {sorted(SENSOR_TYPES)}")
        cols = ["t_ms", "x", "y", "z"] + (["w"] if name == "rot" else [])
        df = self.sensors.loc[self.sensors["sensor"] == name, cols]
        return df.reset_index(drop=True)


def _read_csv(path: Path, required: tuple[str, ...], optional: tuple[str, ...] = ()) -> pd.DataFrame:
    try:
        df = pd.read_csv(path, dtype=str, keep_default_na=False, skipinitialspace=True)
    except pd.errors.EmptyDataError as exc:
        raise SessionError(f"{path.name} is empty (no header)") from exc
    except pd.errors.ParserError as exc:
        raise SessionError(f"{path.name} could not be parsed as CSV: {exc}") from exc
    df.columns = [c.strip() for c in df.columns]
    missing = [c for c in required if c not in df.columns]
    if missing:
        raise SessionError(f"{path.name} is missing required column(s) {missing}; found {list(df.columns)}")
    for col in optional:
        if col not in df.columns:
            df[col] = ""
    return df


def _to_num(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series.str.strip().replace("", np.nan), errors="coerce")


def _load_sensors(path: Path, warnings: list[str]) -> pd.DataFrame:
    if not path.is_file():
        raise SessionError(f"required file {path.name} not found in session directory")
    raw = _read_csv(path, SENSORS_REQUIRED, ("w",))
    df = pd.DataFrame({"sensor": raw["sensor"].str.strip().str.lower()})
    for col in ("t_ms", "x", "y", "z", "w"):
        df[col] = _to_num(raw[col])
    df = df[["t_ms", "sensor", "x", "y", "z", "w"]]

    unknown = ~df["sensor"].isin(SENSOR_TYPES)
    if unknown.any():
        names = sorted(set(df.loc[unknown, "sensor"]))[:5]
        warnings.append(f"sensors.csv: dropped {int(unknown.sum())} row(s) with unknown sensor type(s) {names}")
        df = df[~unknown]

    bad = df[["t_ms", "x", "y", "z"]].isna().any(axis=1) | ((df["sensor"] == "rot") & df["w"].isna())
    if bad.any():
        warnings.append(f"sensors.csv: dropped {int(bad.sum())} row(s) with missing or non-numeric values")
        df = df[~bad]

    if df.empty:
        raise SessionError("sensors.csv contains no usable samples")

    df = _sort_dedup(df, ["sensor"], "sensors.csv", warnings)
    return df


def _sort_dedup(df: pd.DataFrame, group: list[str], label: str, warnings: list[str]) -> pd.DataFrame:
    keys = group + ["t_ms"]
    if group:
        monotonic = all(g["t_ms"].is_monotonic_increasing for _, g in df.groupby(group, sort=False))
    else:
        monotonic = df["t_ms"].is_monotonic_increasing
    if not monotonic:
        warnings.append(f"{label}: timestamps were not monotonic; rows re-sorted by t_ms")
    df = df.sort_values(keys, kind="stable")
    dup = df.duplicated(subset=keys, keep="first")
    if dup.any():
        warnings.append(f"{label}: dropped {int(dup.sum())} row(s) with duplicate timestamps")
        df = df[~dup]
    return df.reset_index(drop=True)


def _load_location(path: Path, warnings: list[str]) -> pd.DataFrame:
    cols = list(LOCATION_REQUIRED) + list(LOCATION_OPTIONAL)
    if not path.is_file():
        warnings.append("location.csv not found; session has no GPS fixes")
        return pd.DataFrame({c: pd.Series(dtype=float) for c in cols})
    raw = _read_csv(path, LOCATION_REQUIRED, LOCATION_OPTIONAL)
    df = pd.DataFrame({c: _to_num(raw[c]) for c in cols})
    lat, lon = df["lat"], df["lon"]
    invalid = (
        df["t_ms"].isna()
        | lat.isna()
        | lon.isna()
        | (lat.abs() > 90)
        | (lon.abs() > 180)
        | ((lat == 0) & (lon == 0))
    )
    if invalid.any():
        warnings.append(f"location.csv: dropped {int(invalid.sum())} fix(es) with missing or invalid lat/lon/t_ms")
        df = df[~invalid]
    return _sort_dedup(df, [], "location.csv", warnings)


def _load_tags(path: Path, warnings: list[str]) -> pd.DataFrame:
    if not path.is_file():
        return pd.DataFrame(
            {"t_ms": pd.Series(dtype=float), "wall_ms": pd.Series(dtype=float),
             "type": pd.Series(dtype=str), "severity": pd.Series(dtype=float)}
        )
    raw = _read_csv(path, TAGS_REQUIRED, TAGS_OPTIONAL)
    df = pd.DataFrame(
        {
            "t_ms": _to_num(raw["t_ms"]),
            "wall_ms": _to_num(raw["wall_ms"]),
            "type": raw["type"].str.strip().str.lower(),
            "severity": _to_num(raw["severity"]),
        }
    )
    bad_t = df["t_ms"].isna()
    if bad_t.any():
        warnings.append(f"tags.csv: dropped {int(bad_t.sum())} tag(s) with missing t_ms")
        df = df[~bad_t]
    df = df.sort_values("t_ms", kind="stable").reset_index(drop=True)
    return df


def _load_meta(path: Path, warnings: list[str]) -> dict[str, Any]:
    if not path.is_file():
        warnings.append("meta.json not found")
        return {}
    try:
        meta = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise SessionError(f"meta.json is not valid UTF-8 JSON: {exc}") from exc
    if not isinstance(meta, dict):
        raise SessionError("meta.json must contain a JSON object at the top level")
    return meta


def load_session(session_dir: str | Path) -> Session:
    """Load and validate a logger session directory.

    Raises:
        SessionError: if the directory or ``sensors.csv`` is missing, a required
            column is absent, or no usable sensor samples remain.
    """
    path = Path(session_dir)
    if not path.is_dir():
        raise SessionError(f"session directory not found: {path}")
    warnings: list[str] = []
    sensors = _load_sensors(path / "sensors.csv", warnings)
    location = _load_location(path / "location.csv", warnings)
    tags = _load_tags(path / "tags.csv", warnings)
    meta = _load_meta(path / "meta.json", warnings)
    return Session(path=path, sensors=sensors, location=location, tags=tags, meta=meta, warnings=warnings)
