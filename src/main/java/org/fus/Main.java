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
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;
import java.io.InputStream;
import org.fus.runtime.RuntimeState;
import org.fus.runtime.ProcessSupervisor;
import org.fus.runtime.ProgressWatchdog;

/** One physical tag must be isolated in the enclosure. */
public final class Main {
    private static RuntimeState.Owner processOwner;
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && ("--stop".equals(args[0]) || "--stop-state".equals(args[0]))) {
            int exit;
            try {
                Path root;
                if ("--stop-state".equals(args[0])) root = canonicalRoot(args[1]);
                else {
                    Properties p = new Properties();
                    try (InputStream input = Files.newInputStream(Paths.get(args[1]))) { p.load(input); }
                    root = canonicalRoot(p.getProperty("station.state.directory", "").trim());
                }
                // Stop must work even if operational config/checksum is broken. Maximum supported stop budget.
                exit = ProcessSupervisor.stop(root, 601000);
            } catch (Exception failure) { System.err.println("STOP_CONTROL_UNAVAILABLE"); exit = 23; }
            System.exit(exit); return;
        }
        if (args.length == 2 && ("--supervise".equals(args[0]) || "--check-startup".equals(args[0]))) {
            StationConfig config;
            Path root;
            try {
                config = new StationConfig(args[1]);
                root = stateRoot(config);
                RuntimeState.validateConfig(root, Paths.get(args[1]), Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
            } catch (Exception invalid) { System.err.println("STARTUP_CONFIG_INVALID"); System.exit(20); return; }
            int exit;
            try {
                if ("--check-startup".equals(args[0])) {
                    try (RuntimeState.Owner owner = RuntimeState.lock(root.resolve("station-owner.lock"))) {
                        exit = checkStartup(config, root);
                    }
                } else {
                    // Pin evidence before the supervisor clears stop or writes status/control files.
                    try (RuntimeState.Owner owner = RuntimeState.lock(root.resolve("station-owner.lock"))) {
                        RuntimeState.bind(root, Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
                    }
                    String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
                    exit = new ProcessSupervisor(root, Arrays.asList(java, "-cp", System.getProperty("java.class.path"),
                            Main.class.getName(), Paths.get(args[1]).toAbsolutePath().toString()), config.restartLimit,
                            config.restartBaseMs, config.restartCapMs, config.stopTimeoutMs).run();
                }
            } catch (Exception failure) { System.err.println("SUPERVISOR_OR_STATE_UNAVAILABLE"); exit = 23; }
            System.exit(exit); return;
        }
        boolean acknowledgement = args.length == 6 && "--ack-recovery".equals(args[0]);
        if (args.length != 1 && !acknowledgement) {
            System.err.println("Usage: org.fus.Main <config> | --supervise <config> | --stop <config> | --stop-state <canonical-state-root> | --check-startup <config> | --ack-recovery <config> <txId> QUARANTINED_BOX_EMPTY <operator/evidence-ref> OFFLINE");
            System.exit(2); return;
        }
        final StationConfig config;
        try { config = new StationConfig(args[acknowledgement ? 1 : 0]); }
        catch (Exception invalid) {
            if (acknowledgement) throw invalid;
            System.err.println("STARTUP_CONFIG_INVALID"); System.exit(20); return;
        }
        if (acknowledgement) {
            final TransactionJournal journal = new TransactionJournal(Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
            try {
                if (!"OFFLINE".equals(args[5])) throw new IllegalArgumentException("OFFLINE_RECONCILIATION_REQUIRED");
                journal.acknowledgeQuarantine(args[2], args[3], args[4]);
                System.out.println("RECOVERY_ACKNOWLEDGED; historical outcome retained; no RFID operation performed");
            } finally { journal.close(); }
            return;
        }
        final Path root;
        try {
            root = stateRoot(config);
            RuntimeState.validateConfig(root, Paths.get(args[0]), Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
        }
        catch (Exception invalid) { System.err.println("STARTUP_STATE_CONFIG_INVALID"); System.exit(20); return; }
        // Retain process ownership through shutdown hooks and any blocked native thread, until JVM exit.
        try { processOwner = RuntimeState.lock(root.resolve("station-owner.lock")); }
        catch (Exception duplicate) { System.err.println("STATION_OWNER_UNAVAILABLE"); System.exit(23); return; }
        final TransactionJournal journal;
        try {
            RuntimeState.bind(root, Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
            if (RuntimeState.uncertain(root)) throw new IllegalStateException("SESSION_RECOVERY_REQUIRED");
            journal = new TransactionJournal(Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
            if (journal.recoveryRequired()) { journal.close(); throw new IllegalStateException("JOURNAL_RECOVERY_REQUIRED"); }
        } catch (Exception unsafe) { System.err.println("STARTUP_RECOVERY_REQUIRED"); System.exit(21); return; }
        final ProgressWatchdog progress = new ProgressWatchdog(config.progressTimeoutMs, () -> Runtime.getRuntime().halt(24));
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
            if (!Files.notExists(root.resolve("stop.request"))) break;
            progress.progress();
            Path fence = root.resolve("sdk-session.pending");
            RuntimeState.create(fence, "SDK_SESSION_UNCONFIRMED\n");
            RFIDReader reader = null;
            try {
                reader = new RFIDReader(config.host, config.port, config.timeoutMs);
                reader.connect();
                if (!reader.Config.GPI.isPortEnabled(1)) throw new IllegalStateException("GPI1_DISABLED");
                gateway.set(new RfidGateway(reader, config.password));
                synchronized (connectionLock) { readerReady.set(true); station.connections(scannerReady.get(), true); }
                GpiButton button = new GpiButton(TimeUnit.MILLISECONDS.toNanos(config.debounceMs));
                while (running.get()) {
                    if (!Files.notExists(root.resolve("stop.request"))) break;
                    // No GPI SDK calls overlap an inventory/access operation on the same session.
                    if (operation != null && !operation.isDone()) { Thread.sleep(25); continue; }
                    progress.progress();
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
                boolean cleaned = reader != null;
                if (reader != null) {
                    try { if (reader.isConnected()) reader.disconnect(); } catch (Exception failure) { cleaned = false; }
                    try { reader.Dispose(); } catch (Exception failure) { cleaned = false; }
                }
                if (!cleaned) { System.err.println("SDK_CLEANUP_UNCONFIRMED"); Runtime.getRuntime().halt(24); }
                Files.delete(fence); RuntimeState.syncDirectory(root);
                progress.progress();
            }
            if (running.get() && Files.notExists(root.resolve("stop.request"))) Thread.sleep(config.reconnectMs);
        }
        running.set(false); station.shutdown(); worker.shutdownNow(); guard.close(); status.close(); scanner.close();
        progress.close();
        // Do not close owner/journal here: a daemon shutdown hook may still be using station state.
    }
    private static Path stateRoot(StationConfig config) throws Exception {
        return canonicalRoot(config.stateDirectory);
    }
    private static Path canonicalRoot(String directory) throws Exception {
        if (directory.isEmpty()) throw new IllegalArgumentException("STATE_DIRECTORY_REQUIRED");
        Path root = Paths.get(directory);
        if (!root.isAbsolute() || !root.normalize().equals(root.toRealPath())) throw new IllegalArgumentException("STATE_DIRECTORY_NOT_CANONICAL");
        return root;
    }
    private static int checkStartup(StationConfig config, Path root) throws Exception {
        RuntimeState.bind(root, Paths.get(config.journalPath), Paths.get(config.recoveryMarker));
        if (RuntimeState.uncertain(root)) return 24;
        try (TransactionJournal journal = new TransactionJournal(Paths.get(config.journalPath), Paths.get(config.recoveryMarker))) {
            return journal.recoveryRequired() ? 21 : 0;
        }
    }
}
