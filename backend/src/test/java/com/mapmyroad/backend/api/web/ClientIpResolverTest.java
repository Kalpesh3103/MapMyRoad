package com.mapmyroad.backend.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.config.WebLayerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(WebLayerProperties.DEFAULT_TRUSTED_PROXIES);

    @Test
    void untrustedPeerIgnoresForwardedFor() {
        MockHttpServletRequest request = request("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedProxyUsesForwardedClient() {
        MockHttpServletRequest request = request("172.18.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
    }

    @Test
    void forgedLeftmostValueIsNotUsed() {
        MockHttpServletRequest request = request("172.18.0.2");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.1");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
    }

    @Test
    void trustedHopsAreSkippedFromTheRight() {
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 10.0.0.5");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
    }

    @Test
    void multipleHeadersAreConcatenated() {
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Forwarded-For", "198.51.100.2");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.2");
    }

    @Test
    void garbageEntryFallsBackToPeer() {
        MockHttpServletRequest request = request("172.18.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.1, evil.example.com");
        assertThat(resolver.resolve(request)).isEqualTo("172.18.0.2");
    }

    @Test
    void trustedProxyWithoutHeaderUsesPeer() {
        assertThat(resolver.resolve(request("172.18.0.2"))).isEqualTo("172.18.0.2");
    }

    @Test
    void allHopsTrustedReturnsLeftmost() {
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.9, 10.0.0.5");
        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void ipv6LoopbackIsTrusted() {
        MockHttpServletRequest request = request("::1");
        request.addHeader("X-Forwarded-For", "2001:db8::7");
        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::7");
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/config");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
