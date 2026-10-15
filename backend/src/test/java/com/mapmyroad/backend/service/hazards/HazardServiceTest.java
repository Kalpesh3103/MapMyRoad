package com.mapmyroad.backend.service.hazards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.repo.hazards.HazardRepository;
import com.mapmyroad.backend.repo.hazards.HazardRow;
import com.mapmyroad.backend.repo.hazards.HazardState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class HazardServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final UUID HAZARD = UUID.fromString("4b1e9c5a-2d7f-4a39-9f55-0c3d7a2e8b14");
    private static final UUID DEVICE = UUID.fromString("5f0c7a2e-2b1d-4c8e-9a3f-111111111111");

    private final HazardRepository repository = mock(HazardRepository.class);
    private final HazardService service = new HazardService(
            repository, IngestProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

    private static HazardRow row() {
        return new HazardRow(
                HAZARD,
                73.856712345,
                18.520455,
                3,
                0.7799999713897705,
                "active",
                Instant.parse("2026-09-02T07:10:00Z"),
                Instant.parse("2026-10-12T18:45:00Z"),
                4,
                0);
    }

    private static FeedbackCommand vote(Instant observedAt) {
        return new FeedbackCommand(HAZARD, DEVICE, FeedbackAction.CONFIRM, 18.52045, 73.85672, observedAt);
    }

    @Test
    void publishAppliesPrecisionRules() {
        PublishedHazard published = HazardService.publish(row());

        assertThat(published.lon()).isEqualTo(73.85671);
        assertThat(published.lat()).isEqualTo(18.52046);
        assertThat(published.confidence()).isEqualTo(0.78);
        assertThat(published.status()).isEqualTo(HazardStatus.ACTIVE);
        assertThat(published.firstSeenDay()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(published.lastSeenDay()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
    }

    @Test
    void listPassesValidatedQuery() {
        when(repository.findInBbox(73.80, 18.50, 73.85, 18.55, 2, "stale", 10)).thenReturn(List.of(row()));

        List<PublishedHazard> result = service.list(HazardQuery.of("73.80,18.50,73.85,18.55", 2, "stale", 10));

        assertThat(result).hasSize(1);
    }

    @Test
    void getUnknownOrHiddenIs404() {
        when(repository.findVisible(HAZARD)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(HAZARD)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void feedbackStoresVoteRecountsAndQueuesInOrder() {
        when(repository.lockFeedbackTarget(eq(HAZARD), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.of(true));
        when(repository.upsertFeedback(HAZARD, DEVICE, "confirm", NOW.minusSeconds(3600))).thenReturn(true);
        when(repository.recountFeedback(HAZARD)).thenReturn(new HazardState(0.8100000023841858, "active"));

        FeedbackResult result = service.feedback(vote(NOW.minusSeconds(3600)));

        assertThat(result.id()).isEqualTo(HAZARD);
        assertThat(result.confidence()).isEqualTo(0.81);
        assertThat(result.status()).isEqualTo(HazardStatus.ACTIVE);
        InOrder order = inOrder(repository);
        order.verify(repository)
                .lockFeedbackTarget(HAZARD, 18.52045, 73.85672, 50.0, NOW.minus(Duration.ofDays(14)));
        order.verify(repository).upsertFeedback(HAZARD, DEVICE, "confirm", NOW.minusSeconds(3600));
        order.verify(repository).recountFeedback(HAZARD);
        order.verify(repository).enqueueRecompute(HAZARD);
    }

    @Test
    void feedbackOnUnknownHazardIs404() {
        when(repository.lockFeedbackTarget(eq(HAZARD), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.feedback(vote(NOW))).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(repository, never()).upsertFeedback(any(), any(), anyString(), any());
    }

    @Test
    void feedbackFarFromHazardIsTooFarAndNotStored() {
        when(repository.lockFeedbackTarget(eq(HAZARD), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.of(false));

        assertThatThrownBy(() -> service.feedback(vote(NOW))).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.TOO_FAR));
        verify(repository, never()).upsertFeedback(any(), any(), anyString(), any());
        verify(repository, never()).enqueueRecompute(any());
    }

    @Test
    void feedbackFromDeletedDeviceIs401() {
        when(repository.lockFeedbackTarget(eq(HAZARD), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.of(true));
        when(repository.upsertFeedback(any(), any(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.feedback(vote(NOW))).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(repository, never()).enqueueRecompute(any());
    }

    @Test
    void observedAtBounds() {
        service.validateObservedAt(NOW.plus(Duration.ofMinutes(5)), NOW);
        service.validateObservedAt(NOW.minus(Duration.ofDays(14)), NOW);
        assertThatThrownBy(() -> service.validateObservedAt(NOW.plus(Duration.ofMinutes(6)), NOW))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.validateObservedAt(NOW.minus(Duration.ofDays(15)), NOW))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void futureObservedAtIsRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.feedback(vote(NOW.plus(Duration.ofHours(1)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verify(repository, never()).lockFeedbackTarget(any(), anyDouble(), anyDouble(), anyDouble(), any());
    }
}
