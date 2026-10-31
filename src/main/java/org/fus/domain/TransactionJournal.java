package org.fus.domain;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Append-only transaction evidence. An unreadable or incomplete log is never repaired automatically. */
public final class TransactionJournal implements AutoCloseable {
    public enum Stage { PREPARED, SELECTED, WRITE_INTENT, WRITE_RETURNED, VERIFIED, FAILED_SAFE, RECOVERY_REQUIRED, ACKNOWLEDGED, LEGACY_RECOVERY }

    public static final class Record {
        public final long sequence, timestamp;
        public final String transactionId, bootId, barcode, oldEpc, expectedEpc, tid;
        public final Stage stage;
        public final String writeOutcome, verifyOutcome, errorCode, acknowledgement, legacyFingerprint;
        public final boolean tagMayHaveChanged;

        private Record(long sequence, long timestamp, String id, String boot, String barcode,
                String oldEpc, String expectedEpc, String tid, Stage stage, String write, String verify,
                String error, boolean changed, String ack, String legacy) {
            this.sequence = sequence; this.timestamp = timestamp; transactionId = id; bootId = boot;
            this.barcode = barcode; this.oldEpc = oldEpc; this.expectedEpc = expectedEpc; this.tid = tid;
            this.stage = stage; writeOutcome = write; verifyOutcome = verify; errorCode = error;
            tagMayHaveChanged = changed; acknowledgement = ack; legacyFingerprint = legacy;
        }
        public boolean resolved() { return stage == Stage.VERIFIED || stage == Stage.FAILED_SAFE || stage == Stage.ACKNOWLEDGED; }
    }

    /** Fault seam for local disk-full/short-write/fsync tests; never configurable at runtime. */
    public interface Appender { void append(FileChannel channel, byte[] frame, Stage stage) throws IOException; }
    public static final Appender SYNC_APPEND = (channel, frame, stage) -> {
        ByteBuffer bytes = ByteBuffer.wrap(frame);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    };
    private static final byte[] HEADER = new byte[] { 'F', 'X', 'J', 'N', 'L', '0', '1', '\n' };
    private static final int MAX_RECORD = 16384;
    private final String bootId = UUID.randomUUID().toString();
    private final FileChannel channel;
    private final FileLock lock;
    private final Path legacyMarker;
    private final Path faultPath, headPath, pendingHead;
    private final Appender appender;
    private Record last;
    private byte[] previousHash = new byte[32];
    private boolean healthy = true, closed;
    private String problem = "";
    private String acknowledgedLegacy = "";

    public TransactionJournal(Path path, Path legacyMarker) throws IOException { this(path, legacyMarker, SYNC_APPEND); }
    public TransactionJournal(Path path, Path legacyMarker, Appender appender) throws IOException {
        this.legacyMarker = legacyMarker;
        this.appender = Objects.requireNonNull(appender);
        Path absolute = path.toAbsolutePath().normalize();
        faultPath = absolute.resolveSibling(absolute.getFileName().toString() + ".fault");
        headPath = absolute.resolveSibling(absolute.getFileName().toString() + ".head");
        pendingHead = absolute.resolveSibling(absolute.getFileName().toString() + ".head.pending");
        if (legacyMarker != null && absolute.equals(legacyMarker.toAbsolutePath().normalize()))
            throw new IOException("JOURNAL_AND_MARKER_PATH_COLLISION");
        boolean existed;
        try { Files.readAttributes(absolute, BasicFileAttributes.class); existed = true; }
        catch (NoSuchFileException e) { existed = false; }
        if (!existed && (!Files.notExists(headPath) || !Files.notExists(pendingHead) || !Files.notExists(faultPath)))
            throw new IOException("JOURNAL_MISSING_WITH_EXISTING_EVIDENCE");
        channel = FileChannel.open(absolute, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("JOURNAL_IN_USE");
        } catch (Exception e) { channel.close(); throw new IOException("JOURNAL_IN_USE", e); }
        lock = acquired;
        try {
            if (!existed && channel.size() == 0) {
                ByteBuffer header = ByteBuffer.wrap(HEADER);
                while (header.hasRemaining()) channel.write(header);
                channel.force(true);
                syncDirectory(absolute.getParent());
                checkpoint(0, previousHash);
            }
            load();
            validateCheckpoint();
            try {
                Files.readAttributes(faultPath, BasicFileAttributes.class);
                poison("JOURNAL_IO_FAILURE_REQUIRES_OFFLINE_REPAIR");
                return;
            } catch (NoSuchFileException absent) { /* No historical disk failure latch. */ }
            importLegacy();
        } catch (Exception e) { poison("JOURNAL_UNREADABLE_OR_CORRUPT"); }
    }

