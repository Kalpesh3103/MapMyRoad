package com.mapmyroad.backend.integration.polyline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PolylineTest {

    // Google's documented example (Encoded Polyline Algorithm Format).
    private static final List<LatLon> GOOGLE_POINTS =
            List.of(new LatLon(38.5, -120.2), new LatLon(40.7, -120.95), new LatLon(43.252, -126.453));
    private static final String GOOGLE_PRECISION_5 = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";
    // The same points at precision 6, as in Valhalla's decoding documentation.
    private static final String GOOGLE_PRECISION_6 = "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI";

    @Test
    void encodesGoogleExampleAtPrecision5() {
        assertThat(Polyline.encode(GOOGLE_POINTS, Polyline.PRECISION_5)).isEqualTo(GOOGLE_PRECISION_5);
    }

    @Test
    void decodesGoogleExampleAtPrecision5() {
        assertClose(Polyline.decode(GOOGLE_PRECISION_5, Polyline.PRECISION_5), GOOGLE_POINTS, 1e-5);
    }

    @Test
    void encodesAndDecodesAtPrecision6() {
        assertThat(Polyline.encode6(GOOGLE_POINTS)).isEqualTo(GOOGLE_PRECISION_6);
        assertClose(Polyline.decode6(GOOGLE_PRECISION_6), GOOGLE_POINTS, 1e-6);
    }

    @Test
    void precision6RoundTripsRandomPointsInIndia() {
        Random random = new Random(42);
        List<LatLon> points = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            points.add(new LatLon(8 + random.nextDouble() * 27, 68 + random.nextDouble() * 29));
        }
        assertClose(Polyline.decode6(Polyline.encode6(points)), points, 0.5e-6 + 1e-12);
    }

    @Test
    void roundTripsNegativeAndExtremeCoordinates() {
        List<LatLon> points = List.of(new LatLon(-89.999999, -179.999999), new LatLon(89.999999, 179.999999),
                new LatLon(0, 0), new LatLon(0.000001, -0.000001));
        assertClose(Polyline.decode6(Polyline.encode6(points)), points, 1e-9);
    }

    @Test
    void emptyStringIsNoPoints() {
        assertThat(Polyline.decode6("")).isEmpty();
        assertThat(Polyline.encode6(List.of())).isEmpty();
    }

    @Test
    void rejectsTruncatedInput() {
        assertThatThrownBy(() -> Polyline.decode6("_izlhA")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Polyline.decode6("_izlh")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> Polyline.decode6("_izl hA~rlgdF")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void haversineDistanceIsPlausible() {
        // One degree of latitude is about 111.2 km.
        assertThat(new LatLon(18, 73).distanceMTo(new LatLon(19, 73))).isCloseTo(111_195, within(50.0));
        assertThat(new LatLon(18.5, 73.8).distanceMTo(new LatLon(18.5, 73.8))).isZero();
    }

    @Test
    void toStringDoesNotLeakCoordinates() {
        assertThat(new LatLon(18.5204, 73.8567).toString()).doesNotContain("18.52").doesNotContain("73.85");
    }

    private static void assertClose(List<LatLon> actual, List<LatLon> expected, double tolerance) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i).lat()).isCloseTo(expected.get(i).lat(), within(tolerance));
            assertThat(actual.get(i).lon()).isCloseTo(expected.get(i).lon(), within(tolerance));
        }
    }
}
