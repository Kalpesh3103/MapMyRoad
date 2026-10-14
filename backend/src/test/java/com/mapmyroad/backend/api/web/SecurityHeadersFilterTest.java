package com.mapmyroad.backend.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SecurityHeadersFilterTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EchoController())
            .addFilters(new SecurityHeadersFilter())
            .build();

    @Test
    void apiResponsesDefaultToNoStore() throws Exception {
        mvc.perform(get("/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void controllerCacheControlReplacesTheDefault() throws Exception {
        MvcResult result = mvc.perform(get("/v1/cached")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeaders("Cache-Control")).containsExactly("max-age=300, public");
    }

    @Test
    void nonApiPathsGetBaselineHeadersOnly() throws Exception {
        mvc.perform(get("/other/ping"))
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
