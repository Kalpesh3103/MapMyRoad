package com.mapmyroad.backend.service.device;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mapmyroad.backend.repo.device.DeviceRepository;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers "does this device still exist?" for every authenticated request, so an access token stops working as soon
 * as its device is deleted (API_SPEC.md section 5.2) even though JWTs are stateless.
 *
 * <p>Only positive answers are cached, for {@link #TTL}, so the database sees at most one lookup per active device
 * per minute. Deletion evicts the entry. A missing device is never cached, so a deleted device cannot be revived.
 * The cache is per process, which matches the single-VM deployment; with several instances another instance could
 * accept a deleted device's token for up to {@link #TTL}.
 */
@Component
public class KnownDeviceCache {

    static final Duration TTL = Duration.ofSeconds(60);
    static final long MAX_ENTRIES = 100_000;

    private final DeviceRepository devices;
    private final Cache<UUID, Boolean> known = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_ENTRIES)
            .build();

    public KnownDeviceCache(DeviceRepository devices) {
        this.devices = devices;
    }

    public boolean exists(UUID deviceId) {
        if (known.getIfPresent(deviceId) != null) {
            return true;
        }
        boolean exists = devices.exists(deviceId);
        if (exists) {
            known.put(deviceId, Boolean.TRUE);
        }
        return exists;
    }

    public void evict(UUID deviceId) {
        known.invalidate(deviceId);
    }
}
