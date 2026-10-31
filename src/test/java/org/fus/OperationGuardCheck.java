package org.fus;

import org.fus.device.rfid.OperationGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class OperationGuardCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "blocked".equals(args[0])) {
            try (org.fus.api.StatusServer status = new org.fus.api.StatusServer("127.0.0.1", 0);
                    OperationGuard guard = new OperationGuard(Paths.get(args[1]))) {
                synchronized (status) { synchronized (System.out) { synchronized (System.err) {
                    guard.startFatal(100);
                    Thread.sleep(10000);
                } } }
            }
            throw new AssertionError("fatal deadline waited for status/log locks");
        }
        if (args.length == 2 && "child".equals(args[0])) {
            try (OperationGuard guard = new OperationGuard(Paths.get(args[1]))) {
                guard.start(100, () -> Runtime.getRuntime().halt(23));
                Thread.sleep(10000);
            }
            throw new AssertionError("watchdog did not halt child");
        }
        Path directory = Files.createTempDirectory("rfid-watchdog-");
        Path marker = directory.resolve("operation.marker");
        Files.write(marker, "legacy evidence".getBytes("US-ASCII"));
        try (OperationGuard existing = new OperationGuard(marker)) {
            try { existing.start(100, () -> { }); throw new AssertionError("existing marker overwritten"); }
            catch (java.io.IOException expected) { }
            if (!new String(Files.readAllBytes(marker), "US-ASCII").equals("legacy evidence"))
                throw new AssertionError("legacy evidence changed");
        }
        Files.delete(marker);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch timedOut = new CountDownLatch(1);
        try (OperationGuard guard = new OperationGuard(marker)) {
            guard.start(50, calls::incrementAndGet);
            if (!Files.exists(marker)) throw new AssertionError("marker not created");
            guard.completed();
            guard.start(200, () -> { calls.incrementAndGet(); timedOut.countDown(); });
            Thread.sleep(100);
            if (calls.get() != 0) throw new AssertionError("previous deadline affected new operation");
            if (!timedOut.await(2, TimeUnit.SECONDS)) throw new AssertionError("deadline did not fire");
            if (!guard.hasUnfinishedOperation() || calls.get() != 1) throw new AssertionError("unfinished operation not retained");
            Files.delete(marker);
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    OperationGuardCheck.class.getName(), "child", marker.toString()).start();
            if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("child did not halt"); }
            if (child.exitValue() != 23 || !Files.exists(marker)) throw new AssertionError("crash recovery marker missing");
            Files.delete(marker);
            Process blocked = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    OperationGuardCheck.class.getName(), "blocked", marker.toString()).start();
            if (!blocked.waitFor(5, TimeUnit.SECONDS)) { blocked.destroyForcibly(); throw new AssertionError("fatal deadline blocked on status/log locks"); }
            if (blocked.exitValue() != 2 || !Files.exists(marker)) throw new AssertionError("fatal deadline lost evidence");
        } finally { Files.deleteIfExists(marker); Files.deleteIfExists(directory); }
        System.out.println("Operation watchdog checks passed");
    }
}
