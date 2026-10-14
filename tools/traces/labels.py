"""Convert logger tags into fixture labels (TESTING_STRATEGY 3.1).

Logger tag types: ``pothole``, ``speed_breaker``, ``rough_patch_start``,
``rough_patch_end``, ``other``. Fixture label types: ``pothole``,
``speed_breaker``, ``rough_patch`` (with ``durationMs``), ``other``.

Rough-patch pairing: a ``rough_patch_start`` is closed by the next
``rough_patch_end``. A second start while one is open drops the earlier start;
an end without an open start is dropped; a start still open at the end of the
session is dropped. Each dropped tag produces a warning.
"""

from __future__ import annotations

import math
from typing import Any

import pandas as pd

Label = dict[str, Any]


def _severity(value: Any, t_ms: float, warnings: list[str]) -> int | None:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return None
    try:
        sev = float(value)
    except (TypeError, ValueError):
        warnings.append(f"tag at t_ms={t_ms:.0f}: non-numeric severity ignored")
        return None
    if sev in (1.0, 2.0, 3.0):
        return int(sev)
    warnings.append(f"tag at t_ms={t_ms:.0f}: severity {value!r} outside 1..3 ignored")
    return None


def _make(t_ms: float, kind: str, severity: int | None, duration_ms: float | None = None) -> Label:
    label: Label = {"tMs": float(t_ms), "type": kind}
    if severity is not None:
        label["severity"] = severity
    if duration_ms is not None:
        label["durationMs"] = float(duration_ms)
    return label


def tags_to_labels(tags: pd.DataFrame) -> tuple[list[Label], list[str]]:
    """Convert a tags DataFrame (``t_ms, type, severity``) into labels on the session timeline.

    Times are left in session milliseconds (floats); use :func:`crop_and_rebase`
    to clip to a window and re-base. Returns ``(labels, warnings)`` with labels
    sorted by ``tMs``.
    """
    labels: list[Label] = []
    warnings: list[str] = []
    open_start: tuple[float, int | None] | None = None

    ordered = tags.sort_values("t_ms", kind="stable") if len(tags) else tags
    for row in ordered.itertuples(index=False):
        t = float(row.t_ms)
        kind = str(row.type).strip().lower()
        sev = _severity(getattr(row, "severity", None), t, warnings)
        if kind in ("pothole", "speed_breaker", "other"):
            labels.append(_make(t, kind, sev))
        elif kind == "rough_patch_start":
            if open_start is not None:
                warnings.append(
                    f"rough_patch_start at t_ms={open_start[0]:.0f} has no matching end "
                    f"(another start at t_ms={t:.0f}); dropped"
                )
            open_start = (t, sev)
        elif kind == "rough_patch_end":
            if open_start is None:
                warnings.append(f"rough_patch_end at t_ms={t:.0f} has no matching start; dropped")
                continue
            start_t, start_sev = open_start
            labels.append(_make(start_t, "rough_patch", start_sev if start_sev is not None else sev, t - start_t))
            open_start = None
        else:
            warnings.append(f"tag at t_ms={t:.0f}: unknown type '{kind}'; dropped")
    if open_start is not None:
        warnings.append(f"rough_patch_start at t_ms={open_start[0]:.0f} has no matching end; dropped")

    labels.sort(key=lambda lab: lab["tMs"])
    return labels, warnings


def crop_and_rebase(labels: list[Label], t0_ms: float, t1_ms: float) -> tuple[list[Label], list[str]]:
    """Keep labels inside ``[t0_ms, t1_ms]`` and re-base times so ``t0_ms`` becomes 0.

    Point labels outside the window are dropped silently (they belong to the
    trimmed part of the trip). Rough patches that overlap the window are clipped
    to it, with a warning. Times are rounded to integer milliseconds
    (round-half-to-even, same as the fixture CSV).
    """
    out: list[Label] = []
    warnings: list[str] = []
    for lab in labels:
        start = lab["tMs"]
        if lab["type"] == "rough_patch":
            end = start + lab["durationMs"]
            if end < t0_ms or start > t1_ms:
                continue
            c_start, c_end = max(start, t0_ms), min(end, t1_ms)
            if (c_start, c_end) != (start, end):
                warnings.append(
                    f"rough_patch at t_ms={start:.0f} clipped to the kept window "
                    f"({end - start:.0f} ms -> {c_end - c_start:.0f} ms)"
                )
            new = dict(lab)
            new["tMs"] = int(round(c_start - t0_ms))
            new["durationMs"] = int(round(c_end - c_start))
            out.append(new)
        else:
            if start < t0_ms or start > t1_ms:
                continue
            new = dict(lab)
            new["tMs"] = int(round(start - t0_ms))
            out.append(new)
    return out, warnings
