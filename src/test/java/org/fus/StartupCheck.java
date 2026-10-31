package org.fus;

import org.fus.device.scanner.ScannerSource;
import org.fus.domain.TransactionJournal;
import org.fus.runtime.RuntimeState;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.*;

/** Offline startup preflight and missing CDC; never constructs RFIDReader. */
public final class StartupCheck {
    private static void check(boolean v, String message) { if (!v) throw new AssertionError(message); }
    private static Path config(Path root) throws Exception {
        Properties p = new Properties();
        p.setProperty("station.state.directory", root.toString());
        p.setProperty("transaction.journal", root.resolve("transactions.journal").toString());
        p.setProperty("recovery.marker", root.resolve("operation.marker").toString());
        p.setProperty("scanner.device", root.resolve("missing-scanner").toString());
        Path file = root.resolve("station.properties");
        try (OutputStream out = Files.newOutputStream(file)) { p.store(out, "test only"); }
        return file;
    }
    private static int mainChild(String mode, Path file) throws Exception {
        Process child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Main.class.getName(), mode, file.toString()).inheritIO().start();
        if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(); throw new AssertionError("startup hung"); }
        return child.exitValue();
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("fx-startup-").toRealPath(), config = config(root);
        check(mainChild("--check-startup", config) == 0, "missing scanner blocked offline preflight");
        AtomicBoolean ready = new AtomicBoolean(); CountDownLatch offline = new CountDownLatch(1);
        ScannerSource scanner = new ScannerSource(root.resolve("missing-scanner").toString(), 30, new ScannerSource.Listener() {
            public void state(boolean value) { ready.set(value); if (!value) offline.countDown(); }
            public void scan(String value) { throw new AssertionError("phantom scan"); }
        });
        Thread thread = new Thread(scanner::run); thread.setDaemon(true); thread.start();
        check(offline.await(2, TimeUnit.SECONDS) && !ready.get(), "missing scanner did not report offline");
        scanner.close(); thread.interrupt(); thread.join(2000); check(!thread.isAlive(), "missing scanner thread stuck");
        try (RuntimeState.Owner owner = RuntimeState.lock(root.resolve("station-owner.lock"))) {
            check(mainChild("--check-startup", config) == 23, "duplicate startup accepted");
        }
        Path journal = root.resolve("transactions.journal"), marker = root.resolve("operation.marker");
        String tx;
        try (TransactionJournal j = new TransactionJournal(journal, marker)) { tx = j.begin("AB1234567890123456").transactionId; }
        byte[] original = Files.readAllBytes(journal), checkpoint = Files.readAllBytes(root.resolve("transactions.journal.head"));
        check(mainChild("--check-startup", config) == 21, "unresolved startup allowed");
        check(Arrays.equals(original, Files.readAllBytes(journal)) && Arrays.equals(checkpoint,
                Files.readAllBytes(root.resolve("transactions.journal.head"))), "startup altered unresolved evidence");
        try (TransactionJournal j = new TransactionJournal(journal, marker)) { check(tx.equals(j.latest().transactionId), "startup lost tx"); }
        Files.write(journal, new byte[] { 1 }, StandardOpenOption.APPEND);
        byte[] corrupt = Files.readAllBytes(journal);
        check(mainChild("--check-startup", config) == 21 && Arrays.equals(corrupt, Files.readAllBytes(journal)), "corrupt journal repaired/ignored");
        Path fencedRoot = Files.createTempDirectory("fx-startup-fence-").toRealPath(), fencedConfig = config(fencedRoot);
        RuntimeState.create(fencedRoot.resolve("sdk-session.pending"), "TEST\n");
        check(mainChild("--check-startup", fencedConfig) == 24, "session fence ignored");
        Path invalid = root.resolve("invalid.properties"); Files.write(invalid, "status.port=0\n".getBytes("US-ASCII"));
        check(mainChild("--supervise", invalid) == 20, "invalid config restarted");
        check(mainChild("--supervise", root.resolve("absent.properties")) == 20, "missing config restarted");
        Properties drift = new Properties(); try (InputStream in = Files.newInputStream(config)) { drift.load(in); }
        drift.setProperty("transaction.journal", root.resolve("other.journal").toString());
        Path changed = root.resolve("changed.properties"); try (OutputStream out = Files.newOutputStream(changed)) { drift.store(out, "test"); }
        check(mainChild("--check-startup", changed) == 23 && !Files.exists(root.resolve("other.journal")), "binding drift bypassed recovery");
        for (String collision : Arrays.asList("stop.request", "supervisor.status")) {
            Path collisionRoot = Files.createTempDirectory("fx-collision-").toRealPath();
            Path collisionConfig = config(collisionRoot);
            Properties collisionProperties = new Properties();
            try (InputStream in = Files.newInputStream(collisionConfig)) { collisionProperties.load(in); }
            String key = "stop.request".equals(collision) ? "recovery.marker" : "transaction.journal";
            collisionProperties.setProperty(key, collisionRoot.resolve(collision).toString());
            try (OutputStream out = Files.newOutputStream(collisionConfig)) { collisionProperties.store(out, "test"); }
            byte[] evidence = "PRESERVE_PHASE1_EVIDENCE\n".getBytes("US-ASCII");
            Files.write(collisionRoot.resolve(collision), evidence);
            // Corrupt journal guarantees this RED regression cannot reach SDK, even before the fix.
            Files.write(collisionRoot.resolve("transactions.journal"), evidence);
            int code = mainChild("--supervise", collisionConfig);
            check(Files.exists(collisionRoot.resolve(collision)) && Arrays.equals(evidence,
                    Files.readAllBytes(collisionRoot.resolve(collision))), "supervisor overwrote evidence: " + collision);
            check(code == 20 && !Files.exists(collisionRoot.resolve("supervisor.status.pending")), "collision was not config failure");
        }
        Path configRoot = Files.createTempDirectory("fx-config-collision-").toRealPath();
        Path normalConfig = config(configRoot), collidingConfig = configRoot.resolve("supervisor.status");
        byte[] configEvidence = Files.readAllBytes(normalConfig);
        Files.write(collidingConfig, configEvidence);
        Files.write(configRoot.resolve("transactions.journal"), "CORRUPT_NO_SDK\n".getBytes("US-ASCII"));
        int configExit = mainChild("--supervise", collidingConfig);
        check(Arrays.equals(configEvidence, Files.readAllBytes(collidingConfig)), "supervisor overwrote config");
        check(configExit == 20, "colliding config not rejected");
        Path legacyRoot = Files.createTempDirectory("fx-legacy-timeout-").toRealPath(), legacyConfig = config(legacyRoot);
        Properties legacy = new Properties(); try (InputStream in = Files.newInputStream(legacyConfig)) { legacy.load(in); }
        legacy.setProperty("operation.timeout.ms", "300000");
        try (OutputStream out = Files.newOutputStream(legacyConfig)) { legacy.store(out, "valid Phase 1 maximum timeout, no Phase 2 watchdog setting"); }
        check(mainChild("--check-startup", legacyConfig) == 0, "valid Phase 1 timeout rejected by new default watchdog");
        legacy.setProperty("process.progress.timeout.ms", "90000");
        try (OutputStream out = Files.newOutputStream(legacyConfig)) { legacy.store(out, "explicit unsafe deadline must still be rejected"); }
        check(mainChild("--check-startup", legacyConfig) == 20, "explicit unsafe progress deadline accepted");
        System.out.println("Startup checks passed (offline preflight, missing CDC, journal preservation, config, owner, fence)");
    }
}
