package com.mapmyroad.backend.integration.polyline;

import java.util.ArrayList;
import java.util.List;

/**
 * Encoded polyline algorithm (Google's format) with a configurable precision. Valhalla and the app use precision 6
 * (TECH_STACK.md section 5); precision 5 is the original Google format and is kept for compatibility tests.
 *
 * <p>Each value is the delta from the previous point, rounded to {@code 10^precision}, zig-zag encoded (sign in the
 * lowest bit) and written in 5-bit chunks, each chunk offset by 63 with 0x20 marking a continuation. Latitude comes
 * before longitude. Pure and thread-safe.
 */
public final class Polyline {

    public static final int PRECISION_6 = 6;
    public static final int PRECISION_5 = 5;

    private Polyline() {}

    /** Encodes with precision 6. */
    public static String encode6(List<LatLon> points) {
        return encode(points, PRECISION_6);
    }

    /** Decodes a precision 6 polyline. */
    public static List<LatLon> decode6(String encoded) {
        return decode(encoded, PRECISION_6);
    }

    public static String encode(List<LatLon> points, int precision) {
        double factor = factor(precision);
        StringBuilder out = new StringBuilder(points.size() * 8);
        long previousLat = 0;
        long previousLon = 0;
        for (LatLon point : points) {
            long lat = Math.round(point.lat() * factor);
            long lon = Math.round(point.lon() * factor);
            writeValue(out, lat - previousLat);
            writeValue(out, lon - previousLon);
            previousLat = lat;
            previousLon = lon;
        }
        return out.toString();
    }

    /**
     * Decodes a polyline.
     *
     * @throws IllegalArgumentException when the string is truncated or contains characters outside the alphabet
     */
    public static List<LatLon> decode(String encoded, int precision) {
        double factor = factor(precision);
        List<LatLon> points = new ArrayList<>(encoded.length() / 4 + 1);
        int[] index = {0};
        long lat = 0;
        long lon = 0;
        while (index[0] < encoded.length()) {
            lat += readValue(encoded, index);
            if (index[0] >= encoded.length()) {
                throw new IllegalArgumentException("Polyline ends after a latitude");
            }
            lon += readValue(encoded, index);
            points.add(new LatLon(lat / factor, lon / factor));
        }
        return points;
    }

    private static double factor(int precision) {
        if (precision < 1 || precision > 7) {
            throw new IllegalArgumentException("Unsupported polyline precision " + precision);
        }
        return Math.pow(10, precision);
    }

    private static void writeValue(StringBuilder out, long delta) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            out.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        out.append((char) (value + 63));
    }

    private static long readValue(String encoded, int[] index) {
        long result = 0;
        int shift = 0;
        int chunk;
        do {
            if (index[0] >= encoded.length()) {
                throw new IllegalArgumentException("Truncated polyline");
            }
            chunk = encoded.charAt(index[0]++) - 63;
            if (chunk < 0 || chunk > 0x3f) {
                throw new IllegalArgumentException("Invalid polyline character");
            }
            if (shift > 60) {
                throw new IllegalArgumentException("Polyline value too long");
            }
            result |= (long) (chunk & 0x1f) << shift;
            shift += 5;
        } while (chunk >= 0x20);
        return (result & 1) != 0 ? ~(result >> 1) : result >> 1;
    }
}
