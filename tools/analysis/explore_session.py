"""Exploratory starter for M1-4: plot vertical acceleration, filtered signal, speed and tags.

Usage (from the ``tools/`` directory)::

    python -m analysis.explore_session <session_dir | fixture.csv> [--out out/]

Input can be a raw logger session directory (sensors.csv, location.csv,
tags.csv, meta.json) or a fixture CSV produced by ``traces.convert_session``
(labels are read from the sibling ``<name>.labels.json`` if present).

Outputs ``<out>/<input name>_overview.png`` (default ``tools/out/``, git-ignored)
and prints per-label peak statistics. Coordinates are never printed or plotted.

This is a script rather than a notebook to keep diffs reviewable. If you do use
a notebook, clear all outputs before committing (CODING_STANDARDS section 6) and
move reusable logic into modules under ``tools/`` with tests.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

import matplotlib

matplotlib.use("Agg")  # file output only, works headless
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import signal  # noqa: E402

from traces.convert_session import interpolate_gated, sample_and_hold  # noqa: E402
from traces.labels import tags_to_labels  # noqa: E402
from traces.session import SessionError, load_session  # noqa: E402

# Initial values from ARCHITECTURE section 5 (detection pipeline). They are
# starting points, to be tuned in M1-4/M1-5 and recorded in the findings doc;
# they are not measured results.
HIGHPASS_HZ = 0.5  # removes gravity residue and slow body/vehicle motion
LOWPASS_HZ = 20.0  # smooths high-frequency vibration (ARCHITECTURE: 20-25 Hz)
FILTER_ORDER = 4  # Butterworth order per filter; applied forward-backward (zero phase)
LABEL_WINDOW_S = 1.5  # +/- window around a label (TESTING_STRATEGY 3.2 matching window)
MAX_INTERP_GAP_MS = 100.0  # gravity samples further apart than this are treated as missing

DEFAULT_OUT = Path(__file__).resolve().parents[1] / "out"

# Plot styling: neutral ink, one series colour per panel, recessive grid.
INK = "#0b0b0b"
INK_MUTED = "#52514e"
SERIES_COLOR = "#2a78d6"
SERIES_COLOR_2 = "#eb6834"
LABEL_STYLES: dict[str, str] = {"pothole": "-", "speed_breaker": "--", "rough_patch": ":", "other": "-."}


@dataclass
class Trace:
    """Sensor data on one timeline plus labels on the same timeline (ms)."""

    name: str
    frame: pd.DataFrame  # columns t_ms, ax, ay, az, grav_x, grav_y, grav_z, speed_mps
    labels: list[dict[str, Any]]


# --------------------------------------------------------------------------- loading


def load_trace(path: str | Path) -> Trace:
    """Load a session directory or a fixture CSV into a :class:`Trace`."""
    p = Path(path)
    if p.is_dir():
        return _trace_from_session(p)
    if p.is_file() and p.suffix.lower() == ".csv":
        return _trace_from_fixture(p)
    raise FileNotFoundError(f"expected a session directory or a fixture .csv, got: {p}")


def _trace_from_session(path: Path) -> Trace:
    session = load_session(path)
    for w in session.warnings:
        print(f"warning: {w}", file=sys.stderr)
    acc = session.sensor("acc")
    if acc.empty:
        raise SessionError("session has no accelerometer samples")
    grav = session.sensor("grav")
    t = acc["t_ms"].to_numpy(dtype=float)
    xyz = ["x", "y", "z"]
    grav_v = interpolate_gated(t, grav["t_ms"].to_numpy(dtype=float), grav[xyz].to_numpy(dtype=float),
                               MAX_INTERP_GAP_MS)
    loc = session.location
    speed = sample_and_hold(t, loc["t_ms"].to_numpy(dtype=float), loc[["speed_mps"]].to_numpy(dtype=float))
    frame = pd.DataFrame(
        {
            "t_ms": t,
            "ax": acc["x"].to_numpy(), "ay": acc["y"].to_numpy(), "az": acc["z"].to_numpy(),
            "grav_x": grav_v[:, 0], "grav_y": grav_v[:, 1], "grav_z": grav_v[:, 2],
            "speed_mps": speed[:, 0],
        }
    )
    labels, warnings = tags_to_labels(session.tags)
    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)
    return Trace(name=path.name, frame=frame, labels=labels)


def _trace_from_fixture(path: Path) -> Trace:
    frame = pd.read_csv(path)
    needed = ["t_ms", "ax", "ay", "az", "grav_x", "grav_y", "grav_z", "speed_mps"]
    missing = [c for c in needed if c not in frame.columns]
    if missing:
        raise ValueError(f"{path.name} is missing column(s) {missing}; is it a fixture CSV?")
    labels: list[dict[str, Any]] = []
    labels_path = path.with_name(path.stem + ".labels.json")
    if labels_path.is_file():
        labels = json.loads(labels_path.read_text(encoding="utf-8")).get("labels", [])
    else:
        print(f"warning: no {labels_path.name} next to the fixture; plotting without labels", file=sys.stderr)
    return Trace(name=path.stem, frame=frame[needed].astype(float), labels=labels)


# --------------------------------------------------------------------------- signal processing


def vertical_acceleration(frame: pd.DataFrame) -> np.ndarray:
    """Project acceleration onto the gravity direction: ``dot(acc, grav)/|grav| - |grav|`` (m/s^2).

    Positive is "up" against gravity in the device's sensor convention
    (``acc`` includes gravity at rest, so the result is ~0 when stationary).
    NaN where gravity is missing or zero.
    """
    acc = frame[["ax", "ay", "az"]].to_numpy(dtype=float)
    grav = frame[["grav_x", "grav_y", "grav_z"]].to_numpy(dtype=float)
    g_norm = np.linalg.norm(grav, axis=1)
    with np.errstate(invalid="ignore", divide="ignore"):
        a_vert = np.einsum("ij,ij->i", acc, grav) / g_norm - g_norm
    a_vert[~np.isfinite(a_vert)] = np.nan
    return a_vert


def estimate_rate_hz(t_ms: np.ndarray) -> float:
    """Sampling rate from the median sample spacing."""
    dt = float(np.median(np.diff(t_ms)))
    if not dt > 0:
        raise ValueError("cannot estimate sampling rate: non-increasing timestamps")
    return 1000.0 / dt


def to_uniform(t_ms: np.ndarray, values: np.ndarray, fs_hz: float) -> tuple[np.ndarray, np.ndarray, int]:
    """Resample onto a uniform grid (filters assume uniform sampling); NaNs are bridged linearly.

    Returns ``(t_uniform_ms, values_uniform, n_bridged)``.
    """
    valid = np.isfinite(values)
    if valid.sum() < 2:
        raise ValueError("fewer than 2 valid samples of vertical acceleration (gravity missing?)")
    t_u = np.arange(t_ms[0], t_ms[-1], 1000.0 / fs_hz)
    v_u = np.interp(t_u, t_ms[valid], values[valid])
    return t_u, v_u, int((~valid).sum())


def bandpass(values: np.ndarray, fs_hz: float) -> np.ndarray:
    """Zero-phase Butterworth high-pass (HIGHPASS_HZ) and, if below Nyquist, low-pass (LOWPASS_HZ)."""
    nyquist = fs_hz / 2.0
    sos = [signal.butter(FILTER_ORDER, HIGHPASS_HZ, btype="highpass", fs=fs_hz, output="sos")]
    if LOWPASS_HZ < 0.95 * nyquist:
        sos.append(signal.butter(FILTER_ORDER, LOWPASS_HZ, btype="lowpass", fs=fs_hz, output="sos"))
    else:
        print(f"note: low-pass {LOWPASS_HZ:g} Hz skipped, not below Nyquist ({nyquist:.1f} Hz)", file=sys.stderr)
    sos_all = np.vstack(sos)
    padlen = 3 * (2 * len(sos_all) + 1)
    if len(values) <= padlen:
        raise ValueError(f"trace too short to filter ({len(values)} samples, need > {padlen})")
    return signal.sosfiltfilt(sos_all, values)


def label_stats(
    labels: list[dict[str, Any]], t_ms: np.ndarray, a_filt: np.ndarray, speed_t_ms: np.ndarray, speed: np.ndarray
) -> pd.DataFrame:
    """Peak |a_vert filtered| within +/- LABEL_WINDOW_S of each label and the speed at the label."""
    rows = []
    half = LABEL_WINDOW_S * 1000.0
    for lab in labels:
        t = float(lab["tMs"])
        win = (t_ms >= t - half) & (t_ms <= t + half)
        peak = float(np.max(np.abs(a_filt[win]))) if win.any() else float("nan")
        i = int(np.searchsorted(speed_t_ms, t, side="right")) - 1
        spd = float(speed[i]) if i >= 0 else float("nan")
        rows.append(
            {
                "t_s": t / 1000.0,
                "type": lab["type"],
                "severity": lab.get("severity"),
                "peak_abs_a_vert": peak,
                "speed_kmh": spd * 3.6,
            }
        )
    return pd.DataFrame(rows, columns=["t_s", "type", "severity", "peak_abs_a_vert", "speed_kmh"])


# --------------------------------------------------------------------------- plotting


def _style(ax: plt.Axes, ylabel: str) -> None:
    ax.set_ylabel(ylabel, color=INK_MUTED)
    ax.grid(True, color="#e6e5e1", linewidth=0.6)
    ax.tick_params(colors=INK_MUTED, labelsize=8)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color("#c9c8c3")


def _draw_labels(ax: plt.Axes, labels: list[dict[str, Any]], with_legend: bool) -> None:
    seen: set[str] = set()
    for lab in labels:
        kind = lab["type"]
        t_s = float(lab["tMs"]) / 1000.0
        legend_name = kind if kind not in seen else None
        seen.add(kind)
        if kind == "rough_patch" and lab.get("durationMs"):
            ax.axvspan(t_s, t_s + float(lab["durationMs"]) / 1000.0, color=INK_MUTED, alpha=0.12, label=legend_name)
        else:
            ax.axvline(t_s, color=INK_MUTED, linewidth=1.0, linestyle=LABEL_STYLES.get(kind, "-"), label=legend_name)
    if with_legend and seen:
        ax.legend(loc="upper right", fontsize=8, frameon=False, labelcolor=INK)


def plot_overview(
    trace: Trace, t_u: np.ndarray, a_raw: np.ndarray, a_filt: np.ndarray, out_path: Path
) -> None:
    """Three stacked panels on a shared time axis: raw and filtered vertical acceleration, speed."""
    t_s = t_u / 1000.0
    fig, axes = plt.subplots(3, 1, figsize=(14, 8), sharex=True, constrained_layout=True)
    axes[0].plot(t_s, a_raw, color=SERIES_COLOR, linewidth=0.8)
    _style(axes[0], "a_vert raw (m/s²)")
    axes[1].plot(t_s, a_filt, color=SERIES_COLOR, linewidth=0.8)
    _style(axes[1], f"a_vert {HIGHPASS_HZ:g}-{LOWPASS_HZ:g} Hz (m/s²)")
    sp = trace.frame["speed_mps"].to_numpy(dtype=float) * 3.6
    axes[2].step(trace.frame["t_ms"].to_numpy() / 1000.0, sp, where="post", color=SERIES_COLOR_2, linewidth=1.5)
    _style(axes[2], "speed (km/h)")
    axes[2].set_xlabel("time (s)", color=INK_MUTED)
    for i, ax in enumerate(axes):
        _draw_labels(ax, trace.labels, with_legend=(i == 0))
    fig.suptitle(f"{trace.name}: vertical acceleration and speed", color=INK, fontsize=11)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


# --------------------------------------------------------------------------- CLI


def main(argv: Sequence[str] | None = None) -> int:
    """CLI entry point. Returns a process exit code."""
    parser = argparse.ArgumentParser(prog="python -m analysis.explore_session", description=__doc__.split("\n")[0])
    parser.add_argument("input", type=Path, help="session directory or fixture .csv")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output folder (default {DEFAULT_OUT})")
    args = parser.parse_args(argv)

    try:
        trace = load_trace(args.input)
        t = trace.frame["t_ms"].to_numpy(dtype=float)
        fs = estimate_rate_hz(t)
        a_vert = vertical_acceleration(trace.frame)
        t_u, a_u, bridged = to_uniform(t, a_vert, fs)
        if bridged:
            print(f"warning: {bridged} samples without gravity were bridged by linear interpolation", file=sys.stderr)
        a_filt = bandpass(a_u, fs)
    except (SessionError, ValueError, FileNotFoundError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    out_path = args.out / f"{trace.name}_overview.png"
    plot_overview(trace, t_u, a_u, a_filt, out_path)

    print(f"input: {trace.name}  samples: {len(t)}  rate: ~{fs:.0f} Hz  duration: {(t[-1] - t[0]) / 1000:.1f} s")
    stats = label_stats(trace.labels, t_u, a_filt, t, trace.frame["speed_mps"].to_numpy(dtype=float))
    if stats.empty:
        print("no labels")
    else:
        with pd.option_context("display.float_format", "{:.2f}".format, "display.width", 120):
            print(stats.to_string(index=False))
    print(f"saved: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
