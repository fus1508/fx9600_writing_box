package org.fus;

import org.fus.runtime.ProcessSupervisor;
import org.fus.runtime.RuntimeState;
import org.fus.runtime.ProgressWatchdog;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real child JVMs; no SDK initialization or hardware access. */
public final class ProcessSupervisorCheck {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static List<String> child(Path root, String mode) {
        return Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), ProcessSupervisorCheck.class.getName(), "child", root.toString(), mode);
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) {
            Path root = Paths.get(args[1]); String mode = args[2];
            try (RuntimeState.Owner owner = RuntimeState.lock(root.resolve("station-owner.lock"))) {
                Files.write(root.resolve("launches"), new byte[] { 1 }, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if ("fenced".equals(mode)) RuntimeState.create(root.resolve("sdk-session.pending"), "uncertain");
                if ("wait".equals(mode)) {
                    Files.write(root.resolve("ready"), new byte[] { 1 });
                    while (!Files.exists(root.resolve("stop.request"))) Thread.sleep(20);
                    return;
                }
                if ("hang".equals(mode)) {
                    new ProgressWatchdog(100, () -> Runtime.getRuntime().halt(24));
                    synchronized (System.out) { synchronized (System.err) { Thread.sleep(60000); } }
                }
                if ("ignore-stop".equals(mode)) { Files.write(root.resolve("ready"), new byte[] { 1 }); Thread.sleep(60000); }
                if ("config".equals(mode)) System.exit(20);
                if ("clean".equals(mode)) return;
                Runtime.getRuntime().halt(7);
            }
        }
        for (String mode : Arrays.asList("crash", "clean", "config", "hang", "fenced")) {
            Path root = Files.createTempDirectory("fx-supervisor-");
            long start = System.nanoTime();
            int code = new ProcessSupervisor(root, child(root, mode), 2, 80, 160, 2000).run();
            int launches = Files.readAllBytes(root.resolve("launches")).length;
            check(launches == ("crash".equals(mode) ? 3 : 1), "unexpected restart " + mode + ": " + launches);
            if ("crash".equals(mode)) {
                check(code == 25 && Files.exists(root.resolve("restart.exhausted")), "missing crash-loop latch");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 240, "backoff skipped");
                new ProcessSupervisor(root, child(root, mode), 2, 80, 160, 2000).run();
                check(Files.readAllBytes(root.resolve("launches")).length == 3, "budget reset across invocation");
            }
            if ("clean".equals(mode)) check(code == 0, "clean exit restarted");
            if ("config".equals(mode)) check(code == 20, "config not classified");
            if ("hang".equals(mode) || "fenced".equals(mode)) check(code == 24, "unsafe session restarted");
        }
        Path root = Files.createTempDirectory("fx-stop-");
        final int[] result = { -1 };
        Thread thread = new Thread(() -> {
            try { result[0] = new ProcessSupervisor(root, child(root, "wait"), 2, 80, 160, 3000).run(); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        thread.start(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(root.resolve("ready")) && System.nanoTime() < deadline) Thread.sleep(20);
        check(Files.exists(root.resolve("ready")), "child not ready");
        try { new ProcessSupervisor(root, child(root, "wait"), 2, 80, 160, 3000).run(); throw new AssertionError("duplicate supervisor accepted"); }
        catch (java.io.IOException expected) { }
        check(ProcessSupervisor.stop(root, 5000) == 0, "stop failed");
        thread.join(5000); check(!thread.isAlive() && result[0] == 0, "stop did not finish");
        check(Files.readAllBytes(root.resolve("launches")).length == 1, "stop restarted child");
        try (RuntimeState.Owner owner = RuntimeState.lock(root.resolve("station-owner.lock"))) {
            try { RuntimeState.lock(root.resolve("station-owner.lock")); throw new AssertionError("duplicate station accepted"); }
            catch (java.io.IOException expected) { }
            try { new ProcessSupervisor(root, child(root, "clean"), 2, 80, 160, 3000).run(); throw new AssertionError("orphan accepted"); }
            catch (java.io.IOException expected) { }
        }
        for (String mode : Arrays.asList("ignore-stop", "crash")) {
            Path stopRoot = Files.createTempDirectory("fx-stop-boundary-"); final int[] outcome = { -1 };
            Thread runner = new Thread(() -> {
                try { outcome[0] = new ProcessSupervisor(stopRoot, child(stopRoot, mode), 2, 3000, 6000, 200).run(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }); runner.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Path signal = stopRoot.resolve("ignore-stop".equals(mode) ? "ready" : "restart.count");
            while (System.nanoTime() < until) {
                if (Files.exists(signal)) break;
                Thread.sleep(20);
            }
            check(Files.exists(signal), "stop boundary not reached");
            int stopped = ProcessSupervisor.stop(stopRoot, 5000); runner.join(5000);
            check(!runner.isAlive() && stopped == ("ignore-stop".equals(mode) ? 24 : 0), "stop boundary result " + mode);
            check(outcome[0] == stopped, "supervisor result differs from stop " + mode);
            check(Files.readAllBytes(stopRoot.resolve("launches")).length == 1, "respawn after stop " + mode);
        }
        for (String fault : Arrays.asList("restart.count", "restart.count.pending")) {
            Path broken = Files.createTempDirectory("fx-budget-fault-");
            RuntimeState.create(broken.resolve(fault), "CORRUPT\n");
            try { new ProcessSupervisor(broken, child(broken, "clean"), 2, 80, 160, 3000).run(); throw new AssertionError("budget fault ignored"); }
            catch (java.io.IOException expected) { }
            check(!Files.exists(broken.resolve("launches")), "budget failure launched child");
        }
        final java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        try (ProgressWatchdog watchdog = new ProgressWatchdog(200, () -> expired.set(true))) {
            for (int n = 0; n < 8; n++) { watchdog.progress(); Thread.sleep(40); }
            check(!expired.get(), "healthy progress expired");
            Thread.sleep(450); check(expired.get(), "stalled progress did not expire");
        }
        System.out.println("Process supervisor checks passed (child crash, budget, backoff, stop, duplicate, fence, exit)");
    }
}
