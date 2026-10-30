package org.fus.device.gpi;

/** Debounces an active-low button. The initial reading cannot itself trigger a press. */
public final class GpiButton {
    private final long debounceNanos;
    private boolean initialized;
    private boolean rawHigh;
    private boolean stableHigh;
    private boolean armed;
    private long changedAt;
    public GpiButton(long debounceNanos) {
        if (debounceNanos <= 0) throw new IllegalArgumentException();
        this.debounceNanos = debounceNanos;
    }
    public boolean update(boolean high, long now) {
        if (!initialized) {
            initialized = true;
            rawHigh = high;
            stableHigh = high;
            changedAt = now;
            return false;
        }
        if (high != rawHigh) { rawHigh = high; changedAt = now; }
        if (now - changedAt < debounceNanos) return false;
        if (rawHigh && !armed) armed = true;
        if (stableHigh != rawHigh) {
            stableHigh = rawHigh;
            if (!stableHigh && armed) { armed = false; return true; }
        }
        return false;
    }
}
