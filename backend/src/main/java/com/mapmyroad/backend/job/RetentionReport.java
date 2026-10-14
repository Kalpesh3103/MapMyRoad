package com.mapmyroad.backend.job;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rows affected per retention step in one run, in execution order. Empty when the run was skipped because
 * another run was still in progress.
 */
public record RetentionReport(Map<String, Long> rowsByStep) {

    public RetentionReport {
        rowsByStep = Collections.unmodifiableMap(new LinkedHashMap<>(rowsByStep));
    }

    public long rows(String step) {
        return rowsByStep.getOrDefault(step, 0L);
    }
}
