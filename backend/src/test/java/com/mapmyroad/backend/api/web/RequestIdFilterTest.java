package com.mapmyroad.backend.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void wellFormedClientIdIsUsedInMdcAndEchoed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/config");
        request.addHeader(RequestIdFilter.HEADER, "abc-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isEqualTo("abc-12345678");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-12345678");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void malformedOrMissingIdIsReplacedWithUuid() {
        for (String candidate : new String[] {null, "short", "has space 123", "x".repeat(65), "inj\nected-123"}) {
            String id = RequestIdFilter.resolve(candidate);
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        }
    }

    @Test
    void mdcIsClearedEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/config");
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        try {
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        } catch (Exception expected) {
            // expected
        }
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
