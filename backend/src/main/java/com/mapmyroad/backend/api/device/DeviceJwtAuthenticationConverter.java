package com.mapmyroad.backend.api.device;

import com.mapmyroad.backend.service.device.KnownDeviceCache;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Turns a validated access token into an authentication, after checking that the device still exists.
 * This is what makes {@code DELETE /v1/devices/me} invalidate the caller's access token immediately: throwing an
 * {@link org.springframework.security.core.AuthenticationException} here makes the bearer filter answer 401
 * UNAUTHORIZED through the entry point. The existence check is cached ({@link KnownDeviceCache}).
 */
public final class DeviceJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final KnownDeviceCache knownDevices;
    /** Default mapping: principal is the Jwt, {@code scope=device} becomes the authority {@code SCOPE_device}. */
    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    public DeviceJwtAuthenticationConverter(KnownDeviceCache knownDevices) {
        this.knownDevices = knownDevices;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID deviceId;
        try {
            // DeviceClaimsValidator has already checked the format; this guards against a misconfigured decoder.
            deviceId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DeviceNotRegisteredException();
        }
        if (!knownDevices.exists(deviceId)) {
            throw new DeviceNotRegisteredException();
        }
        return delegate.convert(jwt);
    }
}
