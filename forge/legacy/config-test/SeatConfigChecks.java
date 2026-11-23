/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher
 * Test only: settings boundaries without starting a game server. */
package dev.eyecrasher.tandemount.legacy;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class SeatConfigChecks {
    private static int checks;
    private static void check(boolean passed, String name) {
        if (!passed) throw new AssertionError(name);
        checks++;
    }
    private static void rejected(Path path, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        Files.write(path, bytes);
        boolean refused = false;
        try { SeatConfig.load(path); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused && Arrays.equals(bytes, Files.readAllBytes(path)), "Rejected file must remain byte-for-byte intact: " + text);
    }
    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]);Files.createDirectories(directory);
        Path path = directory.resolve("tandemount.properties");
        SeatConfig fresh = new SeatConfig(new Properties());
        check(fresh.horseFeet && fresh.vertical == .8125 && fresh.rear == .5 && fresh.enabled && !fresh.debug && fresh.excludedDimensions.isEmpty(), "New owner defaults");
        Properties old = new Properties();old.setProperty("vertical", "0.0625");old.setProperty("rear", "0.625");
        SeatConfig saved = new SeatConfig(old);
        check(!saved.horseFeet && saved.vertical == .0625 && saved.rear == .625 && "legacy-mounted-offset".equals(saved.properties.getProperty("vertical-origin")), "Preserve old saved values and units");
        saved.save(path);SeatConfig migrated = SeatConfig.load(path);
        check(!migrated.horseFeet && migrated.vertical == .0625 && migrated.rear == .625, "Migration survives disk round trip");
        old.remove("vertical");old.setProperty("vertical-origin", "legacy-mounted-offset");
        check(new SeatConfig(old).horseFeet && new SeatConfig(old).vertical == .8125, "Missing vertical selects new units");
        check(saved.defaults().horseFeet && saved.defaults().vertical == .8125 && saved.defaults().rear == .5, "Reset owner defaults");
        SeatConfig excluded = fresh.change("excluded-dimensions", "1, -1, 0,2147483647,-2147483648");
        check(excluded.excludedDimensions.size() == 5 && excluded.excludedDimensions.contains(-1), "Signed Forge dimension IDs");
        check(fresh.change("vertical", "-1").vertical == -1 && fresh.change("vertical", "2").vertical == 2 && fresh.change("rear", "0.25").rear == .25 && fresh.change("rear", "2").rear == 2, "Inclusive seat bounds");
        for (String value : new String[]{"NaN", "Infinity", "-Infinity", "1e300", "-1.0625", "2.0625", "0.03", "abc", ""}) rejected(path, "vertical=" + value + "\n");
        for (String value : new String[]{"0.1875", "2.0625", "NaN", "true"}) rejected(path, "rear=" + value + "\n");
        for (String value : new String[]{"maybe", "1", "TRUE", ""}) { rejected(path, "enabled=" + value + "\n");rejected(path, "debug=" + value + "\n"); }
        for (String value : new String[]{"overworld", "minecraft:overworld", "1.5", "1,,2", "1,", ",1", "1,1", "2147483648", "+1", "01", "null"}) rejected(path, "excluded-dimensions=" + value + "\n");
        rejected(path, "debug=true\ndebug=false\n");rejected(path, "types=donkey\n");rejected(path, "enabled=true\nverticl=1\n");
        rejected(path, "vertical-origin=unknown\nvertical=0.8125\n");rejected(path, "debug=\\uXXXX\n");
        System.out.println("[TandemountConfig] passed=" + checks);
    }
}
