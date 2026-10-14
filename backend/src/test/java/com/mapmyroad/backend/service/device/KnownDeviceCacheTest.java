package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.repo.device.DeviceRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnownDeviceCacheTest {

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final KnownDeviceCache cache = new KnownDeviceCache(devices);
    private final UUID deviceId = UUID.randomUUID();

    @Test
    void positiveLookupIsCached() {
        when(devices.exists(deviceId)).thenReturn(true);

        assertThat(cache.exists(deviceId)).isTrue();
        assertThat(cache.exists(deviceId)).isTrue();

        verify(devices, times(1)).exists(deviceId);
    }

    @Test
    void missingDeviceIsNeverCached() {
        when(devices.exists(deviceId)).thenReturn(false);

        assertThat(cache.exists(deviceId)).isFalse();
        assertThat(cache.exists(deviceId)).isFalse();

        verify(devices, times(2)).exists(deviceId);
    }

    @Test
    void evictForcesANewLookupSoDeletedDevicesAreRejected() {
        when(devices.exists(deviceId)).thenReturn(true, false);
        assertThat(cache.exists(deviceId)).isTrue();

        cache.evict(deviceId);

        assertThat(cache.exists(deviceId)).isFalse();
    }
}
