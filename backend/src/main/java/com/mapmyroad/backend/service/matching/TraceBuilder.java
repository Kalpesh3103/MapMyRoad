package com.mapmyroad.backend.service.matching;

import com.mapmyroad.backend.integration.valhalla.TracePoint;
import com.mapmyroad.backend.repo.matching.PendingEvent;
import com.mapmyroad.backend.repo.matching.PendingWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the map-matching trace of one trip from its event points and window start and end points, ordered by
 * time (DATABASE_SCHEMA.md A6). Pure and deterministic.
 *
 * <p>A window's {@code observedAt} is the time at its end. Its start time is estimated from the window length and
 * speed, so the start point sorts before events that happened inside the window. Ties keep input order (events,
 * then windows, start before end), which keeps the result stable.
 */
public final class TraceBuilder {

    /** Below this speed the duration estimate becomes meaningless; ingest rejects slower windows anyway. */
    private static final double MIN_SPEED_MPS = 1.0;

    /** Upper bound on a window's estimated duration (a 120 m window at walking pace). */
    private static final Duration MAX_WINDOW_DURATION = Duration.ofMinutes(2);

    private TraceBuilder() {
    }

    public static Trace build(List<PendingEvent> events, List<PendingWindow> windows) {
        List<Entry> entries = new ArrayList<>(events.size() + 2 * windows.size());
        int seq = 0;
        for (int i = 0; i < events.size(); i++) {
            PendingEvent e = events.get(i);
            entries.add(new Entry(new TracePoint(e.lat(), e.lon(), e.observedAt()), seq++, Kind.EVENT, i));
        }
        for (int i = 0; i < windows.size(); i++) {
            PendingWindow w = windows.get(i);
            Instant start = w.observedAt().minus(estimatedDuration(w.lengthM(), w.speedKmh()));
            entries.add(new Entry(new TracePoint(w.startLat(), w.startLon(), start), seq++, Kind.WINDOW_START, i));
            entries.add(new Entry(new TracePoint(w.endLat(), w.endLon(), w.observedAt()), seq++, Kind.WINDOW_END, i));
        }
        entries.sort(Comparator.comparing((Entry entry) -> entry.point().time()).thenComparingInt(Entry::seq));

        List<TracePoint> points = new ArrayList<>(entries.size());
        int[] eventPoint = new int[events.size()];
        int[] windowStart = new int[windows.size()];
        int[] windowEnd = new int[windows.size()];
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            points.add(entry.point());
            switch (entry.kind()) {
                case EVENT -> eventPoint[entry.ref()] = index;
                case WINDOW_START -> windowStart[entry.ref()] = index;
                case WINDOW_END -> windowEnd[entry.ref()] = index;
            }
        }
        return new Trace(List.copyOf(points), eventPoint, windowStart, windowEnd);
    }

    static Duration estimatedDuration(double lengthM, double speedKmh) {
        if (!(lengthM > 0)) {
            return Duration.ZERO;
        }
        double speedMps = Math.max(MIN_SPEED_MPS, Double.isNaN(speedKmh) ? 0 : speedKmh / 3.6);
        Duration duration = Duration.ofMillis(Math.round(lengthM / speedMps * 1000));
        return duration.compareTo(MAX_WINDOW_DURATION) > 0 ? MAX_WINDOW_DURATION : duration;
    }

    private enum Kind { EVENT, WINDOW_START, WINDOW_END }

    private record Entry(TracePoint point, int seq, Kind kind, int ref) {
    }
}
