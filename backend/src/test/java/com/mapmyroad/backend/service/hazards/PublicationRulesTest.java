package com.mapmyroad.backend.service.hazards;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PublicationRulesTest {

    @Test
    void coordinatesKeepFiveDecimals() {
        assertThat(PublicationRules.roundCoordinate(73.856712345)).isEqualTo(73.85671);
        assertThat(PublicationRules.roundCoordinate(18.520455)).isEqualTo(18.52046);
        assertThat(PublicationRules.roundCoordinate(-12.3456749)).isEqualTo(-12.34567);
    }

    @Test
    void confidenceKeepsTwoDecimalsIncludingFloatColumnValues() {
        assertThat(PublicationRules.roundConfidence(0.785)).isEqualTo(0.79);
        assertThat(PublicationRules.roundConfidence((double) 0.78f)).isEqualTo(0.78);
        assertThat(PublicationRules.roundConfidence(1.0)).isEqualTo(1.0);
    }

    @Test
    void timestampsAreTruncatedToUtcMidnight() {
        assertThat(PublicationRules.truncateToDay(Instant.parse("2026-10-12T18:45:31.123Z")))
                .isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(PublicationRules.truncateToDay(Instant.parse("2026-10-12T00:00:00Z")).toString())
                .isEqualTo("2026-10-12T00:00:00Z");
    }
}
