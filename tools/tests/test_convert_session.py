"""Tests for traces.convert_session: trimming, shifting, resampling, output format, privacy."""

from __future__ import annotations

import json
import math
import re
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from traces.convert_session import (
    FIXTURE_COLUMNS,
    OFFSET_MAX_M,
    OFFSET_MIN_M,
    ConversionError,
    ConvertOptions,
    convert,
    crop_window,
    interpolate_gated,
    main,
    sample_and_hold,
    write_fixture,
)
from traces.geo import cumulative_distance_m, haversine_m
from traces.session import load_session

EXPECTED_HEADER = "t_ms,ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z,lat,lon,speed_mps,accuracy_m"


def _convert(session_dir: Path, **opts) -> object:
    opts.setdefault("seed", 1234)
    return convert(load_session(session_dir), "synthetic_fixture_01", ConvertOptions(**opts))


# ----------------------------------------------------------------- trimming


def test_crop_window_function_excludes_first_and_last_trim_m() -> None:
    t = np.arange(11) * 1000.0
    cum = np.arange(11) * 200.0  # 0..2000 m
    t0, t1 = crop_window(t, cum, 500.0)
    assert t0 == 3000.0  # first fix at >= 500 m is 600 m
    assert t1 == 7000.0  # last fix at <= 1500 m is 1400 m


def test_crop_window_rejects_short_trip() -> None:
    with pytest.raises(ConversionError, match="too short"):
        crop_window(np.array([0.0, 1000.0]), np.array([0.0, 900.0]), 500.0)


def test_no_output_row_within_trim_distance_of_start_or_end(make_session) -> None:
    session_dir = make_session()
    result = _convert(session_dir)
    loc = pd.read_csv(session_dir / "location.csv")
    cum = cumulative_distance_m(loc["lat"].to_numpy(), loc["lon"].to_numpy())
    total = cum[-1]

    dlat, dlon = result.offset_deg
    frame = result.frame.dropna(subset=["lat", "lon"])
    assert len(frame) > 0
    orig_lat = frame["lat"].to_numpy() - dlat
    orig_lon = frame["lon"].to_numpy() - dlon
    # map every output position back to the fix it came from (sample-and-hold)
    for la, lo in set(zip(orig_lat.round(6), orig_lon.round(6))):
        d = haversine_m(la, lo, loc["lat"].to_numpy(), loc["lon"].to_numpy())
        i = int(np.argmin(d))
        assert d[i] < 0.5, "output position does not match any input fix"
        assert cum[i] >= 500.0 - 1e-6
        assert cum[i] <= total - 500.0 + 1e-6


def test_trim_also_bounds_sensor_rows_in_time(make_session) -> None:
    # 2000 m at 10 m/s, trim 500 m -> session window 50 s .. 150 s
    result = _convert(make_session())
    assert result.frame["t_ms"].iloc[0] == 0
    assert 99_000 <= result.frame["t_ms"].iloc[-1] <= 100_000
    assert result.stats.distance_kept_m == pytest.approx(1000.0, rel=0.01)


def test_start_end_ms_only_narrow_the_window(make_session) -> None:
    session_dir = make_session()
    narrowed = _convert(session_dir, start_ms=60_000, end_ms=90_000)
    assert 29_000 <= narrowed.frame["t_ms"].iloc[-1] <= 30_000
    widened = _convert(session_dir, start_ms=0, end_ms=10_000_000)
    assert widened.frame["t_ms"].iloc[-1] <= 100_000
    assert any("clamped" in w for w in widened.warnings)


# ----------------------------------------------------------------- shifting


def test_shift_preserves_relative_distances_and_is_1_to_5_km(make_session) -> None:
    session_dir = make_session()
    result = _convert(session_dir)
    dlat, dlon = result.offset_deg
    pts = result.frame.dropna(subset=["lat"]).drop_duplicates(subset=["lat", "lon"])
    lat_s, lon_s = pts["lat"].to_numpy(), pts["lon"].to_numpy()
    lat_o, lon_o = lat_s - dlat, lon_s - dlon

    shift = haversine_m(lat_o[0], lon_o[0], lat_s[0], lon_s[0])
    assert OFFSET_MIN_M * 0.99 <= shift <= OFFSET_MAX_M * 1.01

    d_orig = haversine_m(lat_o[0], lon_o[0], lat_o[1:], lon_o[1:])
    d_shift = haversine_m(lat_s[0], lon_s[0], lat_s[1:], lon_s[1:])
    np.testing.assert_allclose(d_shift, d_orig, rtol=2e-3, atol=0.05)


def test_deterministic_given_seed(make_session) -> None:
    session_dir = make_session()
    a = _convert(session_dir, seed=7)
    b = _convert(session_dir, seed=7)
    c = _convert(session_dir, seed=8)
    pd.testing.assert_frame_equal(a.frame, b.frame)
    assert a.labels_doc == b.labels_doc
    assert not np.allclose(a.frame["lat"].dropna(), c.frame["lat"].dropna())


# ----------------------------------------------------------------- resampling


def test_header_and_column_order_exact(make_session, tmp_path: Path) -> None:
    result = _convert(make_session())
    assert tuple(result.frame.columns) == FIXTURE_COLUMNS
    csv_path, _ = write_fixture(result, tmp_path / "out")
    with csv_path.open(encoding="utf-8") as fh:
        assert fh.readline().rstrip("\n") == EXPECTED_HEADER
    assert result.frame["t_ms"].dtype == np.int64
    assert result.frame["t_ms"].is_monotonic_increasing


