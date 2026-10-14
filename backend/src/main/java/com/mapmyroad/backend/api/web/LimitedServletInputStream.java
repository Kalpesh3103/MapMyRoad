package com.mapmyroad.backend.api.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Request body stream that optionally gunzips and fails with {@link BodyTooLargeException} as soon as more than
 * {@code maxBytes} bytes have been produced. Counting the produced (decompressed) bytes is what defeats gzip bombs:
 * a tiny compressed body that inflates to gigabytes is cut off after {@code maxBytes + 1} bytes.
 *
 * <p>Blocking only: async (non-blocking) reads are not used by this API, so {@link #setReadListener} is rejected.
 */
final class LimitedServletInputStream extends ServletInputStream {

    private final ServletInputStream raw;
    private final boolean gunzip;
    private final long maxBytes;
    private InputStream source;
    private long produced;
    private boolean finished;

    LimitedServletInputStream(ServletInputStream raw, boolean gunzip, long maxBytes) {
        this.raw = raw;
        this.gunzip = gunzip;
        this.maxBytes = maxBytes;
    }

    /** Lazily opened because GZIPInputStream reads the gzip header in its constructor. */
    private InputStream source() throws IOException {
        if (source == null) {
            source = gunzip ? new GZIPInputStream(raw, 8192) : raw;
        }
        return source;
    }

    @Override
    public int read() throws IOException {
        if (finished) {
            return -1;
        }
        int b = source().read();
        if (b < 0) {
            finished = true;
            return -1;
        }
        count(1);
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (finished) {
            return -1;
        }
        if (length == 0) {
            return 0;
        }
        // Never read more than one byte past the limit, so a bomb cannot make us inflate a large chunk first.
        long allowed = maxBytes - produced + 1;
        int n = source().read(buffer, offset, (int) Math.min(length, Math.max(1, allowed)));
        if (n < 0) {
            finished = true;
            return -1;
        }
        count(n);
        return n;
    }

    private void count(int n) throws BodyTooLargeException {
        produced += n;
        if (produced > maxBytes) {
            finished = true;
            throw new BodyTooLargeException();
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        throw new IllegalStateException("Non-blocking reads are not supported for size-limited request bodies");
    }

    @Override
    public void close() throws IOException {
        if (source != null) {
            source.close();
        } else {
            raw.close();
        }
    }
}
