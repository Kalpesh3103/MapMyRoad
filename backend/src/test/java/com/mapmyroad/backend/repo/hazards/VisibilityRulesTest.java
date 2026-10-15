package com.mapmyroad.backend.repo.hazards;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.config.VisibilityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Guards the shape of the shared rules; their effect on data is covered by the PostGIS integration tests. */
class VisibilityRulesTest {

    @Test
    void bindsEveryThresholdUsedByTheFragments() {
        MapSqlParameterSource params = new VisibilityRules(VisibilityProperties.defaults()).bind(new MapSqlParameterSource());

        for (String fragment : List.of(
                VisibilityRules.SEGMENT_VISIBLE,
                VisibilityRules.HAZARD_VISIBLE_ANY_STATUS,
                VisibilityRules.HAZARD_VISIBLE_IN_TILES)) {
            for (String name : params.getParameterNames()) {
                if (fragment.contains(":" + name)) {
                    assertThat(params.getValue(name)).isNotNull();
                }
            }
        }
        assertThat(params.getValue("visLowTrafficHighways")).isEqualTo("residential,service,living_street,unclassified");
        assertThat(params.getValue("visExcludedAccess")).isEqualTo("private,no");
        assertThat(params.getValue("visMinDevicesLowTraffic")).isEqualTo(3);
    }

    @Test
    void tilesAndApiShareThePrivacyRules() {
        assertThat(VisibilityRules.HAZARD_VISIBLE_IN_TILES).startsWith(VisibilityRules.HAZARD_VISIBLE_ANY_STATUS);
        assertThat(VisibilityRules.HAZARD_VISIBLE_ANY_STATUS).startsWith(VisibilityRules.HAZARD_PRIVACY);
        assertThat(VisibilityRules.HAZARD_VISIBLE_IN_TILES).contains("h.status = 'active'");
        assertThat(VisibilityRules.HAZARD_PRIVACY)
                .contains("suppressed_reason IS NULL")
                .contains(":visMinDevicesLowTraffic")
                .contains("vw.highway IS NULL")
                .contains("vw.access");
        assertThat(VisibilityRules.SEGMENT_VISIBLE)
                .contains(":visMinSamples")
                .contains(":visMinDevicesLowTraffic")
                .contains("vw.access");
    }
}
