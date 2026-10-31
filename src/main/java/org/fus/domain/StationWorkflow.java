package org.fus.domain;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One reservation, one physical identity, durable intent, verified durable result. */
public final class StationWorkflow {
    public interface Gateway {
        /** True only for an adapter whose discovery AND same-identity access have been validated. */
        default boolean supportsVerifiedIdentityAccess() { return false; }
        Target selectUnique() throws Exception;
        void write(Target target, String epc) throws Exception;
        String read(Target target) throws Exception;
    }
    public interface Target {
        default String oldEpc() { return null; }
        default String serializedTid() { return null; }
        default boolean identityVerified() { return false; }
    }
    public interface Listener {
        void status(String state, String barcode, String epc, String detail);
        default void safety(boolean recoveryRequired, boolean safeToTrigger, String transactionId, String reason) { }
        default void safety(boolean recoveryRequired, boolean safeToTrigger, String transactionId, String reason, long validForNanos) {
            safety(recoveryRequired, safeToTrigger, transactionId, reason);
        }
    }
    private final Gateway gateway;
    private final Listener listener;
    private final LongSupplier clock;
    private final long validityNanos;
    private final TransactionJournal journal;
    private String barcode;
    private long scannedAt, generation, resultAt;
    private boolean writing, scannerReady, readerReady, resultVisible, stopped;
    private boolean latchedRecovery;
    private String activeTransactionId = "";

