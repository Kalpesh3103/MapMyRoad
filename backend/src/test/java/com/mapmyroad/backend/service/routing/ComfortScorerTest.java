package com.mapmyroad.backend.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mapmyroad.backend.config.RoutingProperties;
import com.mapmyroad.backend.repo.routing.HazardNearRoute;
import com.mapmyroad.backend.repo.routing.SegmentNearRoute;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Comfort score arithmetic with the default (initial, uncalibrated) constants. */
class ComfortScorerTest {

    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");

    private final ComfortScorer scorer = new ComfortScorer(RoutingProperties.defaults());

    private static HazardNearRoute hazard(int severity, double confidence, Duration age) {
        return new HazardNearRoute(severity, confidence, NOW.minus(age));
    }

    @Test
    void oneSevereConfidentFreshHazardPerKmIsAbout70() {
        CandidateScore score = scorer.score(List.of(hazard(3, 1.0, Duration.ZERO)), List.of(), 1000, NOW);

        assertThat(score.comfortScore()).isEqualTo(70);
        assertThat(score.hazards()).isEqualTo(new HazardCounts(1, 0, 0));
    }

    @Test
    void scoreIsNormalizedPerKm() {
        List<HazardNearRoute> two = List.of(hazard(3, 1.0, Duration.ZERO), hazard(3, 1.0, Duration.ZERO));

        assertThat(scorer.score(two, List.of(), 2000, NOW).comfortScore()).isEqualTo(70);
    }

    @Test
    void noDataGivesFullComfortButNoCoverage() {
        CandidateScore score = scorer.score(List.of(), List.of(), 9200, NOW);

        assertThat(score.comfortScore()).isEqualTo(100);
        assertThat(score.hazards()).isEqualTo(HazardCounts.NONE);
        assertThat(score.coverage().level()).isEqualTo(CoverageLevel.NONE);
        assertThat(score.coverage().fractionWithData()).isZero();
    }

    @Test
    void severityWeightsAre1And3And8() {
        assertThat(scorer.score(List.of(hazard(1, 1.0, Duration.ZERO)), List.of(), 1000, NOW).comfortScore())
                .isEqualTo(96); // 100 - 1 * 3.75
        assertThat(scorer.score(List.of(hazard(2, 1.0, Duration.ZERO)), List.of(), 1000, NOW).comfortScore())
                .isEqualTo(89); // 100 - 3 * 3.75 = 88.75
    }

    @Test
    void confidenceScalesThePenalty_andAgeIsNotDiscountedTwice() {
        // Confidence 0.5 halves the penalty: 100 - 15.
        assertThat(scorer.score(List.of(hazard(3, 0.5, Duration.ZERO)), List.of(), 1000, NOW).comfortScore())
                .isEqualTo(85);
        // Age is already part of the stored confidence, so an old hazard with the same confidence scores the same.
        assertThat(scorer.score(List.of(hazard(3, 1.0, Duration.ofDays(30))), List.of(), 1000, NOW).comfortScore())
                .isEqualTo(70);
    }

    @Test
    void lowConfidenceHazardsAreNeitherScoredNorCounted() {
        CandidateScore score = scorer.score(List.of(hazard(3, 0.29, Duration.ZERO)), List.of(), 1000, NOW);

        assertThat(score.comfortScore()).isEqualTo(100);
        assertThat(score.hazards()).isEqualTo(HazardCounts.NONE);
    }

    @Test
    void manyHazardsClampAtZero() {
        List<HazardNearRoute> many = java.util.Collections.nCopies(20, hazard(3, 1.0, Duration.ZERO));

        assertThat(scorer.score(many, List.of(), 1000, NOW).comfortScore()).isZero();
    }

    @Test
    void roughnessPenalizesByScoreAndLength() {
        // Fully covered km with score 0: 20 * 1 * 3.75 = 75 -> 25.
        CandidateScore rough = scorer.score(List.of(), List.of(new SegmentNearRoute(0, 10, 1000)), 1000, NOW);
        assertThat(rough.comfortScore()).isEqualTo(25);
        assertThat(rough.coverage().level()).isEqualTo(CoverageLevel.GOOD);
        assertThat(rough.coverage().fractionWithData()).isCloseTo(1.0, within(1e-9));

        // Score 100 roads cost nothing.
        assertThat(scorer.score(List.of(), List.of(new SegmentNearRoute(100, 10, 1000)), 1000, NOW).comfortScore())
                .isEqualTo(100);
    }

    @Test
    void thinSegmentsCountForNeitherRoughnessNorCoverage() {
        CandidateScore score = scorer.score(List.of(), List.of(new SegmentNearRoute(0, 2, 1000)), 1000, NOW);

        assertThat(score.comfortScore()).isEqualTo(100);
        assertThat(score.coverage().level()).isEqualTo(CoverageLevel.NONE);
    }

    @Test
    void coverageLevelsAndCap() {
        assertThat(scorer.score(List.of(), List.of(new SegmentNearRoute(80, 5, 190)), 1000, NOW).coverage().level())
                .isEqualTo(CoverageLevel.NONE);
        assertThat(scorer.score(List.of(), List.of(new SegmentNearRoute(80, 5, 200)), 1000, NOW).coverage().level())
                .isEqualTo(CoverageLevel.PARTIAL);
        assertThat(scorer.score(List.of(), List.of(new SegmentNearRoute(80, 5, 700)), 1000, NOW).coverage().level())
                .isEqualTo(CoverageLevel.GOOD);
        // Overlapping segments longer than the route: coverage caps at 1 and roughness scales to the route length.
        CandidateScore overlap = scorer.score(
                List.of(), List.of(new SegmentNearRoute(0, 5, 1000), new SegmentNearRoute(0, 5, 1000)), 1000, NOW);
        assertThat(overlap.coverage().fractionWithData()).isEqualTo(1.0);
        assertThat(overlap.comfortScore()).isEqualTo(25);
    }

    @Test
    void veryShortRoutesUseTheMinimumNormalizationLength() {
        // 100 m route, divisor 0.5 km: 8 / 0.5 * 3.75 = 60 -> 40 (instead of 0 with the true length).
        assertThat(scorer.score(List.of(hazard(3, 1.0, Duration.ZERO)), List.of(), 100, NOW).comfortScore())
                .isEqualTo(40);
    }
}
