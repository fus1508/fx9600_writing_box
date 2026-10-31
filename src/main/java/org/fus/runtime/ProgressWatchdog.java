package org.fus.runtime;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Progress comes from the SDK-owning loop, not HTTP or a heartbeat-only thread. */
public final class ProgressWatchdog implements AutoCloseable {
    private final AtomicLong last = new AtomicLong(System.nanoTime());
    private final ScheduledExecutorService timer;
    public ProgressWatchdog(long timeoutMs, Runnable fatal) {
        if (timeoutMs <= 0) throw new IllegalArgumentException();
        timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "station-progress"); t.setDaemon(true); return t; });
        timer.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - last.get() >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) fatal.run();
        }, Math.min(250, timeoutMs), Math.min(250, timeoutMs), TimeUnit.MILLISECONDS);
    }
    public void progress() { last.set(System.nanoTime()); }
    public void close() { timer.shutdownNow(); }
}
