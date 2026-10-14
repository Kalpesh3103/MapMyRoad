package com.mapmyroad.backend.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrRangeTest {

    @Test
    void ipv4PrefixOnByteBoundary() {
        CidrRange range = CidrRange.parse("10.0.0.0/8");
        assertThat(range.contains("10.0.0.1")).isTrue();
        assertThat(range.contains("10.255.255.255")).isTrue();
        assertThat(range.contains("11.0.0.0")).isFalse();
    }

    @Test
    void ipv4PrefixInsideByte() {
        CidrRange range = CidrRange.parse("172.16.0.0/12");
        assertThat(range.contains("172.16.0.1")).isTrue();
        assertThat(range.contains("172.31.255.254")).isTrue();
        assertThat(range.contains("172.32.0.1")).isFalse();
        assertThat(range.contains("172.15.255.255")).isFalse();
    }

    @Test
    void bareAddressIsSingleHost() {
        CidrRange range = CidrRange.parse("127.0.0.1");
        assertThat(range.contains("127.0.0.1")).isTrue();
        assertThat(range.contains("127.0.0.2")).isFalse();
    }

    @Test
    void zeroPrefixMatchesWholeFamily() {
        assertThat(CidrRange.parse("0.0.0.0/0").contains("203.0.113.9")).isTrue();
        assertThat(CidrRange.parse("0.0.0.0/0").contains("2001:db8::1")).isFalse();
    }

    @Test
    void ipv6() {
        CidrRange loopback = CidrRange.parse("::1/128");
        assertThat(loopback.contains("::1")).isTrue();
        assertThat(loopback.contains("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(loopback.contains("::2")).isFalse();
        CidrRange doc = CidrRange.parse("2001:db8::/32");
        assertThat(doc.contains("2001:db8:1234::1")).isTrue();
        assertThat(doc.contains("2001:db9::1")).isFalse();
        assertThat(doc.contains("10.0.0.1")).isFalse();
    }

    @Test
    void ipv4MappedIpv6MatchesIpv4Range() {
        assertThat(CidrRange.parse("10.0.0.0/8").contains("::ffff:10.1.2.3")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "10.0.0", "256.0.0.1", "1.2.3.4.5", "", "01x.0.0.1", "::g"})
    void nonLiteralsNeverMatchAndNeverResolve(String value) {
        // Host names must not trigger DNS lookups; they are simply not addresses.
        assertThat(CidrRange.parse("0.0.0.0/0").contains(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/x", "nope/8", "::/129"})
    void invalidConfigurationFailsFast(String cidr) {
        assertThatThrownBy(() -> CidrRange.parse(cidr)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ipv6RateLimitKeyGroupsSlash64() {
        assertThat(IpAddresses.rateLimitKey("2001:db8:1:2:aaaa::1"))
                .isEqualTo(IpAddresses.rateLimitKey("2001:db8:1:2:bbbb::9"));
        assertThat(IpAddresses.rateLimitKey("2001:db8:1:3::1"))
                .isNotEqualTo(IpAddresses.rateLimitKey("2001:db8:1:2::1"));
        assertThat(IpAddresses.rateLimitKey("203.0.113.7")).isEqualTo("v4:203.0.113.7");
    }
}
