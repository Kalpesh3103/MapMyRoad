package com.mapmyroad.backend.service.tiles;

import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.config.TileProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Builds the TileJSON document for {@code GET /v1/tiles/tilejson.json} (API_SPEC.md 5.4). Static per config. */
@Service
public class TileJsonService {

    static final String TILE_PATH = "/v1/tiles/{z}/{x}/{y}.mvt";

    private final TileJson document;

    public TileJsonService(TileProperties tileProperties, MmrProperties mmrProperties) {
        this.document = build(tileProperties.publicBaseUrl(), mmrProperties.supportedRegion().toArray());
    }

    public TileJson tileJson() {
        return document;
    }

    static TileJson build(String publicBaseUrl, double[] bounds) {
        Map<String, String> roadFields = new LinkedHashMap<>();
        roadFields.put("score", "Number: 0 to 100, higher is smoother");
        roadFields.put("samples", "Number: roughness samples");
        roadFields.put("devices", "Number: distinct contributing devices");
        roadFields.put("ageDays", "Number: whole days since the last observation");
        Map<String, String> hazardFields = new LinkedHashMap<>();
        hazardFields.put("id", "String: hazard ID");
        hazardFields.put("sev", "Number: severity 1 to 3");
        hazardFields.put("conf", "Number: confidence 0 to 1");
        hazardFields.put("status", "String: active");
        hazardFields.put("seenDays", "Number: whole days since last seen");
        return new TileJson(
                "3.0.0",
                "MapMyRoad road quality",
                "Crowdsourced road roughness and potholes",
                "© OpenStreetMap contributors",
                "xyz",
                List.of(publicBaseUrl + TILE_PATH),
                TileLayers.SOURCE_MIN_ZOOM,
                TileLayers.SOURCE_MAX_ZOOM,
                bounds,
                List.of(
                        new TileJson.VectorLayer(
                                TileLayers.ROADS,
                                "Road segment roughness",
                                TileLayers.ROADS_MIN_ZOOM,
                                TileLayers.ROADS_MAX_ZOOM,
                                Collections.unmodifiableMap(roadFields)),
                        new TileJson.VectorLayer(
                                TileLayers.HAZARDS,
                                "Active potholes and severe bumps",
                                TileLayers.HAZARDS_MIN_ZOOM,
                                TileLayers.HAZARDS_MAX_ZOOM,
                                Collections.unmodifiableMap(hazardFields))));
    }
}
