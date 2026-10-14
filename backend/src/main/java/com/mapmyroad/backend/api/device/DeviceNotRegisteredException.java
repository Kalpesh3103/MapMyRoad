package com.mapmyroad.backend.api.device;

import org.springframework.security.core.AuthenticationException;

/** A validly signed access token whose device no longer exists (deleted or expired by retention). */
public class DeviceNotRegisteredException extends AuthenticationException {

    public DeviceNotRegisteredException() {
        super("Device is not registered.");
    }
}
