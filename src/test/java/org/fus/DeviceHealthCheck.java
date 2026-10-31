package org.fus;

import org.fus.health.DeviceHealth;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic evidence expiry; no SDK or physical device. */
public final class DeviceHealthCheck {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong();
        DeviceHealth health = new DeviceHealth(clock::get);
        check(health.snapshot().reader.state.equals("UNKNOWN"), "startup guessed a connection");
        health.readerConnecting();
        check(health.snapshot().reader.state.equals("RECOVERING"), "connect attempt missing");
        health.readerConnected();
        check(health.snapshot().reader.state.equals("CONNECTED"), "SDK connect alone became READY");
        health.readerProgress(); health.applicationProgress();
        health.scannerObservation(true, true, "node-A", "");
        check(health.snapshot().reader.state.equals("READY"), "GPI progress missing");
        check(health.snapshot().scanner.state.equals("CONNECTED") && !health.snapshot().scanner.safeToOperate,
                "plain CDC open became responsive READY");
        health.scannerDecode();
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1000));
        check(health.snapshot().reader.state.equals("UNKNOWN"), "hung GPI retained READY");
        check(health.snapshot().application.state.equals("UNKNOWN"), "hung loop retained READY");
        health.scannerObservation(true, true, "node-A", "");
        check(health.snapshot().scanner.state.equals("CONNECTED"), "operator idle blamed scanner");
        health.readerFault("SDK_DISCONNECT");
        check(health.snapshot().reader.state.equals("DISCONNECTED"), "disconnect callback lost during GPI hang");
        health.readerConnecting(); health.readerConnected(); health.readerProgress(); health.applicationProgress();
        check(health.snapshot().reader.state.equals("READY"), "reader reconnect failed");
        health.scannerObservation(false, false, "", "NODE_MISSING");
        check(health.snapshot().scanner.state.equals("DISCONNECTED"), "unplug not published");
        health.scannerObservation(true, false, "node-B", "REOPENING");
        check(health.snapshot().scanner.state.equals("RECOVERING"), "replug incorrectly READY");
        health.scannerObservation(true, true, "node-B", "");
        health.scannerObservation(true, false, "node-B", "CDC_CLEANUP_FAILED");
        check(health.snapshot().scanner.state.equals("UNKNOWN"), "unconfirmed cleanup presented as ready");
        health.stopped(); health.applicationProgress(); health.readerProgress();
        check(health.snapshot().application.state.equals("DISCONNECTED"), "terminal stop revived");
        check(!health.snapshot().reader.safeToOperate, "stopped application allows reader use");
        System.out.println("Device health checks passed (fake clock, SDK/GPI stall, reconnect, CDC idle, terminal stop)");
    }
}
