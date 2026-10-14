"""Tests for traces.labels and traces.geo."""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from traces.geo import cumulative_distance_m, destination_point, haversine_m
from traces.labels import crop_and_rebase, tags_to_labels


def _tags(rows: list[tuple[float, str, float]]) -> pd.DataFrame:
    return pd.DataFrame(rows, columns=["t_ms", "type", "severity"])


def test_rough_patch_pair_becomes_one_label_with_duration() -> None:
    labels, warnings = tags_to_labels(_tags([(1000.0, "rough_patch_start", 2), (3500.0, "rough_patch_end", np.nan)]))
    assert labels == [{"tMs": 1000.0, "type": "rough_patch", "severity": 2, "durationMs": 2500.0}]
    assert warnings == []


def test_unmatched_end_and_start_are_dropped_with_warnings() -> None:
    labels, warnings = tags_to_labels(
        _tags(
            [
                (500.0, "rough_patch_end", np.nan),  # no start
                (1000.0, "rough_patch_start", np.nan),  # superseded by next start
                (2000.0, "rough_patch_start", np.nan),
                (2600.0, "rough_patch_end", np.nan),
                (5000.0, "rough_patch_start", np.nan),  # never closed
            ]
        )
    )
    assert labels == [{"tMs": 2000.0, "type": "rough_patch", "durationMs": 600.0}]
    assert len(warnings) == 3
    assert all("dropped" in w for w in warnings)


def test_point_labels_severity_and_unknown_types() -> None:
    labels, warnings = tags_to_labels(
        _tags([(3.0, "pothole", 3), (1.0, "speed_breaker", np.nan), (2.0, "other", 7), (4.0, "manhole", 1)])
    )
    assert labels == [
        {"tMs": 1.0, "type": "speed_breaker"},
        {"tMs": 2.0, "type": "other"},
        {"tMs": 3.0, "type": "pothole", "severity": 3},
    ]
    assert any("outside 1..3" in w for w in warnings)
    assert any("unknown type" in w for w in warnings)


def test_crop_and_rebase_clips_rough_patches() -> None:
    labels = [
        {"tMs": 900.0, "type": "pothole"},
        {"tMs": 1500.4, "type": "pothole"},
        {"tMs": 1800.0, "type": "rough_patch", "durationMs": 1000.0},
    ]
    out, warnings = crop_and_rebase(labels, 1000.0, 2500.0)
    assert out == [
        {"tMs": 500, "type": "pothole"},
        {"tMs": 800, "type": "rough_patch", "durationMs": 700},
    ]
    assert len(warnings) == 1 and "clipped" in warnings[0]


def test_haversine_one_degree_latitude() -> None:
    assert haversine_m(0.0, 0.0, 1.0, 0.0) == pytest.approx(111_195, rel=1e-3)


def test_destination_point_round_trip() -> None:
    lat, lon = destination_point(18.5, 73.8, 45.0, 3000.0)
    assert haversine_m(18.5, 73.8, lat, lon) == pytest.approx(3000.0, rel=1e-6)


def test_cumulative_distance_rejects_nan() -> None:
    with pytest.raises(ValueError, match="NaN"):
        cumulative_distance_m([1.0, np.nan], [1.0, 2.0])
