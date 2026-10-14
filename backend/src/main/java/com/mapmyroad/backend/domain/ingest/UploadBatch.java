package com.mapmyroad.backend.domain.ingest;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One {@code POST /v1/events} request after structural validation. */
public record UploadBatch(
        UUID tripToken, VehicleType vehicleType, List<EventObservation> events, List<WindowObservation> windows) {

    public UploadBatch {
        Objects.requireNonNull(tripToken, "tripToken");
        Objects.requireNonNull(vehicleType, "vehicleType");
        events = List.copyOf(events);
        windows = List.copyOf(windows);
    }

    @Override
    public String toString() {
        // The trip token is a linking key (DATABASE_SCHEMA.md A11), so only counts are printed.
        return "UploadBatch[events=" + events.size() + ", windows=" + windows.size() + "]";
    }
}
