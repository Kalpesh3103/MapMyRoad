package com.mapmyroad.backend.service.tiles;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** TileJSON 3.0.0 document (https://github.com/mapbox/tilejson-spec/tree/master/3.0.0). */
public record TileJson(
        String tilejson,
        String name,
        String description,
        String attribution,
        String scheme,
        List<String> tiles,
        int minzoom,
        int maxzoom,
        double[] bounds,
        @JsonProperty("vector_layers") List<VectorLayer> vectorLayers) {

    /** One entry of {@code vector_layers}. {@code fields} maps property names to a type description. */
    public record VectorLayer(String id, String description, int minzoom, int maxzoom, Map<String, String> fields) {}
}