    public StationWorkflow(Gateway gateway, Listener listener, LongSupplier clock, long validityNanos, TransactionJournal journal) {
        if (gateway == null || listener == null || clock == null || journal == null || validityNanos <= 0) throw new IllegalArgumentException();
        this.gateway = gateway; this.listener = listener; this.clock = clock; this.validityNanos = validityNanos; this.journal = journal;
        publishSafety();
    }
    public synchronized boolean recoveryRequired() { return latchedRecovery || !journal.isHealthy() || (!writing && journal.recoveryRequired()); }
    public synchronized boolean safeToTrigger() {
        return !stopped && !writing && !recoveryRequired() && scannerReady && readerReady
                && gateway.supportsVerifiedIdentityAccess() && barcode != null && clock.getAsLong() - scannedAt < validityNanos;
    }
    private void publishSafety() {
        TransactionJournal.Record record = journal.latest();
        boolean safe = safeToTrigger();
        listener.safety(recoveryRequired(), safe, writing ? activeTransactionId : record == null ? "" : record.transactionId,
                !journal.isHealthy() ? journal.problem() : recoveryRequired() ? "RECOVERY_REQUIRED"
                : !gateway.supportsVerifiedIdentityAccess() ? "HARDWARE_VERIFICATION_REQUIRED" : "",
                safe ? Math.max(0, validityNanos - (clock.getAsLong() - scannedAt)) : 0);
    }
    public synchronized void connections(boolean scanner, boolean reader) {
        if (scannerReady == scanner && readerReady == reader) return;
        if (!scanner || !reader) { barcode = null; generation++; resultVisible = false; }
        scannerReady = scanner; readerReady = reader;
        publishSafety();
        listener.status("CONNECTION", null, null, "scanner=" + (scanner ? "READY" : "DISCONNECTED") + " reader=" + (reader ? "READY" : "DISCONNECTED"));
    }
    public synchronized void shutdown() { stopped = true; generation++; barcode = null; publishSafety(); }
    public synchronized void tick() {
        if (writing || recoveryRequired() || stopped) { publishSafety(); return; }
        long now = clock.getAsLong();
        if (barcode != null && now - scannedAt >= validityNanos) {
            barcode = null; publishSafety(); listener.status("IDLE", null, null, "BARCODE_EXPIRED");
        } else if (resultVisible && now - resultAt >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
            resultVisible = false; listener.status("IDLE", null, null, "");
        }
        publishSafety();
    }
    public synchronized boolean scan(String value) {
        if (writing || stopped || recoveryRequired() || !scannerReady || !readerReady) { publishSafety(); return false; }
        resultVisible = false;
        try { EpcCodec.encode(value); }
        catch (IllegalArgumentException e) {
            barcode = null; publishSafety(); fail(null, null, "INVALID_BARCODE", "SCAN", false); return false;
        }
        barcode = value; scannedAt = clock.getAsLong(); publishSafety();
        listener.status("WAIT_TRIGGER", value, EpcCodec.encode(value), "");
        return true;
    }
    public void trigger() { Runnable work = beginTrigger(); if (work != null) work.run(); }
    public synchronized void abortReserved(String reason) {
        if (!writing) return;
        try { journal.block(reason); } catch (IOException e) { /* Journal is poisoned and remains gated. */ }
        generation++; writing = false; latchedRecovery = true; publishSafety(); fail(null, null, reason, "PRECHECK", false);
    }
    public synchronized Runnable beginTrigger() {
        if (writing || stopped || recoveryRequired() || !scannerReady || !readerReady) { publishSafety(); return null; }
        if (barcode == null || clock.getAsLong() - scannedAt >= validityNanos) {
            barcode = null; publishSafety(); fail(null, null, "NO_VALID_BARCODE", "TRIGGER", false); return null;
        }
        final String selected = barcode;
        final long selectedGeneration = generation;
        barcode = null; writing = true; activeTransactionId = ""; publishSafety(); listener.status("WRITING", selected, null, "");
        final AtomicBoolean claimed = new AtomicBoolean();
        return () -> { if (claimed.compareAndSet(false, true)) perform(selected, selectedGeneration); };
    }
    private void checkConnections(long selectedGeneration, boolean changed) throws StationFailure {
        if (stopped || !scannerReady || !readerReady || generation != selectedGeneration)
            throw new StationFailure("TECHNICAL", "CONNECTION_LOST", "WORKFLOW", changed, "Device disconnected or station stopped", null);
    }
    private void perform(String selected, long selectedGeneration) {
        String epc = EpcCodec.encode(selected), id = null;
        boolean writeStarted = false;
        try {
            synchronized (this) { checkConnections(selectedGeneration, false); }
            id = journal.begin(selected).transactionId;
            synchronized (this) { activeTransactionId = id; publishSafety(); }
            if (!gateway.supportsVerifiedIdentityAccess()) throw PhysicalTagIdentity.blocked();
            Target target = gateway.selectUnique();
            if (target == null || !target.identityVerified() || target.oldEpc() == null
                    || target.serializedTid() == null || !target.serializedTid().matches("[0-9A-Fa-f]{4,512}")
                    || target.serializedTid().length() % 4 != 0) throw PhysicalTagIdentity.blocked();
            if (!target.oldEpc().matches("[0-9A-Fa-f]{20}"))
                throw new StationFailure("BUSINESS", "EPC_RESIZE_UNVERIFIED", "PRECHECK", false, "Only existing EPC80 tags are supported", null);
            journal.selected(id, target.oldEpc(), target.serializedTid());
            synchronized (this) { checkConnections(selectedGeneration, false); }
            journal.intent(id);
            writeStarted = true;
            synchronized (this) { checkConnections(selectedGeneration, true); }
            gateway.write(target, epc);
            journal.written(id);
            String readBack = gateway.read(target);
            if (readBack == null || !epc.equalsIgnoreCase(readBack) || !selected.equals(EpcCodec.decode(readBack)))
                throw new StationFailure("BUSINESS", "VERIFY_MISMATCH", "VERIFY_EPC", true, "Read-back differs", null);
            synchronized (this) {
                checkConnections(selectedGeneration, true);
                journal.verified(id);
                publishSafety(); listener.status("PASS", selected, epc, ""); resultAt = clock.getAsLong(); resultVisible = true;
            }
        } catch (Exception ex) {
            boolean changed = writeStarted || (ex instanceof StationFailure && ((StationFailure) ex).tagMayHaveChanged);
            String code = ex instanceof StationFailure ? ((StationFailure) ex).code
                    : !journal.isHealthy() ? "JOURNAL_FAILURE" : "UNEXPECTED_FAILURE";
            if (id != null && journal.isHealthy()) {
                try { journal.failed(id, code, changed); }
                catch (IOException e) { synchronized (this) { latchedRecovery = true; } }
            }
            synchronized (this) {
                if (!journal.isHealthy() || changed) latchedRecovery = true;
                publishSafety(); fail(selected, epc, code, ex instanceof StationFailure ? ((StationFailure) ex).stage : "WORKFLOW", changed);
            }
        } finally {
            synchronized (this) { writing = false; publishSafety(); }
        }
    }
    private void fail(String selected, String epc, String code, String stage, boolean changed) {
        listener.status("FAIL", selected, epc, "category=TECHNICAL code=" + code + " stage=" + stage + " tagMayHaveChanged=" + changed);
        resultAt = clock.getAsLong(); resultVisible = true;
    }
}
