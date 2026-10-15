package com.mapmyroad.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Route candidates and comfort scoring (M4-14, ARCHITECTURE.md sections 8.4 and 9.1, ADR-0004). Every weight and
 * threshold is an initial value, not a measured fact.
 *
 * <p>Score: {@code penalty = Σ severityWeight × confidence + roughnessWeight × Σ (100 - score) / 100 ×
 * lengthKm}, {@code comfort = round(100 - clamp(penalty / max(routeKm, minNormalizationLengthKm) ×
 * normalizationFactor, 0, 100))}. With the defaults one severe hazard of confidence 1 seen today per km gives
 * {@code 8 × 3.75 = 30}, so comfort 70; a fully covered road with segment score 0 gives {@code 20 × 3.75 = 75},
 * so comfort 25; segment score 70 (the "Smooth" band edge, UI_UX_GUIDELINES.md 4.2) gives comfort about 78.
 *
 * <p>TODO(M1-5): calibrate weights, normalization and coverage thresholds against pilot drives.
 *
 * @param maxAlternates alternates requested from Valhalla in addition to the main route
 * @param maxCandidates distinct candidates returned (API_SPEC.md 5.8: at most three)
 * @param resolveWayIds best-effort second Valhalla call ({@code trace_attributes}, {@code edge_walk}) to learn the
 *     OSM way IDs of each candidate, which tightens the spatial queries (DATABASE_SCHEMA.md A9)
 * @param minOriginDestinationM origin and destination closer than this are rejected with 400
 * @param hazardBufferM hazards within this distance of the route line are scored
 * @param minHazardConfidence hazards below this confidence are ignored (conservative, independent of map rules)
 * @param segmentBufferWithWaysM segment distance limit when way IDs are known
 * @param segmentBufferWithoutWaysM tighter segment distance limit when way IDs are unknown (parallel-road noise)
 * @param minSegmentSamples segments need this many samples to count for roughness and coverage
 * @param severityWeightMinor weight of severity 1
 * @param severityWeightModerate weight of severity 2
 * @param severityWeightSevere weight of severity 3
 * @param roughnessWeight penalty per fully rough (score 0) km
 * @param normalizationFactor maps penalty per km to comfort points
 * @param minNormalizationLengthKm lower bound of the per-km divisor, so one hazard on a very short trip does not
 *     floor the score
 * @param coveragePartialFrom coverage fraction at which the level becomes {@code partial}
 * @param coverageGoodFrom coverage fraction at which the level becomes {@code good}
 * @param candidateTtl how long candidates stay available for v3 navigation (API_SPEC.md 5.9)
 * @param candidateCacheMaxEntries upper bound of the in-memory candidate cache
 */
@ConfigurationProperties(prefix = "mmr.routing")
public record RoutingProperties(
        Integer maxAlternates,
        Integer maxCandidates,
        Boolean resolveWayIds,
        Double minOriginDestinationM,
        Double hazardBufferM,
        Double minHazardConfidence,
        Double segmentBufferWithWaysM,
        Double segmentBufferWithoutWaysM,
        Integer minSegmentSamples,
        Double severityWeightMinor,
        Double severityWeightModerate,
        Double severityWeightSevere,
        Double roughnessWeight,
        Double normalizationFactor,
        Double minNormalizationLengthKm,
        Double coveragePartialFrom,
        Double coverageGoodFrom,
        Duration candidateTtl,
        Integer candidateCacheMaxEntries) {

    public RoutingProperties {
        maxAlternates = maxAlternates == null ? 2 : maxAlternates;
        maxCandidates = maxCandidates == null ? 3 : maxCandidates;
        resolveWayIds = resolveWayIds == null ? Boolean.TRUE : resolveWayIds;
        minOriginDestinationM = minOriginDestinationM == null ? 50.0 : minOriginDestinationM;
        hazardBufferM = hazardBufferM == null ? 15.0 : hazardBufferM;
        minHazardConfidence = minHazardConfidence == null ? 0.3 : minHazardConfidence;
        segmentBufferWithWaysM = segmentBufferWithWaysM == null ? 10.0 : segmentBufferWithWaysM;
        segmentBufferWithoutWaysM = segmentBufferWithoutWaysM == null ? 8.0 : segmentBufferWithoutWaysM;
        minSegmentSamples = minSegmentSamples == null ? 3 : minSegmentSamples;
        severityWeightMinor = severityWeightMinor == null ? 1.0 : severityWeightMinor;
        severityWeightModerate = severityWeightModerate == null ? 3.0 : severityWeightModerate;
        severityWeightSevere = severityWeightSevere == null ? 8.0 : severityWeightSevere;
        roughnessWeight = roughnessWeight == null ? 20.0 : roughnessWeight;
        normalizationFactor = normalizationFactor == null ? 3.75 : normalizationFactor;
        minNormalizationLengthKm = minNormalizationLengthKm == null ? 0.5 : minNormalizationLengthKm;
        coveragePartialFrom = coveragePartialFrom == null ? 0.2 : coveragePartialFrom;
        coverageGoodFrom = coverageGoodFrom == null ? 0.7 : coverageGoodFrom;
        candidateTtl = candidateTtl == null ? Duration.ofMinutes(15) : candidateTtl;
        candidateCacheMaxEntries = candidateCacheMaxEntries == null ? 10_000 : candidateCacheMaxEntries;
        if (maxAlternates < 0 || maxCandidates < 1) {
            throw new IllegalArgumentException("mmr.routing candidate counts are inconsistent");
        }
        if (minOriginDestinationM < 0 || hazardBufferM <= 0 || segmentBufferWithWaysM <= 0
                || segmentBufferWithoutWaysM <= 0) {
            throw new IllegalArgumentException("mmr.routing distances must be positive");
        }
        if (minHazardConfidence < 0 || minHazardConfidence > 1 || minSegmentSamples < 1) {
            throw new IllegalArgumentException("mmr.routing data thresholds are inconsistent");
        }
        if (severityWeightMinor < 0 || severityWeightModerate < 0 || severityWeightSevere < 0
                || roughnessWeight < 0 || normalizationFactor <= 0 || minNormalizationLengthKm <= 0) {
            throw new IllegalArgumentException("mmr.routing weights are inconsistent");
        }
        if (coveragePartialFrom < 0 || coverageGoodFrom > 1 || coverageGoodFrom < coveragePartialFrom) {
            throw new IllegalArgumentException("mmr.routing coverage thresholds are inconsistent");
        }
        if (candidateTtl.isNegative() || candidateTtl.isZero() || candidateCacheMaxEntries < 1) {
            throw new IllegalArgumentException("mmr.routing candidate cache settings are inconsistent");
        }
    }

    /** Defaults, for tests and code that runs without Spring. */
    public static RoutingProperties defaults() {
        return new RoutingProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /** Weight of a hazard severity (1 minor, 2 moderate, 3 severe); 0 for unknown values. */
    public double severityWeight(int severity) {
        return switch (severity) {
            case 1 -> severityWeightMinor;
            case 2 -> severityWeightModerate;
            case 3 -> severityWeightSevere;
            default -> 0.0;
        };
    }
}
