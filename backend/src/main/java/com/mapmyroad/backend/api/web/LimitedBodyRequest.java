package com.mapmyroad.backend.api.web;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.http.HttpHeaders;

/**
 * Request whose body is size-limited and, for {@code Content-Encoding: gzip}, transparently decompressed. The
 * {@code Content-Encoding} and {@code Content-Length} headers are hidden because they describe the wire bytes,
 * not the body that downstream readers now see.
 */
final class LimitedBodyRequest extends HttpServletRequestWrapper {

    private final boolean gunzip;
    private final long maxBytes;
    private ServletInputStream stream;
    private BufferedReader reader;

    LimitedBodyRequest(HttpServletRequest request, boolean gunzip, long maxBytes) {
        super(request);
        this.gunzip = gunzip;
        this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (reader != null) {
            throw new IllegalStateException("getReader() has already been called");
        }
        return limitedStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            if (stream != null) {
                throw new IllegalStateException("getInputStream() has already been called");
            }
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            reader = new BufferedReader(new InputStreamReader(limitedStream(), charset));
        }
        return reader;
    }

    private ServletInputStream limitedStream() throws IOException {
        if (stream == null) {
            stream = new LimitedServletInputStream(super.getInputStream(), gunzip, maxBytes);
        }
        return stream;
    }

    @Override
    public int getContentLength() {
        return -1;
    }

    @Override
    public long getContentLengthLong() {
        return -1L;
    }

    @Override
    public String getHeader(String name) {
        return isHidden(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return isHidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = Collections.list(super.getHeaderNames());
        names.removeIf(LimitedBodyRequest::isHidden);
        return Collections.enumeration(names);
    }

    @Override
    public int getIntHeader(String name) {
        return isHidden(name) ? -1 : super.getIntHeader(name);
    }

    private static boolean isHidden(String name) {
        return HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name) || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name);
    }
}
