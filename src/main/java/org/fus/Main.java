package org.fus;

import org.fus.api.StatusServer;
import org.fus.config.StationConfig;
import org.fus.domain.StationWorkflow;
import org.fus.domain.TransactionJournal;
import org.fus.device.scanner.ScannerSource;
import org.fus.device.rfid.RfidGateway;
import org.fus.device.rfid.OperationGuard;
import org.fus.device.gpi.GpiButton;
import com.mot.rfid.api3.GPI_PORT_STATE;
import com.mot.rfid.api3.RFIDReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Paths;

/** One physical tag must be isolated in the enclosure. */
public final class Main {
    public static void main(String[] args) throws Exception {
        boolean acknowledgement = args.length == 6 && "--ack-recovery".equals(args[0]);
        if (args.length != 1 && !acknowledgement) {
            System.err.println("Usage: org.fus.Main <station.properties> | --ack-recovery <config> <txId> QUARANTINED_BOX_EMPTY <operator/evidence-ref> OFFLINE");
            System.exit(2); return;
        }
        final StationConfig config = new StationConfig(args[acknowledgement ? 1 : 0]);
        final TransactionJournal journal = new TransactionJournal(Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
        if (acknowledgement) {
            try {
                if (!"OFFLINE".equals(args[5])) throw new IllegalArgumentException("OFFLINE_RECONCILIATION_REQUIRED");
                journal.acknowledgeQuarantine(args[2], args[3], args[4]);
                System.out.println("RECOVERY_ACKNOWLEDGED; historical outcome retained; no RFID operation performed");
            } finally { journal.close(); }
            return;
        }
        final AtomicBoolean scannerReady = new AtomicBoolean();
        final AtomicBoolean readerReady = new AtomicBoolean();
        final Object connectionLock = new Object();
        final AtomicReference<RfidGateway> gateway = new AtomicReference<RfidGateway>();
        final AtomicBoolean running = new AtomicBoolean(true);
        final StatusServer status = new StatusServer(config.statusHost, config.statusPort);
        final OperationGuard guard = new OperationGuard();
        StationWorkflow.Gateway delegated = new StationWorkflow.Gateway() {
            private RfidGateway current() { RfidGateway g = gateway.get(); if (g == null) throw new IllegalStateException("READER_DISCONNECTED"); return g; }
            public StationWorkflow.Target selectUnique() throws Exception { return current().selectUnique(); }
            public void write(StationWorkflow.Target t, String epc) throws Exception { current().write(t, epc); }
            public String read(StationWorkflow.Target t) throws Exception { return current().read(t); }
        };
        final StationWorkflow station = new StationWorkflow(delegated,
                status,
                System::nanoTime, TimeUnit.SECONDS.toNanos(config.validitySeconds), journal);
        status.start();
        final ScannerSource scanner = new ScannerSource(config.device, config.reconnectMs, new ScannerSource.Listener() {
            public void state(boolean ready) { synchronized (connectionLock) { scannerReady.set(ready); station.connections(ready, readerReady.get()); } }
            public void scan(String value) { station.scan(value); }
        });
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        Thread scannerThread = new Thread(scanner::run, "scanner-cdc"); scannerThread.setDaemon(true); scannerThread.start();
        Thread scannerMonitor = new Thread(() -> {
            while (running.get()) {
                scanner.checkPresence();
                try { Thread.sleep(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "scanner-monitor");
        scannerMonitor.setDaemon(true); scannerMonitor.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false); station.shutdown(); worker.shutdownNow(); guard.close(); status.close(); scanner.close();
            // The process retains the journal lock until exit, including a blocked native worker.
        }, "station-shutdown"));
        System.out.println("CONNECTION scanner=DISCONNECTED reader=DISCONNECTED");
        Future<?> operation = null;
        while (running.get()) {
            RFIDReader reader = new RFIDReader(config.host, config.port, config.timeoutMs);
            try {
                reader.connect();
                if (!reader.Config.GPI.isPortEnabled(1)) throw new IllegalStateException("GPI1_DISABLED");
                gateway.set(new RfidGateway(reader, config.password));
                synchronized (connectionLock) { readerReady.set(true); station.connections(scannerReady.get(), true); }
                GpiButton button = new GpiButton(TimeUnit.MILLISECONDS.toNanos(config.debounceMs));
                while (running.get()) {
                    // No GPI SDK calls overlap an inventory/access operation on the same session.
                    if (operation != null && !operation.isDone()) { Thread.sleep(25); continue; }
                    if (!gateway.get().isSessionUsable()) break;
                    station.tick();
                    GPI_PORT_STATE value = reader.Config.GPI.getPortState(1);
                    if (value != GPI_PORT_STATE.GPI_PORT_STATE_HIGH && value != GPI_PORT_STATE.GPI_PORT_STATE_LOW) throw new IllegalStateException("GPI_STATE_UNKNOWN");
                    if (button.update(value == GPI_PORT_STATE.GPI_PORT_STATE_HIGH, System.nanoTime()) && (operation == null || operation.isDone())) {
                        Runnable work = station.beginTrigger();
                        if (work != null) {
                            try {
                                guard.startFatal(config.operationTimeoutMs);
                                operation = worker.submit(() -> {
                                    try {
                                        work.run();
                                        try { guard.completed(); }
                                        catch (Exception e) { Runtime.getRuntime().halt(2); }
                                    } catch (Throwable failure) {
                                        Runtime.getRuntime().halt(2);
                                    }
                                });
                            } catch (Exception e) {
                                // Keep any durable evidence; never clear a failed operation here.
                                station.abortReserved("OPERATION_SUBMISSION_FAILED");
                            }
                        }
                    }
                    Thread.sleep(25);
                }
            } catch (Exception e) { if (running.get()) System.err.println("READER_ERROR type=" + e.getClass().getSimpleName()); }
            finally {
                synchronized (connectionLock) { readerReady.set(false); station.connections(scannerReady.get(), false); }
                // Do not replace the session while an access operation is still running.
                if (operation != null && !operation.isDone()) {
                    try { operation.get(); } catch (Exception ignored) { }
                }
                gateway.set(null);
                try { if (reader.isConnected()) reader.disconnect(); } catch (Exception ignored) { }
                try { reader.Dispose(); } catch (Exception ignored) { }
            }
            if (running.get()) Thread.sleep(config.reconnectMs);
        }
    }
}
