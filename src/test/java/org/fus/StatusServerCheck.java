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
        try (StatusServer server = new StatusServer("127.0.0.1", 0)) {
            server.start();
            server.setRecoveryWarning();
            if (!get(server.port()).contains("PREVIOUS_OPERATION_INCOMPLETE")) throw new AssertionError();
            server.status("CONNECTION", null, null, "scanner=READY reader=READY");
            if (!get(server.port()).contains("PREVIOUS_OPERATION_INCOMPLETE")) throw new AssertionError();
            server.status("WAIT_TRIGGER", "9K1135600660004039", "394B1135600660004039", "");
            String json = get(server.port());
            if (!json.contains("\"scanner\":\"READY\"") || !json.contains("\"reader\":\"READY\"") || !json.contains("\"state\":\"WAIT_TRIGGER\"")) throw new AssertionError(json);
            if (json.contains("PREVIOUS_OPERATION_INCOMPLETE")) throw new AssertionError(json);
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
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500) throw new AssertionError("heartbeat returned too early");
            } finally { pool.shutdownNow(); }
            server.status("CONNECTION", null, null, "scanner=DISCONNECTED reader=READY");
            json = get(server.port());
            if (!json.contains("\"scanner\":\"DISCONNECTED\"") || json.contains("9K1135600660004039")) throw new AssertionError(json);
            server.status("FAIL", null, null, "quote=\" newline=\n");
            if (!get(server.port()).contains("quote=\\\" newline=\\u000a")) throw new AssertionError();
        }
        System.out.println("Status server checks passed");
    }
}
