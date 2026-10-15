package com.mapmyroad.backend.service.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Labels candidates and picks the recommended one (API_SPEC.md 5.8, ARCHITECTURE.md 8.4, PRD RT-3).
 *
 * <ul>
 *   <li>{@code fastest}: shortest duration (ties: shorter distance).
 *   <li>{@code smoothest}: highest comfort score (ties: shorter duration).
 *   <li>{@code balanced}: highest combined score with preference 0.5.
 *   <li>{@code recommended}: highest combined score with the request's preference.
 * </ul>
 *
 * Combined score: {@code (1 - p) × timeScore + p × comfortScore} with {@code timeScore = 100 × fastestDuration /
 * duration}, so both terms are on a 0 to 100 scale. Ties in the combined score go to the shorter duration, then to
 * the earlier candidate. Every label is given to exactly one candidate; a candidate can hold several.
 */
public final class CandidateRanker {

    public static final double BALANCED_PREFERENCE = 0.5;

    private CandidateRanker() {}

    public static List<RankedCandidate> rank(List<ScoredRoute> routes, double preference) {
        if (routes.isEmpty()) {
            return List.of();
        }
        double p = Math.max(0, Math.min(1, preference));
        double fastestDuration = routes.stream().mapToDouble(ScoredRoute::durationSec).min().orElse(0);

        ScoredRoute fastest = best(routes, Comparator.comparingDouble(ScoredRoute::durationSec)
                .thenComparingDouble(ScoredRoute::distanceM));
        ScoredRoute smoothest = best(routes, Comparator.comparingInt((ScoredRoute r) -> -r.score().comfortScore())
                .thenComparingDouble(ScoredRoute::durationSec));
        ScoredRoute balanced = best(routes, byCombined(BALANCED_PREFERENCE, fastestDuration));
        ScoredRoute recommended = best(routes, byCombined(p, fastestDuration));

        List<RankedCandidate> ranked = new ArrayList<>(routes.size());
        for (ScoredRoute route : routes) {
            List<RouteLabel> labels = new ArrayList<>(3);
            if (route == fastest) {
                labels.add(RouteLabel.FASTEST);
            }
            if (route == smoothest) {
                labels.add(RouteLabel.SMOOTHEST);
            }
            if (route == balanced) {
                labels.add(RouteLabel.BALANCED);
            }
            ranked.add(new RankedCandidate(
                    route.id(),
                    route.geometry(),
                    route.distanceM(),
                    route.durationSec(),
                    route.score(),
                    labels,
                    route == recommended));
        }
        return ranked;
    }

    /** Combined score of one route; exposed for tests. */
    static double combinedScore(ScoredRoute route, double preference, double fastestDurationSec) {
        double timeScore = route.durationSec() > 0 ? 100.0 * fastestDurationSec / route.durationSec() : 100.0;
        return (1 - preference) * timeScore + preference * route.score().comfortScore();
    }

    private static Comparator<ScoredRoute> byCombined(double preference, double fastestDurationSec) {
        return Comparator.comparingDouble((ScoredRoute r) -> -combinedScore(r, preference, fastestDurationSec))
                .thenComparingDouble(ScoredRoute::durationSec);
    }

    /** First minimum under {@code order}, so equal routes resolve to the earlier candidate. */
    private static ScoredRoute best(List<ScoredRoute> routes, Comparator<ScoredRoute> order) {
        ScoredRoute best = routes.get(0);
        for (ScoredRoute route : routes) {
            if (order.compare(route, best) < 0) {
                best = route;
            }
        }
        return best;
    }
}
