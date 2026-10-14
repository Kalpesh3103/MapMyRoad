package com.mapmyroad.backend.domain.ingest;

import java.util.Objects;
import java.util.UUID;

/** One item dropped by plausibility checks. */
public record Rejection(UUID id, ItemKind kind, RejectionReason reason) {

    public Rejection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
    }
}
