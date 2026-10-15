package com.mapmyroad.backend.service.routing;

import com.mapmyroad.backend.integration.valhalla.RouteOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Drops near-identical Valhalla candidates so the app never shows the same route twice (API_SPEC.md 5.8). Two
 * options are the same route when their shapes are identical, or when distance and duration both differ by at most
 * {@link #TOLERANCE}. The second rule is a cheap stand-in for a geometric overlap test: it could in rare cases merge
 * two different routes of the same length and time, which only costs the user one option. Order is preserved, so the
 * main route always survives.
 */
public final class CandidateDeduplicator {

    static final double TOLERANCE = 0.01;

    private CandidateDeduplicator() {}

    public static List<RouteOption> distinct(List<RouteOption> options, int maxCandidates) {
        List<RouteOption> kept = new ArrayList<>();
        for (RouteOption option : options) {
            if (kept.size() >= maxCandidates) {
                break;
            }
            if (kept.stream().noneMatch(existing -> sameRoute(existing, option))) {
                kept.add(option);
            }
        }
        return kept;
    }

    static boolean sameRoute(RouteOption a, RouteOption b) {
        if (a.encodedPolyline6().equals(b.encodedPolyline6())) {
            return true;
        }
        return within(a.distanceM(), b.distanceM()) && within(a.durationSec(), b.durationSec());
    }

    private static boolean within(double a, double b) {
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return scale == 0 || Math.abs(a - b) <= TOLERANCE * scale;
    }
}
