package com.mapmyroad.backend.service.tiles;

/** Zoom ranges of the tile layers (API_SPEC.md 5.4), shared by tile generation and TileJSON. */
public final class TileLayers {

    public static final String ROADS = "roads";
    public static final String HAZARDS = "hazards";

    /** Roads are drawn from zoom 12. Their data is designed for 12-16; above 16 the same data is served again. */
    public static final int ROADS_MIN_ZOOM = 12;

    public static final int ROADS_MAX_ZOOM = 16;

    public static final int HAZARDS_MIN_ZOOM = 13;

    public static final int HAZARDS_MAX_ZOOM = 18;

    /** Source zoom range announced in TileJSON. Below 12 tiles are always empty, which also limits scraping. */
    public static final int SOURCE_MIN_ZOOM = ROADS_MIN_ZOOM;

    public static final int SOURCE_MAX_ZOOM = HAZARDS_MAX_ZOOM;

    private TileLayers() {}

    /**
     * Roads are included for every zoom from 12. MapLibre requests source tiles up to {@link #SOURCE_MAX_ZOOM}
     * and uses them for all layers, so leaving roads out above 16 would make them disappear there.
     */
    public static boolean includesRoads(int z) {
        return z >= ROADS_MIN_ZOOM;
    }

    /** Hazards from 13 to 18. Clients overzoom z18 tiles beyond that; deeper requests return no hazards. */
    public static boolean includesHazards(int z) {
        return z >= HAZARDS_MIN_ZOOM && z <= HAZARDS_MAX_ZOOM;
    }
}
