package org.fus.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exit codes: 0 intentional/clean, 20 config, 21 safety, 23 owner, 24 hung/session, 25 budget. */
public final class ProcessSupervisor {
    private final Path root;
    private final List<String> command;
    private final int maxRestarts;
    private final long baseMs, capMs, stopMs;
    private final AtomicBoolean stopping = new AtomicBoolean();
    public ProcessSupervisor(Path root, List<String> command, int maxRestarts, long baseMs, long capMs, long stopMs) {
        if (maxRestarts < 0 || maxRestarts > 100 || baseMs <= 0 || capMs < baseMs || stopMs <= 0) throw new IllegalArgumentException();
        this.root = root; this.command = new ArrayList<String>(command); this.maxRestarts = maxRestarts;
        this.baseMs = baseMs; this.capMs = capMs; this.stopMs = stopMs;
    }
    private boolean requested() { return stopping.get() || !Files.notExists(root.resolve("stop.request")); }
    private void event(String code) throws IOException { RuntimeState.replace(root.resolve("supervisor.status"), code + "\n"); }
    private int count() throws IOException {
        Path file = root.resolve("restart.count");
        if (!Files.notExists(root.resolve("restart.count.pending"))) throw new IOException("RESTART_BUDGET_INCOMPLETE");
        if (Files.notExists(file)) return 0;
        String value = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII);
        if (!value.matches("[0-9]{1,3}\\n")) throw new IOException("RESTART_BUDGET_CORRUPT");
        return Integer.parseInt(value.trim());
    }
    public int run() throws Exception {
        RuntimeState.Owner acquired;
        // Serialize clearing a stale stop with a new stop request. Lock order is control -> supervisor.
        try (RuntimeState.Owner control = control(root, 2000)) {
            acquired = RuntimeState.lock(root.resolve("supervisor.lock"));
            try { Files.deleteIfExists(root.resolve("stop.request")); }
            catch (Exception failure) { acquired.close(); throw failure; }
        }
        try (RuntimeState.Owner owner = acquired) {
            if (RuntimeState.uncertain(root)) { event("SESSION_RECOVERY_REQUIRED"); return 24; }
            if (!Files.notExists(root.resolve("restart.exhausted"))) { event("RESTART_EXHAUSTED"); return 25; }
            int restarts = count();
            Thread hook = new Thread(() -> {
                stopping.set(true);
                try { if (Files.notExists(root.resolve("stop.request"))) RuntimeState.create(root.resolve("stop.request"), "STOP\n"); }
                catch (IOException ignored) { }
                // The run loop retains ownership until child termination; JVM shutdown must wait for it.
                while (!finished.get()) try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }, "supervisor-stop");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                while (!requested()) {
                    if (RuntimeState.uncertain(root)) { event("SESSION_RECOVERY_REQUIRED"); return 24; }
                    // Probe orphan ownership before launch. Child also acquires this same lock.
                    try (RuntimeState.Owner station = RuntimeState.lock(root.resolve("station-owner.lock"))) { }
                    event("STARTING");
                    Process process = new ProcessBuilder(command).inheritIO().start();
                    try {
                        event("CHILD_RUNNING");
                        while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                            if (requested()) {
                                event("STOPPING");
                                if (!process.waitFor(stopMs, TimeUnit.MILLISECONDS)) {
                                    if (Files.notExists(root.resolve("hung.latch"))) RuntimeState.create(root.resolve("hung.latch"), "STOP_TIMEOUT\n");
                                    process.destroy();
                                    if (!process.waitFor(stopMs, TimeUnit.MILLISECONDS)) {
                                        process.destroyForcibly(); process.waitFor();
                                    }
                                    event("STOP_TIMEOUT_RECOVERY_REQUIRED"); return 24;
                                }
                                break;
                            }
                        }
                        int exit = process.exitValue();
                        if (exit == 24 || RuntimeState.uncertain(root)) {
                            if (Files.notExists(root.resolve("hung.latch"))) RuntimeState.create(root.resolve("hung.latch"), "SESSION_OR_PROGRESS_FAILURE\n");
                            event("SESSION_RECOVERY_REQUIRED"); return 24;
                        }
                        if (requested() || exit == 0) { event("STOPPED"); return 0; }
                        if (exit == 20 || exit == 21 || exit == 23) { event("TERMINAL_EXIT_" + exit); return exit; }
                        if (restarts >= maxRestarts) {
                            RuntimeState.create(root.resolve("restart.exhausted"), "OFFLINE_INVESTIGATION_REQUIRED\n");
                            event("RESTART_EXHAUSTED"); return 25;
                        }
                        restarts++;
                        RuntimeState.replace(root.resolve("restart.count"), restarts + "\n");
                        long delay = baseMs;
                        for (int n = 1; n < restarts && delay < capMs; n++) delay = delay > capMs / 2 ? capMs : Math.min(capMs, delay * 2);
                        event("BACKOFF_" + delay);
                        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
                        while (!requested() && System.nanoTime() < until) Thread.sleep(25);
                    } finally {
                        if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
                    }
                }
                event("STOPPED"); return 0;
            } finally {
                finished.set(true);
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { }
            }
        }
    }
    private final AtomicBoolean finished = new AtomicBoolean();
    private static RuntimeState.Owner control(Path root, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            try { return RuntimeState.lock(root.resolve("control.lock")); }
            catch (IOException busy) { if (System.nanoTime() >= deadline) throw busy; Thread.sleep(25); }
        }
    }
    public static int stop(Path root, long timeoutMs) throws Exception {
        try (RuntimeState.Owner control = control(root, timeoutMs)) {
            try (RuntimeState.Owner owner = RuntimeState.lock(root.resolve("supervisor.lock"))) {
                // No supervisor: do not claim an orphan child has stopped.
                try (RuntimeState.Owner station = RuntimeState.lock(root.resolve("station-owner.lock"))) { return RuntimeState.uncertain(root) ? 24 : 0; }
            } catch (IOException active) {
                if (Files.notExists(root.resolve("stop.request"))) RuntimeState.create(root.resolve("stop.request"), "STOP\n");
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            try (RuntimeState.Owner owner = RuntimeState.lock(root.resolve("supervisor.lock"));
                    RuntimeState.Owner station = RuntimeState.lock(root.resolve("station-owner.lock"))) {
                return RuntimeState.uncertain(root) ? 24 : 0;
            } catch (IOException stillActive) { Thread.sleep(50); }
        }
        return 24;
    }
}
