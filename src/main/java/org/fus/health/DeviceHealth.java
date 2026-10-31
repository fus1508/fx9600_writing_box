package org.fus.health;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Evidence only: never calls SDK, filesystem, HTTP, logging or workflow. */
public final class DeviceHealth {
    public static final long STALE_MS = 1000;
    private static final long NONE = Long.MIN_VALUE;
    private final LongSupplier clock;
    private final long startedAt;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong application = new AtomicLong(NONE), decode = new AtomicLong(NONE);
    private final AtomicReference<Evidence> reader = new AtomicReference<Evidence>(Evidence.unknown());
    private final AtomicReference<Evidence> scanner = new AtomicReference<Evidence>(Evidence.unknown());

    private static final class Evidence {
        final String connection, state, reason, source, identity;
        final long observed, progress;
        final boolean portOpen;
        Evidence(String connection, String state, String reason, String source, String identity,
                 long observed, long progress, boolean portOpen) {
            this.connection = connection; this.state = state; this.reason = reason; this.source = source;
            this.identity = identity; this.observed = observed; this.progress = progress; this.portOpen = portOpen;
        }
        static Evidence unknown() { return new Evidence("UNKNOWN", "UNKNOWN", "NO_EVIDENCE", "NONE", "", NONE, NONE, false); }
    }
    public static final class Device {
        public final String connection, readiness, state, reason, evidence, identity;
        public final long observedAgeMs, progressAgeMs;
        public final boolean safeToOperate, portOpen;
        private Device(Evidence e, String state, String reason, long now, boolean stopped) {
            this.connection = e.connection; this.state = state; this.reason = reason; this.evidence = e.source;
            this.identity = e.identity; this.observedAgeMs = age(now, e.observed); this.progressAgeMs = age(now, e.progress);
            this.readiness = "READY".equals(state) ? "READY" : "UNKNOWN".equals(state) || "CONNECTED".equals(state) ? "UNKNOWN" : "NOT_READY";
            this.safeToOperate = !stopped && "READY".equals(state); this.portOpen = e.portOpen;
        }
    }
    public static final class Snapshot {
        public final Device reader, scanner, application;
        public final long uptimeMs, lastDecodeAgeMs;
        private Snapshot(Device reader, Device scanner, Device application, long uptimeMs, long lastDecodeAgeMs) {
            this.reader = reader; this.scanner = scanner; this.application = application;
            this.uptimeMs = uptimeMs; this.lastDecodeAgeMs = lastDecodeAgeMs;
        }
    }
    public DeviceHealth() { this(System::nanoTime); }
    public DeviceHealth(LongSupplier clock) { this.clock = clock; startedAt = clock.getAsLong(); }
    public void readerConnecting() {
        reader.set(new Evidence("UNKNOWN", "RECOVERING", "SDK_CONNECTING", "SDK_OWNER", "", clock.getAsLong(), NONE, false));
    }
    public void readerConnected() {
        reader.set(new Evidence("CONNECTED", "CONNECTED", "GPI_NOT_OBSERVED", "SDK_CONNECT", "", clock.getAsLong(), NONE, false));
    }
    public void readerProgress() {
        long now = clock.getAsLong();
        reader.updateAndGet(e -> !"CONNECTED".equals(e.connection) ? e :
                new Evidence("CONNECTED", "READY", "", "SDK_GPI_COMPLETED", "", now, now, false));
    }
    public void readerFault(String reason) {
        long now = clock.getAsLong();
        reader.updateAndGet(e -> "DISCONNECTED".equals(e.state) ? e :
                new Evidence("DISCONNECTED", "DISCONNECTED", reason, "SDK_EVENT_OR_OWNER", "", now, NONE, false));
    }
    public void applicationProgress() { if (!stopped.get()) application.set(clock.getAsLong()); }
    public void stopped() { stopped.set(true); }
    public void scannerObservation(boolean nodePresent, boolean portOpen, String identity, String reason) {
        scanner.set(new Evidence(nodePresent ? "CONNECTED" : "DISCONNECTED",
                !nodePresent ? "DISCONNECTED" : "CDC_CLEANUP_FAILED".equals(reason) ? "UNKNOWN" : portOpen ? "CONNECTED" : "RECOVERING",
                portOpen ? "CDC_LIVENESS_UNVERIFIED" : reason, "OS_NODE_AND_CDC", identity,
                clock.getAsLong(), NONE, portOpen));
    }
    public void scannerDecode() { decode.set(clock.getAsLong()); }
    private static long age(long now, long at) { return at == NONE ? -1 : Math.max(0, TimeUnit.NANOSECONDS.toMillis(now - at)); }
    private static Device device(Evidence e, long now, boolean stopped, boolean progressRequired) {
        boolean stale = !"DISCONNECTED".equals(e.state) &&
                (age(now, e.observed) < 0 || age(now, e.observed) >= STALE_MS ||
                 progressRequired && "READY".equals(e.state) && age(now, e.progress) >= STALE_MS);
        return new Device(e, stale ? "UNKNOWN" : e.state, stale ? "PROGRESS_STALE" : e.reason, now, stopped);
    }
    public Snapshot snapshot() {
        long now = clock.getAsLong(), progress = application.get(); boolean terminal = stopped.get();
        Evidence app = new Evidence(terminal ? "DISCONNECTED" : "CONNECTED", terminal ? "DISCONNECTED" : "READY",
                terminal ? "APPLICATION_STOPPED" : "", "CONTROL_LOOP_COMPLETED", "", progress, progress, false);
        return new Snapshot(device(reader.get(), now, terminal, true), device(scanner.get(), now, terminal, false),
                device(app, now, terminal, true), age(now, startedAt), age(now, decode.get()));
    }
}
