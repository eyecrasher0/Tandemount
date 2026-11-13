/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

final class SeatConfig {
    final boolean enabled, debug;
    final double vertical, rear;
    final Properties properties;
    SeatConfig(Properties input) {
        properties = new Properties(); properties.putAll(input);
        enabled = flag(input.getProperty("enabled", "true"));
        debug = flag(input.getProperty("debug", "false"));
        vertical = offset(input.getProperty("vertical", "0.0625"), -1.0, 2.0);
        rear = offset(input.getProperty("rear", "0.625"), 0.25, 2.0);
        if (!"horse".equals(input.getProperty("types", "horse")))
            throw new IllegalArgumentException("Only ordinary horses are supported on these releases: types=horse.");
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("debug", Boolean.toString(debug));
        properties.setProperty("vertical", Double.toString(vertical));
        properties.setProperty("rear", Double.toString(rear));
        properties.setProperty("types", "horse");
    }
    static boolean flag(String value) {
        if ("true".equals(value) || "on".equals(value)) return true;
        if ("false".equals(value) || "off".equals(value)) return false;
        throw new IllegalArgumentException("Expected true/false or on/off.");
    }
    static double offset(String value, double min, double max) {
        double n;
        try { n = Double.parseDouble(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Expected a finite numeric offset."); }
        if (!Double.isFinite(n) || n < min || n > max || n * 16.0 != Math.rint(n * 16.0))
            throw new IllegalArgumentException("Offset must be a multiple of 0.0625 in [" + min + ", " + max + "].");
        return n;
    }
    static SeatConfig load(Path path) throws IOException {
        Properties p = new Properties();
        if (Files.exists(path)) try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        return new SeatConfig(p);
    }
    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "tandemount-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, "Tandemount 1.0.0; offsets are blocks in 1/16 steps; vertical [-1,2], rear [0.25,2]");
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    SeatConfig change(String key, String value) {
        Properties p = new Properties(); p.putAll(properties); p.setProperty(key, value);
        return new SeatConfig(p);
    }
    SeatConfig defaults() {
        Properties p = new Properties(); p.putAll(properties);
        for (String key : new String[] {"enabled", "debug", "vertical", "rear", "types"}) p.remove(key);
        return new SeatConfig(p);
    }
}
