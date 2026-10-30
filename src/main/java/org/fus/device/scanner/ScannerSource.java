package org.fus.device.scanner;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** CDC input is configured externally in raw mode. */
public final class ScannerSource implements AutoCloseable {
    public interface Listener { void state(boolean ready); void scan(String value); }
    private final String device;
    private final int reconnectMs;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile InputStream input;
    public ScannerSource(String device, int reconnectMs, Listener listener) {
        this.device = device; this.reconnectMs = reconnectMs; this.listener = listener;
    }
    public void run() {
        while (running) {
            try (InputStream stream = new FileInputStream(device)) {
                input = stream; listener.state(true);
                BarcodeFramer frame = new BarcodeFramer(1024, TimeUnit.SECONDS.toNanos(2));
                int b;
                while (running && (b = stream.read()) != -1) {
                    String scan = frame.accept(b, System.nanoTime());
                    if (scan != null) listener.scan(scan);
                }
            } catch (Exception e) { if (running) System.err.println("SCANNER_ERROR type=" + e.getClass().getSimpleName()); }
            finally { input = null; listener.state(false); }
            if (running) try { Thread.sleep(reconnectMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    public void checkPresence() {
        if (!Files.exists(Paths.get(device))) {
            listener.state(false);
            InputStream current = input;
            if (current != null) try { current.close(); } catch (Exception ignored) { }
        }
    }
    public void close() { running = false; InputStream current = input; if (current != null) try { current.close(); } catch (Exception ignored) { } }
}
