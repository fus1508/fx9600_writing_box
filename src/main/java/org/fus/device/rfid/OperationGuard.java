package org.fus.device.rfid;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Timer;
import java.util.TimerTask;

/** Records an in-flight access and runs a recovery action if it does not complete. */
public final class OperationGuard implements AutoCloseable {
    private final Path marker;
    private final Timer timer = new Timer("rfid-operation-watchdog", true);
    private boolean active;
    private long generation;

    /** Phase 1 uses the transaction journal as durable evidence; this guard only owns the deadline. */
    public OperationGuard() { this.marker = null; }
    public OperationGuard(Path marker) { this.marker = marker; }
    public boolean hasUnfinishedOperation() { return marker != null && !Files.notExists(marker); }

    /** Fatal deadline shared by production and the blocked-status/log regression test. */
    public void startFatal(long timeoutMs) throws IOException {
        start(timeoutMs, () -> Runtime.getRuntime().halt(2));
    }

    public synchronized void start(long timeoutMs, Runnable onTimeout) throws IOException {
        if (active || timeoutMs <= 0) throw new IllegalStateException("Invalid watchdog state");
        if (marker != null) try (FileChannel file = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap("RFID_OPERATION_IN_PROGRESS\n".getBytes("US-ASCII"));
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
        active = true;
        final long current = ++generation;
        timer.schedule(new TimerTask() {
            @Override public void run() {
                synchronized (OperationGuard.this) {
                    if (!active || generation != current) return;
                    active = false;
                }
                onTimeout.run();
            }
        }, timeoutMs);
    }
    public synchronized void completed() throws IOException {
        if (!active) return;
        if (marker != null) Files.delete(marker);
        active = false;
    }
    @Override public void close() { timer.cancel(); }
}
