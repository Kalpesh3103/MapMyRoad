package com.mapmyroad.backend.service.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ARCHITECTURE.md 8.2 / DATABASE_SCHEMA.md A9 confidence heuristic (initial values, not measured). */
class ConfidenceFormulaTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final double HALF_LIFE_DAYS = 30;

    private static double confidence(List<Double> trust, Duration age, int confirm, int dismiss) {
        return ConfidenceFormula.confidence(trust, NOW.minus(age), confirm, dismiss, NOW, HALF_LIFE_DAYS);
    }

    @Test
    void threeDefaultTrustDevicesSeenNowGiveFullConfidence() {
        assertThat(confidence(List.of(0.5, 0.5, 0.5), Duration.ZERO, 0, 0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void oneDefaultDeviceGivesOneThird() {
        assertThat(confidence(List.of(0.5), Duration.ZERO, 0, 0)).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void lowTrustDevicesCountLessAndHighTrustIsCappedAtOne() {
        // MEETS_BASIC_INTEGRITY devices start at 0.3, i.e. weight 0.6 each.
        assertThat(ConfidenceFormula.trustWeightedDevices(List.of(0.3, 0.3))).isCloseTo(1.2, within(1e-9));
        assertThat(ConfidenceFormula.trustWeightedDevices(List.of(1.0))).isEqualTo(1.0);
        assertThat(ConfidenceFormula.trustWeightedDevices(List.of(0.0, -1.0))).isEqualTo(0.0);
        // One very trusted device can never reach the corroboration of three devices.
        assertThat(confidence(List.of(1.0), Duration.ZERO, 0, 0)).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(confidence(List.of(0.3, 0.3, 0.3), Duration.ZERO, 0, 0)).isCloseTo(0.6, within(1e-9));
        // Five low-trust devices reach full device weight (5 * 0.6 = 3).
        assertThat(confidence(Collections.nCopies(5, 0.3), Duration.ZERO, 0, 0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void noKnownDevicesGivesZero() {
        assertThat(confidence(List.of(), Duration.ZERO, 5, 0)).isZero();
    }

    @Test
    void recencyHalvesEveryHalfLife() {
        assertThat(ConfidenceFormula.recencyWeight(NOW, NOW, HALF_LIFE_DAYS)).isEqualTo(1.0);
        assertThat(ConfidenceFormula.recencyWeight(NOW.minus(Duration.ofDays(30)), NOW, HALF_LIFE_DAYS))
                .isCloseTo(0.5, within(1e-9));
        assertThat(ConfidenceFormula.recencyWeight(NOW.minus(Duration.ofDays(60)), NOW, HALF_LIFE_DAYS))
                .isCloseTo(0.25, within(1e-9));
        // A last-seen time in the future (phone clock skew) does not boost confidence above the formula's maximum.
        assertThat(ConfidenceFormula.recencyWeight(NOW.plus(Duration.ofDays(1)), NOW, HALF_LIFE_DAYS)).isEqualTo(1.0);
        assertThat(confidence(List.of(0.5, 0.5, 0.5), Duration.ofDays(30), 0, 0)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void feedbackMovesConfidenceWithinBounds() {
        assertThat(ConfidenceFormula.agreementWeight(0, 0)).isEqualTo(1.0);
        assertThat(ConfidenceFormula.agreementWeight(2, 2)).isEqualTo(1.0);
        assertThat(ConfidenceFormula.agreementWeight(2, 0)).isCloseTo(1.25, within(1e-9));
        assertThat(ConfidenceFormula.agreementWeight(0, 2)).isCloseTo(0.75, within(1e-9));
        assertThat(ConfidenceFormula.agreementWeight(1000, 0)).isLessThan(1.5);
        assertThat(ConfidenceFormula.agreementWeight(0, 1000)).isGreaterThan(0.5);

        double base = confidence(List.of(0.5), Duration.ZERO, 0, 0);
        assertThat(confidence(List.of(0.5), Duration.ZERO, 2, 0)).isCloseTo(base * 1.25, within(1e-9));
        assertThat(confidence(List.of(0.5), Duration.ZERO, 0, 2)).isCloseTo(base * 0.75, within(1e-9));
    }

    @Test
    void resultIsClampedToOne() {
        assertThat(confidence(List.of(0.5, 0.5, 0.5), Duration.ZERO, 10, 0)).isEqualTo(1.0);
    }

    @Test
    void displayThresholdNeedsCorroborationOrRecentFeedback() {
        // One fresh default device is above the 0.3 display threshold only barely; two weeks later it is below.
        assertThat(confidence(List.of(0.5), Duration.ZERO, 0, 0)).isGreaterThan(0.3);
        assertThat(confidence(List.of(0.5), Duration.ofDays(14), 0, 0)).isLessThan(0.3);
        assertThat(confidence(List.of(0.5), Duration.ZERO, 0, 1)).isLessThan(0.3);
    }
}
