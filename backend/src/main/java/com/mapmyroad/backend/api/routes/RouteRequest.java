package com.mapmyroad.backend.api.routes;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.integration.polyline.LatLon;
import com.mapmyroad.backend.service.routing.CandidateRanker;
import com.mapmyroad.backend.service.routing.RouteQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /v1/routes} (API_SPEC.md 5.8). Holds precise coordinates, so {@link #toString()} omits them
 * and the body is never logged. Region and minimum distance are checked in the service.
 *
 * @param preference 0 (prefer fastest) to 1 (prefer smoothest), default 0.5
 */
public record RouteRequest(
        @NotNull @Valid Position origin,
        @NotNull @Valid Position destination,
        @NotNull @Pattern(regexp = VehicleType.WIRE_PATTERN) String vehicleType,
        @DecimalMin("0.0") @DecimalMax("1.0") Double preference) {

    /** A WGS 84 position in decimal degrees. */
    public record Position(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon) {

        LatLon toLatLon() {
            return new LatLon(lat, lon);
        }

        @Override
        public String toString() {
            return "Position[redacted]";
        }
    }

    /** Converts a request that passed Bean Validation. */
    RouteQuery toQuery() {
        VehicleType type = VehicleType.fromWireName(vehicleType)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, "Unknown vehicleType."));
        double p = preference == null ? CandidateRanker.BALANCED_PREFERENCE : preference;
        return new RouteQuery(origin.toLatLon(), destination.toLatLon(), type, p);
    }

    @Override
    public String toString() {
        return "RouteRequest[vehicleType=" + vehicleType + ", preference=" + preference + "]";
    }
}
