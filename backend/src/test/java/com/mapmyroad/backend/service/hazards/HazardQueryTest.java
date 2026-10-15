package com.mapmyroad.backend.service.hazards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mapmyroad.backend.api.error.ApiException;
import org.junit.jupiter.api.Test;

class HazardQueryTest {

    private static final String BBOX = "73.80,18.50,73.85,18.55";

    @Test
    void defaultsFromSpec() {
        HazardQuery query = HazardQuery.of(BBOX, null, null, null);
        assertThat(query.minSeverity()).isEqualTo(1);
        assertThat(query.status()).isEqualTo(HazardStatus.ACTIVE);
        assertThat(query.limit()).isEqualTo(200);
    }

    @Test
    void acceptsNonActiveStatusesAndBounds() {
        assertThat(HazardQuery.of(BBOX, 3, "stale", 500).status()).isEqualTo(HazardStatus.STALE);
        assertThat(HazardQuery.of(BBOX, 1, "fixed", 1).status()).isEqualTo(HazardStatus.FIXED);
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> HazardQuery.of(BBOX, 0, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HazardQuery.of(BBOX, 4, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HazardQuery.of(BBOX, null, "ACTIVE", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HazardQuery.of(BBOX, null, "gone", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HazardQuery.of(BBOX, null, null, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HazardQuery.of(BBOX, null, null, 501)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HazardQuery.of(null, null, null, null)).isInstanceOf(ApiException.class);
    }
}
