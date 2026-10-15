package com.mapmyroad.backend.service.routing;

import com.mapmyroad.backend.config.RoutingProperties;
import com.mapmyroad.backend.repo.routing.HazardNearRoute;
import com.mapmyroad.backend.repo.routing.SegmentNearRoute;
import java.time.Instant;
import java.util.List;

/**
 * Comfort score of one route (ARCHITECTURE.md 8.4). Pure and deterministic for a given {@code now}.
 *
 * <pre>
 * penalty = Σ hazards  severityWeight[severity] × confidence
 *         + roughnessWeight × Σ segments (100 - score) / 100 × lengthKm
 * comfort = round(100 - clamp(penalty / max(routeKm, minNormalizationLengthKm) × normalizationFactor, 0, 100))
 * </pre>
 *
 * Only segments with at least {@code minSegmentSamples} samples count, both for roughness and for coverage: data
 * that is too thin to report as coverage should not move the score either. When matched segments add up to more
 * than the route (overlapping buckets, parallel-road noise without way IDs) their roughness contribution is scaled
 * down to the route length. There is no separate recency factor here: {@code hazard.confidence} already decays
 * with a 30-day half-life on {@code last_seen_at} (DATABASE_SCHEMA.md A9), and a second decay would discount old
 * hazards twice.
 */
public final class ComfortScorer {

    private static final double MAX_SCORE = 100.0;

    private final RoutingProperties properties;

    public ComfortScorer(RoutingProperties properties) {
        this.properties = properties;
    }

    public CandidateScore score(
            List<HazardNearRoute> hazards, List<SegmentNearRoute> segments, double routeLengthM, Instant now) {
        double hazardPenalty = 0;
        int severe = 0;
        int moderate = 0;
        int minor = 0;
        for (HazardNearRoute hazard : hazards) {
            double weight = properties.severityWeight(hazard.severityMax());
            if (weight <= 0 || hazard.confidence() < properties.minHazardConfidence()) {
                continue;
            }
            hazardPenalty += weight * hazard.confidence();
            switch (hazard.severityMax()) {
                case 3 -> severe++;
                case 2 -> moderate++;
                default -> minor++;
            }
        }

        double coveredM = 0;
        double roughKm = 0;
        for (SegmentNearRoute segment : segments) {
            if (segment.sampleCount() < properties.minSegmentSamples() || segment.lengthM() <= 0) {
                continue;
            }
            coveredM += segment.lengthM();
            roughKm += (MAX_SCORE - clampScore(segment.score())) / MAX_SCORE * segment.lengthM() / 1000.0;
        }
        if (routeLengthM > 0 && coveredM > routeLengthM) {
            roughKm *= routeLengthM / coveredM;
        }
        double penalty = hazardPenalty + properties.roughnessWeight() * roughKm;

        double normalizationKm = Math.max(routeLengthM / 1000.0, properties.minNormalizationLengthKm());
        double deduction = clamp(penalty / normalizationKm * properties.normalizationFactor(), 0, MAX_SCORE);
        int comfort = (int) Math.round(MAX_SCORE - deduction);

        double fraction = routeLengthM > 0 ? Math.min(1.0, coveredM / routeLengthM) : 0.0;
        return new CandidateScore(comfort, new HazardCounts(severe, moderate, minor), coverage(fraction));
    }

    Coverage coverage(double fraction) {
        CoverageLevel level;
        if (fraction >= properties.coverageGoodFrom()) {
            level = CoverageLevel.GOOD;
        } else if (fraction >= properties.coveragePartialFrom()) {
            level = CoverageLevel.PARTIAL;
        } else {
            level = CoverageLevel.NONE;
        }
        return new Coverage(level, fraction);
    }

    private static double clampScore(int score) {
        return clamp(score, 0, MAX_SCORE);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
