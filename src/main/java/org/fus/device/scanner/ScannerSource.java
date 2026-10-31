package org.fus.device.scanner;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** CDC input is configured externally in raw mode. */
public final class ScannerSource implements AutoCloseable {
    public interface Listener {
        void state(boolean ready);
        void scan(String value);
        default void health(boolean nodePresent, boolean portOpen, String identity, String reason) { }
    }
    public interface Access { Node inspect() throws Exception; InputStream open() throws Exception; }
    /** OS node identity, not an attestation of USB model/serial or decode engine health. */
    public static final class Node {
        public final boolean present;
        public final String identity;
        public Node(boolean present, String identity) { this.present = present; this.identity = identity; }
    }
    private static final class Session {
        final InputStream stream; final String identity;
        volatile boolean valid, cleanupComplete, cleanupFailed;
        boolean invalidated; // Guarded by stateLock, including activation after inspect/open.
        final AtomicBoolean closeRequested = new AtomicBoolean();
        Session(InputStream stream, String identity) { this.stream = stream; this.identity = identity; }
    }
    private final Access access;
    private final int reconnectMs;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile Session active;
    private final Object stateLock = new Object();
    private Boolean connected;
    private final ThreadPoolExecutor closer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(2), r -> { Thread t = new Thread(r, "scanner-close"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    public ScannerSource(String device, int reconnectMs, Listener listener) {
        this(new Access() {
            private final Path path = Paths.get(device);
            public Node inspect() throws Exception {
                try {
                    BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
                    if (!a.isOther()) throw new IOException("SCANNER_NOT_CHARACTER_DEVICE");
                    // On the target require a character device; never treat a regular file/FIFO as CDC.
                    int mode = ((Number) Files.getAttribute(path, "unix:mode")).intValue();
                    if ((mode & 0170000) != 0020000) throw new IOException("SCANNER_NOT_CHARACTER_DEVICE");
                    if (a.fileKey() == null) throw new IOException("SCANNER_NODE_IDENTITY_UNAVAILABLE");
                    return new Node(true, path.toRealPath() + ":" + a.fileKey());
                } catch (NoSuchFileException missing) { return new Node(false, ""); }
            }
            public InputStream open() throws Exception { return new FileInputStream(device); }
        }, reconnectMs, listener);
    }
    public ScannerSource(Access access, int reconnectMs, Listener listener) {
        this.access = access; this.reconnectMs = reconnectMs; this.listener = listener;
    }
    private void publish(Node node, boolean open, String reason) {
        listener.health(node.present, open, node.identity, reason);
        if (connected == null || connected != open) { connected = open; listener.state(open); }
    }
    private void closeLater(Session session) {
        if (session == null || !session.closeRequested.compareAndSet(false, true)) return;
        try {
            closer.execute(() -> {
                try { session.stream.close(); }
                catch (Exception failure) { session.cleanupFailed = true; }
                finally { session.cleanupComplete = true; }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            session.cleanupFailed = true; session.cleanupComplete = true;
        }
    }
    private void invalidate(Session session, Node node, String reason) {
        synchronized (stateLock) {
            if (session != null) { session.invalidated = true; session.valid = false; }
            publish(node, false, reason);
        }
        closeLater(session);
    }
    public void run() {
        try { readLoop(); } finally { closer.shutdown(); }
    }
    private void readLoop() {
        while (running) {
            Session session = null;
            try {
                Node before = access.inspect();
                if (!before.present) throw new IOException("NODE_MISSING");
                synchronized (stateLock) { publish(before, false, "REOPENING"); }
                session = new Session(access.open(), before.identity);
                synchronized (stateLock) { active = session; }
                Node after = access.inspect();
                if (!after.present || !before.identity.equals(after.identity)) throw new IOException("NODE_CHANGED_DURING_OPEN");
                synchronized (stateLock) {
                    if (!running || active != session || session.invalidated)
                        throw new IOException("SESSION_INVALIDATED_DURING_OPEN");
                    session.valid = true; publish(after, true, "CDC_LIVENESS_UNVERIFIED");
                }
                BarcodeFramer frame = new BarcodeFramer(1024, TimeUnit.SECONDS.toNanos(2));
                int b;
                while (running && session.valid && (b = session.stream.read()) != -1) {
                    if (!session.valid || !running) break;
                    String scan = frame.accept(b, System.nanoTime());
                    if (scan != null) synchronized (stateLock) { if (session.valid && running) listener.scan(scan); }
                }
            } catch (Exception e) { /* Transport failure is reported below, without blocking stdio. */ }
            finally {
                invalidate(session, new Node(false, ""), running ? "CDC_READ_OR_OPEN_FAILED" : "SCANNER_STOPPED");
            }
            // A returned read does not prove the previous fd was closed. Never accumulate sessions.
            while (running && session != null && (!session.cleanupComplete || session.cleanupFailed)) {
                try { Thread.sleep(Math.min(250, reconnectMs)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            synchronized (stateLock) { if (active == session) active = null; }
            if (running) try { Thread.sleep(reconnectMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    public void checkPresence() {
        Session session = active;
        try {
            Node node = access.inspect();
            synchronized (stateLock) {
                if (session != active || !running) return;
                if (session != null && session.cleanupFailed) publish(node, false, "CDC_CLEANUP_FAILED");
                else if (session != null && (!node.present || !session.identity.equals(node.identity)))
                    invalidate(session, node, node.present ? "NODE_IDENTITY_CHANGED" : "NODE_MISSING");
                else publish(node, session != null && session.valid,
                        session != null && session.cleanupFailed ? "CDC_CLEANUP_FAILED" :
                        session != null && session.closeRequested.get() ? "CDC_CLEANUP_PENDING" : node.present ? "REOPENING" : "NODE_MISSING");
            }
        } catch (Exception failure) { invalidate(session, new Node(false, ""), "NODE_INSPECTION_FAILED"); }
    }
    public void close() {
        running = false;
        invalidate(active, new Node(false, ""), "SCANNER_STOPPED");
    }
}
