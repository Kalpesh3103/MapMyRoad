package com.mapmyroad.backend.repo.hazards;

import com.mapmyroad.backend.config.VisibilityProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * The single definition of what aggregated data may be published (API_SPEC.md 5.4, SECURITY.md T2 and 6.4).
 * Tiles and the hazards API both build their WHERE clauses from these fragments, so a rule cannot drift between
 * the two. The fragments are constant SQL text with named parameters; thresholds are bound by {@link #bind}.
 *
 * <p>Fixed aliases: {@code s} for {@code road_segment_stat}, {@code h} for {@code hazard} and {@code vw} for the
 * joined {@code osm_way}. The join is a LEFT JOIN because a way can disappear in an OSM refresh before the
 * reconciliation job re-keys its statistics; an unknown way gets the stricter low-traffic threshold, which is
 * the conservative choice for re-identification (T2).
 *
 * <p>Tag lists are bound as one comma-separated string and split with {@code string_to_array}, which keeps the
 * SQL valid for empty lists (an expanded {@code IN ()} would not be) and needs no JDBC array handling.
 */
@Component
public class VisibilityRules {

    /** {@code FROM} clause for segment queries. */
    public static final String SEGMENT_FROM = "road_segment_stat s LEFT JOIN osm_way vw ON vw.way_id = s.way_id";

    /** {@code FROM} clause for hazard queries. */
    public static final String HAZARD_FROM = "hazard h LEFT JOIN osm_way vw ON vw.way_id = h.way_id";

    private static final String DEVICE_THRESHOLD = """
            (CASE WHEN vw.highway IS NULL
                    OR vw.highway = ANY (string_to_array(:visLowTrafficHighways, ','))
                  THEN :visMinDevicesLowTraffic ELSE :visMinDevices END)""";

    // access is NULL for most ways (tag absent) and for every way until the OSM import fills the column (V9).
    private static final String ACCESS_ALLOWED =
            "(vw.access IS NULL OR NOT (vw.access = ANY (string_to_array(:visExcludedAccess, ','))))";

    /** Road segment may be published: enough samples, enough devices for its road type, not a private way. */
    public static final String SEGMENT_VISIBLE = "s.sample_count >= :visMinSamples"
            + " AND s.distinct_devices >= " + DEVICE_THRESHOLD
            + " AND " + ACCESS_ALLOWED;

    /**
     * Privacy rules for a hazard that apply whatever its status: corroborated by enough devices for its road type,
     * not on a private way, and not suppressed (known speed breaker, crossing, repeat pattern).
     */
    public static final String HAZARD_PRIVACY = "h.suppressed_reason IS NULL"
            + " AND h.distinct_devices >= " + DEVICE_THRESHOLD
            + " AND " + ACCESS_ALLOWED;

    /** {@link #HAZARD_PRIVACY} plus the confidence floor. Used by the hazards API, which filters status itself. */
    public static final String HAZARD_VISIBLE_ANY_STATUS =
            HAZARD_PRIVACY + " AND h.confidence >= :visHazardMinConfidence";

    /** What the public tiles show: visible and active (stale and fixed hazards are excluded, API_SPEC.md 5.4). */
    public static final String HAZARD_VISIBLE_IN_TILES = HAZARD_VISIBLE_ANY_STATUS + " AND h.status = 'active'";

    private final VisibilityProperties properties;

    public VisibilityRules(VisibilityProperties properties) {
        this.properties = properties;
    }

    /** Adds the threshold parameters used by the fragments. Returns {@code params} for chaining. */
    public MapSqlParameterSource bind(MapSqlParameterSource params) {
        return params.addValue("visMinSamples", properties.minSamples())
                .addValue("visMinDevices", properties.minDevices())
                .addValue("visMinDevicesLowTraffic", properties.minDevicesLowTraffic())
                .addValue("visLowTrafficHighways", String.join(",", properties.lowTrafficHighways()))
                .addValue("visExcludedAccess", String.join(",", properties.excludedAccessValues()))
                .addValue("visHazardMinConfidence", properties.hazardMinConfidence());
    }
}
