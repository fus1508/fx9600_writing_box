package org.fus;

import org.fus.runtime.ProcessSupervisor;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.*;

public final class StopControlCheck {
    public static void main(String[] args) throws Exception {
        for (String mode : Arrays.asList("--stop", "--stop-state")) {
            Path root = Files.createTempDirectory("fx-stop-config-").toRealPath();
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = Arrays.asList(java, "-cp", System.getProperty("java.class.path"),
                    ProcessSupervisorCheck.class.getName(), "child", root.toString(), "wait");
            final int[] result = { -1 };
            Thread supervisor = new Thread(() -> {
                try { result[0] = new ProcessSupervisor(root, command, 2, 80, 160, 1000).run(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }); supervisor.start();
            int exit = -1;
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(root.resolve("ready")) && System.nanoTime() < until) Thread.sleep(20);
                if (!Files.exists(root.resolve("ready"))) throw new AssertionError("stop child not ready");
                Properties p = new Properties(); p.setProperty("station.state.directory", root.toString()); p.setProperty("status.port", "0");
                Path config = root.resolve("invalid.properties"); try (OutputStream out = Files.newOutputStream(config)) { p.store(out, "test"); }
                Process stop = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                        mode, "--stop".equals(mode) ? config.toString() : root.toString()).inheritIO().start();
                if (!stop.waitFor(10, TimeUnit.SECONDS)) { stop.destroyForcibly(); stop.waitFor(); throw new AssertionError("stop command stuck"); }
                exit = stop.exitValue();
            } finally { ProcessSupervisor.stop(root, 5000); supervisor.join(5000); }
            if (exit != 0 || supervisor.isAlive() || result[0] != 0) throw new AssertionError("stop blocked by invalid operational config: " + mode + "/" + exit);
        }
        System.out.println("Stop control checks passed (invalid operational config, canonical state-only stop)");
    }
}