    public synchronized boolean isHealthy() { return healthy && !closed; }
    public synchronized boolean recoveryRequired() { return !isHealthy() || (last != null && !last.resolved()); }
    public synchronized String problem() { return problem; }
    public synchronized Record latest() { return last; }
    private void poison(String code) { healthy = false; problem = code; }
    private void requireHealthy() throws IOException { if (!isHealthy()) throw new IOException("JOURNAL_UNAVAILABLE"); }

    public synchronized Record begin(String barcode) throws IOException {
        requireHealthy();
        if (recoveryRequired()) throw new IOException("RECOVERY_REQUIRED");
        String expected = EpcCodec.encode(barcode);
        return append(new Record(next(), System.currentTimeMillis(), UUID.randomUUID().toString(), bootId,
                barcode, "", expected, "", Stage.PREPARED, "NOT_STARTED", "NOT_STARTED", "", false, "", acknowledgedLegacy));
    }
    public synchronized void selected(String id, String oldEpc, String tid) throws IOException {
        requireStage(id, Stage.PREPARED);
        append(copy(Stage.SELECTED, oldEpc, tid, "NOT_STARTED", "NOT_STARTED", "", false, ""));
    }
    public synchronized void intent(String id) throws IOException {
        requireStage(id, Stage.SELECTED);
        append(copy(Stage.WRITE_INTENT, last.oldEpc, last.tid, "UNKNOWN", "NOT_STARTED", "", true, ""));
    }
    public synchronized void verified(String id) throws IOException {
        requireStage(id, Stage.WRITE_RETURNED);
        append(copy(Stage.VERIFIED, last.oldEpc, last.tid, "COMPLETED", "MATCH", "", false, ""));
    }
    public synchronized void written(String id) throws IOException {
        requireStage(id, Stage.WRITE_INTENT);
        append(copy(Stage.WRITE_RETURNED, last.oldEpc, last.tid, "COMPLETED", "NOT_STARTED", "", true, ""));
    }
    public synchronized void block(String code) throws IOException {
        requireHealthy();
        if (last != null && !last.resolved()) { failed(last.transactionId, code, true); return; }
        append(new Record(next(), System.currentTimeMillis(), UUID.randomUUID().toString(), bootId, "", "", "", "",
                Stage.LEGACY_RECOVERY, "UNKNOWN", "UNCONFIRMED", code, true, "", acknowledgedLegacy));
    }
    public synchronized void failed(String id, String code, boolean changed) throws IOException {
        requireHealthy();
        if (last == null || !last.transactionId.equals(id) || last.resolved()) throw new IOException("INVALID_TRANSACTION");
        // Once intent is durable, no exception can prove the physical tag remained unchanged.
        changed |= last.tagMayHaveChanged;
        append(copy(changed ? Stage.RECOVERY_REQUIRED : Stage.FAILED_SAFE, last.oldEpc, last.tid,
                "COMPLETED".equals(last.writeOutcome) ? "COMPLETED" : changed ? "UNKNOWN" : "NOT_STARTED",
                changed ? "UNCONFIRMED" : "NOT_STARTED", code, changed, ""));
    }