def test_one_row_per_acc_sample_and_gyro_interpolated(make_session) -> None:
    session_dir = make_session()
    result = _convert(session_dir)
    # 100 Hz over a 100 s window
    assert 9_990 <= len(result.frame) <= 10_010
    # gyro x == t_session/1000 in the synthetic data; interpolation of a linear signal is exact
    acc = load_session(session_dir).sensor("acc")
    t_sess = acc["t_ms"].to_numpy()
    t_sess = t_sess[(t_sess >= 50_000) & (t_sess <= 150_000)]
    gx = result.frame["gx"].to_numpy()
    valid = ~np.isnan(gx)
    np.testing.assert_allclose(gx[valid], t_sess[valid] / 1000.0, atol=1e-5)


def test_gravity_gap_left_empty(make_session) -> None:
    result = _convert(make_session(grav_gap=(80_000, 81_000)))
    missing = result.frame["grav_z"].isna()
    assert missing.sum() > 50
    assert any("gravity" in w for w in result.warnings)


def test_interpolate_gated_unit() -> None:
    t_src = np.array([0.0, 10.0, 20.0, 500.0])
    vals = np.array([[0.0], [1.0], [2.0], [3.0]])
    out = interpolate_gated(np.array([-1.0, 0.0, 5.0, 20.0, 100.0, 500.0, 600.0]), t_src, vals, 100.0)
    expected = [np.nan, 0.0, 0.5, 2.0, np.nan, 3.0, np.nan]
    np.testing.assert_array_equal(np.isnan(out[:, 0]), np.isnan(expected))
    np.testing.assert_allclose(out[~np.isnan(out[:, 0]), 0], [0.0, 0.5, 2.0, 3.0])


def test_location_is_sample_and_hold_not_interpolated() -> None:
    t_src = np.array([1000.0, 2000.0])
    vals = np.array([[1.0], [2.0]])
    out = sample_and_hold(np.array([500.0, 1000.0, 1500.0, 1999.0, 2500.0]), t_src, vals)[:, 0]
    assert math.isnan(out[0])
    np.testing.assert_array_equal(out[1:], [1.0, 1.0, 1.0, 2.0])


def test_speed_column_holds_fix_values(make_session) -> None:
    # speed_mps == fix index in the synthetic data, so held values must be integers
    result = _convert(make_session())
    speed = result.frame["speed_mps"].dropna().to_numpy()
    np.testing.assert_array_equal(speed, np.round(speed))


# ----------------------------------------------------------------- privacy / output content


def test_wall_clock_and_identifying_fields_absent(make_session, tmp_path: Path) -> None:
    result = _convert(make_session())
    csv_path, json_path = write_fixture(result, tmp_path / "out")
    header = csv_path.read_text(encoding="utf-8").splitlines()[0]
    assert "wall" not in header
    assert "bearing" not in header and "altitude" not in header
    doc = json.loads(json_path.read_text(encoding="utf-8"))
    assert set(doc) == {"fixture", "vehicleType", "phonePlacement", "labels"}
    for path in (csv_path, json_path):
        text = path.read_text(encoding="utf-8")
        for forbidden in ("170000000", "SecretModel", "AcmePhones", "Elm street"):
            assert forbidden not in text


def test_cli_prints_no_coordinates(make_session, tmp_path: Path, capsys) -> None:
    session_dir = make_session(tags=[(100_000.0, "pothole", "3")])
    code = main([str(session_dir), "--out", str(tmp_path / "out"), "--name", "cli_fixture_01", "--seed", "3"])
    assert code == 0
    captured = capsys.readouterr()
    text = captured.out + captured.err
    assert "rows:" in text
    # no decimal number with 4+ fractional digits (coordinates), and no part of the synthetic origin
    assert not re.search(r"\d+\.\d{4,}", text)
    assert "18.5" not in text and "73.8" not in text


def test_cli_error_is_clear_and_nonzero(tmp_path: Path, capsys) -> None:
    code = main([str(tmp_path / "missing"), "--out", str(tmp_path / "o"), "--name", "x_01"])
    assert code == 2
    assert "session directory not found" in capsys.readouterr().err


def test_refuses_to_overwrite(make_session, tmp_path: Path) -> None:
    result = _convert(make_session())
    write_fixture(result, tmp_path / "out")
    with pytest.raises(ConversionError, match="overwrite"):
        write_fixture(result, tmp_path / "out")


def test_invalid_name_rejected(make_session) -> None:
    with pytest.raises(ConversionError, match="invalid fixture name"):
        convert(load_session(make_session()), "../escape", ConvertOptions(seed=1))


def test_missing_vehicle_type_is_an_error(make_session) -> None:
    with pytest.raises(ConversionError, match="vehicleType"):
        _convert(make_session(meta={"vehicleType": "bicycle"}))


def test_downsample_reduces_rows_and_warns(make_session) -> None:
    session_dir = make_session()
    full = _convert(session_dir)
    down = _convert(session_dir, downsample_hz=50)
    assert len(down.frame) == pytest.approx(len(full.frame) / 2, abs=2)
    assert any("downsampling" in w for w in down.warnings)


def test_labels_rebased_and_trimmed(make_session) -> None:
    tags = [
        (10_000.0, "pothole", "2"),  # inside the trimmed start -> dropped
        (100_000.0, "pothole", "3"),
        (120_000.0, "rough_patch_start", ""),
        (122_500.0, "rough_patch_end", ""),
        (190_000.0, "speed_breaker", ""),  # inside the trimmed end -> dropped
    ]
    result = _convert(make_session(tags=tags))
    labels = result.labels_doc["labels"]
    assert labels == [
        {"tMs": 50_000, "type": "pothole", "severity": 3},
        {"tMs": 70_000, "type": "rough_patch", "durationMs": 2_500},
    ]
    assert result.labels_doc["vehicleType"] == "scooter"
    assert result.labels_doc["phonePlacement"] == "mount"
