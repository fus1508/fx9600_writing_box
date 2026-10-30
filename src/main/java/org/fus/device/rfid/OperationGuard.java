package org.fus.device.rfid;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Timer;
import java.util.TimerTask;

/** Records an in-flight access and runs a recovery action if it does not complete. */
public final class OperationGuard implements AutoCloseable {
    private final Path marker;
    private final Timer timer = new Timer("rfid-operation-watchdog", true);
    private boolean active;
    private long generation;

    public OperationGuard(Path marker) { this.marker = marker; }
    public boolean hasUnfinishedOperation() { return Files.exists(marker); }

    public synchronized void start(long timeoutMs, Runnable onTimeout) throws IOException {
        if (active || timeoutMs <= 0) throw new IllegalStateException("Invalid watchdog state");
        try (FileOutputStream file = new FileOutputStream(marker.toFile())) {
            file.write("RFID_OPERATION_IN_PROGRESS\n".getBytes("US-ASCII"));
            file.getFD().sync();
        }
        active = true;
        final long current = ++generation;
        timer.schedule(new TimerTask() {
            @Override public void run() {
                synchronized (OperationGuard.this) {
                    if (!active || generation != current) return;
                    active = false;
                    onTimeout.run();
                }
            }
        }, timeoutMs);
    }
    public synchronized void completed() throws IOException {
        if (!active) return;
        active = false;
        Files.deleteIfExists(marker);
    }
    @Override public void close() { timer.cancel(); }
}
