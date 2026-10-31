package org.fus;

import org.fus.domain.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.fus.SafetyFixture.*;

public final class Phase1SafetyCheck {
    public static void main(String[] args) throws Exception {
        try (SafetyFixture f = new SafetyFixture()) {
            f.rfid.onWrite = () -> {
                TransactionJournal.Record record = f.journal.latest();
                check(record.stage == TransactionJournal.Stage.WRITE_INTENT && record.tagMayHaveChanged, "write without durable intent");
                check(record.barcode.equals(BARCODE) && record.oldEpc.equals(EPC) && record.expectedEpc.equals(EPC) && record.tid.equals(TID), "intent fields lost");
                check(!record.bootId.isEmpty() && record.timestamp > 0 && !record.transactionId.isEmpty(), "transaction identity missing");
            };
            f.write(); check(f.journal.latest().stage == TransactionJournal.Stage.VERIFIED && f.journal.isHealthy(), "PASS before durable verified record");
        }
        try (SafetyFixture f = new SafetyFixture()) {
            f.rfid.mismatch = true;
            f.station.scan(BARCODE); Runnable reserved = f.station.beginTrigger(); reserved.run(); reserved.run();
            check(f.rfid.writes == 1, "reservation replayed RF write");
            check(f.station.recoveryRequired() && !f.station.scan(BARCODE) && f.station.beginTrigger() == null, "uncertain transaction accepted work");
            f.now.addAndGet(TimeUnit.SECONDS.toNanos(10)); f.station.tick();
            f.station.connections(false, false); f.station.connections(true, true);
            check(f.station.recoveryRequired(), "UI/connection cleared gate");
            f.restartWorkflow(); check(!f.station.scan(BARCODE), "new workflow cleared gate");
        }
        for (int failure = 0; failure < 4; failure++) try (SafetyFixture f = new SafetyFixture()) {
            if (failure == 0) f.rfid.writeFailure = true;
            if (failure == 1) f.rfid.readFailure = true;
            if (failure == 2) f.rfid.onWrite = () -> f.station.connections(false, true);
            if (failure == 3) f.rfid.onRead = () -> f.station.shutdown();
            f.write();
            check(!f.states.contains("PASS") && f.station.recoveryRequired(), "failure produced PASS or lost gate " + failure);
            check(f.journal.latest().tagMayHaveChanged, "changed flag downgraded");
        }
        for (int precheck = 0; precheck < 4; precheck++) try (SafetyFixture f = new SafetyFixture()) {
            if (precheck == 0) f.rfid.supported = false;
            if (precheck == 1) f.rfid.unknownIdentity = true;
            if (precheck == 2) f.rfid.oldEpc = EPC + "0000";
            if (precheck == 3) f.rfid.oldEpc = EPC + "000000000000";
            f.write(); check(f.rfid.writes == 0 && !f.states.contains("PASS"), "unproven identity/resize wrote");
        }
        try (SafetyFixture f = new SafetyFixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.rfid.onWrite = () -> {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            };
            f.station.scan(BARCODE); Runnable work = f.station.beginTrigger();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = workers.submit(work); check(entered.await(2, TimeUnit.SECONDS), "write did not start");
                workers.submit(work).get(1, TimeUnit.SECONDS);
                check(!f.station.scan(BARCODE) && f.station.beginTrigger() == null && !f.station.safeToTrigger(), "concurrent work accepted");
                f.station.connections(false, true); release.countDown(); first.get(2, TimeUnit.SECONDS);
                check(f.rfid.writes == 1 && f.station.recoveryRequired() && !f.states.contains("PASS"), "overlap/disconnect false PASS");
            } finally { release.countDown(); workers.shutdownNow(); }
        }
        try (SafetyFixture f = new SafetyFixture()) {
            f.station.scan(BARCODE); Runnable cancelled = f.station.beginTrigger();
            f.station.abortReserved("SUBMISSION_FAILED"); cancelled.run();
            check(f.rfid.writes == 0 && f.station.recoveryRequired(), "aborted reservation ran");
        }
        System.out.println("Phase 1 safety checks passed");
    }
}
