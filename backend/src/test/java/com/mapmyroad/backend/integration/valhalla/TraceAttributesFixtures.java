package com.mapmyroad.backend.integration.valhalla;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Fixture loading and synthetic traces (synthetic coordinates only). */
final class TraceAttributesFixtures {

    private TraceAttributesFixtures() {
    }

    static String fixture(String name) {
        try (InputStream in = TraceAttributesFixtures.class.getResourceAsStream("/valhalla/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<TracePoint> trace(int n) {
        List<TracePoint> points = new ArrayList<>(n);
        Instant start = Instant.parse("2026-10-13T08:30:00Z");
        for (int i = 0; i < n; i++) {
            points.add(new TracePoint(18.52 + i * 0.0001, 73.85 + i * 0.0001, start.plusSeconds(i)));
        }
        return points;
    }
}
