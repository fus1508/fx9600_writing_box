package org.fus;

import org.fus.api.StatusServer;
import org.fus.health.DeviceHealth;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class HealthApiCheck {
    static String get(int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(1500); c.setReadTimeout(2000);
        try {
            int code = c.getResponseCode();
            if (code == 503) return "BUSY";
            if (code != 200) throw new AssertionError("HTTP " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) >= 0) b.write(buf, 0, n); }
            return new String(b.toByteArray(), "UTF-8");
        } finally { c.disconnect(); }
    }
    private static void check(boolean b, String why) { if (!b) throw new AssertionError(why); }
    static String revision(String json) { return json.split("\"revision\":\"")[1].split("\"")[0]; }
    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(); DeviceHealth health = new DeviceHealth(now::get);
        try (StatusServer server = new StatusServer("127.0.0.1", 0, health)) {
            health.readerConnected(); health.readerProgress(); health.applicationProgress();
            health.scannerObservation(true, true, "fake-node", ""); server.start();
            server.status("CONNECTION", null, null, "scanner=READY reader=READY");
            server.status("WAIT_TRIGGER", "9K1135600660004039", "", ""); server.safety(false, true, "fake", "");
            String json = get(server.port(), "/status");
            check(json.contains("\"schemaVersion\":2") && json.contains("\"bootId\":") && json.contains("\"health\":"), "schema v2 missing");
            check(json.contains("\"scanner\":\"CONNECTED\"") && json.contains("\"safeToTrigger\":false"), "CDC claimed safe readiness");
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1000));
            json = get(server.port(), "/status");
            check(json.contains("\"reader\":\"UNKNOWN\"") && json.contains("PROGRESS_STALE"), "HTTP 200 renewed dead SDK");
            health.readerFault("SDK_DISCONNECT");
            check(get(server.port(), "/status").contains("\"reader\":\"DISCONNECTED\""), "callback disconnect not visible");
            server.setRecoveryWarning();
            server.status("WRITING", "", "", "");
            server.status("CONNECTION", null, null, "scanner=DISCONNECTED reader=DISCONNECTED");
            check(get(server.port(), "/status").contains("\"state\":\"WRITING\""), "disconnect hid in-flight outcome");
            health.stopped();
            json = get(server.port(), "/status");
            check(json.contains("\"recoveryRequired\":true") && json.contains("APPLICATION_STOPPED"), "stop cleared recovery");
            ExecutorService clients = Executors.newFixedThreadPool(16);
            try {
                String rev = revision(json);
                CountDownLatch started = new CountDownLatch(16);
                Future<?>[] waits = new Future<?>[16];
                for (int i = 0; i < waits.length; i++) waits[i] = clients.submit(() -> { started.countDown(); return get(server.port(), "/status/wait?since=" + rev); });
                check(started.await(1, TimeUnit.SECONDS), "clients did not start");
                long start = System.nanoTime();
                check(get(server.port(), "/status").contains("schemaVersion"), "ordinary GET starved");
                check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "ordinary GET delayed by waiters");
                int busy = 0, served = 0;
                for (Future<?> wait : waits) {
                    if ("BUSY".equals(wait.get(3, TimeUnit.SECONDS))) busy++; else served++;
                }
                check(busy > 0 && served > 0, "saturation did not exercise both 503 and admitted waiters");
            } finally { clients.shutdownNow(); }
        }
        System.out.println("Health API checks passed (localhost, stale backend, recovery, stop, 16 long-poll clients)");
    }
}
