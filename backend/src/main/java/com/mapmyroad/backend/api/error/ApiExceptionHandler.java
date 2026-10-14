package com.mapmyroad.backend.api.error;

import com.mapmyroad.backend.api.web.BodyTooLargeException;
import com.mapmyroad.backend.api.web.RequestIdFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central mapping of exceptions to RFC 9457 problem details with a stable {@code code} (API_SPEC.md section 3,
 * CODING_STANDARDS.md section 4.3).
 *
 * <p>Privacy: details and field messages never echo submitted values, and unexpected errors are logged with the
 * request ID and exception types only, never messages or bodies, because those can contain coordinates or tokens
 * (AGENTS.md section 6).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String INVALID_FIELDS_DETAIL = "One or more fields are invalid.";
    static final String INVALID_VALUE_MESSAGE = "has an invalid value";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return respond(ProblemResponses.problem(ex.code(), ex.getMessage(), ex.properties()), ex.properties());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<FieldProblem> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.add(fieldProblem(e)));
        ex.getBindingResult().getGlobalErrors().forEach(e -> errors.add(globalProblem(e)));
        return invalid(INVALID_FIELDS_DETAIL, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
        List<FieldProblem> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(e -> errors.add(fieldProblem(e)));
                parameterErrors.getGlobalErrors().forEach(e -> errors.add(globalProblem(e)));
            } else {
                String name = parameterName(result);
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new FieldProblem(name, messageOrDefault(error.getDefaultMessage())));
                }
            }
        }
        return invalid(INVALID_FIELDS_DETAIL, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldProblem> errors = ex.getConstraintViolations().stream()
                .map(ApiExceptionHandler::violationProblem)
                .sorted(Comparator.comparing(FieldProblem::field))
                .toList();
        return invalid(INVALID_FIELDS_DETAIL, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex) {
        // The size-limited body stream (GzipRequestFilter) surfaces here, wrapped by Jackson and the converter.
        if (hasCause(ex, BodyTooLargeException.class)) {
            return simple(ErrorCode.PAYLOAD_TOO_LARGE, BodyTooLargeException.DETAIL);
        }
        return invalid("Malformed request body.", List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException ex) {
        return invalid(INVALID_FIELDS_DETAIL, List.of(new FieldProblem(ex.getParameterName(), "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return invalid(INVALID_FIELDS_DETAIL, List.of(new FieldProblem(ex.getName(), INVALID_VALUE_MESSAGE)));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(Exception ex) {
        return simple(ErrorCode.NOT_FOUND, "Resource not found.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        // API_SPEC.md has no code for 405, so it reuses INVALID_REQUEST with the real status.
        ProblemDetail problem = ProblemResponses.problem(
                ErrorCode.INVALID_REQUEST, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed.", Map.of());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return builder.body(problem);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return simple(ErrorCode.PAYLOAD_TOO_LARGE, "Upload exceeds the size limit.");
    }

    /**
     * Last resort. Framework client errors (406, 415, ...) keep their status with INVALID_REQUEST; anything else
     * becomes a generic 500. Security exceptions are rethrown so Spring Security's entry point and access-denied
     * handler (SecurityConfig) produce the 401/403 response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = errorResponse.getStatusCode();
            ErrorCode code = status.value() == HttpStatus.NOT_FOUND.value()
                    ? ErrorCode.NOT_FOUND
                    : ErrorCode.INVALID_REQUEST;
            String detail = status instanceof HttpStatus known ? known.getReasonPhrase() + "." : "Request rejected.";
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemResponses.problem(code, status, detail, Map.of()));
        }
        log.error(
                "Unhandled exception requestId={} types={} at={}",
                MDC.get(RequestIdFilter.MDC_KEY),
                causeTypes(ex),
                firstFrame(ex));
        return simple(ErrorCode.INTERNAL, "An unexpected error occurred.");
    }

    private static ResponseEntity<ProblemDetail> invalid(String detail, List<FieldProblem> errors) {
        Map<String, Object> properties = errors.isEmpty() ? Map.of() : Map.of("errors", errors);
        return respond(ProblemResponses.problem(ErrorCode.INVALID_REQUEST, detail, properties), properties);
    }

    private static ResponseEntity<ProblemDetail> simple(ErrorCode code, String detail) {
        return respond(ProblemResponses.problem(code, detail, Map.of()), Map.of());
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem, Map<String, Object> properties) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        String retryAfter = ProblemResponses.retryAfterHeader(properties);
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(problem);
    }

    /** Binding failures (type conversion) carry a default message that includes the rejected value, so drop it. */
    private static FieldProblem fieldProblem(FieldError error) {
        String message = error.isBindingFailure() ? INVALID_VALUE_MESSAGE : messageOrDefault(error.getDefaultMessage());
        return new FieldProblem(error.getField(), message);
    }

    private static FieldProblem globalProblem(ObjectError error) {
        return new FieldProblem(error.getObjectName(), messageOrDefault(error.getDefaultMessage()));
    }

    private static FieldProblem violationProblem(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        // Method validation paths start with "method.argN"; the client only cares about the last nodes.
        int firstDot = path.indexOf('.');
        String field = firstDot >= 0 && path.indexOf('(') < 0 ? path.substring(firstDot + 1) : path;
        return new FieldProblem(field, messageOrDefault(violation.getMessage()));
    }

    private static String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name == null ? "arg" + result.getMethodParameter().getParameterIndex() : name;
    }

    private static String messageOrDefault(String message) {
        return message == null || message.isBlank() ? INVALID_VALUE_MESSAGE : message;
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** Exception class names along the cause chain, without messages (messages may contain user data). */
    static String causeTypes(Throwable ex) {
        StringJoiner joiner = new StringJoiner(" <- ");
        int depth = 0;
        for (Throwable t = ex; t != null && depth < 8; t = t.getCause() == t ? null : t.getCause(), depth++) {
            joiner.add(t.getClass().getName());
        }
        return joiner.toString();
    }

    /** First stack frame in our own code (or the root cause's top frame) to locate the failure without messages. */
    private static String firstFrame(Throwable ex) {
        Throwable root = ex;
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().startsWith("com.mapmyroad.")) {
                    return frame.toString();
                }
            }
            root = t;
        }
        StackTraceElement[] trace = root.getStackTrace();
        return trace.length == 0 ? "unknown" : trace[0].toString();
    }
}
