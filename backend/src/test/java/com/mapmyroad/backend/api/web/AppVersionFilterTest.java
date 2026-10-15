package com.mapmyroad.backend.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AppVersionFilterTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EchoController())
            .addFilters(new AppVersionFilter("1.2.0"))
            .build();

    @Test
    void missingHeaderIs400() throws Exception {
        mvc.perform(get("/v1/ping"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.type").value("https://api.example.com/problems/invalid-request"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void malformedHeaderIs400() throws Exception {
        mvc.perform(get("/v1/ping").header(AppVersionFilter.HEADER, "one.two"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void olderVersionIs426() throws Exception {
        mvc.perform(get("/v1/ping").header(AppVersionFilter.HEADER, "1.1.9"))
                .andExpect(status().is(426))
                .andExpect(jsonPath("$.code").value("APP_UPDATE_REQUIRED"))
                .andExpect(jsonPath("$.title").value("App update required"))
                .andExpect(jsonPath("$.minSupportedAppVersion").value("1.2.0"));
    }

    @Test
    void supportedVersionPasses() throws Exception {
        mvc.perform(get("/v1/ping").header(AppVersionFilter.HEADER, "1.2.0"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
        mvc.perform(get("/v1/ping").header(AppVersionFilter.HEADER, "1.10.0-debug"))
                .andExpect(status().isOk());
    }

    @Test
    void pathsOutsideTheApiAreNotChecked() throws Exception {
        mvc.perform(get("/other/ping")).andExpect(status().isOk());
    }

    @Test
    void tilesAreNotChecked() throws Exception {
        // EchoController has no tile route, so 404 (not 400) proves the filter let the request through.
        mvc.perform(get("/v1/tiles/13/5760/3640.mvt")).andExpect(status().isNotFound());
    }
}
