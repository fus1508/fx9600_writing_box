package org.fus.domain;

import java.util.function.LongSupplier;

/** Thread-safe station rules. The RFID gateway selects a target under the single-physical-tag operating condition. */
public final class StationWorkflow {
    public interface Gateway {
        /** Select one observed EPC or throw; the enclosure must contain one physical tag. */
        Target selectUnique() throws Exception;
        void write(Target target, String epc) throws Exception;
        /** Read after changing EPC; the single-tag enclosure must remain unchanged. */
        String read(Target target) throws Exception;
    }
    public interface Target { }
    public interface Listener { void status(String state, String barcode, String epc, String detail); }
    private final Gateway gateway;
    private final Listener listener;
    private final LongSupplier clock;
    private final long validityNanos;
    private String barcode;
    private long scannedAt;
    private boolean writing;
    private boolean scannerReady;
    private boolean readerReady;
    private long generation;
    private long resultAt;
    private boolean resultVisible;

    public StationWorkflow(Gateway gateway, Listener listener, LongSupplier clock, long validityNanos) {
        if (gateway == null || listener == null || clock == null || validityNanos <= 0) throw new IllegalArgumentException();
        this.gateway = gateway;
        this.listener = listener;
        this.clock = clock;
        this.validityNanos = validityNanos;
    }
    public synchronized void connections(boolean scanner, boolean reader) {
        if (scannerReady == scanner && readerReady == reader) return;
        if (!scanner || !reader) { barcode = null; generation++; resultVisible = false; }
        scannerReady = scanner; readerReady = reader;
        listener.status("CONNECTION", null, null, "scanner=" + (scanner ? "READY" : "DISCONNECTED") + " reader=" + (reader ? "READY" : "DISCONNECTED"));
    }
    public synchronized void tick() {
        long now = clock.getAsLong();
        if (barcode != null && now - scannedAt >= validityNanos) {
            barcode = null;
            listener.status("IDLE", null, null, "BARCODE_EXPIRED");
        } else if (resultVisible && now - resultAt >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
            resultVisible = false;
            listener.status("IDLE", null, null, "");
        }
    }
    public synchronized boolean scan(String value) {
        if (writing || !scannerReady || !readerReady) return false;
        resultVisible = false;
        try { EpcCodec.encode(value); }
        catch (IllegalArgumentException e) {
            barcode = null;
            listener.status("FAIL", value, null, "category=BUSINESS code=INVALID_BARCODE stage=SCAN tagMayHaveChanged=false");
            resultAt = clock.getAsLong(); resultVisible = true;
            return false;
        }
        barcode = value;
        scannedAt = clock.getAsLong();
        listener.status("WAIT_TRIGGER", value, EpcCodec.encode(value), "");
        return true;
    }
    /** Called once per accepted button press. Consumes the scan even when an operation fails. */
    public void trigger() {
        Runnable work = beginTrigger();
        if (work != null) work.run();
    }
    public synchronized void abortReserved(String reason) {
        if (!writing) return;
        writing = false;
        listener.status("FAIL", null, null, "category=TECHNICAL code=" + reason + " stage=PRECHECK tagMayHaveChanged=false");
        resultAt = clock.getAsLong();
        resultVisible = true;
    }
    public Runnable beginTrigger() {
        final String selected;
        final long selectedGeneration;
        synchronized (this) {
            if (writing || !scannerReady || !readerReady) return null;
            if (barcode == null || clock.getAsLong() - scannedAt >= validityNanos) {
                barcode = null;
                listener.status("FAIL", null, null, "category=BUSINESS code=NO_VALID_BARCODE stage=TRIGGER tagMayHaveChanged=false");
                resultAt = clock.getAsLong(); resultVisible = true;
                return null;
            }
            selected = barcode;
            selectedGeneration = generation;
            barcode = null;
            writing = true;
            listener.status("WRITING", selected, null, "");
        }
        return () -> perform(selected, selectedGeneration);
    }
    private void perform(String selected, long selectedGeneration) {
        String epc = EpcCodec.encode(selected);
        boolean writeStarted = false;
        try {
            Target target = gateway.selectUnique();
            if (target == null) throw new IllegalStateException("NO_UNIQUE_TAG");
            synchronized (this) {
                if (!scannerReady || !readerReady || generation != selectedGeneration)
                    throw new StationFailure("TECHNICAL", "CONNECTION_LOST", "PRECHECK", false, "Device disconnected", null);
                writeStarted = true;
            }
            gateway.write(target, epc);
            String readBack = gateway.read(target);
            if (readBack == null || !epc.equalsIgnoreCase(readBack) || !selected.equals(EpcCodec.decode(readBack))) {
                throw new StationFailure("BUSINESS", "VERIFY_MISMATCH", "VERIFY_EPC", true, "Read-back differs from requested EPC", null);
            }
            synchronized (this) {
                if (!scannerReady || !readerReady || generation != selectedGeneration)
                    throw new StationFailure("TECHNICAL", "CONNECTION_LOST", "VERIFY_EPC", true, "Device disconnected during operation", null);
                listener.status("PASS", selected, epc, "");
                resultAt = clock.getAsLong(); resultVisible = true;
            }
        } catch (StationFailure ex) {
            synchronized (this) { listener.status("FAIL", selected, epc, ex.describe()); resultAt = clock.getAsLong(); resultVisible = true; }
        } catch (Exception ex) {
            synchronized (this) { listener.status("FAIL", selected, epc, "category=TECHNICAL code=UNEXPECTED_FAILURE stage=WORKFLOW tagMayHaveChanged=" + writeStarted + " detail=" + ex.getClass().getSimpleName()); resultAt = clock.getAsLong(); resultVisible = true; }
        } finally {
            synchronized (this) { writing = false; }
        }
    }
}
