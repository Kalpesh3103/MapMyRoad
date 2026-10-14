package com.mapmyroad.backend.domain.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class VehicleTypeTest {

    @Test
    void wireNamesMapToDatabaseCodes() {
        assertThat(VehicleType.fromWireName("motorcycle")).contains(VehicleType.MOTORCYCLE);
        assertThat(VehicleType.fromWireName("scooter")).contains(VehicleType.SCOOTER);
        assertThat(VehicleType.fromWireName("car")).contains(VehicleType.CAR);
        assertThat(VehicleType.fromWireName("auto_rickshaw")).contains(VehicleType.AUTO_RICKSHAW);
        assertThat(VehicleType.MOTORCYCLE.code()).isEqualTo(1);
        assertThat(VehicleType.SCOOTER.code()).isEqualTo(2);
        assertThat(VehicleType.CAR.code()).isEqualTo(3);
        assertThat(VehicleType.AUTO_RICKSHAW.code()).isEqualTo(4);
    }

    @Test
    void unknownOrDifferentlyCasedNamesAreNotMapped() {
        assertThat(VehicleType.fromWireName("truck")).isEmpty();
        assertThat(VehicleType.fromWireName("Car")).isEmpty();
        assertThat(VehicleType.fromWireName(null)).isEmpty();
    }

    @Test
    void validationPatternMatchesExactlyTheEnumWireNames() {
        Pattern pattern = Pattern.compile(VehicleType.WIRE_PATTERN);
        for (VehicleType type : VehicleType.values()) {
            assertThat(pattern.matcher(type.wireName()).matches()).isTrue();
        }
        assertThat(pattern.matcher("truck").matches()).isFalse();
        assertThat(pattern.matcher("carx").matches()).isFalse();
        assertThat(VehicleType.WIRE_PATTERN.split("\\|")).hasSize(VehicleType.values().length);
    }
}
