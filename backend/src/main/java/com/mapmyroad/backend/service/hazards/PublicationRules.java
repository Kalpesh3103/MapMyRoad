package com.mapmyroad.backend.service.hazards;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Precision applied to published hazard data (SECURITY.md 6.4): coordinates to 5 decimals (about 1.1 m of
 * latitude, never sub-meter in practice and well within "a few meters"), timestamps to the UTC day, confidence to
 * 2 decimals. The same coordinate grid is used for tiles ({@code TileRepository.PUBLISHED_GRID_DEG}).
 */
public final class PublicationRules {

    public static final int COORDINATE_DECIMALS = 5;
    public static final int CONFIDENCE_DECIMALS = 2;

    private PublicationRules() {}

    public static double roundCoordinate(double degrees) {
        return round(degrees, COORDINATE_DECIMALS);
    }

    public static double roundConfidence(double confidence) {
        return round(confidence, CONFIDENCE_DECIMALS);
    }

    /** Midnight UTC of the same day. */
    public static Instant truncateToDay(Instant instant) {
        return instant.truncatedTo(ChronoUnit.DAYS);
    }

    // BigDecimal.valueOf uses the shortest decimal representation, so 0.785 rounds to 0.79 rather than 0.78.
    private static double round(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
