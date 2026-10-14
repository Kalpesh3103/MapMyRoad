package com.mapmyroad.backend.api.ingest;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.UploadBatch;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.domain.ingest.WindowObservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /v1/events} (API_SPEC.md section 5.3). Bean Validation covers structure (required fields,
 * known vehicle type) and fails the whole request with 400. Plausibility of each item is checked later and
 * rejects only that item. Array caps come from configuration and are checked in the service; the body itself is
 * limited to 256 KB after decompression by the web layer, which bounds the array sizes Jackson will parse.
 *
 * <p>Numbers are boxed so that a missing field is {@code null} and fails {@code @NotNull} instead of silently
 * becoming 0. Records hold precise coordinates, so {@code toString()} is overridden to omit them.
 */
public record EventUploadRequest(
        @NotNull UUID tripToken,
        @NotNull @Pattern(regexp = VehicleType.WIRE_PATTERN) String vehicleType,
        @NotNull List<@NotNull @Valid EventItem> events,
        @NotNull List<@NotNull @Valid WindowItem> windows) {

    /** One detected bump. {@code headingDeg} is optional. */
    public record EventItem(
            @NotNull UUID id,
            @NotNull Double lat,
            @NotNull Double lon,
            @NotNull Instant observedAt,
            @NotNull Integer severity,
            @NotNull Double speedKmh,
            @NotNull Double peakScore,
            Integer headingDeg) {

        EventObservation toDomain() {
            return new EventObservation(id, lat, lon, observedAt, severity, speedKmh, peakScore, headingDeg);
        }

        @Override
        public String toString() {
            return "EventItem[id=" + id + "]";
        }
    }

    /** One roughness window of about 50 m. */
    public record WindowItem(
            @NotNull UUID id,
            @NotNull Double startLat,
            @NotNull Double startLon,
            @NotNull Double endLat,
            @NotNull Double endLon,
            @NotNull Instant observedAt,
            @NotNull Double lengthM,
            @NotNull Double roughness,
            @NotNull Double speedKmh) {

        WindowObservation toDomain() {
            return new WindowObservation(
                    id, startLat, startLon, endLat, endLon, observedAt, lengthM, roughness, speedKmh);
        }

        @Override
        public String toString() {
            return "WindowItem[id=" + id + "]";
        }
    }

    /** Converts a request that passed Bean Validation. */
    UploadBatch toDomain() {
        VehicleType type = VehicleType.fromWireName(vehicleType)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, "Unknown vehicleType."));
        return new UploadBatch(
                tripToken,
                type,
                events.stream().map(EventItem::toDomain).toList(),
                windows.stream().map(WindowItem::toDomain).toList());
    }

    @Override
    public String toString() {
        // The trip token is a linking key and items hold coordinates, so only counts are printed.
        return "EventUploadRequest[events=" + (events == null ? 0 : events.size())
                + ", windows=" + (windows == null ? 0 : windows.size()) + "]";
    }
}
