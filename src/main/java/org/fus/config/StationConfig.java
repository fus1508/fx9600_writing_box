package org.fus.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/** Configuration contains no credentials; RFID password is supplied via environment. */
public final class StationConfig {
    public final String device, host, statusHost, recoveryMarker;
    public final int port, timeoutMs, reconnectMs, debounceMs, validitySeconds, statusPort, operationTimeoutMs;
    public final long password;
    public StationConfig(String file) throws IOException {
        Properties p = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { p.load(input); }
        device = text(p, "scanner.device", "/dev/ttyACM0");
        host = text(p, "reader.host", "127.0.0.1");
        statusHost = text(p, "status.bind", "0.0.0.0");
        statusPort = number(p, "status.port", 8081, 1, 65535);
        recoveryMarker = text(p, "recovery.marker", "rfid-operation.marker");
        operationTimeoutMs = number(p, "operation.timeout.ms", 30000, 10000, 300000);
        port = number(p, "reader.port", 5084, 1, 65535);
        timeoutMs = number(p, "reader.timeout.ms", 5000, 100, 60000);
        reconnectMs = number(p, "reconnect.ms", 3000, 1000, 60000);
        debounceMs = number(p, "gpi.debounce.ms", 150, 1, 5000);
        validitySeconds = number(p, "barcode.validity.seconds", 60, 1, 3600);
        String secret = System.getenv("FX9600_ACCESS_PASSWORD");
        if (secret == null) secret = "00000000";
        if (secret == null || !secret.matches("[0-9A-Fa-f]{8}"))
            throw new IllegalArgumentException("FX9600_ACCESS_PASSWORD must be eight hexadecimal digits");
        password = Long.parseLong(secret, 16);
    }
    private static String text(Properties p, String key, String fallback) {
        String value = p.getProperty(key, fallback).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing configuration: " + key);
        return value;
    }
    private static int number(Properties p, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(text(p, key, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("Invalid configuration: " + key);
        return value;
    }
}
