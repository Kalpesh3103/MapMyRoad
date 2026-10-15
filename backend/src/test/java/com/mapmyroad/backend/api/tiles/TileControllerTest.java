package com.mapmyroad.backend.api.tiles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.api.error.ApiExceptionHandler;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.config.TileProperties;
import com.mapmyroad.backend.service.tiles.TileCoord;
import com.mapmyroad.backend.service.tiles.TileEtag;
import com.mapmyroad.backend.service.tiles.TileJsonService;
import com.mapmyroad.backend.service.tiles.TileResult;
import com.mapmyroad.backend.service.tiles.TileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Tile endpoints with a standalone MockMvc; rate limiting and security are tested elsewhere. */
class TileControllerTest {

    private static final byte[] TILE = {0x1a, 0x05, 'r', 'o', 'a', 'd', 's'};

    private final TileService tileService = mock(TileService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TileProperties properties = TileProperties.defaults();
        TileJsonService tileJson =
                new TileJsonService(properties, new MmrProperties("72.6,17.9,75.1,19.6", null, null));
        mvc = MockMvcBuilders.standaloneSetup(new TileController(tileService, tileJson, properties))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void tileWithDataIsMvtWithCacheHeaders() throws Exception {
        when(tileService.tile(new TileCoord(14, 11490, 7470))).thenReturn(new TileResult(TILE, TileEtag.of(TILE)));

        mvc.perform(get("/v1/tiles/14/11490/7470.mvt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.mapbox-vector-tile"))
                .andExpect(content().bytes(TILE))
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(header().string("ETag", TileEtag.of(TILE)));
    }

    @Test
    void matchingIfNoneMatchIs304() throws Exception {
        when(tileService.tile(any())).thenReturn(new TileResult(TILE, TileEtag.of(TILE)));

        mvc.perform(get("/v1/tiles/14/11490/7470.mvt").header("If-None-Match", TileEtag.of(TILE)))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", TileEtag.of(TILE)))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void emptyTileIs204() throws Exception {
        when(tileService.tile(any())).thenReturn(new TileResult(new byte[0], null));

        mvc.perform(get("/v1/tiles/14/11490/7470.mvt"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(header().doesNotExist("ETag"));
    }

    @Test
    void outOfRangeCoordinatesAre400() throws Exception {
        mvc.perform(get("/v1/tiles/23/0/0.mvt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/v1/tiles/2/4/0.mvt")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/tiles/2/x/0.mvt")).andExpect(status().isBadRequest());
        verify(tileService, never()).tile(any());
    }

    @Test
    void tileJsonDescribesTheSource() throws Exception {
        mvc.perform(get("/v1/tiles/tilejson.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.tilejson").value("3.0.0"))
                .andExpect(jsonPath("$.tiles[0]").value("https://api.example.com/v1/tiles/{z}/{x}/{y}.mvt"))
                .andExpect(jsonPath("$.minzoom").value(12))
                .andExpect(jsonPath("$.maxzoom").value(18))
                .andExpect(jsonPath("$.bounds[0]").value(72.6))
                .andExpect(jsonPath("$.vector_layers[0].id").value("roads"))
                .andExpect(jsonPath("$.vector_layers[0].maxzoom").value(16))
                .andExpect(jsonPath("$.vector_layers[1].id").value("hazards"))
                .andExpect(jsonPath("$.vector_layers[1].minzoom").value(13));
    }
}
