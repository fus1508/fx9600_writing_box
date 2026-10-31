package org.fus;

import org.fus.api.StatusServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatusServerCheck {
    private static String get(int port) throws Exception {
        return getPath(port, "/status");
    }
    private static String getPath(int port, String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setConnectTimeout(2000); conn.setReadTimeout(4000);
        if (conn.getResponseCode() != 200) throw new AssertionError();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) {
            int ch; while ((ch = in.read()) != -1) bytes.write(ch);
        } finally { conn.disconnect(); }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        org.fus.health.DeviceHealth health = new org.fus.health.DeviceHealth();
        health.readerConnected(); health.readerProgress(); health.applicationProgress();
        health.scannerObservation(true, true, "fake-node", "");
        try (StatusServer server = new StatusServer("127.0.0.1", 0, health)) {
            server.start();
            server.setRecoveryWarning();
            if (!get(server.port()).contains("PREVIOUS_OPERATION_INCOMPLETE")) throw new AssertionError();
            server.status("CONNECTION", null, null, "scanner=READY reader=READY");
            if (!get(server.port()).contains("PREVIOUS_OPERATION_INCOMPLETE")) throw new AssertionError();
            server.status("WAIT_TRIGGER", "9K1135600660004039", "394B1135600660004039", "");
            String json = get(server.port());
            if (!json.contains("\"scanner\":\"CONNECTED\"") || !json.contains("\"reader\":\"READY\"") || !json.contains("\"state\":\"WAIT_TRIGGER\"")) throw new AssertionError(json);
            if (!json.contains("PREVIOUS_OPERATION_INCOMPLETE")) throw new AssertionError("scan cleared recovery: " + json);
            if (!json.contains("\"recoveryRequired\":true") || !json.contains("\"safeToTrigger\":false")) throw new AssertionError(json);
            Matcher revision = Pattern.compile("\\\"revision\\\":\\\"([^\\\"]+)\\\"").matcher(json);
            if (!revision.find()) throw new AssertionError(json);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<String> wait = pool.submit(() -> getPath(server.port(), "/status/wait?since=" + revision.group(1)));
                Thread.sleep(200);
                if (wait.isDone()) throw new AssertionError("long-poll did not wait");
                if (!get(server.port()).contains("WAIT_TRIGGER")) throw new AssertionError("ordinary status request blocked");
                server.status("WRITING", "9K1135600660004039", null, "");
                if (!wait.get(1, TimeUnit.SECONDS).contains("\"state\":\"WRITING\"")) throw new AssertionError("change not delivered");
                long start = System.nanoTime();
                String current = get(server.port());
                Matcher m = Pattern.compile("\\\"revision\\\":\\\"([^\\\"]+)\\\"").matcher(current);
                if (!m.find()) throw new AssertionError();
                getPath(server.port(), "/status/wait?since=" + m.group(1));
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 300) throw new AssertionError("heartbeat returned too early");
            } finally { pool.shutdownNow(); }
            server.status("CONNECTION", null, null, "scanner=DISCONNECTED reader=READY");
            health.scannerObservation(false, false, "", "NODE_MISSING");
            json = get(server.port());
            if (!json.contains("\"scanner\":\"DISCONNECTED\"") || json.contains("9K1135600660004039")) throw new AssertionError(json);
            if (!json.contains("\"recoveryRequired\":true")) throw new AssertionError("disconnect cleared recovery");
            server.safety(false, false, "test-transaction", "ACKNOWLEDGED");
            server.status("FAIL", null, null, "quote=\" newline=\n");
            if (!get(server.port()).contains("quote=\\\" newline=\\u000a")) throw new AssertionError();
            server.status("CONNECTION", null, null, "scanner=READY reader=READY");
            server.status("WAIT_TRIGGER", "9K1135600660004039", null, "");
            server.safety(false, true, "lease-test", "", TimeUnit.SECONDS.toNanos(5));
            if (!get(server.port()).contains("\"workflowSafeToTrigger\":true")) throw new AssertionError("live lease missing");
            Thread.sleep(300);
            if (!get(server.port()).contains("\"workflowSafeToTrigger\":false")) throw new AssertionError("stale safety lease survived stalled workflow");
            server.safety(false, true, "expired-barcode", "", 0);
            if (!get(server.port()).contains("\"workflowSafeToTrigger\":false")) throw new AssertionError("expired barcode safe");
        }
        try (SafetyFixture fixture = new SafetyFixture(); StatusServer server = new StatusServer("127.0.0.1", 0)) {
            org.fus.domain.StationWorkflow workflow = new org.fus.domain.StationWorkflow(fixture.rfid, server,
                    System::nanoTime, TimeUnit.SECONDS.toNanos(2), fixture.journal);
            workflow.connections(true, true); server.start(); workflow.scan(SafetyFixture.BARCODE);
            Thread.sleep(150); workflow.tick(); Thread.sleep(150); workflow.tick();
            if (!get(server.port()).contains("\"workflowSafeToTrigger\":true")) throw new AssertionError("healthy progress did not renew lease");
            Thread.sleep(300);
            if (!get(server.port()).contains("\"workflowSafeToTrigger\":false")) throw new AssertionError("stalled workflow renewed lease");
        }
        System.out.println("Status server checks passed");
    }
}
