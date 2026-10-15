package com.mapmyroad.backend.service.tiles;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mapmyroad.backend.config.TileProperties;
import com.mapmyroad.backend.repo.tiles.TileRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Produces vector tiles with both layers (API_SPEC.md 5.4, M4-11). Visibility rules are applied in SQL by
 * {@link TileRepository} through the shared {@code VisibilityRules}.
 *
 * <p>Caching: a small in-process Caffeine cache keyed by z/x/y absorbs bursts (map panning, many clients on the
 * same area) in front of PostGIS. It is bounded by total tile bytes and invalidated by time only
 * ({@code mmr.tiles.server-cache-ttl}, 60 s by default); aggregation jobs do not evict entries, so a change is
 * visible after at most that TTL plus the HTTP max-age. Empty tiles are cached too, as zero-length arrays.
 */
@Service
public class TileService {

    private final TileRepository repository;
    private final Clock clock;
    private final Cache<TileCoord, TileResult> cache;

    public TileService(TileRepository repository, TileProperties properties, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.serverCacheTtl())
                .maximumWeight(properties.serverCacheMaxBytes())
                // Per-entry overhead approximated as 64 bytes so that empty tiles still count.
                .weigher((TileCoord key, TileResult value) -> value.bytes().length + 64)
                .build();
    }

    /** The tile for {@code coord}; empty when nothing visible lies in it or the zoom has no layers. */
    public TileResult tile(TileCoord coord) {
        return cache.get(coord, this::render);
    }

    private TileResult render(TileCoord coord) {
        Instant now = clock.instant();
        byte[] roads = TileLayers.includesRoads(coord.z())
                ? repository.roadsLayer(coord.z(), coord.x(), coord.y(), now)
                : new byte[0];
        byte[] hazards = TileLayers.includesHazards(coord.z())
                ? repository.hazardsLayer(coord.z(), coord.x(), coord.y(), now)
                : new byte[0];
        return TileResult.of(concat(roads, hazards));
    }

    /** Layers are top-level repeated fields of the tile message, so byte concatenation merges them. */
    static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
