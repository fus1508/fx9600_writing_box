package org.fus;

import org.fus.api.StatusServer;
import org.fus.health.DeviceHealth;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Local Java observable event -> HTTP body. Does NOT measure physical detection or ESP32 rendering. */
public final class HealthLatencyCheck {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length == 0 ? "target/phase3-evidence" : args[0]);
        Files.createDirectories(output);
        DeviceHealth health = new DeviceHealth(() -> 0L);
        health.readerConnected(); health.readerProgress(); health.applicationProgress();
        health.scannerObservation(true, true, "FAKE_NODE", "");
        long[] samples = new long[300];
        List<String> rows = new ArrayList<String>(); rows.add("sample,event_to_http_us");
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (StatusServer server = new StatusServer("127.0.0.1", 0, health)) {
            server.start();
            String previous = HealthApiCheck.get(server.port(), "/status");
            for (int i = 0; i < samples.length; i++) {
                final String revision = HealthApiCheck.revision(previous);
                Future<String> response = client.submit(() -> HealthApiCheck.get(server.port(), "/status/wait?since=" + revision));
                Thread.sleep(2);
                String marker = "SAMPLE_" + i;
                long event = System.nanoTime();
                server.status("FAIL", "", "", marker);
                previous = response.get(2, TimeUnit.SECONDS);
                samples[i] = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - event);
                if (!previous.contains(marker)) throw new AssertionError("missed event " + i + ": " + previous);
                rows.add(i + "," + samples[i]);
            }
            server.safety(false, false, "FAKE_TRANSACTION", "HARDWARE_VERIFICATION_REQUIRED");
            server.status("WAIT_TRIGGER", "9K1135600660004039", "394B1135600660004039", "");
            Files.write(output.resolve("java-status-fixture.json"), HealthApiCheck.get(server.port(), "/status").getBytes(StandardCharsets.UTF_8));
        } finally { client.shutdownNow(); }
        Files.write(output.resolve("event-http.csv"), rows, StandardCharsets.UTF_8);
        Arrays.sort(samples);
        String summary = "SIMULATED localhost Java-event-to-HTTP: N=300 missed=0 timeouts=0 p50_us=" + samples[149]
                + " p95_us=" + samples[284] + " max_us=" + samples[299] + "; physical/network-LAN/ESP32-render NOT RUN";
        Files.write(output.resolve("latency-summary.txt"), Collections.singletonList(summary), StandardCharsets.UTF_8);
        System.out.println(summary);
        if (samples[284] > 500000) throw new AssertionError("local event-to-HTTP p95 exceeded 500ms");
    }
}
