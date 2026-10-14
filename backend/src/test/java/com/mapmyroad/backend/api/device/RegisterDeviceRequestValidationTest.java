package com.mapmyroad.backend.api.device;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RegisterDeviceRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static RegisterDeviceRequest valid() {
        return new RegisterDeviceRequest("token", "nonce", "1.0.3", "android", 34);
    }

    private static Set<String> invalidFields(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void validRequestPasses() {
        assertThat(invalidFields(valid())).isEmpty();
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "2.10.0-beta.1", "android", 26))).isEmpty();
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0.0", "android", 100))).isEmpty();
    }

    @Test
    void integrityTokenIsRequiredAndAtMost8Kb() {
        assertThat(invalidFields(new RegisterDeviceRequest(" ", "n", "1.0.3", "android", 34)))
                .containsExactly("integrityToken");
        assertThat(invalidFields(new RegisterDeviceRequest("x".repeat(8193), "n", "1.0.3", "android", 34)))
                .containsExactly("integrityToken");
        assertThat(invalidFields(new RegisterDeviceRequest("x".repeat(8192), "n", "1.0.3", "android", 34)))
                .isEmpty();
    }

    @Test
    void nonceIsRequiredAndBounded() {
        assertThat(invalidFields(new RegisterDeviceRequest("t", null, "1.0.3", "android", 34)))
                .containsExactly("nonce");
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n".repeat(129), "1.0.3", "android", 34)))
                .containsExactly("nonce");
    }

    @Test
    void appVersionMustLookLikeSemver() {
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0", "android", 34)))
                .containsExactly("appVersion");
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "v1.0.3", "android", 34)))
                .containsExactly("appVersion");
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0.3" + "x".repeat(30), "android", 34)))
                .containsExactly("appVersion");
    }

    @Test
    void platformMustBeAndroid() {
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0.3", "ios", 34)))
                .containsExactly("platform");
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0.3", null, 34)))
                .containsExactly("platform");
    }

    @Test
    void osApiLevelMustBeBetween26And100() {
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0.3", "android", 25)))
                .containsExactly("osApiLevel");
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0.3", "android", 101)))
                .containsExactly("osApiLevel");
        assertThat(invalidFields(new RegisterDeviceRequest("t", "n", "1.0.3", "android", null)))
                .containsExactly("osApiLevel");
    }

    @Test
    void refreshTokenIsRequiredAndBounded() {
        assertThat(invalidFields(new RefreshTokenRequest(""))).containsExactly("refreshToken");
        assertThat(invalidFields(new RefreshTokenRequest("r".repeat(129)))).containsExactly("refreshToken");
        assertThat(invalidFields(new RefreshTokenRequest("r".repeat(43)))).isEmpty();
    }

    @Test
    void toStringNeverContainsSecrets() {
        RegisterDeviceRequest request = new RegisterDeviceRequest("secret-token", "secret-nonce", "1.0.3", "android", 34);

        assertThat(request.toString()).doesNotContain("secret");
        assertThat(new RefreshTokenRequest("secret-refresh").toString()).doesNotContain("secret");
    }
}
