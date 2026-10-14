package com.mapmyroad.backend.api.web;

import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.api.error.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request body size limits and gzip decoding (API_SPEC.md sections 1 and 5.3, SECURITY.md T11).
 *
 * <ul>
 *   <li>{@code Content-Encoding: gzip}: the body is decompressed on the fly and limited to
 *       {@code maxBodyBytes} decompressed bytes (gzip bomb protection).
 *   <li>Other encodings than gzip or identity: 400, because nothing downstream could read them.
 *   <li>Plain bodies: a declared {@code Content-Length} above the limit is rejected before reading anything;
 *       bodies without a length (chunked) are counted while read.
 *   <li>{@code multipart/form-data} without compression is left to Spring's multipart limits (v3 photo reports).
 * </ul>
 *
 * Violations detected up front are answered here with 413; violations found while a controller reads the body
 * surface as {@link BodyTooLargeException} and are mapped to 413 by {@code ApiExceptionHandler}.
 */
public class GzipRequestFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public GzipRequestFilter(long maxBodyBytes) {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String encoding = normalizedEncoding(request.getHeader(HttpHeaders.CONTENT_ENCODING));
        long declaredLength = request.getContentLengthLong();
        if ("gzip".equals(encoding) || "x-gzip".equals(encoding)) {
            // Compressed bytes never legitimately exceed the decompressed limit, so fail fast on the wire size too.
            if (declaredLength > maxBodyBytes) {
                ProblemResponses.write(response, ErrorCode.PAYLOAD_TOO_LARGE, BodyTooLargeException.DETAIL);
                return;
            }
            chain.doFilter(new LimitedBodyRequest(request, true, maxBodyBytes), response);
            return;
        }
        if (!encoding.isEmpty() && !"identity".equals(encoding)) {
            ProblemResponses.write(response, ErrorCode.INVALID_REQUEST, "Unsupported Content-Encoding.");
            return;
        }
        if (isMultipart(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (declaredLength > maxBodyBytes) {
            ProblemResponses.write(response, ErrorCode.PAYLOAD_TOO_LARGE, BodyTooLargeException.DETAIL);
            return;
        }
        // With a declared length the container already stops at that many bytes; only unbounded bodies need counting.
        HttpServletRequest next = declaredLength < 0 ? new LimitedBodyRequest(request, false, maxBodyBytes) : request;
        chain.doFilter(next, response);
    }

    private static String normalizedEncoding(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }
}
