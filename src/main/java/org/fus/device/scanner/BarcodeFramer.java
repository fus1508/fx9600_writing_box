package org.fus.device.scanner;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Collects LF-terminated scanner frames and discards stale or oversized input. */
public final class BarcodeFramer {
    private final int maximumBytes;
    private final long timeoutNanos;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean overflow;
    private long lastByteAt;
    private boolean started;

    public BarcodeFramer(int maximumBytes, long timeoutNanos) {
        if (maximumBytes < 1 || timeoutNanos < 1) throw new IllegalArgumentException();
        this.maximumBytes = maximumBytes;
        this.timeoutNanos = timeoutNanos;
    }
    public String accept(int value, long now) {
        if (value < 0 || value > 255) throw new IllegalArgumentException();
        if (started && now - lastByteAt >= timeoutNanos) reset();
        if (value == 10) {
            String frame = !overflow && bytes.size() > 0 ? new String(bytes.toByteArray(), StandardCharsets.US_ASCII) : null;
            reset();
            return frame;
        }
        if (bytes.size() < maximumBytes) bytes.write(value); else overflow = true;
        started = true;
        lastByteAt = now;
        return null;
    }
    private void reset() { bytes.reset(); overflow = false; started = false; }
}
