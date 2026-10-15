package com.mapmyroad.backend.repo.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mapmyroad.backend.integration.polyline.LatLon;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteWktTest {

    @Test
    void writesLongitudeFirstInPlainNotation() {
        String wkt = RouteWkt.lineString(List.of(new LatLon(18.5204, 73.8567), new LatLon(0.00001, -0.0001)));

        assertThat(wkt).isEqualTo("LINESTRING(73.8567 18.5204,-0.0001 0.00001)");
    }

    @Test
    void needsTwoPoints() {
        assertThatThrownBy(() -> RouteWkt.lineString(List.of(new LatLon(1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteCoordinates() {
        assertThatThrownBy(() -> RouteWkt.lineString(List.of(new LatLon(1, 2), new LatLon(Double.NaN, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wayIdsBecomeAnArrayLiteral() {
        assertThat(RouteDataRepository.arrayLiteral(List.of(1L, 22L, 333L))).isEqualTo("{1,22,333}");
    }
}
