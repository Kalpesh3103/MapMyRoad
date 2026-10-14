package com.mapmyroad.backend.api.device;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.service.device.KnownDeviceCache;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class DeviceJwtAuthenticationConverterTest {

    private final KnownDeviceCache knownDevices = mock(KnownDeviceCache.class);
    private final DeviceJwtAuthenticationConverter converter = new DeviceJwtAuthenticationConverter(knownDevices);

    @Test
    void deletedDeviceIsRejected() {
        UUID deviceId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(deviceId.toString());
        when(knownDevices.exists(deviceId)).thenReturn(false);

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(DeviceNotRegisteredException.class);
    }

    @Test
    void malformedSubjectIsRejected() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("not-a-uuid");

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(DeviceNotRegisteredException.class);
    }
}
