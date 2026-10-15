package com.mapmyroad.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.config.MatchingProperties;
import com.mapmyroad.backend.integration.valhalla.MatchedPoint;
import com.mapmyroad.backend.integration.valhalla.ValhallaCosting;
import com.mapmyroad.backend.integration.valhalla.ValhallaMapMatcher;
import com.mapmyroad.backend.integration.valhalla.ValhallaUnavailableException;
import com.mapmyroad.backend.repo.matching.MatchedRow;
import com.mapmyroad.backend.repo.matching.MatchingRepository;
import com.mapmyroad.backend.repo.matching.PendingEvent;
import com.mapmyroad.backend.repo.matching.PendingWindow;
import com.mapmyroad.backend.repo.matching.WindowWayCandidates;
import com.mapmyroad.backend.repo.matching.WindowWayChoice;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Synthetic coordinates only. */
class MatchingServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-13T08:30:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC);

    private final MatchingRepository repository = mock(MatchingRepository.class);
    private final ValhallaMapMatcher matcher = mock(ValhallaMapMatcher.class);
    private final MatchingService service = new MatchingService(
            repository, matcher, MatchingProperties.defaults(), transactionManager(), CLOCK);

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }

    @Test
    void acceptedWayAppliesSnapDistance() {
        assertThat(MatchingService.acceptedWay(new MatchedPoint(7L, 0, 0, 25.0), 25)).isEqualTo(7L);
        assertThat(MatchingService.acceptedWay(new MatchedPoint(7L, 0, 0, 25.1), 25)).isNull();
        assertThat(MatchingService.acceptedWay(new MatchedPoint(7L, 0, 0, null), 25)).isNull();
        assertThat(MatchingService.acceptedWay(new MatchedPoint(null, 0, 0, null), 25)).isNull();
    }

    @Test
    void writesWaysAndBucketsForAMatchedTrip() {
        UUID trip = UUID.randomUUID();
        PendingEvent event = new PendingEvent(UUID.randomUUID(), 1.5, 1.5, T0.plusSeconds(7), 1);
        PendingEvent farEvent = new PendingEvent(UUID.randomUUID(), 3.0, 3.0, T0.plusSeconds(30), 1);
        PendingWindow window = new PendingWindow(UUID.randomUUID(), 1.0, 1.0, 2.0, 2.0, T0.plusSeconds(10), 50, 36, 1);
        when(repository.findTripEvents(trip)).thenReturn(List.of(event, farEvent));
        when(repository.findTripWindows(trip)).thenReturn(List.of(window));
        // Trace order: window start, event, window end, far event.
        when(matcher.match(anyList(), eq(ValhallaCosting.MOTORCYCLE))).thenReturn(List.of(
                new MatchedPoint(100L, 1, 1, 2.0),
                new MatchedPoint(100L, 1.5, 1.5, 3.0),
                new MatchedPoint(200L, 2, 2, 4.0),
                new MatchedPoint(300L, 3, 3, 80.0)));
        when(repository.chooseWindowWays(anyList())).thenReturn(
                List.of(new WindowWayChoice(window.id(), 100L, 230, 0.5)));

        TripMatchResult result = service.matchTrip(trip);

        assertThat(result).isEqualTo(new TripMatchResult(1, 1, 1, 0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WindowWayCandidates>> candidates = ArgumentCaptor.forClass(List.class);
        verify(repository).chooseWindowWays(candidates.capture());
        assertThat(candidates.getValue()).containsExactly(new WindowWayCandidates(window.id(), 100L, 200L));
        verify(repository).updateEvents(eq(List.of(new MatchedRow(event.id(), 100L, null),
                new MatchedRow(farEvent.id(), null, null))), eq(CLOCK.instant()));
        // 0.5 * 230 m = 115 m -> bucket 2
        verify(repository).updateWindows(eq(List.of(new MatchedRow(window.id(), 100L, 2))), eq(CLOCK.instant()));
    }

    @Test
    void valhallaUnavailableWritesNothing() {
        UUID trip = UUID.randomUUID();
        when(repository.findTripEvents(trip)).thenReturn(List.of(
                new PendingEvent(UUID.randomUUID(), 1, 1, T0, 2),
                new PendingEvent(UUID.randomUUID(), 2, 2, T0.plusSeconds(5), 2)));
        when(repository.findTripWindows(trip)).thenReturn(List.of());
        when(matcher.match(anyList(), any())).thenThrow(new ValhallaUnavailableException("down"));

        assertThatThrownBy(() -> service.matchTrip(trip)).isInstanceOf(ValhallaUnavailableException.class);
        verify(repository, never()).updateEvents(anyList(), any());
        verify(repository, never()).updateWindows(anyList(), any());
    }

    @Test
    void unexpectedMatcherFailureMarksTripUnmatched() {
        UUID trip = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(repository.findTripEvents(trip)).thenReturn(List.of(
                new PendingEvent(eventId, 1, 1, T0, 3),
                new PendingEvent(UUID.randomUUID(), 2, 2, T0.plusSeconds(5), 3)));
        when(repository.findTripWindows(trip)).thenReturn(List.of());
        when(matcher.match(anyList(), any())).thenThrow(new IllegalStateException("bug"));

        TripMatchResult result = service.matchTrip(trip);

        assertThat(result).isEqualTo(new TripMatchResult(0, 2, 0, 0));
    }

    @Test
    void singlePointTripIsMarkedWithoutCallingValhalla() {
        UUID trip = UUID.randomUUID();
        when(repository.findTripEvents(trip)).thenReturn(List.of(new PendingEvent(UUID.randomUUID(), 1, 1, T0, 1)));
        when(repository.findTripWindows(trip)).thenReturn(List.of());

        TripMatchResult result = service.matchTrip(trip);

        assertThat(result).isEqualTo(new TripMatchResult(0, 1, 0, 0));
        verify(matcher, never()).match(anyList(), any());
    }

    @Test
    void windowWithoutUsableWayGetsNoBucket() {
        UUID windowId = UUID.randomUUID();
        List<MatchedRow> rows = MatchingService.windowRows(
                List.of(new WindowWayCandidates(windowId, null, null)), Map.of());

        assertThat(rows).containsExactly(new MatchedRow(windowId, null, null));
    }
}
