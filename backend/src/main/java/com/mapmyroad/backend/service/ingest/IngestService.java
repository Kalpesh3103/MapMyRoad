package com.mapmyroad.backend.service.ingest;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.domain.ingest.IngestResult;
import com.mapmyroad.backend.domain.ingest.ItemKind;
import com.mapmyroad.backend.domain.ingest.Rejection;
import com.mapmyroad.backend.domain.ingest.UploadBatch;
import com.mapmyroad.backend.repo.ingest.IngestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingests one upload of events and roughness windows (M4-8, API_SPEC.md section 5.3). Implausible items are
 * rejected individually, the rest is stored idempotently in one transaction. Map-matching and aggregation
 * happen later in jobs.
 *
 * <p>Logs contain counts only: never coordinates, device IDs or trip tokens (CODING_STANDARDS.md 4.6). The
 * request ID comes from the logging context set by the web layer.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    static final String METRIC_ACCEPTED = "mmr.ingest.accepted";
    static final String METRIC_DUPLICATES = "mmr.ingest.duplicates";
    static final String METRIC_REJECTED = "mmr.ingest.rejected";

    private final IngestRepository repository;
    private final MmrProperties mmrProperties;
    private final UploadValidator validator;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public IngestService(
            IngestRepository repository,
            MmrProperties mmrProperties,
            IngestProperties ingestProperties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.repository = repository;
        this.mmrProperties = mmrProperties;
        this.validator = new UploadValidator(ingestProperties, mmrProperties.supportedRegion());
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Validates and stores one upload for the authenticated device.
     *
     * @throws ApiException 503 when uploads are paused, 400 for request-level problems, 401 when the device
     *     no longer exists (its data was deleted while an access token was still valid)
     */
    @Transactional
    public IngestResult ingest(UUID deviceId, UploadBatch batch) {
        if (!mmrProperties.uploadsEnabled()) {
            // Kill switch (SECURITY.md section 7). 503 makes clients retry later with backoff (API_SPEC.md 6).
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Uploads are paused.");
        }
        List<FieldProblem> problems = validator.checkBatchShape(batch.events().size(), batch.windows().size());
        if (!problems.isEmpty()) {
            throw invalidRequest(problems);
        }
        ValidationOutcome outcome = validator.validate(batch, clock.instant());

        if (repository.touchDevice(deviceId) == 0) {
            // Without this check the inserts would fail on the device foreign key with a 500.
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Unknown device.");
        }
        int acceptedEvents = repository.insertEvents(deviceId, batch.tripToken(), batch.vehicleType(), outcome.events());
        int acceptedWindows =
                repository.insertWindows(deviceId, batch.tripToken(), batch.vehicleType(), outcome.windows());

        IngestResult result = new IngestResult(
                acceptedEvents,
                acceptedWindows,
                outcome.events().size() - acceptedEvents,
                outcome.windows().size() - acceptedWindows,
                outcome.rejected());
        recordMetrics(result);
        log.info(
                "Ingested upload: events accepted={} duplicates={}, windows accepted={} duplicates={}, rejected={}",
                result.acceptedEvents(),
                result.duplicateEvents(),
                result.acceptedWindows(),
                result.duplicateWindows(),
                result.rejected().size());
        return result;
    }

    private void recordMetrics(IngestResult result) {
        increment(METRIC_ACCEPTED, ItemKind.EVENT, result.acceptedEvents());
        increment(METRIC_ACCEPTED, ItemKind.WINDOW, result.acceptedWindows());
        increment(METRIC_DUPLICATES, ItemKind.EVENT, result.duplicateEvents());
        increment(METRIC_DUPLICATES, ItemKind.WINDOW, result.duplicateWindows());
        for (Rejection rejection : result.rejected()) {
            meterRegistry
                    .counter(METRIC_REJECTED, "kind", rejection.kind().wireName(), "reason", rejection.reason().name())
                    .increment();
        }
    }

    private void increment(String name, ItemKind kind, int amount) {
        if (amount > 0) {
            meterRegistry.counter(name, "kind", kind.wireName()).increment(amount);
        }
    }

    private static ApiException invalidRequest(List<FieldProblem> problems) {
        List<Map<String, String>> errors = problems.stream()
                .map(p -> Map.of("field", p.field(), "message", p.message()))
                .toList();
        // Same detail and errors shape ({field, message}) as Bean Validation failures in ApiExceptionHandler.
        return new ApiException(ErrorCode.INVALID_REQUEST, "One or more fields are invalid.", Map.of("errors", errors));
    }
}
