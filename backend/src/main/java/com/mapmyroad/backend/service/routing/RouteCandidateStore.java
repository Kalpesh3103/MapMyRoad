package com.mapmyroad.backend.service.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mapmyroad.backend.config.RoutingProperties;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps recent candidate sets in memory so v3 navigation can return exactly the chosen candidate by
 * {@code requestId} and {@code candidateId} (API_SPEC.md 5.9).
 *
 * <p>Privacy: entries hold geometries, scores and the vehicle type only, never the device ID and never origin or
 * destination as separate fields. The geometry does imply both endpoints, which is accepted for this short-lived,
 * in-memory cache (expire after {@code mmr.routing.candidate-ttl}, about 15 minutes; lost on restart). The request
 * ID is a random UUID and unguessable, so entries are not bound to a device. Nothing is persisted (API_SPEC.md 5.8).
 */
@Component
public class RouteCandidateStore {

    /** What v3 navigation needs to re-request the chosen candidate. */
    public record StoredCandidates(RouteCandidates candidates, VehicleType vehicleType) {}

    private final Cache<UUID, StoredCandidates> cache;

    public RouteCandidateStore(RoutingProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.candidateTtl())
                .maximumSize(properties.candidateCacheMaxEntries())
                .build();
    }

    public void put(RouteCandidates candidates, VehicleType vehicleType) {
        cache.put(candidates.requestId(), new StoredCandidates(candidates, vehicleType));
    }

    public Optional<StoredCandidates> find(UUID requestId) {
        return Optional.ofNullable(cache.getIfPresent(requestId));
    }
}
