package com.mapmyroad.backend.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiExceptionHandlerTest {

    private static final String SECRET = "18.520412,73.856701";

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void apiExceptionBecomesProblemWithCodeAndProperties() throws Exception {
        mvc.perform(get("/v1/api-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.example.com/problems/unsupported-region"))
                .andExpect(jsonPath("$.title").value("Unsupported region"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Outside the supported region."))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_REGION"))
                .andExpect(jsonPath("$.hint").value("x"));
    }

    @Test
    void rateLimitedSetsRetryAfterHeader() throws Exception {
        mvc.perform(get("/v1/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSec").value(120));
    }

    @Test
    void bodyValidationListsFieldErrorsWithoutValues() throws Exception {
        MvcResult result = mvc.perform(post("/v1/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speedKmh\": 999, \"name\": \"" + SECRET + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").exists())
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("speedKmh").contains("name").doesNotContain(SECRET).doesNotContain("999");
    }

    @Test
    void typeMismatchInBodyIsMalformed() throws Exception {
        mvc.perform(post("/v1/body").contentType(MediaType.APPLICATION_JSON).content("{\"speedKmh\": \"fast\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedJsonIsInvalidRequest() throws Exception {
        mvc.perform(post("/v1/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Malformed request body."));
    }

    @Test
    void methodParameterValidation() throws Exception {
        mvc.perform(get("/v1/params").param("limit", "9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void missingParameter() throws Exception {
        mvc.perform(get("/v1/params"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    @Test
    void parameterTypeMismatchDoesNotEchoValue() throws Exception {
        MvcResult result = mvc.perform(get("/v1/params").param("limit", SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void constraintViolation() throws Exception {
        mvc.perform(get("/v1/violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("speedKmh"));
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(post("/v1/api-error"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void uploadTooLarge() throws Exception {
        mvc.perform(get("/v1/upload"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void unexpectedErrorIsGenericInternal() throws Exception {
        MvcResult result = mvc.perform(get("/v1/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurity() {
        assertThatThrownBy(() -> mvc.perform(get("/v1/denied"))).hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    void causeTypesNeverIncludeMessages() {
        String types = ApiExceptionHandler.causeTypes(new IllegalStateException(SECRET, new RuntimeException(SECRET)));
        assertThat(types).isEqualTo("java.lang.IllegalStateException <- java.lang.RuntimeException");
    }

    record Body(@NotNull @Max(200) Double speedKmh, @Size(max = 5) String name) {}

    @RestController
    static class ThrowingController {

        private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

        @GetMapping("/v1/api-error")
        String apiError() {
            throw new ApiException(ErrorCode.UNSUPPORTED_REGION, "Outside the supported region.", Map.of("hint", "x"));
        }

        @GetMapping("/v1/rate-limited")
        String rateLimited() {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Retry after 120 seconds.", Map.of("retryAfterSec", 120L));
        }

        @PostMapping("/v1/body")
        String body(@RequestBody @jakarta.validation.Valid Body body) {
            return "ok";
        }

        @GetMapping("/v1/params")
        String params(@RequestParam @Min(1) @Max(500) int limit) {
            return "ok";
        }

        @GetMapping("/v1/violation")
        String violation() {
            throw new ConstraintViolationException(VALIDATOR.validate(new Body(500.0, "ok")));
        }

        @GetMapping("/v1/upload")
        String upload() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/v1/boom")
        String boom() {
            throw new IllegalStateException("failed for " + SECRET);
        }

        @GetMapping("/v1/denied")
        String denied() {
            throw new AccessDeniedException("no");
        }
    }
}
