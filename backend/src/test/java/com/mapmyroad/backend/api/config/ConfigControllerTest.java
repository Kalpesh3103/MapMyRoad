package com.mapmyroad.backend.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.config.ConfigEndpointProperties;
import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.config.MmrProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConfigControllerTest {

    @Test
    void matchesApiSpecExample() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigController(
                        new MmrProperties("72.6,17.9,75.1,19.6", "1.0.0", true),
                        ConfigEndpointProperties.defaults(),
                        IngestProperties.defaults()))
                .build();

        mvc.perform(get("/v1/config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(content().json("""
                        {
                          "minSupportedAppVersion": "1.0.0",
                          "supportedRegion": { "bbox": [72.6, 17.9, 75.1, 19.6] },
                          "tileJsonUrl": "https://api.example.com/v1/tiles/tilejson.json",
                          "baseMapStyleUrl": "https://tiles.openfreemap.org/styles/liberty",
                          "retentionDays": 90,
                          "limits": { "maxEventsPerRequest": 500, "maxWindowsPerRequest": 1000 },
                          "features": { "navigation": false, "reports": false, "areas": false }
                        }
                        """, true));
    }

    @Test
    void reflectsConfiguredValues() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigController(
                        new MmrProperties("73.0,18.0,74.0,19.0", "1.4.2", true),
                        new ConfigEndpointProperties(
                                "https://tiles.test/v1/tiles/tilejson.json",
                                null,
                                30,
                                new ConfigEndpointProperties.Features(true, null, false)),
                        IngestProperties.defaults()))
                .build();

        mvc.perform(get("/v1/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minSupportedAppVersion").value("1.4.2"))
                .andExpect(jsonPath("$.supportedRegion.bbox[0]").value(73.0))
                .andExpect(jsonPath("$.supportedRegion.bbox[3]").value(19.0))
                .andExpect(jsonPath("$.tileJsonUrl").value("https://tiles.test/v1/tiles/tilejson.json"))
                .andExpect(jsonPath("$.retentionDays").value(30))
                .andExpect(jsonPath("$.features.navigation").value(true))
                .andExpect(jsonPath("$.features.reports").value(false));
    }
}
