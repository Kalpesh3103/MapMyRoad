"""Small spherical-earth geometry helpers (no external GIS dependency).

All functions accept scalars or numpy arrays (broadcast) and use a spherical
earth model, which is accurate to well under 0.5% for the distances used here
(trimming and anonymization, not surveying).
"""

from __future__ import annotations

import numpy as np
from numpy.typing import ArrayLike, NDArray

#: Mean earth radius in meters (IUGG).
EARTH_RADIUS_M = 6_371_008.8


def haversine_m(lat1: ArrayLike, lon1: ArrayLike, lat2: ArrayLike, lon2: ArrayLike) -> NDArray[np.float64]:
    """Great-circle distance in meters between points given in decimal degrees."""
    p1 = np.radians(np.asarray(lat1, dtype=float))
    p2 = np.radians(np.asarray(lat2, dtype=float))
    dp = p2 - p1
    dl = np.radians(np.asarray(lon2, dtype=float) - np.asarray(lon1, dtype=float))
    a = np.sin(dp / 2.0) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2.0) ** 2
    return 2.0 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.clip(a, 0.0, 1.0)))


def cumulative_distance_m(lat: ArrayLike, lon: ArrayLike) -> NDArray[np.float64]:
    """Cumulative along-path distance in meters for a sequence of fixes.

    The first element is 0. Inputs must not contain NaN (filter invalid fixes
    first); a ``ValueError`` is raised otherwise.
    """
    lat_a = np.asarray(lat, dtype=float)
    lon_a = np.asarray(lon, dtype=float)
    if lat_a.shape != lon_a.shape or lat_a.ndim != 1:
        raise ValueError("lat and lon must be 1-D arrays of equal length")
    if np.isnan(lat_a).any() or np.isnan(lon_a).any():
        raise ValueError("lat/lon contain NaN; drop invalid fixes before computing distance")
    if lat_a.size == 0:
        return np.zeros(0)
    steps = haversine_m(lat_a[:-1], lon_a[:-1], lat_a[1:], lon_a[1:])
    return np.concatenate(([0.0], np.cumsum(steps)))


def destination_point(
    lat: ArrayLike, lon: ArrayLike, bearing_deg: ArrayLike, distance_m: ArrayLike
) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    """Point reached by travelling ``distance_m`` from (lat, lon) on initial bearing ``bearing_deg``.

    Bearing is in degrees clockwise from true north. Returns (lat, lon) in
    degrees, longitude normalized to [-180, 180).
    """
    p1 = np.radians(np.asarray(lat, dtype=float))
    l1 = np.radians(np.asarray(lon, dtype=float))
    brg = np.radians(np.asarray(bearing_deg, dtype=float))
    delta = np.asarray(distance_m, dtype=float) / EARTH_RADIUS_M
    p2 = np.arcsin(np.sin(p1) * np.cos(delta) + np.cos(p1) * np.sin(delta) * np.cos(brg))
    l2 = l1 + np.arctan2(
        np.sin(brg) * np.sin(delta) * np.cos(p1),
        np.cos(delta) - np.sin(p1) * np.sin(p2),
    )
    lon2 = (np.degrees(l2) + 540.0) % 360.0 - 180.0
    return np.degrees(p2), lon2
