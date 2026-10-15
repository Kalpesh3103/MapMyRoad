package com.mapmyroad.backend.service.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.config.TileProperties;
import org.junit.jupiter.api.Test;

class TileJsonServiceTest {

    @Test
    void describesBothLayersWithSpecZoomsAndRegionBounds() {
        TileJson json = new TileJsonService(
                        new TileProperties("https://tiles.test/", null, null, null),
                        new MmrProperties("72.6,17.9,75.1,19.6", null, null))
                .tileJson();

        assertThat(json.tilejson()).isEqualTo("3.0.0");
        assertThat(json.tiles()).containsExactly("https://tiles.test/v1/tiles/{z}/{x}/{y}.mvt");
        assertThat(json.minzoom()).isEqualTo(12);
        assertThat(json.maxzoom()).isEqualTo(18);
        assertThat(json.bounds()).containsExactly(72.6, 17.9, 75.1, 19.6);
        assertThat(json.attribution()).contains("OpenStreetMap contributors");
        assertThat(json.vectorLayers()).hasSize(2);
        TileJson.VectorLayer roads = json.vectorLayers().get(0);
        assertThat(roads.id()).isEqualTo("roads");
        assertThat(roads.minzoom()).isEqualTo(12);
        assertThat(roads.maxzoom()).isEqualTo(16);
        assertThat(roads.fields()).containsOnlyKeys("score", "samples", "devices", "ageDays");
        TileJson.VectorLayer hazards = json.vectorLayers().get(1);
        assertThat(hazards.id()).isEqualTo("hazards");
        assertThat(hazards.minzoom()).isEqualTo(13);
        assertThat(hazards.maxzoom()).isEqualTo(18);
        assertThat(hazards.fields()).containsOnlyKeys("id", "sev", "conf", "status", "seenDays");
    }
}
