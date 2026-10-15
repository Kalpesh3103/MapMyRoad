package com.mapmyroad.backend.service.hazards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundingBoxTest {

    @Test
    void parsesFourNumbersInSpecOrder() {
        BoundingBox box = BoundingBox.parse("73.80, 18.50,73.85,18.55");
        assertThat(box.minLon()).isEqualTo(73.80);
        assertThat(box.minLat()).isEqualTo(18.50);
        assertThat(box.maxLon()).isEqualTo(73.85);
        assertThat(box.maxLat()).isEqualTo(18.55);
    }

    @Test
    void acceptsExactlyTheMaximumArea() {
        assertThat(BoundingBox.parse("73.80,18.50,73.90,18.60").areaDeg2()).isLessThanOrEqualTo(0.0100001);
        // A long thin strip is fine as long as the area stays within the cap.
        BoundingBox.parse("73.60,18.50,73.80,18.55");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "73.8,18.5,73.9",
        "73.8,18.5,73.9,18.6,1",
        "a,18.5,73.9,18.6",
        "NaN,18.5,73.9,18.6",
        "Infinity,18.5,73.9,18.6",
        "73.9,18.5,73.8,18.6",
        "73.8,18.6,73.9,18.5",
        "73.8,18.5,73.8,18.6",
        "73.8,18.5,73.95,18.6",
        "-181,18.5,-180.95,18.55",
        "73.8,90,73.85,91",
        "73.8,18.5,73.85,"
    })
    void rejectsInvalidBoxesWith400(String text) {
        assertThatThrownBy(() -> BoundingBox.parse(text))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> BoundingBox.parse(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void toStringHidesCoordinates() {
        assertThat(BoundingBox.parse("73.80,18.50,73.85,18.55").toString()).doesNotContain("73.8");
    }
}
