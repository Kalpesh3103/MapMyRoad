package com.mapmyroad.backend.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.config.SupportedRegion;
import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.ItemKind;
import com.mapmyroad.backend.domain.ingest.Rejection;
import com.mapmyroad.backend.domain.ingest.RejectionReason;
import com.mapmyroad.backend.domain.ingest.UploadBatch;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.domain.ingest.WindowObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every rejection reason of API_SPEC.md section 5.3 and its boundaries (TESTING_STRATEGY.md section 5.1). */
class UploadValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-13T08:30:00Z");
    // Synthetic test coordinates inside the illustrative Pune bounding box, not real trips.
    private static final SupportedRegion REGION = SupportedRegion.parse("72.6,17.9,75.1,19.6");

    private final UploadValidator validator = new UploadValidator(IngestProperties.defaults(), REGION);

    private static EventObservation event() {
        return new EventObservation(UUID.randomUUID(), 18.52, 73.85, NOW.minusSeconds(60), 2, 32.5, 2.8, 187);
    }

    private static EventObservation event(double lat, double lon, Instant at, int severity, double speedKmh) {
        return new EventObservation(UUID.randomUUID(), lat, lon, at, severity, speedKmh, 2.8, 187);
    }

    private static WindowObservation window() {
        return window(18.5201, 73.8565, 18.5206, 73.8568, NOW.minusSeconds(60), 52, 0.42, 28);
    }

    private static WindowObservation window(
            double startLat, double startLon, double endLat, double endLon,
            Instant at, double lengthM, double roughness, double speedKmh) {
        return new WindowObservation(
                UUID.randomUUID(), startLat, startLon, endLat, endLon, at, lengthM, roughness, speedKmh);
    }

    private Optional<RejectionReason> check(EventObservation e) {
        return validator.check(e, NOW);
    }

    private Optional<RejectionReason> check(WindowObservation w) {
        return validator.check(w, NOW);
    }

    @Test
    void validItemsPass() {
        assertThat(check(event())).isEmpty();
        assertThat(check(window())).isEmpty();
    }

    @Test
    void eventOutsideRegionIsRejected() {
        assertThat(check(event(28.61, 77.20, NOW, 2, 30))).contains(RejectionReason.OUT_OF_REGION);
        assertThat(check(event(Double.NaN, 73.85, NOW, 2, 30))).contains(RejectionReason.OUT_OF_REGION);
        assertThat(check(event(18.52, Double.POSITIVE_INFINITY, NOW, 2, 30))).contains(RejectionReason.OUT_OF_REGION);
    }

    @Test
    void regionEdgeIsInside() {
        assertThat(check(event(17.9, 72.6, NOW, 2, 30))).isEmpty();
        assertThat(check(event(19.6, 75.1, NOW, 2, 30))).isEmpty();
    }

    @Test
    void windowWithOneEndpointOutsideRegionIsRejected() {
        WindowObservation w = window(18.52, 73.85, 19.7, 73.85, NOW, 52, 0.4, 28);
        assertThat(check(w)).contains(RejectionReason.OUT_OF_REGION);
        WindowObservation w2 = window(17.8, 73.85, 18.52, 73.85, NOW, 52, 0.4, 28);
        assertThat(check(w2)).contains(RejectionReason.OUT_OF_REGION);
    }

    @Test
    void eventSpeedBoundaries() {
        assertThat(check(event(18.52, 73.85, NOW, 2, 10.0))).isEmpty();
        assertThat(check(event(18.52, 73.85, NOW, 2, 9.99))).contains(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(check(event(18.52, 73.85, NOW, 2, 200.0))).isEmpty();
        assertThat(check(event(18.52, 73.85, NOW, 2, 200.01))).contains(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(check(event(18.52, 73.85, NOW, 2, -1))).contains(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(check(event(18.52, 73.85, NOW, 2, Double.NaN))).contains(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(check(event(18.52, 73.85, NOW, 2, Double.POSITIVE_INFINITY)))
                .contains(RejectionReason.IMPLAUSIBLE_SPEED);
    }

    @Test
    void windowSpeedHasNoMinimumButAMaximum() {
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, 0.4, 0))).isEmpty();
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, 0.4, 200))).isEmpty();
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, 0.4, 200.5)))
                .contains(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, 0.4, -0.1)))
                .contains(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, 0.4, Double.NaN)))
                .contains(RejectionReason.IMPLAUSIBLE_SPEED);
    }

    @Test
    void futureTimestampBoundary() {
        Instant exactlyFiveMinutes = NOW.plus(Duration.ofMinutes(5));
        assertThat(check(event(18.52, 73.85, exactlyFiveMinutes, 2, 30))).isEmpty();
        assertThat(check(event(18.52, 73.85, exactlyFiveMinutes.plusMillis(1), 2, 30)))
                .contains(RejectionReason.FUTURE_TIMESTAMP);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, exactlyFiveMinutes.plusSeconds(1), 52, 0.4, 28)))
                .contains(RejectionReason.FUTURE_TIMESTAMP);
    }

    @Test
    void tooOldBoundary() {
        Instant exactlyFourteenDays = NOW.minus(Duration.ofDays(14));
        assertThat(check(event(18.52, 73.85, exactlyFourteenDays, 2, 30))).isEmpty();
        assertThat(check(event(18.52, 73.85, exactlyFourteenDays.minusMillis(1), 2, 30)))
                .contains(RejectionReason.TOO_OLD);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, exactlyFourteenDays.minusSeconds(1), 52, 0.4, 28)))
                .contains(RejectionReason.TOO_OLD);
    }

    @Test
    void severityRange() {
        assertThat(check(event(18.52, 73.85, NOW, 1, 30))).isEmpty();
        assertThat(check(event(18.52, 73.85, NOW, 3, 30))).isEmpty();
        assertThat(check(event(18.52, 73.85, NOW, 0, 30))).contains(RejectionReason.INVALID_SEVERITY);
        assertThat(check(event(18.52, 73.85, NOW, 4, 30))).contains(RejectionReason.INVALID_SEVERITY);
    }

    @Test
    void nonFinitePeakScoreIsInvalidSeverity() {
        EventObservation nan = new EventObservation(UUID.randomUUID(), 18.52, 73.85, NOW, 2, 30, Double.NaN, null);
        EventObservation inf =
                new EventObservation(UUID.randomUUID(), 18.52, 73.85, NOW, 2, 30, Double.NEGATIVE_INFINITY, null);
        assertThat(check(nan)).contains(RejectionReason.INVALID_SEVERITY);
        assertThat(check(inf)).contains(RejectionReason.INVALID_SEVERITY);
    }

    @Test
    void windowLengthBoundaries() {
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 20, 0.4, 28))).isEmpty();
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 120, 0.4, 28))).isEmpty();
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 19.9, 0.4, 28)))
                .contains(RejectionReason.INVALID_WINDOW);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 120.1, 0.4, 28)))
                .contains(RejectionReason.INVALID_WINDOW);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, Double.NaN, 0.4, 28)))
                .contains(RejectionReason.INVALID_WINDOW);
    }

    @Test
    void windowRoughnessMustBeFiniteAndNonNegative() {
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, 0, 28))).isEmpty();
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, -0.01, 28)))
                .contains(RejectionReason.INVALID_WINDOW);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, Double.NaN, 28)))
                .contains(RejectionReason.INVALID_WINDOW);
        assertThat(check(window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 52, Double.POSITIVE_INFINITY, 28)))
                .contains(RejectionReason.INVALID_WINDOW);
    }

    @Test
    void windowWithIdenticalEndsIsRejected() {
        assertThat(check(window(18.52, 73.85, 18.52, 73.85, NOW, 52, 0.4, 28)))
                .contains(RejectionReason.INVALID_WINDOW);
    }

    @Test
    void firstFailingCheckDecidesTheReason() {
        // Outside the region and too slow: region is checked first.
        assertThat(check(event(28.61, 77.20, NOW, 9, 1))).contains(RejectionReason.OUT_OF_REGION);
        // Too slow and invalid severity: speed is checked before severity.
        assertThat(check(event(18.52, 73.85, NOW, 9, 1))).contains(RejectionReason.IMPLAUSIBLE_SPEED);
    }

    @Test
    void headingIsKeptInRangeAndDroppedOutside() {
        assertThat(UploadValidator.normalizeHeading(null)).isNull();
        assertThat(UploadValidator.normalizeHeading(0)).isEqualTo(0);
        assertThat(UploadValidator.normalizeHeading(359)).isEqualTo(359);
        assertThat(UploadValidator.normalizeHeading(360)).isNull();
        assertThat(UploadValidator.normalizeHeading(-1)).isNull();
    }

    @Test
    void validateSplitsAcceptedAndRejectedAndKeepsOrder() {
        EventObservation good = event();
        EventObservation slow = event(18.52, 73.85, NOW, 2, 5);
        EventObservation badHeading =
                new EventObservation(UUID.randomUUID(), 18.52, 73.85, NOW, 2, 30, 2.0, 400);
        WindowObservation goodWindow = window();
        WindowObservation shortWindow = window(18.5201, 73.8565, 18.5206, 73.8568, NOW, 10, 0.4, 28);
        UploadBatch batch = new UploadBatch(
                UUID.randomUUID(), VehicleType.SCOOTER,
                List.of(good, slow, badHeading), List.of(goodWindow, shortWindow));

        ValidationOutcome outcome = validator.validate(batch, NOW);

        assertThat(outcome.events()).extracting(EventObservation::id).containsExactly(good.id(), badHeading.id());
        assertThat(outcome.events().get(1).headingDeg()).isNull();
        assertThat(outcome.windows()).containsExactly(goodWindow);
        assertThat(outcome.rejected()).containsExactly(
                new Rejection(slow.id(), ItemKind.EVENT, RejectionReason.IMPLAUSIBLE_SPEED),
                new Rejection(shortWindow.id(), ItemKind.WINDOW, RejectionReason.INVALID_WINDOW));
    }

    @Test
    void batchShapeRules() {
        assertThat(validator.checkBatchShape(1, 0)).isEmpty();
        assertThat(validator.checkBatchShape(0, 1)).isEmpty();
        assertThat(validator.checkBatchShape(500, 1000)).isEmpty();
        assertThat(validator.checkBatchShape(0, 0)).extracting(FieldProblem::field).containsExactly("events");
        assertThat(validator.checkBatchShape(501, 1)).extracting(FieldProblem::field).containsExactly("events");
        assertThat(validator.checkBatchShape(1, 1001)).extracting(FieldProblem::field).containsExactly("windows");
    }

    @Test
    void limitsComeFromProperties() {
        IngestProperties strict = new IngestProperties(2, 3, 15.0, 120.0, Duration.ofMinutes(1), Duration.ofDays(1),
                30.0, 60.0);
        UploadValidator custom = new UploadValidator(strict, REGION);
        assertThat(custom.checkBatchShape(3, 0)).isNotEmpty();
        assertThat(custom.check(event(18.52, 73.85, NOW, 2, 12), NOW)).contains(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(custom.check(event(18.52, 73.85, NOW.minus(Duration.ofDays(2)), 2, 30), NOW))
                .contains(RejectionReason.TOO_OLD);
    }
}