    /** Offline manual reconciliation: all suspect tags quarantined, box empty, external evidence reference. */
    public synchronized void acknowledgeQuarantine(String id, String disposition, String operatorReference) throws IOException {
        requireHealthy();
        if (last == null || last.resolved() || !last.transactionId.equals(id)
                || !"QUARANTINED_BOX_EMPTY".equals(disposition)
                || operatorReference == null || !operatorReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw new IOException("INVALID_RECONCILIATION");
        append(copy(Stage.ACKNOWLEDGED, last.oldEpc, last.tid, last.writeOutcome, last.verifyOutcome,
                last.errorCode, last.tagMayHaveChanged, disposition + ":" + operatorReference));
        acknowledgedLegacy = last.legacyFingerprint;
    }
    private long next() { return last == null ? 1 : last.sequence + 1; }
    private Record copy(Stage stage, String old, String tid, String write, String verify, String code, boolean changed, String ack) {
        return new Record(next(), System.currentTimeMillis(), last.transactionId, bootId, last.barcode, old,
                last.expectedEpc, tid, stage, write, verify, code, changed, ack, last.legacyFingerprint);
    }
    private void requireStage(String id, Stage stage) throws IOException {
        requireHealthy();
        if (last == null || !last.transactionId.equals(id) || last.stage != stage) throw new IOException("INVALID_TRANSACTION_STAGE");
    }
    private Record append(Record record) throws IOException {
        requireHealthy();
        try {
            byte[] payload = encode(record, previousHash);
            byte[] hash = digest(payload);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(payload.length); out.write(payload); out.write(hash); out.flush();
            channel.position(channel.size());
            appender.append(channel, bytes.toByteArray(), record.stage);
            checkpoint(record.sequence, hash);
            last = record; previousHash = hash;
            return record;
        } catch (Exception e) {
            poison("JOURNAL_APPEND_FAILED");
            // Preserve an I/O error even if the failed fsync left a complete record visible after restart.
            // If this also fails, the last durable nonterminal record remains the recovery evidence.
            try (FileChannel fault = FileChannel.open(faultPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap("JOURNAL_APPEND_FAILED\n".getBytes("US-ASCII"));
                while (bytes.hasRemaining()) fault.write(bytes);
                fault.force(true);
                syncDirectory(faultPath.getParent());
            } catch (IOException latchFailure) { e.addSuppressed(latchFailure); }
            throw new IOException("JOURNAL_APPEND_FAILED", e);
        }
    }
    private static void syncDirectory(Path path) throws IOException {
        // Windows FileChannel cannot open directories. Power-loss semantics still require target SAT.
        if (!System.getProperty("os.name").startsWith("Windows"))
            try (FileChannel directory = FileChannel.open(path, StandardOpenOption.READ)) { directory.force(true); }
    }
    private void checkpoint(long sequence, byte[] hash) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(sequence); out.write(hash); out.flush();
        byte[] payload = bytes.toByteArray();
        out.write(digest(payload)); out.flush();
        try (FileChannel pending = FileChannel.open(pendingHead, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
            while (buffer.hasRemaining()) pending.write(buffer);
            pending.force(true);
        }
        // Never fall back to a non-atomic replacement. A leftover pending file is evidence, not garbage.
        Files.move(pendingHead, headPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(headPath.getParent());
    }
    private void validateCheckpoint() throws IOException {
        if (!Files.notExists(pendingHead)) throw new IOException("CHECKPOINT_UPDATE_INCOMPLETE");
        if (Files.size(headPath) != 72) throw new IOException("INVALID_CHECKPOINT_LENGTH");
        byte[] bytes = Files.readAllBytes(headPath);
        byte[] payload = Arrays.copyOf(bytes, 40);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, 40, 72))) throw new IOException("INVALID_CHECKPOINT_CHECKSUM");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        long sequence = in.readLong(); byte[] hash = new byte[32]; in.readFully(hash);
        if (sequence != (last == null ? 0 : last.sequence) || !MessageDigest.isEqual(hash, previousHash))
            throw new IOException("JOURNAL_CHECKPOINT_MISMATCH");
    }
    private static byte[] encode(Record r, byte[] previous) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(previous); out.writeLong(r.sequence); out.writeLong(r.timestamp);
        for (String value : new String[] { r.transactionId, r.bootId, r.barcode, r.oldEpc, r.expectedEpc, r.tid,
                r.stage.name(), r.writeOutcome, r.verifyOutcome, r.errorCode, r.acknowledgement, r.legacyFingerprint }) out.writeUTF(value);
        out.writeBoolean(r.tagMayHaveChanged); out.flush();
        if (bytes.size() > MAX_RECORD) throw new IOException("RECORD_TOO_LARGE");
        return bytes.toByteArray();
    }
    private void load() throws IOException {
        channel.position(0);
        ByteBuffer header = ByteBuffer.allocate(HEADER.length); readFully(header);
        if (!Arrays.equals(header.array(), HEADER)) throw new IOException("INVALID_HEADER");
        while (channel.position() < channel.size()) {
            ByteBuffer length = ByteBuffer.allocate(4); readFully(length); length.flip();
            int size = length.getInt();
            if (size < 32 || size > MAX_RECORD) throw new IOException("INVALID_RECORD_LENGTH");
            ByteBuffer payload = ByteBuffer.allocate(size); readFully(payload);
            ByteBuffer hash = ByteBuffer.allocate(32); readFully(hash);
            if (!MessageDigest.isEqual(digest(payload.array()), hash.array())) throw new IOException("INVALID_CHECKSUM");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload.array()));
            byte[] previous = new byte[32]; in.readFully(previous);
            if (!MessageDigest.isEqual(previousHash, previous)) throw new IOException("BROKEN_HASH_CHAIN");
            long sequence = in.readLong(), timestamp = in.readLong();
            String[] values = new String[12]; for (int i = 0; i < values.length; i++) values[i] = in.readUTF();
            Record r = new Record(sequence, timestamp, values[0], values[1], values[2], values[3], values[4], values[5],
                    Stage.valueOf(values[6]), values[7], values[8], values[9], in.readBoolean(), values[10], values[11]);
            if (in.available() != 0 || r.sequence != next()) throw new IOException("INVALID_RECORD");
            validateTransition(r);
            last = r; previousHash = hash.array();
            if (r.stage == Stage.ACKNOWLEDGED) acknowledgedLegacy = r.legacyFingerprint;
        }
    }
    private void validateTransition(Record r) throws IOException {
        if (r.stage == Stage.PREPARED || r.stage == Stage.LEGACY_RECOVERY) {
            if (last != null && !last.resolved()) throw new IOException("OVERLAPPING_TRANSACTION");
        } else {
            if (last == null || last.resolved() || !r.transactionId.equals(last.transactionId)
                    || !r.barcode.equals(last.barcode) || !r.expectedEpc.equals(last.expectedEpc)) throw new IOException("INVALID_TRANSITION");
            if ((r.stage == Stage.SELECTED && last.stage != Stage.PREPARED)
                    || (r.stage == Stage.WRITE_INTENT && last.stage != Stage.SELECTED)
                    || (r.stage == Stage.WRITE_RETURNED && last.stage != Stage.WRITE_INTENT)
                    || (r.stage == Stage.VERIFIED && last.stage != Stage.WRITE_RETURNED)
                    || (r.stage == Stage.FAILED_SAFE && last.tagMayHaveChanged)) throw new IOException("INVALID_TRANSITION");
        }
    }
    private void readFully(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new EOFException("TRUNCATED_JOURNAL");
    }
    private void importLegacy() throws IOException {
        if (legacyMarker == null) return;
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(legacyMarker, BasicFileAttributes.class); }
        catch (NoSuchFileException e) { return; }
        if (!attributes.isRegularFile() || attributes.size() > 65536) throw new IOException("LEGACY_MARKER_UNREADABLE");
        String fingerprint = Base64.getEncoder().encodeToString(digest(Files.readAllBytes(legacyMarker)))
                + ":" + attributes.creationTime() + ":" + attributes.lastModifiedTime() + ":" + attributes.fileKey();
        if (last != null && !last.resolved()) return;
        if (fingerprint.equals(acknowledgedLegacy)) return;
        append(new Record(next(), System.currentTimeMillis(), UUID.randomUUID().toString(), bootId, "", "", "", "",
                Stage.LEGACY_RECOVERY, "UNKNOWN", "UNCONFIRMED", "PREVIOUS_OPERATION_INCOMPLETE", true, "", fingerprint));
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { channel.close(); }
    }
}
