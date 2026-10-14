package com.mapmyroad.backend.api.device;

import com.mapmyroad.backend.api.auth.CurrentDevice;
import com.mapmyroad.backend.api.ratelimit.RateLimitPolicy;
import com.mapmyroad.backend.api.ratelimit.RateLimited;
import com.mapmyroad.backend.domain.device.DeviceTokens;
import com.mapmyroad.backend.service.device.DeviceDeletionService;
import com.mapmyroad.backend.service.device.DeviceRegistrationService;
import com.mapmyroad.backend.service.device.DeviceTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Device registration, token refresh and "delete my data" (API_SPEC.md section 5.2). */
@RestController
@RequestMapping("/v1/devices")
public class DeviceController {

    private final DeviceRegistrationService registration;
    private final DeviceTokenService tokens;
    private final DeviceDeletionService deletion;

    public DeviceController(
            DeviceRegistrationService registration, DeviceTokenService tokens, DeviceDeletionService deletion) {
        this.registration = registration;
        this.tokens = tokens;
        this.deletion = deletion;
    }

    /** Public. */
    @PostMapping("/challenge")
    @RateLimited(RateLimitPolicy.DEVICE_REGISTRATION)
    public ChallengeResponse challenge() {
        DeviceRegistrationService.Challenge challenge = registration.challenge();
        return new ChallengeResponse(challenge.nonce(), challenge.expiresInSec());
    }

    /** Public. 403 INTEGRITY_FAILED for a bad nonce or verdict, 503 UPSTREAM_UNAVAILABLE when Google fails. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitPolicy.DEVICE_REGISTRATION)
    public RegisterDeviceResponse register(@Valid @RequestBody RegisterDeviceRequest request) {
        DeviceTokens issued = registration.register(new DeviceRegistrationService.Registration(
                request.integrityToken(), request.nonce(), request.appVersion(), request.osApiLevel()));
        return new RegisterDeviceResponse(
                issued.deviceId(),
                issued.accessToken().value(),
                issued.accessToken().expiresInSec(),
                issued.refreshToken().value(),
                issued.refreshToken().expiresInSec());
    }

    /** Public: the refresh token is the credential. 401 UNAUTHORIZED for unknown, expired or reused tokens. */
    @PostMapping("/token")
    @RateLimited(RateLimitPolicy.TOKEN_REFRESH)
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        DeviceTokens issued = tokens.refresh(request.refreshToken());
        return new TokenResponse(
                issued.accessToken().value(),
                issued.accessToken().expiresInSec(),
                issued.refreshToken().value(),
                issued.refreshToken().expiresInSec());
    }

    /** Authenticated. Deletes the calling device and everything tied to it. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal Jwt jwt) {
        deletion.deleteDevice(CurrentDevice.id(jwt));
    }
}
