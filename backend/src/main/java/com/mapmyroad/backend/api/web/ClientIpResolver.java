package com.mapmyroad.backend.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Determines the client address for per-IP rate limits (API_SPEC.md section 4, SECURITY.md T20).
 *
 * <p>{@code X-Forwarded-For} is only honored when the direct peer is a trusted proxy (Caddy on the Docker network).
 * The list is then walked from the right, skipping trusted proxies, and the first untrusted address wins. Taking the
 * leftmost value would let a client choose its own rate-limit key by sending a forged header, if the proxy appends
 * rather than replaces. With Caddy's default (replace) both approaches give the same answer.
 */
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<CidrRange> trustedProxies;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        this.trustedProxies = trustedProxyCidrs.stream().map(CidrRange::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrusted(remote)) {
            return remote;
        }
        List<String> hops = forwardedHops(request);
        if (hops.isEmpty()) {
            return remote;
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (IpAddresses.parse(hop).isEmpty()) {
                // A garbled entry means nothing to its left can be trusted; fall back to the peer address.
                return remote;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return hops.get(0); // every hop is a trusted proxy, so the request came from inside our network
    }

    boolean isTrusted(String ip) {
        return IpAddresses.parse(ip)
                .map(bytes -> trustedProxies.stream().anyMatch(range -> range.contains(bytes)))
                .orElse(false);
    }

    private static List<String> forwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            for (String part : header.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        return hops;
    }
}
