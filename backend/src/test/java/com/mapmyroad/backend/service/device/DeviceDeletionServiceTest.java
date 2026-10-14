package com.mapmyroad.backend.service.device;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.mapmyroad.backend.repo.device.DeviceRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DeviceDeletionServiceTest {

    @Test
    void queuesHazardsBeforeDeletingAndEvictsCache() {
        DeviceRepository devices = mock(DeviceRepository.class);
        KnownDeviceCache cache = mock(KnownDeviceCache.class);
        UUID deviceId = UUID.randomUUID();

        new DeviceDeletionService(devices, cache).deleteDevice(deviceId);

        // The queue reads hazard_event and hazard_feedback, which the device delete cascades away.
        InOrder order = inOrder(devices, cache);
        order.verify(devices).queueContributedHazardsForRecompute(deviceId);
        order.verify(devices).delete(deviceId);
        order.verify(cache).evict(deviceId);
    }
}
