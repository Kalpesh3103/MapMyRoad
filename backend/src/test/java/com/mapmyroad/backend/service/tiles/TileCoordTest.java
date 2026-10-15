package com.mapmyroad.backend.service.tiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TileCoordTest {

    @Test
    void acceptsCornersOfTheRange() {
        assertThat(TileCoord.of(0, 0, 0)).isEqualTo(new TileCoord(0, 0, 0));
        assertThat(TileCoord.of(14, 16383, 16383)).isEqualTo(new TileCoord(14, 16383, 16383));
        assertThat(TileCoord.of(22, (1 << 22) - 1, 0).z()).isEqualTo(22);
    }

    @ParameterizedTest
    @CsvSource({"-1,0,0", "23,0,0", "0,1,0", "0,0,1", "14,16384,0", "14,0,16384", "14,-1,0", "14,0,-1"})
    void rejectsOutOfRangeCoordinatesWith400(int z, int x, int y) {
        assertThatThrownBy(() -> TileCoord.of(z, x, y))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
