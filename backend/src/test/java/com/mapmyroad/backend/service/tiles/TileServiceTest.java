package com.mapmyroad.backend.service.tiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.config.TileProperties;
import com.mapmyroad.backend.repo.tiles.TileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TileServiceTest {

    private final TileRepository repository = mock(TileRepository.class);
    private TileService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC);
        service = new TileService(repository, TileProperties.defaults(), clock);
    }

    @Test
    void belowZoom12NoQueryAndEmpty() {
        TileResult result = service.tile(TileCoord.of(11, 1, 1));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.etag()).isNull();
        verify(repository, never()).roadsLayer(anyInt(), anyInt(), anyInt(), any());
        verify(repository, never()).hazardsLayer(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void zoom12HasRoadsOnly() {
        when(repository.roadsLayer(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[] {1, 2});

        TileResult result = service.tile(TileCoord.of(12, 2, 3));

        assertThat(result.bytes()).isEqualTo(new byte[] {1, 2});
        verify(repository, never()).hazardsLayer(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void zoom17ConcatenatesBothLayersRoadsFirst() {
        when(repository.roadsLayer(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[] {1, 2});
        when(repository.hazardsLayer(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[] {3});

        TileResult result = service.tile(TileCoord.of(17, 5, 6));

        assertThat(result.bytes()).isEqualTo(new byte[] {1, 2, 3});
        assertThat(result.etag()).isEqualTo(TileEtag.of(new byte[] {1, 2, 3}));
    }

    @Test
    void aboveZoom18RoadsOnly() {
        when(repository.roadsLayer(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[] {1});

        service.tile(TileCoord.of(19, 5, 6));

        verify(repository, never()).hazardsLayer(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void secondRequestIsServedFromCache() {
        when(repository.roadsLayer(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[0]);
        when(repository.hazardsLayer(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[] {7});

        service.tile(TileCoord.of(14, 1, 1));
        TileResult cached = service.tile(TileCoord.of(14, 1, 1));

        assertThat(cached.bytes()).isEqualTo(new byte[] {7});
        verify(repository, times(1)).hazardsLayer(14, 1, 1, Instant.parse("2026-10-15T10:00:00Z"));
    }
}
