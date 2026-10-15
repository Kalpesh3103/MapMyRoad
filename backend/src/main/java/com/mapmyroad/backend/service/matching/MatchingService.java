package com.mapmyroad.backend.service.matching;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Map-matches one trip at a time (M4-9, DATABASE_SCHEMA.md A6): build the trace, call Valhalla outside any
 * transaction, then write {@code way_id}, {@code bucket}, {@code matched_at} and clear {@code trip_token} for every
 * row of the trace in one short transaction. Rows that could not be matched are still marked processed with no
 * way, so aggregation clusters them by distance only.
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final MatchingRepository repository;
    private final ValhallaMapMatcher matcher;
    private final MatchingProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public MatchingService(
            MatchingRepository repository,
            ValhallaMapMatcher matcher,
            MatchingProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.matcher = matcher;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Trips ready for matching, oldest first. */
    public List<UUID> pendingTrips(int limit) {
        return repository.findPendingTrips(clock.instant().minus(properties.settleDelay()), limit);
    }

    /**
     * Matches one trip. Throws {@link ValhallaUnavailableException} without writing anything when Valhalla
     * cannot be used, so the rows stay pending for the next run.
     */
    public TripMatchResult matchTrip(UUID tripToken) {
        List<PendingEvent> events = repository.findTripEvents(tripToken);
        List<PendingWindow> windows = repository.findTripWindows(tripToken);
        if (events.isEmpty() && windows.isEmpty()) {
            return TripMatchResult.EMPTY;
        }
        Trace trace = TraceBuilder.build(events, windows);
        List<MatchedPoint> matched = matchOrNull(trace, vehicleCode(events, windows));
        Resolution resolution = resolve(trace, events, windows, matched, properties.maxSnapDistanceM());

        Instant matchedAt = clock.instant();
        TripMatchResult result = transactions.execute(status -> {
            Map<UUID, WindowWayChoice> choices = new HashMap<>();
            for (WindowWayChoice choice : repository.chooseWindowWays(resolution.windowCandidates())) {
                choices.put(choice.windowId(), choice);
            }
            List<MatchedRow> windowRows = windowRows(resolution.windowCandidates(), choices);
            repository.updateEvents(resolution.eventRows(), matchedAt);
            repository.updateWindows(windowRows, matchedAt);
            return counts(resolution.eventRows(), windowRows);
        });
        return result == null ? TripMatchResult.EMPTY : result;
    }

    /** Marks one batch of rows without trip token (events and windows) as processed. Returns the rows touched. */
    public int markOrphanBatch() {
        Instant now = clock.instant();
        Integer touched = transactions.execute(status ->
                repository.markOrphanEvents(now, properties.orphanBatchSize())
                        + repository.markOrphanWindows(now, properties.orphanBatchSize()));
        return touched == null ? 0 : touched;
    }

    public long backlogEvents() {
        return repository.countBacklogEvents();
    }

    public long backlogWindows() {
        return repository.countBacklogWindows();
    }

    /**
     * Returns null when the trip cannot be matched for reasons tied to this trip (fewer than 2 points, unknown
     * vehicle code, an unexpected matcher failure), so one bad trip cannot block the queue. Valhalla being
     * unavailable propagates.
     */
    private List<MatchedPoint> matchOrNull(Trace trace, int vehicleCode) {
        if (trace.points().size() < 2) {
            return null;
        }
        try {
            List<MatchedPoint> result = matcher.match(trace.points(), ValhallaCosting.forVehicleCode(vehicleCode));
            return result != null && result.size() == trace.points().size() ? result : null;
        } catch (ValhallaUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Map matching of one trip failed, rows kept without way: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** One upload carries one vehicle type, so the first row decides. */
    private static int vehicleCode(List<PendingEvent> events, List<PendingWindow> windows) {
        return events.isEmpty() ? windows.get(0).vehicleType() : events.get(0).vehicleType();
    }

    /** Event results and window way candidates for a matched trace ({@code matched} null: nothing matched). */
    record Resolution(List<MatchedRow> eventRows, List<WindowWayCandidates> windowCandidates) {
    }

    static Resolution resolve(
            Trace trace,
            List<PendingEvent> events,
            List<PendingWindow> windows,
            List<MatchedPoint> matched,
            double maxSnapDistanceM) {
        List<MatchedRow> eventRows = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            Long wayId = matched == null ? null : acceptedWay(matched.get(trace.eventPoint()[i]), maxSnapDistanceM);
            eventRows.add(new MatchedRow(events.get(i).id(), wayId, null));
        }
        List<WindowWayCandidates> candidates = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            Long startWay = matched == null
                    ? null : acceptedWay(matched.get(trace.windowStartPoint()[i]), maxSnapDistanceM);
            Long endWay = matched == null
                    ? null : acceptedWay(matched.get(trace.windowEndPoint()[i]), maxSnapDistanceM);
            candidates.add(new WindowWayCandidates(windows.get(i).id(), startWay, endWay));
        }
        return new Resolution(eventRows, candidates);
    }

    /** The way of a matched point, or null when unmatched or snapped too far (likely a wrong, parallel road). */
    static Long acceptedWay(MatchedPoint point, double maxSnapDistanceM) {
        if (point == null || !point.matched() || point.distanceFromTraceM() == null) {
            return null;
        }
        return point.distanceFromTraceM() <= maxSnapDistanceM ? point.wayId() : null;
    }

    static List<MatchedRow> windowRows(List<WindowWayCandidates> candidates, Map<UUID, WindowWayChoice> choices) {
        List<MatchedRow> rows = new ArrayList<>(candidates.size());
        for (WindowWayCandidates candidate : candidates) {
            WindowWayChoice choice = choices.get(candidate.windowId());
            if (choice == null) {
                rows.add(new MatchedRow(candidate.windowId(), null, null));
            } else {
                rows.add(new MatchedRow(candidate.windowId(), choice.wayId(),
                        SegmentBuckets.bucketFor(choice.fraction(), choice.wayLengthM())));
            }
        }
        return rows;
    }

    private static TripMatchResult counts(List<MatchedRow> eventRows, List<MatchedRow> windowRows) {
        int eventsMatched = (int) eventRows.stream().filter(row -> row.wayId() != null).count();
        int windowsMatched = (int) windowRows.stream().filter(row -> row.wayId() != null).count();
        return new TripMatchResult(eventsMatched, eventRows.size() - eventsMatched,
                windowsMatched, windowRows.size() - windowsMatched);
    }
}
