package org.fus;

import org.fus.domain.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicitly simulated identity/access, never used by production Main. */
final class SafetyFixture implements AutoCloseable {
    static final String BARCODE = "9K1135600660004039", EPC = "394B1135600660004039";
    static final String TID = "E28011700000000000000001";
    final Path directory = Files.createTempDirectory("rfid-phase1-");
    final Path path = directory.resolve("transactions.journal"), marker = directory.resolve("operation.marker");
    final AtomicLong now = new AtomicLong();
    final List<String> states = Collections.synchronizedList(new ArrayList<String>());
    final Rfid rfid = new Rfid();
    final TransactionJournal journal;
    StationWorkflow station;
    SafetyFixture() throws Exception { this(TransactionJournal.SYNC_APPEND); }
    SafetyFixture(TransactionJournal.Appender appender) throws Exception {
        journal = new TransactionJournal(path, marker, appender);
        restartWorkflow();
    }
    void restartWorkflow() {
        station = new StationWorkflow(rfid, (s, b, e, d) -> states.add(s), now::get, 100, journal);
        station.connections(true, true);
    }
    String state() { return states.get(states.size() - 1); }
    void write() { check(station.scan(BARCODE), "scan rejected"); station.trigger(); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Rfid implements StationWorkflow.Gateway {
        volatile int writes;
        boolean supported = true, unique = true, mismatch, unknownIdentity, writeFailure, readFailure;
        String epc, oldEpc = EPC;
        Runnable onSelect = () -> { }, onWrite = () -> { }, onRead = () -> { };
        public boolean supportsVerifiedIdentityAccess() { return supported; }
        public StationWorkflow.Target selectUnique() throws Exception {
            onSelect.run();
            if (!unique) throw new StationFailure("BUSINESS", "MULTIPLE_TAGS", "IDENTITY", false, "simulation", null);
            if (unknownIdentity) return new StationWorkflow.Target() { };
            return PhysicalTagIdentity.select(Collections.singletonList(new PhysicalTagIdentity.Observation(oldEpc, TID, true)), true);
        }
        public void write(StationWorkflow.Target target, String value) throws Exception {
            writes++; epc = value; onWrite.run();
            if (writeFailure) throw new StationFailure("TECHNICAL", "PARTIAL_WRITE", "WRITE_EPC", false, "deliberately understated changed flag", null);
        }
        public String read(StationWorkflow.Target target) throws Exception {
            onRead.run();
            if (readFailure) throw new java.io.IOException("simulated read failure");
            return mismatch ? "394B1135600660004038" : epc;
        }
    }
    @Override public void close() throws Exception {
        journal.close();
        for (String name : new String[] { "transactions.journal", "transactions.journal.fault", "transactions.journal.head", "transactions.journal.head.pending", "operation.marker", "ack.properties" }) Files.deleteIfExists(directory.resolve(name));
        Files.delete(directory);
    }
}
