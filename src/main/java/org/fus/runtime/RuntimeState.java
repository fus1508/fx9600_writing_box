package org.fus.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persistent runtime evidence is outside releases. Never unlink a lock file. */
public final class RuntimeState {
    private RuntimeState() { }
    public static final class Owner implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private Owner(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    public static Owner lock(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("SYMLINK_LOCK");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("OWNER_ACTIVE");
            return new Owner(channel, lock);
        } catch (Exception e) { channel.close(); throw new IOException("OWNER_ACTIVE_OR_LOCK_UNAVAILABLE", e); }
    }
    public static void create(Path path, String value) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        syncDirectory(path.toAbsolutePath().getParent());
    }
    public static void replace(Path path, String value) throws IOException {
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        create(pending, value);
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(path.toAbsolutePath().getParent());
    }
    public static void syncDirectory(Path path) throws IOException {
        // Windows does not expose directory force through FileChannel. Target Linux must support it.
        if (System.getProperty("os.name").startsWith("Windows")) return;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }
    public static boolean uncertain(Path root) {
        return !Files.notExists(root.resolve("sdk-session.pending")) || !Files.notExists(root.resolve("hung.latch"));
    }
    public static void validatePaths(Path root, Path journal, Path marker) throws IOException {
        if (!root.isAbsolute() || !root.normalize().equals(root.toRealPath())) throw new IOException("STATE_DIRECTORY_MUST_BE_CANONICAL");
        if (!journal.isAbsolute() || !marker.isAbsolute()) throw new IOException("ABSOLUTE_EVIDENCE_PATHS_REQUIRED");
        Path j = journal.toAbsolutePath().normalize(), m = marker.toAbsolutePath().normalize();
        if (!root.equals(j.getParent()) || !root.equals(m.getParent()) || j.equals(m)
                || Files.isSymbolicLink(j) || Files.isSymbolicLink(m)) throw new IOException("STATE_PATH_INVALID");
        Set<Path> reserved = reservedPaths(root), journalFiles = journalFiles(j);
        if (reserved.contains(m) || journalFiles.contains(m)) throw new IOException("EVIDENCE_RUNTIME_PATH_COLLISION");
        for (Path file : journalFiles) if (reserved.contains(file)) throw new IOException("EVIDENCE_RUNTIME_PATH_COLLISION");
    }
    private static Set<Path> reservedPaths(Path root) {
        Set<Path> paths = new HashSet<Path>();
        for (String name : Arrays.asList("supervisor.lock", "station-owner.lock", "control.lock", "state.binding",
                "state.binding.pending", "stop.request", "supervisor.status", "supervisor.status.pending",
                "sdk-session.pending", "hung.latch", "restart.count", "restart.count.pending", "restart.exhausted"))
            paths.add(root.resolve(name));
        return paths;
    }
    private static Set<Path> journalFiles(Path journal) {
        Set<Path> paths = new HashSet<Path>();
        for (String suffix : Arrays.asList("", ".head", ".head.pending", ".fault"))
            paths.add(journal.resolveSibling(journal.getFileName().toString() + suffix));
        return paths;
    }
    public static void validateConfig(Path root, Path config, Path journal, Path marker) throws IOException {
        validatePaths(root, journal, marker);
        Path file = config.toAbsolutePath().normalize();
        if (!file.equals(file.toRealPath()) || reservedPaths(root).contains(file)
                || journalFiles(journal.toAbsolutePath().normalize()).contains(file)
                || marker.toAbsolutePath().normalize().equals(file)) throw new IOException("CONFIG_STATE_PATH_COLLISION");
    }
    public static void bind(Path root, Path journal, Path marker) throws IOException {
        validatePaths(root, journal, marker);
        Path j = journal.toAbsolutePath().normalize(), m = marker.toAbsolutePath().normalize();
        String value = "FX-STATE-1\n" + j + "\n" + m + "\n";
        Path binding = root.resolve("state.binding");
        if (Files.notExists(binding)) create(binding, value);
        if (Files.isSymbolicLink(binding) || !value.equals(new String(Files.readAllBytes(binding), StandardCharsets.UTF_8)))
            throw new IOException("STATE_BINDING_CHANGED");
    }
}
