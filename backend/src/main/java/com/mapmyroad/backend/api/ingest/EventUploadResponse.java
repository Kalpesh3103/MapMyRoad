package com.mapmyroad.backend.api.ingest;

import com.mapmyroad.backend.domain.ingest.IngestResult;
import java.util.List;
import java.util.UUID;

/** Response {@code 202} of {@code POST /v1/events} (API_SPEC.md section 5.3). */
public record EventUploadResponse(Counts accepted, Counts duplicates, List<RejectedItem> rejected) {

    public record Counts(int events, int windows) {}

    /** {@code kind} is {@code event} or {@code window}; {@code reason} is a stable rejection reason name. */
    public record RejectedItem(UUID id, String kind, String reason) {}

    public static EventUploadResponse from(IngestResult result) {
        return new EventUploadResponse(
                new Counts(result.acceptedEvents(), result.acceptedWindows()),
                new Counts(result.duplicateEvents(), result.duplicateWindows()),
                result.rejected().stream()
                        .map(r -> new RejectedItem(r.id(), r.kind().wireName(), r.reason().name()))
                        .toList());
    }
}
