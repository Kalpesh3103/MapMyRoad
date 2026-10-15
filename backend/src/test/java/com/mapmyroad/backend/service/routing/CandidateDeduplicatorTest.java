package com.mapmyroad.backend.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.integration.valhalla.RouteOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateDeduplicatorTest {

    private static RouteOption option(String shape, double distanceM, double durationSec) {
        return new RouteOption(shape, distanceM, durationSec, List.of());
    }

    @Test
    void identicalShapesAreMerged() {
        List<RouteOption> distinct = CandidateDeduplicator.distinct(
                List.of(option("a", 9000, 1200), option("a", 9500, 1300)), 3);

        assertThat(distinct).hasSize(1);
        assertThat(distinct.get(0).distanceM()).isEqualTo(9000);
    }

    @Test
    void sameDistanceAndDurationWithinOnePercentAreMerged() {
        List<RouteOption> distinct = CandidateDeduplicator.distinct(
                List.of(option("a", 9000, 1200), option("b", 9080, 1210)), 3);

        assertThat(distinct).extracting(RouteOption::encodedPolyline6).containsExactly("a");
    }

    @Test
    void differentRoutesAreKeptInOrderAndCapped() {
        List<RouteOption> distinct = CandidateDeduplicator.distinct(List.of(
                option("a", 9000, 1200),
                option("b", 9000, 1300),
                option("c", 9800, 1200),
                option("d", 11000, 1500)), 3);

        assertThat(distinct).extracting(RouteOption::encodedPolyline6).containsExactly("a", "b", "c");
    }
}
