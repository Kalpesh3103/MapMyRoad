package com.mapmyroad.backend.api.ingest;

import com.mapmyroad.backend.api.auth.CurrentDevice;
import com.mapmyroad.backend.api.ratelimit.RateLimitPolicy;
import com.mapmyroad.backend.api.ratelimit.RateLimited;
import com.mapmyroad.backend.domain.ingest.IngestResult;
import com.mapmyroad.backend.service.ingest.IngestService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /v1/events}: upload of events and roughness windows from one trip (API_SPEC.md section 5.3). */
@RestController
@RequestMapping("/v1/events")
public class EventUploadController {

    private final IngestService ingestService;

    public EventUploadController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.EVENTS)
    public ResponseEntity<EventUploadResponse> upload(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EventUploadRequest request) {
        UUID deviceId = CurrentDevice.id(jwt);
        IngestResult result = ingestService.ingest(deviceId, request.toDomain());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(EventUploadResponse.from(result));
    }
}
