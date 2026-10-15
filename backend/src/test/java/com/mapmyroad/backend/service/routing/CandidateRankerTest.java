package com.mapmyroad.backend.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Labels and the preference slider (API_SPEC.md 5.8, TESTING_STRATEGY.md 7). */
class CandidateRankerTest {

    private static final Coverage GOOD = new Coverage(CoverageLevel.GOOD, 0.9);

    private static ScoredRoute route(String id, double durationSec, double distanceM, int comfort) {
        return new ScoredRoute(
                id, "geom-" + id, distanceM, durationSec, new CandidateScore(comfort, HazardCounts.NONE, GOOD));
    }

    // c1 fast and rough, c2 slow and smooth, c3 in between.
    private final List<ScoredRoute> routes = List.of(
            route("c1", 1380, 9200, 62), route("c2", 1620, 10100, 81), route("c3", 1500, 9800, 76));

    private static RankedCandidate byId(List<RankedCandidate> ranked, String id) {
        return ranked.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static String recommended(List<RankedCandidate> ranked) {
        return ranked.stream().filter(RankedCandidate::recommended).map(RankedCandidate::id).findFirst().orElseThrow();
    }

    @Test
    void labelsFastestSmoothestAndBalanced() {
        List<RankedCandidate> ranked = CandidateRanker.rank(routes, 0.5);

        assertThat(byId(ranked, "c1").labels()).containsExactly(RouteLabel.FASTEST);
        assertThat(byId(ranked, "c2").labels()).containsExactly(RouteLabel.SMOOTHEST);
        // Combined at 0.5: c1 0.5*100 + 0.5*62 = 81; c2 0.5*85.2 + 0.5*81 = 83.1; c3 0.5*92 + 0.5*76 = 84.
        assertThat(byId(ranked, "c3").labels()).containsExactly(RouteLabel.BALANCED);
        assertThat(byId(ranked, "c3").recommended()).isTrue();
        assertThat(ranked).extracting(RankedCandidate::id).containsExactly("c1", "c2", "c3");
    }

    @Test
    void movingThePreferenceSliderChangesTheRecommendation() {
        assertThat(recommended(CandidateRanker.rank(routes, 0.0))).isEqualTo("c1");
        assertThat(recommended(CandidateRanker.rank(routes, 1.0))).isEqualTo("c2");
    }

    @Test
    void exactlyOneRecommendedForAnyPreference() {
        for (double p = 0; p <= 1.0001; p += 0.1) {
            assertThat(CandidateRanker.rank(routes, p).stream().filter(RankedCandidate::recommended).count())
                    .isEqualTo(1);
        }
    }

    @Test
    void oneRouteCarriesAllLabels() {
        List<RankedCandidate> ranked = CandidateRanker.rank(List.of(route("c1", 600, 5000, 90)), 0.3);

        assertThat(ranked.get(0).labels())
                .containsExactly(RouteLabel.FASTEST, RouteLabel.SMOOTHEST, RouteLabel.BALANCED);
        assertThat(ranked.get(0).recommended()).isTrue();
    }

    @Test
    void comfortTieGoesToTheShorterDuration() {
        List<RankedCandidate> ranked =
                CandidateRanker.rank(List.of(route("c1", 1000, 8000, 80), route("c2", 900, 8500, 80)), 1.0);

        assertThat(byId(ranked, "c2").labels()).contains(RouteLabel.SMOOTHEST, RouteLabel.FASTEST);
        assertThat(recommended(ranked)).isEqualTo("c2");
    }

    @Test
    void combinedScoreUsesTimeRelativeToFastest() {
        ScoredRoute slow = route("c2", 2000, 10000, 50);

        assertThat(CandidateRanker.combinedScore(slow, 0.0, 1000)).isEqualTo(50.0);
        assertThat(CandidateRanker.combinedScore(slow, 1.0, 1000)).isEqualTo(50.0);
        assertThat(CandidateRanker.combinedScore(slow, 0.5, 1000)).isEqualTo(50.0);
        assertThat(CandidateRanker.combinedScore(route("c1", 1000, 9000, 60), 0.5, 1000)).isEqualTo(80.0);
    }

    @Test
    void emptyInputIsEmpty() {
        assertThat(CandidateRanker.rank(List.of(), 0.5)).isEmpty();
    }
}
