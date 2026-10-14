package com.mapmyroad.backend.api.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies one or more rate-limit policies to a controller method (API_SPEC.md section 4).
 * Exceeding any policy returns 429 RATE_LIMITED with a Retry-After header.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimited {
    RateLimitPolicy[] value();
}
