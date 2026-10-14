package com.mapmyroad.backend.domain.ingest;

/** Why one uploaded item was dropped (API_SPEC.md section 5.3). Names are part of the API contract. */
public enum RejectionReason {
    OUT_OF_REGION,
    IMPLAUSIBLE_SPEED,
    FUTURE_TIMESTAMP,
    TOO_OLD,
    INVALID_SEVERITY,
    INVALID_WINDOW
}
