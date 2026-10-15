package com.mapmyroad.backend.api.routes;

import com.mapmyroad.backend.service.routing.Coverage;
import com.mapmyroad.backend.service.routing.HazardCounts;
import com.mapmyroad.backend.service.routing.RankedCandidate;
import com.mapmyroad.backend.service.routing.RouteCandidates;
import com.mapmyroad.backend.service.routing.RouteLabel;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code POST /v1/routes} (API_SPEC.md 5.8). The top-level {@code coverage} is that of the recommended
 * candidate. Each candidate also carries its own {@code coverage}, an additive field (clients ignore unknown fields,
 * API_SPEC.md 7) to be recorded in API_SPEC.md.
 */
public record RouteResponse(UUID requestId, List<Candidate> candidates, CoverageBody coverage) {

    public record Candidate(
            String id,
            List<String> labels,
            long durationSec,
            long distanceM,
            int comfortScore,
            Hazards hazards,
            String geometry,
            boolean recommended,
            CoverageBody coverage) {

        static Candidate from(RankedCandidate c) {
            return new Candidate(
                    c.id(),
                    c.labels().stream().map(RouteLabel::wireName).toList(),
                    Math.round(c.durationSec()),
                    Math.round(c.distanceM()),
                    c.score().comfortScore(),
                    Hazards.from(c.score().hazards()),
                    c.geometry(),
                    c.recommended(),
                    CoverageBody.from(c.score().coverage()));
        }

        @Override
        public String toString() {
            return "Candidate[id=" + id + "]";
        }
    }

    public record Hazards(int severe, int moderate, int minor) {

        static Hazards from(HazardCounts counts) {
            return new Hazards(counts.severe(), counts.moderate(), counts.minor());
        }
    }

    /** {@code fractionWithData} is rounded to two decimals, as in the API_SPEC example. */
    public record CoverageBody(String level, double fractionWithData) {

        static CoverageBody from(Coverage coverage) {
            return new CoverageBody(
                    coverage.level().wireName(), Math.round(coverage.fractionWithData() * 100.0) / 100.0);
        }
    }

    static RouteResponse from(RouteCandidates result) {
        return new RouteResponse(
                result.requestId(),
                result.candidates().stream().map(Candidate::from).toList(),
                CoverageBody.from(result.coverage()));
    }
}
