package com.mapmyroad.backend.service.aggregation;

import com.mapmyroad.backend.repo.aggregation.SegmentKey;
import com.mapmyroad.backend.repo.aggregation.SegmentStatRow;
import com.mapmyroad.backend.repo.aggregation.SegmentStatUpdate;
import com.mapmyroad.backend.repo.aggregation.WindowSample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Turns a batch of windows plus the current segment rows into segment updates. Pure and deterministic. */
public final class SegmentStatPlanner {

    private SegmentStatPlanner() {
    }

    public static List<SegmentStatUpdate> plan(
            List<WindowSample> windows,
            List<SegmentStatRow> existing,
            double ewmaAlpha,
            double roughnessGood,
            double roughnessPoor) {
        Map<SegmentKey, SegmentStatRow> current = existing.stream()
                .collect(Collectors.toMap(SegmentStatRow::key, Function.identity(), (a, b) -> a));
        Map<SegmentKey, List<WindowSample>> byKey = new TreeMap<>();
        for (WindowSample window : windows) {
            if (window.wayId() != null && window.bucket() != null && !Double.isNaN(window.roughness())) {
                byKey.computeIfAbsent(new SegmentKey(window.wayId(), window.bucket()), k -> new ArrayList<>())
                        .add(window);
            }
        }
        List<SegmentStatUpdate> updates = new ArrayList<>(byKey.size());
        byKey.forEach((key, samples) -> {
            samples.sort(Comparator.comparing(WindowSample::observedAt).thenComparing(WindowSample::id));
            SegmentStatRow row = current.get(key);
            double avg = SegmentMath.ewma(row == null ? null : row.roughnessAvg(),
                    samples.stream().map(WindowSample::roughness).toList(), ewmaAlpha);
            Instant lastObservedAt = samples.get(samples.size() - 1).observedAt();
            updates.add(new SegmentStatUpdate(key, avg, SegmentMath.score(avg, roughnessGood, roughnessPoor),
                    samples.size(), lastObservedAt));
        });
        return updates;
    }
}
