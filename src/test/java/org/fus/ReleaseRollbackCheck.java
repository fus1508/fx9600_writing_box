package org.fus;

import org.fus.domain.TransactionJournal;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Two packaged binaries, one unresolved temporary state. Offline preflight only; no SDK construction. */
public final class ReleaseRollbackCheck {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void preflight(Path jar, Path config, int expected) throws Exception {
        Process child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", jar.toRealPath().toString(), "org.fus.Main", "--check-startup", config.toString())
                .inheritIO().start();
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroyForcibly(); child.waitFor(); throw new AssertionError("rollback preflight hung");
        }
        check(child.exitValue() == expected, "rollback changed recovery classification: " + jar);
    }
    private static Map<String, byte[]> snapshot(Path root) throws Exception {
        Map<String, byte[]> result = new TreeMap<String, byte[]>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) result.put(file.getFileName().toString(), Files.readAllBytes(file));
        }
        return result;
    }
    private static void unchanged(Path root, Map<String, byte[]> before) throws Exception {
        Map<String, byte[]> after = snapshot(root);
        check(before.keySet().equals(after.keySet()), "rollback added/deleted state files");
        for (String name : before.keySet())
            check(Arrays.equals(before.get(name), after.get(name)), "rollback changed evidence: " + name);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("candidate.jar compatible-baseline.jar required");
        Path candidate = Paths.get(args[0]), baseline = Paths.get(args[1]);
        Path root = Files.createTempDirectory("fx-rollback-local-").toRealPath();
        Path journal = root.resolve("transactions.journal"), marker = root.resolve("operation.marker");
        try (TransactionJournal state = new TransactionJournal(journal, marker)) {
            state.begin("AB1234567890123456");
        }
        Properties properties = new Properties();
        properties.setProperty("station.state.directory", root.toString());
        properties.setProperty("transaction.journal", journal.toString());
        properties.setProperty("recovery.marker", marker.toString());
        Path config = root.resolve("station.properties");
        try (OutputStream stream = Files.newOutputStream(config)) { properties.store(stream, "LOCAL TEST ONLY"); }
        Files.write(root.resolve("restart.count"), "2\n".getBytes("US-ASCII"));
        Files.write(root.resolve("supervisor.status"), "STOPPED\n".getBytes("US-ASCII"));
        preflight(candidate, config, 21); // Establish binding/owner file, never an RFID session.
        for (String latch : Arrays.asList("", "sdk-session.pending", "hung.latch")) {
            if (!latch.isEmpty()) Files.write(root.resolve(latch), "PRESERVE_TEST_FENCE\n".getBytes("US-ASCII"));
            Map<String, byte[]> before = snapshot(root);
            for (Path jar : Arrays.asList(baseline, candidate, baseline)) {
                preflight(jar, config, latch.isEmpty() ? 21 : 24);
                unchanged(root, before);
            }
        }
        System.out.println("Packaged rollback simulation PASS (unresolved journal, binding, budget, session/hung fences unchanged)");
    }
}
