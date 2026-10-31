package org.fus;

import org.fus.domain.*;
import org.fus.domain.TransactionJournal.Stage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.fus.SafetyFixture.*;

public final class TransactionJournalCheck {
    private static void rejected(IoAction action) throws Exception {
        try { action.run(); throw new AssertionError("operation should have been rejected"); } catch (IOException expected) { }
    }
    private interface IoAction { void run() throws Exception; }
    private static TransactionJournal reopen(SafetyFixture f) throws Exception { f.journal.close(); return new TransactionJournal(f.path, f.marker); }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "crash".equals(args[0])) {
            boolean gap = args[2].endsWith("_GAP");
            Stage boundary = Stage.valueOf(args[2].replace("_GAP", ""));
            TransactionJournal journal = new TransactionJournal(Paths.get(args[1]), null, (channel, frame, stage) -> {
                TransactionJournal.SYNC_APPEND.append(channel, frame, stage);
                if (gap && stage == boundary) Runtime.getRuntime().halt(25);
            });
            String id = journal.begin(BARCODE).transactionId;
            if (boundary != Stage.PREPARED) journal.selected(id, EPC, TID);
            if (boundary != Stage.PREPARED && boundary != Stage.SELECTED) journal.intent(id);
            if (boundary == Stage.WRITE_RETURNED || boundary == Stage.VERIFIED) journal.written(id);
            if (boundary == Stage.VERIFIED) journal.verified(id);
            Runtime.getRuntime().halt(24);
        }
        for (Stage stage : new Stage[] { Stage.PREPARED, Stage.WRITE_INTENT, Stage.VERIFIED }) try (SafetyFixture f = new SafetyFixture()) {
            f.journal.close();
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    TransactionJournalCheck.class.getName(), "crash", f.path.toString(), stage.name() + "_GAP").start();
            if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("checkpoint gap child timeout"); }
            check(child.exitValue() == 25, "checkpoint gap child failed");
            try (TransactionJournal recovered = new TransactionJournal(f.path, f.marker)) {
                check(!recovered.isHealthy() && recovered.recoveryRequired(), "journal/checkpoint crash gap opened gate");
            }
        }
        for (Stage stage : new Stage[] { Stage.PREPARED, Stage.SELECTED, Stage.WRITE_INTENT, Stage.WRITE_RETURNED, Stage.VERIFIED }) {
            try (SafetyFixture f = new SafetyFixture()) {
                f.journal.close();
                String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        TransactionJournalCheck.class.getName(), "crash", f.path.toString(), stage.name()).start();
                if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("crash child timeout"); }
                check(child.exitValue() == 24, "crash child failure " + child.exitValue());
                try (TransactionJournal recovered = new TransactionJournal(f.path, f.marker)) {
                    check(recovered.isHealthy() && recovered.latest().stage == stage, "stage lost after halt " + stage);
                    check(recovered.recoveryRequired() == (stage != Stage.VERIFIED), "restart gate " + stage);
                    check(recovered.latest().barcode.equals(BARCODE) && recovered.latest().expectedEpc.equals(EPC), "intent metadata lost");
                    StationWorkflow fresh = new StationWorkflow(f.rfid, (s,b,e,d) -> { }, f.now::get, 100, recovered);
                    fresh.connections(true, true);
                    if (stage != Stage.VERIFIED) check(!fresh.scan(BARCODE) && fresh.beginTrigger() == null, "restart replay accepted");
                    check(f.rfid.writes == 0, "automatic replay");
                }
            }
        }
        for (Stage stage : new Stage[] { Stage.PREPARED, Stage.SELECTED, Stage.WRITE_INTENT, Stage.WRITE_RETURNED, Stage.VERIFIED, Stage.RECOVERY_REQUIRED }) {
            for (int mode = 0; mode < 3; mode++) {
                final int failureMode = mode;
                TransactionJournal.Appender failing = (channel, frame, next) -> {
                    if (stage == next) {
                        if (failureMode == 1) channel.write(ByteBuffer.wrap(frame, 0, frame.length / 2));
                        if (failureMode == 2) { ByteBuffer bytes = ByteBuffer.wrap(frame); while (bytes.hasRemaining()) channel.write(bytes); }
                        throw new IOException(failureMode == 0 ? "simulated disk full" : failureMode == 1 ? "partial disk write" : "fsync failure");
                    }
                    TransactionJournal.SYNC_APPEND.append(channel, frame, next);
                };
                try (SafetyFixture f = new SafetyFixture(failing)) {
                    if (stage == Stage.RECOVERY_REQUIRED) f.rfid.mismatch = true;
                    f.write();
                    check(!f.states.contains("PASS") && f.station.recoveryRequired(), "disk error produced PASS " + stage + "/" + mode);
                    if (stage == Stage.PREPARED || stage == Stage.SELECTED || stage == Stage.WRITE_INTENT) check(f.rfid.writes == 0, "write before durable intent");
                    try (TransactionJournal recovered = reopen(f)) {
                        check(recovered.recoveryRequired(), "journal failure lost after restart " + stage + "/" + mode);
                    }
                }
            }
        }
        try (SafetyFixture f = new SafetyFixture()) {
            f.rfid.mismatch = true; f.write();
            String id = f.journal.latest().transactionId;
            rejected(() -> f.journal.begin(BARCODE));
            rejected(() -> f.journal.acknowledgeQuarantine("wrong", "QUARANTINED_BOX_EMPTY", "operator/SAT-001"));
            rejected(() -> f.journal.acknowledgeQuarantine(id, "RETRY", "operator/SAT-001"));
            rejected(() -> f.journal.acknowledgeQuarantine(id, "QUARANTINED_BOX_EMPTY", "short"));
            long before = Files.size(f.path);
            f.journal.acknowledgeQuarantine(id, "QUARANTINED_BOX_EMPTY", "operator/SAT-001");
            check(!f.journal.recoveryRequired() && f.journal.latest().tagMayHaveChanged, "ack erased historical uncertainty");
            check(Files.size(f.path) > before, "ack rewrote journal");
            rejected(() -> f.journal.acknowledgeQuarantine(id, "QUARANTINED_BOX_EMPTY", "operator/SAT-002"));
            try (TransactionJournal recovered = reopen(f)) {
                check(!recovered.recoveryRequired() && recovered.latest().acknowledgement.contains("SAT-001"), "ack lost");
            }
        }
        for (int corruption = 0; corruption < 4; corruption++) try (SafetyFixture f = new SafetyFixture()) {
            f.journal.begin(BARCODE); f.journal.close();
            byte[] bytes = Files.readAllBytes(f.path);
            if (corruption == 0) bytes = java.util.Arrays.copyOf(bytes, bytes.length - 5);
            if (corruption == 1) bytes[bytes.length - 1] ^= 1;
            if (corruption == 2) bytes = new byte[0];
            if (corruption == 3) bytes[8] = 127;
            Files.write(f.path, bytes);
            try (TransactionJournal recovered = new TransactionJournal(f.path, f.marker)) {
                check(!recovered.isHealthy() && recovered.recoveryRequired(), "corruption accepted");
                rejected(() -> recovered.begin(BARCODE));
                rejected(() -> recovered.acknowledgeQuarantine("anything", "QUARANTINED_BOX_EMPTY", "operator/SAT-001"));
            }
            check(java.util.Arrays.equals(bytes, Files.readAllBytes(f.path)), "corruption silently repaired/discarded");
        }
        for (boolean headerOnly : new boolean[] { true, false }) try (SafetyFixture f = new SafetyFixture()) {
            String id = f.journal.begin(BARCODE).transactionId;
            long boundary = headerOnly ? 8 : Files.size(f.path);
            f.journal.selected(id, EPC, TID); f.journal.intent(id); f.journal.failed(id, "UNCERTAIN", true); f.journal.close();
            try (java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(f.path, StandardOpenOption.WRITE)) { file.truncate(boundary); }
            try (TransactionJournal recovered = new TransactionJournal(f.path, f.marker)) {
                check(recovered.recoveryRequired() && !recovered.isHealthy(), "complete-prefix truncation lost unresolved transaction");
            }
        }
        for (int checkpointFault = 0; checkpointFault < 4; checkpointFault++) try (SafetyFixture f = new SafetyFixture()) {
            f.journal.begin(BARCODE); f.journal.close();
            Path head = f.directory.resolve("transactions.journal.head");
            if (checkpointFault == 0) Files.delete(head);
            if (checkpointFault == 1) { byte[] bytes = Files.readAllBytes(head); bytes[0] ^= 1; Files.write(head, bytes); }
            if (checkpointFault == 2) Files.write(f.directory.resolve("transactions.journal.head.pending"), new byte[] { 1 });
            if (checkpointFault == 3) { Files.delete(f.path); rejected(() -> new TransactionJournal(f.path, f.marker)); continue; }
            try (TransactionJournal recovered = new TransactionJournal(f.path, f.marker)) {
                check(!recovered.isHealthy() && recovered.recoveryRequired(), "checkpoint fault ignored " + checkpointFault);
            }
        }
        try (SafetyFixture f = new SafetyFixture()) {
            Files.write(f.directory.resolve("transactions.journal.head.pending"), new byte[] { 1 });
            f.write(); check(f.rfid.writes == 0 && f.station.recoveryRequired(), "checkpoint update error allowed RF write");
            try (TransactionJournal recovered = reopen(f)) { check(recovered.recoveryRequired(), "checkpoint error lost after restart"); }
        }
        for (boolean renameFailure : new boolean[] { false, true }) try (SafetyFixture f = new SafetyFixture()) {
            Runnable breakCheckpoint = () -> {
                try {
                    if (renameFailure) {
                        Path head = f.directory.resolve("transactions.journal.head");
                        Files.delete(head); Files.createDirectory(head);
                    } else Files.write(f.directory.resolve("transactions.journal.head.pending"), new byte[] { 1 });
                } catch (IOException e) { throw new AssertionError(e); }
            };
            if (renameFailure) f.rfid.onRead = breakCheckpoint; else f.rfid.onWrite = breakCheckpoint;
            f.write(); check(f.rfid.writes == 1 && !f.states.contains("PASS") && f.station.recoveryRequired(), "post-RF checkpoint failure produced PASS");
            try (TransactionJournal recovered = reopen(f)) { check(recovered.recoveryRequired(), "post-RF checkpoint failure lost after restart"); }
        }
        try (SafetyFixture f = new SafetyFixture((channel, frame, stage) -> {
            if (stage == Stage.ACKNOWLEDGED) throw new IOException("simulated acknowledgement disk full");
            TransactionJournal.SYNC_APPEND.append(channel, frame, stage);
        })) {
            f.rfid.mismatch = true; f.write();
            rejected(() -> f.journal.acknowledgeQuarantine(f.journal.latest().transactionId, "QUARANTINED_BOX_EMPTY", "operator/ACK-FAIL"));
            check(f.journal.recoveryRequired(), "failed ack cleared gate");
            try (TransactionJournal recovered = reopen(f)) { check(recovered.recoveryRequired(), "failed ack lost gate on restart"); }
        }
        try (SafetyFixture f = new SafetyFixture()) {
            f.journal.close(); Files.write(f.marker, "RFID_OPERATION_IN_PROGRESS\n".getBytes("US-ASCII"));
            byte[] original = Files.readAllBytes(f.marker);
            try (TransactionJournal legacy = new TransactionJournal(f.path, f.marker)) {
                check(legacy.recoveryRequired() && legacy.latest().stage == Stage.LEGACY_RECOVERY, "legacy marker not gated");
                legacy.acknowledgeQuarantine(legacy.latest().transactionId, "QUARANTINED_BOX_EMPTY", "operator/LEGACY-001");
                String id = legacy.begin(BARCODE).transactionId; legacy.failed(id, "SAFE_PRECHECK", false);
            }
            try (TransactionJournal legacy = new TransactionJournal(f.path, f.marker)) {
                check(!legacy.recoveryRequired(), "acknowledged unchanged marker reimported");
                check(java.util.Arrays.equals(original, Files.readAllBytes(f.marker)), "legacy marker overwritten/deleted");
            }
            Files.write(f.marker, "different incident".getBytes("US-ASCII"));
            try (TransactionJournal changed = new TransactionJournal(f.path, f.marker)) { check(changed.recoveryRequired(), "new marker ignored"); }
        }
        try (SafetyFixture f = new SafetyFixture()) {
            rejected(() -> new TransactionJournal(f.path, f.marker));
            rejected(() -> new TransactionJournal(f.directory.resolve("missing/transactions"), f.marker));
            rejected(() -> new TransactionJournal(f.path, f.path));
        }
        try (SafetyFixture f = new SafetyFixture()) {
            f.rfid.mismatch = true; f.write();
            String id = f.journal.latest().transactionId;
            Path config = f.directory.resolve("ack.properties");
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("transaction.journal", f.path.toString());
            properties.setProperty("recovery.marker", f.marker.toString());
            properties.setProperty("reader.host", "127.0.0.1"); properties.setProperty("reader.port", "1");
            properties.setProperty("scanner.device", f.directory.resolve("missing-scanner").toString());
            try (java.io.OutputStream out = Files.newOutputStream(config)) { properties.store(out, "local test only"); }
            rejected(() -> Main.main(new String[] { "--ack-recovery", config.toString(), id, "QUARANTINED_BOX_EMPTY", "operator/CLI-001", "OFFLINE" }));
            f.journal.close();
            Main.main(new String[] { "--ack-recovery", config.toString(), id, "QUARANTINED_BOX_EMPTY", "operator/CLI-001", "OFFLINE" });
            try (TransactionJournal recovered = new TransactionJournal(f.path, f.marker)) {
                check(!recovered.recoveryRequired() && recovered.latest().acknowledgement.contains("CLI-001"), "offline Main ACK lost");
            }
        }
        System.out.println("Transaction journal checks passed (crash, disk, corruption, legacy, acknowledgement, lock)");
    }
}
