/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class SeatConfig {
    final boolean enabled, debug, horseFeet;
    final double vertical, rear;
    final Set<Integer> excludedDimensions;
    final Properties properties;
    private static final Set<String> KEYS = new HashSet<String>(Arrays.asList(
            "enabled", "debug", "vertical", "vertical-origin", "rear", "types", "excluded-dimensions"));
    SeatConfig(Properties input) {
        for (String key : input.stringPropertyNames()) if (!KEYS.contains(key))
            throw new IllegalArgumentException("Unknown Tandemount setting: " + key);
        properties = new Properties(); properties.putAll(input);
        enabled = flag(input.getProperty("enabled", "true"));
        debug = flag(input.getProperty("debug", "false"));
        // Earlier Tandemount files expressed vertical relative to the horse's
        // mounted offset. Keep an explicitly saved value in those units. A new
        // or missing value uses rider feet above horse feet instead.
        String origin = input.getProperty("vertical-origin", input.containsKey("vertical") ? "legacy-mounted-offset" : "horse-feet");
        if (!input.containsKey("vertical")) origin = "horse-feet";
        if (!origin.equals("horse-feet") && !origin.equals("legacy-mounted-offset"))
            throw new IllegalArgumentException("vertical-origin must be horse-feet or legacy-mounted-offset.");
        horseFeet = origin.equals("horse-feet");
        vertical = offset(input.getProperty("vertical", "0.8125"), -1.0, 2.0);
        rear = offset(input.getProperty("rear", "0.5"), 0.25, 2.0);
        if (!"horse".equals(input.getProperty("types", "horse")))
            throw new IllegalArgumentException("Only ordinary horses are supported on these releases: types=horse.");
        TreeSet<Integer> excluded = new TreeSet<Integer>();
        String dimensions = input.getProperty("excluded-dimensions", "").trim();
        if (!dimensions.isEmpty()) for (String part : dimensions.split(",", -1)) {
            String dimension = part.trim();
            if (!dimension.matches("-?(0|[1-9][0-9]*)"))
                throw new IllegalArgumentException("excluded-dimensions must be a comma-separated list of integer dimension IDs, or empty.");
            try {
                if (!excluded.add(Integer.valueOf(dimension)))
                    throw new IllegalArgumentException("Duplicate excluded dimension: " + dimension);
            } catch (NumberFormatException ex) { throw new IllegalArgumentException("Dimension ID is outside the integer range: " + dimension); }
        }
        excludedDimensions = Collections.unmodifiableSet(excluded);
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("debug", Boolean.toString(debug));
        properties.setProperty("vertical", Double.toString(vertical));
        properties.setProperty("vertical-origin", origin);
        properties.setProperty("rear", Double.toString(rear));
        properties.setProperty("types", "horse");
        properties.setProperty("excluded-dimensions", dimensionList());
    }
    String dimensionList() {
        StringBuilder result = new StringBuilder();
        for (Integer dimension : excludedDimensions) { if (result.length() > 0) result.append(','); result.append(dimension); }
        return result.toString();
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
        Properties p = new Properties() {
            private static final long serialVersionUID = 1L;
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate Tandemount setting: " + key);
                return super.put(key, value);
            }
        };
        if (Files.exists(path)) try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        return new SeatConfig(p);
    }
    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "tandemount-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, "Tandemount; blocks in 1/16 steps; vertical [-1,2], rear [0.25,2]; vertical-origin records preserved units; reset uses horse-feet; excluded-dimensions uses integer Forge dimension IDs");
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
        for (String key : KEYS) p.remove(key);
        return new SeatConfig(p);
    }
}
