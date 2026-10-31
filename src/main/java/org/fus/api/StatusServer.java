package org.fus.api;

import org.fus.domain.StationWorkflow;
import org.fus.health.DeviceHealth;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;

/** Read-only LAN status endpoint. No control operations are exposed. */
public final class StatusServer implements StationWorkflow.Listener, AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), new ThreadPoolExecutor.AbortPolicy());
    // Waiters (including their response writes) cannot occupy ordinary GET workers.
    private final ThreadPoolExecutor waiters = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService freshness = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "status-freshness"); t.setDaemon(true); return t;
    });
    private final DeviceHealth health;
    private final String instance = UUID.randomUUID().toString();
    private long revision;
    private boolean closed;
    private String scanner = "DISCONNECTED", reader = "DISCONNECTED";
    private String state = "IDLE", barcode = "", epc = "", error = "";
    private boolean recoveryWarning;
    private boolean safeToTrigger;
    private long safeDeadline;
    private String transactionId = "", safetyReason = "";
    private String healthKey = "";
    private long sampleSequence;

    public StatusServer(String host, int port) throws IOException {
        this(host, port, new DeviceHealth());
    }
    public StatusServer(String host, int port, DeviceHealth health) throws IOException {
        this.health = health;
        server = HttpServer.create(new InetSocketAddress(host, port), 8);
        server.setExecutor(requests);
        server.createContext("/status", this::handle);
    }

    public void start() {
        freshness.scheduleAtFixedRate(this::refreshHealth, 0, 50, TimeUnit.MILLISECONDS);
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    public synchronized void setRecoveryWarning() { recoveryWarning = true; changed(); }

    @Override public synchronized void safety(boolean recovery, boolean safe, String id, String reason) {
        safety(recovery, safe, id, reason, TimeUnit.MILLISECONDS.toNanos(250));
    }
    @Override public synchronized void safety(boolean recovery, boolean safe, String id, String reason, long validForNanos) {
        safeDeadline = System.nanoTime() + Math.min(Math.max(0, validForNanos), TimeUnit.MILLISECONDS.toNanos(250));
        if (recoveryWarning == recovery && safeToTrigger == safe && transactionId.equals(id) && safetyReason.equals(reason)) return;
        recoveryWarning = recovery; safeToTrigger = safe; transactionId = limit(id, 64); safetyReason = limit(reason, 160);
        changed();
    }

    @Override
    public synchronized void status(String next, String scanned, String encoded, String detail) {
        if ("CONNECTION".equals(next)) {
            scanner = detail.contains("scanner=READY") ? "READY" : "DISCONNECTED";
            reader = detail.contains("reader=READY") ? "READY" : "DISCONNECTED";
            if (!"READY".equals(scanner) || !"READY".equals(reader)) {
                // Preserve the in-flight outcome across a disconnect until workflow publishes a result.
                if (!"WRITING".equals(state)) state = "IDLE";
                barcode = ""; epc = ""; error = "";
            }
        } else {
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

    private synchronized void refreshHealth() {
        DeviceHealth.Snapshot h = health.snapshot();
        String key = h.reader.state + h.reader.reason + h.scanner.state + h.scanner.reason + h.scanner.identity
                + h.application.state + h.application.reason + (safeToTrigger && safeDeadline - System.nanoTime() > 0);
        if (!healthKey.equals(key)) { healthKey = key; changed(); }
    }
    private static String device(DeviceHealth.Device d) {
        return "{\"connection\":" + quote(d.connection) + ",\"readiness\":" + quote(d.readiness)
                + ",\"healthState\":" + quote(d.state) + ",\"observedAgeMs\":" + d.observedAgeMs
                + ",\"lastProgressAgeMs\":" + d.progressAgeMs + ",\"reason\":" + quote(d.reason)
                + ",\"evidence\":" + quote(d.evidence) + ",\"safeToOperate\":" + d.safeToOperate
                + ",\"portOpen\":" + d.portOpen + ",\"identity\":" + quote(limit(d.identity, 160)) + "}";
    }

    private synchronized byte[] snapshot() {
        refreshHealth();
        DeviceHealth.Snapshot h = health.snapshot();
        String json = "{\"schemaVersion\":2,\"bootId\":" + quote(instance) + ",\"sampleSequence\":" + (++sampleSequence)
                + ",\"sampleUptimeMs\":" + h.uptimeMs + ",\"freshnessLimitMs\":" + DeviceHealth.STALE_MS
                + ",\"revision\":" + quote(revisionId()) + ",\"state\":" + quote(state) + ",\"scanner\":" + quote(h.scanner.state)
                + ",\"reader\":" + quote(h.reader.state) + ",\"barcode\":" + quote(barcode)
                + ",\"epc\":" + quote(epc) + ",\"error\":" + quote(recoveryWarning
                ? "PREVIOUS_OPERATION_INCOMPLETE tagMayHaveChanged=true" : error)
                + ",\"recoveryRequired\":" + recoveryWarning
                + ",\"workflowSafeToTrigger\":" + (safeToTrigger && safeDeadline - System.nanoTime() > 0 && !recoveryWarning && "WAIT_TRIGGER".equals(state))
                + ",\"safeToTrigger\":" + (safeToTrigger && safeDeadline - System.nanoTime() > 0 && !recoveryWarning && "WAIT_TRIGGER".equals(state)
                    && h.reader.safeToOperate && h.scanner.safeToOperate && h.application.safeToOperate)
                + ",\"transactionId\":" + quote(transactionId) + ",\"safetyReason\":" + quote(safetyReason)
                + ",\"health\":{\"reader\":" + device(h.reader) + ",\"scanner\":" + device(h.scanner)
                + ",\"application\":" + device(h.application) + ",\"lastDecodeAgeMs\":" + h.lastDecodeAgeMs
                + ",\"usbPresence\":\"UNKNOWN\"}}";
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
        if ("/status/wait".equals(exchange.getRequestURI().getPath()) && "GET".equals(exchange.getRequestMethod())) {
            try {
                waiters.execute(() -> {
                    try { respond(exchange, true); } catch (IOException ignored) { exchange.close(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                try { exchange.sendResponseHeaders(503, -1); } finally { exchange.close(); }
            }
            return;
        }
        respond(exchange, false);
    }
    private void respond(HttpExchange exchange, boolean wait) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ((!"/status".equals(path) && !"/status/wait".equals(path)) || !"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (wait) {
                    String query = exchange.getRequestURI().getRawQuery();
                    String since = query != null && query.startsWith("since=") ? query.substring(6) : "";
                    synchronized (this) {
                        refreshHealth();
                        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
                        while (!closed && since.equals(revisionId())) {
                            long left = deadline - System.nanoTime();
                            if (left <= 0) break;
                            try { TimeUnit.NANOSECONDS.timedWait(this, left); }
                            catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                        }
                    }
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
        freshness.shutdownNow();
        waiters.shutdownNow();
        requests.shutdownNow();
    }
}
