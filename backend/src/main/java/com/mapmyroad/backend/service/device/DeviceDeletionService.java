package com.mapmyroad.backend.service.device;

import com.mapmyroad.backend.repo.device.DeviceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** "Delete my data" (API_SPEC.md section 5.2, DATABASE_SCHEMA.md section A10). */
@Service
public class DeviceDeletionService {

    private final DeviceRepository devices;
    private final KnownDeviceCache knownDevices;

    public DeviceDeletionService(DeviceRepository devices, KnownDeviceCache knownDevices) {
        this.devices = devices;
        this.knownDevices = knownDevices;
    }

    /**
     * In one transaction: queue every hazard the device contributed to for recomputation, then delete the device row,
     * which cascades to refresh tokens, events, observations and feedback. Idempotent: deleting twice is not an error.
     */
    @Transactional
    public void deleteDevice(UUID deviceId) {
        devices.queueContributedHazardsForRecompute(deviceId);
        devices.delete(deviceId);
        knownDevices.evict(deviceId);
        // Evict again after commit: a concurrent request could re-cache the device between the first eviction and
        // the commit, while the row is still visible to it.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    knownDevices.evict(deviceId);
                }
            });
        }
    }
}
