package com.mapmyroad.backend.service.ingest;

import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.Rejection;
import com.mapmyroad.backend.domain.ingest.WindowObservation;
import java.util.List;

/** Items that passed the plausibility checks, in request order, and the items that did not. */
public record ValidationOutcome(List<EventObservation> events, List<WindowObservation> windows, List<Rejection> rejected) {

    public ValidationOutcome {
        events = List.copyOf(events);
        windows = List.copyOf(windows);
        rejected = List.copyOf(rejected);
    }
}
