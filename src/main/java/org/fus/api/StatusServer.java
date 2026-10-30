package org.fus.api;

import org.fus.domain.StationWorkflow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/** Read-only LAN status endpoint. No control operations are exposed. */
public final class StatusServer implements StationWorkflow.Listener, AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), new ThreadPoolExecutor.CallerRunsPolicy());
    private final Semaphore waitSlots = new Semaphore(4);
    private final String instance = UUID.randomUUID().toString();
    private long revision;
    private boolean closed;
    private String scanner = "DISCONNECTED", reader = "DISCONNECTED";
    private String state = "IDLE", barcode = "", epc = "", error = "";
    private boolean recoveryWarning;

    public StatusServer(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 8);
        server.setExecutor(requests);
        server.createContext("/status", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public synchronized void setRecoveryWarning() { recoveryWarning = true; changed(); }

    @Override
    public synchronized void status(String next, String scanned, String encoded, String detail) {
        System.out.println(next + " barcode=" + scanned + " epc=" + encoded + " " + detail);
        if ("CONNECTION".equals(next)) {
            scanner = detail.contains("scanner=READY") ? "READY" : "DISCONNECTED";
            reader = detail.contains("reader=READY") ? "READY" : "DISCONNECTED";
            if (!"READY".equals(scanner) || !"READY".equals(reader)) {
                state = "IDLE"; barcode = ""; epc = ""; error = "";
            }
        } else {
            if ("WAIT_TRIGGER".equals(next)) recoveryWarning = false;
            state = next;
            barcode = limit(scanned, 64);
            epc = limit(encoded, 124);
            error = "FAIL".equals(next) ? limit(detail, 240) : "";
        }
        changed();
    }
    private void changed() { revision++; notifyAll(); }
    private String revisionId() { return instance + "-" + revision; }
    private static String limit(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private synchronized byte[] snapshot() {
        String json = "{\"revision\":" + quote(revisionId()) + ",\"state\":" + quote(state) + ",\"scanner\":" + quote(scanner)
                + ",\"reader\":" + quote(reader) + ",\"barcode\":" + quote(barcode)
                + ",\"epc\":" + quote(epc) + ",\"error\":" + quote(error.isEmpty() && recoveryWarning
                ? "PREVIOUS_OPERATION_INCOMPLETE tagMayHaveChanged=true" : error) + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ((!"/status".equals(path) && !"/status/wait".equals(path)) || !"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if ("/status/wait".equals(path)) {
                if (!waitSlots.tryAcquire()) { exchange.sendResponseHeaders(503, -1); return; }
                try {
                    String query = exchange.getRequestURI().getRawQuery();
                    String since = query != null && query.startsWith("since=") ? query.substring(6) : "";
                    synchronized (this) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (!closed && since.equals(revisionId())) {
                            long left = deadline - System.nanoTime();
                            if (left <= 0) break;
                            try { TimeUnit.NANOSECONDS.timedWait(this, left); }
                            catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                } finally { waitSlots.release(); }
            }
            byte[] body = snapshot();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally { exchange.close(); }
    }
    @Override public void close() {
        synchronized (this) { closed = true; notifyAll(); }
        server.stop(0);
        requests.shutdownNow();
    }
}
