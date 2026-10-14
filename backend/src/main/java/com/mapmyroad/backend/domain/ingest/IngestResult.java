package com.mapmyroad.backend.domain.ingest;

import java.util.List;

/** Outcome of one upload: newly stored items, items already stored earlier, and rejected items. */
public record IngestResult(
        int acceptedEvents, int acceptedWindows, int duplicateEvents, int duplicateWindows, List<Rejection> rejected) {

    public IngestResult {
        rejected = List.copyOf(rejected);
    }
}
