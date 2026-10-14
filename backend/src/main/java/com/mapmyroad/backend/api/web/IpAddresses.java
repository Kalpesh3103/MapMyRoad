package com.mapmyroad.backend.api.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parsing of IP address literals without DNS. {@link InetAddress#getByName} resolves host names, so values from
 * headers such as {@code X-Forwarded-For} are only passed to it once they are known to be IPv6 literals, which it
 * parses locally. IPv4 is parsed by hand.
 */
public final class IpAddresses {

    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private IpAddresses() {}

    /** Address bytes (4 for IPv4 and IPv4-mapped IPv6, 16 for IPv6), or empty when not an IP literal. */
    public static Optional<byte[]> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String literal = value.trim();
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.indexOf(':') < 0) {
            return parseIpv4(literal);
        }
        if (!IPV6_CHARS.matcher(literal).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(literal).getAddress());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> parseIpv4(String literal) {
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return Optional.empty();
            }
            bytes[i] = (byte) octet;
        }
        return Optional.of(bytes);
    }

    /**
     * Rate-limit key for a client address. IPv6 clients are grouped by their /64 prefix because a single subscriber
     * usually controls a whole /64 and could otherwise rotate addresses to escape per-IP limits.
     */
    public static String rateLimitKey(String ip) {
        Optional<byte[]> bytes = parse(ip);
        if (bytes.isEmpty()) {
            return "raw:" + ip;
        }
        byte[] address = bytes.get();
        if (address.length == 16) {
            return "v6:" + HexFormat.of().formatHex(address, 0, 8) + "::/64";
        }
        try {
            return "v4:" + InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException e) {
            return "raw:" + ip; // unreachable: getByAddress only fails on an illegal length
        }
    }

    /** 32 for IPv4 (including IPv4-mapped IPv6, which parses to 4 bytes), 128 for IPv6. */
    static int bitLength(byte[] address) {
        return address.length * 8;
    }
}
