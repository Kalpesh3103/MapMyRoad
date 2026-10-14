package com.mapmyroad.backend.api.web;

import java.util.Arrays;

/**
 * An IPv4 or IPv6 network in CIDR notation ({@code 10.0.0.0/8}, {@code ::1/128}); a bare address means a single
 * host. Used to decide whether the direct peer is a trusted reverse proxy.
 */
public final class CidrRange {

    private final byte[] network;
    private final int prefixLength;

    private CidrRange(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /** Parses configuration such as {@code 172.16.0.0/12}; fails fast on malformed values. */
    public static CidrRange parse(String cidr) {
        String value = cidr == null ? "" : cidr.trim();
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        byte[] bytes = IpAddresses.parse(address)
                .orElseThrow(() -> new IllegalArgumentException("Invalid CIDR address: " + cidr));
        int maxBits = IpAddresses.bitLength(bytes);
        int prefix;
        try {
            prefix = slash < 0 ? maxBits : Integer.parseInt(value.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR prefix: " + cidr, e);
        }
        if (prefix < 0 || prefix > maxBits) {
            throw new IllegalArgumentException("Invalid CIDR prefix: " + cidr);
        }
        return new CidrRange(bytes, prefix);
    }

    /** True when {@code ip} is a valid literal of the same family inside this range. */
    public boolean contains(String ip) {
        return IpAddresses.parse(ip).map(this::contains).orElse(false);
    }

    boolean contains(byte[] address) {
        if (address.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CidrRange range
                && prefixLength == range.prefixLength
                && Arrays.equals(network, range.network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefixLength;
    }
}
