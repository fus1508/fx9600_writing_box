package org.fus;

import org.fus.device.scanner.ScannerSource;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real ScannerSource transport loop with injected node identity and blocking fake CDC. */
public final class ScannerHealthCheck {
    private static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    private static void await(java.util.function.BooleanSupplier condition, String m) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(condition.getAsBoolean(), m);
    }
    private static final class Stream extends InputStream {
        final BlockingQueue<Integer> bytes = new LinkedBlockingQueue<Integer>();
        volatile boolean blockClose;
        final CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();
        public int read() throws IOException {
            try { int b = bytes.take(); if (b == -2) throw new IOException("fake disconnect"); return b; }
            catch (InterruptedException e) { throw new IOException(e); }
        }
        void send(String value) { for (byte b : value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) bytes.add((int) b); }
        public void close() {
            closeCalls.incrementAndGet();
            if (blockClose) {
                closeEntered.countDown();
                try { releaseClose.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            bytes.add(-1);
        }
    }
    public static void main(String[] args) throws Exception {
        AtomicReference<ScannerSource.Node> node = new AtomicReference<ScannerSource.Node>(new ScannerSource.Node(true, "A"));
        BlockingQueue<Stream> streams = new LinkedBlockingQueue<Stream>();
        Stream first = new Stream(), second = new Stream(); streams.add(first); streams.add(second);
        AtomicInteger opens = new AtomicInteger(); AtomicBoolean connected = new AtomicBoolean();
        List<String> scans = new CopyOnWriteArrayList<String>();
        ScannerSource source = new ScannerSource(new ScannerSource.Access() {
            public ScannerSource.Node inspect() { return node.get(); }
            public InputStream open() throws Exception { opens.incrementAndGet(); return streams.take(); }
        }, 10, new ScannerSource.Listener() {
            public void state(boolean ready) { connected.set(ready); }
            public void scan(String value) { scans.add(value); }
        });
        Thread reader = new Thread(source::run); reader.setDaemon(true); reader.start();
        try {
            await(connected::get, "port did not open");
            first.send("OLD_PARTIAL"); Thread.sleep(30);
            node.set(new ScannerSource.Node(false, "")); source.checkPresence();
            check(!connected.get(), "unplug did not invalidate connection");
            node.set(new ScannerSource.Node(true, "B"));
            await(() -> opens.get() == 2 && connected.get(), "replug did not reopen");
            second.send("NEW\n"); await(() -> scans.size() == 1, "new frame missing");
            check(scans.get(0).equals("NEW"), "partial barcode reused after unplug");
            second.blockClose = true;
            node.set(new ScannerSource.Node(true, "C"));
            long at = System.nanoTime(); source.checkPresence();
            check(System.nanoTime() - at < TimeUnit.MILLISECONDS.toNanos(200), "monitor blocked closing CDC");
            check(!connected.get(), "same path new identity did not disconnect");
            check(second.closeEntered.await(1, TimeUnit.SECONDS), "fake close did not actually block");
            second.send("STALE\n"); Thread.sleep(30);
            check(scans.size() == 1, "old file descriptor emitted a barcode after invalidation");
            check(opens.get() == 2, "reopened CDC before the previous close completed");
        } finally { source.close(); second.releaseClose.countDown(); reader.interrupt(); reader.join(2000); }
        await(() -> second.closeCalls.get() >= 1, "close not attempted");
        Thread.sleep(30);
        check(first.closeCalls.get() == 1 && second.closeCalls.get() == 1, "session close was not at-most-once");
        java.nio.file.Path ordinary = java.nio.file.Files.createTempFile("fx-not-cdc-", ".txt");
        java.nio.file.Files.write(ordinary, "SHOULD_NOT_SCAN\n".getBytes("US-ASCII"));
        CountDownLatch rejected = new CountDownLatch(1);
        ScannerSource invalid = new ScannerSource(ordinary.toString(), 10, new ScannerSource.Listener() {
            public void state(boolean value) { check(!value, "regular file reported connected"); rejected.countDown(); }
            public void scan(String value) { throw new AssertionError("regular file consumed as scanner"); }
        });
        Thread invalidThread = new Thread(invalid::run); invalidThread.setDaemon(true); invalidThread.start();
        check(rejected.await(1, TimeUnit.SECONDS), "invalid CDC path not rejected");
        invalid.close(); invalidThread.interrupt(); invalidThread.join(1000);
        AtomicInteger failedOpens = new AtomicInteger(), failedCloses = new AtomicInteger();
        AtomicReference<String> reason = new AtomicReference<String>("");
        ScannerSource failed = new ScannerSource(new ScannerSource.Access() {
            public ScannerSource.Node inspect() { return new ScannerSource.Node(true, "FAILED_CLOSE_NODE"); }
            public InputStream open() {
                failedOpens.incrementAndGet();
                return new InputStream() {
                    public int read() throws IOException { throw new IOException("fake read failure"); }
                    public void close() throws IOException { failedCloses.incrementAndGet(); throw new IOException("fake close failure"); }
                };
            }
        }, 10, new ScannerSource.Listener() {
            public void state(boolean value) { }
            public void scan(String value) { throw new AssertionError("phantom scan"); }
            public void health(boolean present, boolean open, String identity, String detail) { reason.set(detail); }
        });
        Thread failedThread = new Thread(failed::run); failedThread.setDaemon(true); failedThread.start();
        try {
            await(() -> failedCloses.get() == 1, "fake read/close failure not exercised");
            Thread.sleep(30); failed.checkPresence();
            check(failedOpens.get() == 1 && failedCloses.get() == 1, "failed cleanup retried/opened another session");
            check(reason.get().equals("CDC_CLEANUP_FAILED"), "failed cleanup reason missing");
        } finally { failed.close(); failedThread.interrupt(); failedThread.join(1000); }
        CountDownLatch afterOpenCaptured = new CountDownLatch(1), releaseAfterOpen = new CountDownLatch(1), attemptFinished = new CountDownLatch(1);
        AtomicInteger inspections = new AtomicInteger(), raceScans = new AtomicInteger();
        AtomicReference<ScannerSource.Node> raceNode = new AtomicReference<ScannerSource.Node>(new ScannerSource.Node(true, "RACE_NODE"));
        ScannerSource raced = new ScannerSource(new ScannerSource.Access() {
            public ScannerSource.Node inspect() throws Exception {
                ScannerSource.Node captured = raceNode.get();
                if (inspections.incrementAndGet() == 2) {
                    afterOpenCaptured.countDown(); releaseAfterOpen.await();
                }
                return captured;
            }
            public InputStream open() {
                // ByteArrayInputStream.close does not discard buffered bytes: this exposes invalid-session reads.
                return new ByteArrayInputStream("FORBIDDEN\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
        }, 10, new ScannerSource.Listener() {
            public void state(boolean value) { }
            public void scan(String value) { raceScans.incrementAndGet(); }
            public void health(boolean present, boolean open, String identity, String detail) {
                if (detail.equals("CDC_READ_OR_OPEN_FAILED")) attemptFinished.countDown();
            }
        });
        Thread racedThread = new Thread(raced::run); racedThread.setDaemon(true); racedThread.start();
        try {
            check(afterOpenCaptured.await(1, TimeUnit.SECONDS), "post-open identity barrier not reached");
            raceNode.set(new ScannerSource.Node(false, "")); raced.checkPresence();
            releaseAfterOpen.countDown();
            check(attemptFinished.await(1, TimeUnit.SECONDS), "invalidated open attempt did not finish");
            check(raceScans.get() == 0, "monitor-invalidated session was reactivated and emitted a barcode");
        } finally { raced.close(); releaseAfterOpen.countDown(); racedThread.interrupt(); racedThread.join(1000); }
        System.out.println("Scanner health checks passed (unplug/replug, identity, partial/fd discard, cleanup gate/once/failure, activation race)");
    }
}
