package com.mapmyroad.backend.service.tiles;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.api.error.FieldProblem;
import java.util.List;
import java.util.Map;

/** A tile address in the XYZ scheme (Web Mercator, y counted from the north). */
public record TileCoord(int z, int x, int y) {

    /** Highest zoom accepted. MapLibre never asks for more, and 2^22 still fits an int. */
    public static final int MAX_ZOOM = 22;

    /**
     * Validates z/x/y: zoom within 0..22 and x, y within 0..2^z - 1.
     *
     * @throws ApiException 400 INVALID_REQUEST naming the bad coordinate
     */
    public static TileCoord of(int z, int x, int y) {
        if (z < 0 || z > MAX_ZOOM) {
            throw invalid("z", "must be between 0 and " + MAX_ZOOM);
        }
        int size = 1 << z;
        if (x < 0 || x >= size) {
            throw invalid("x", "is outside the tile range for this zoom");
        }
        if (y < 0 || y >= size) {
            throw invalid("y", "is outside the tile range for this zoom");
        }
        return new TileCoord(z, x, y);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.INVALID_REQUEST,
                "One or more fields are invalid.",
                Map.of("errors", List.of(new FieldProblem(field, message))));
    }
}
