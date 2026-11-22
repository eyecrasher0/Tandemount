/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

final class Settings {
    static final double STEP = 1.0 / 16.0;
    static final double DEFAULT_VERTICAL = 0.8125;
    static final double DEFAULT_REARWARD = 0.5;
    private static final List<String> HORSE_TYPES = Collections.singletonList("HORSE");
    final boolean enabled, debug;
    final double vertical, rearward;
    final Set<String> excludedWorlds;

    Settings(boolean enabled, double vertical, double rearward, boolean debug, Set<String> excludedWorlds) {
        this.enabled = enabled;
        this.vertical = bounded("vertical", vertical, 0.25, 3.0);
        this.rearward = bounded("rearward", rearward, 0.0, 2.0);
        this.debug = debug;
        this.excludedWorlds = Collections.unmodifiableSet(new LinkedHashSet<String>(excludedWorlds));
    }

    static Settings read(FileConfiguration config, Set<String> explicitKeys) {
        if (!HORSE_TYPES.equals(value(config, explicitKeys, "supported-horse-types", HORSE_TYPES)))
            throw new IllegalArgumentException("supported-horse-types must contain only HORSE");
        return new Settings(flag(config, explicitKeys, "enabled", true),
                number(config, explicitKeys, "vertical", DEFAULT_VERTICAL),
                number(config, explicitKeys, "rearward", DEFAULT_REARWARD),
                flag(config, explicitKeys, "debug", false),
                exclusions(value(config, explicitKeys, "excluded-worlds", Collections.emptyList())));
    }

    private static Object value(FileConfiguration config, Set<String> explicitKeys, String name, Object fallback) {
        Object value = config.get(name);
        return value != null || explicitKeys.contains(name) ? value : fallback;
    }

    private static boolean flag(FileConfiguration config, Set<String> explicitKeys, String name, boolean fallback) {
        Object value = value(config, explicitKeys, name, fallback);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(name + " must be a boolean");
        return (Boolean)value;
    }

    private static double number(FileConfiguration config, Set<String> explicitKeys, String name, double fallback) {
        Object value = value(config, explicitKeys, name, fallback);
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " must be a number");
        return ((Number)value).doubleValue();
    }

    private static Set<String> exclusions(Object value) {
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("excluded-worlds must be a list of names");
        Set<String> worlds = new LinkedHashSet<String>();
        for (Object entry : (List<?>)value) {
            if (!(entry instanceof String))
                throw new IllegalArgumentException("excluded-worlds must contain only strings");
            String name = (String)entry;
            if (name.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                    || name.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("excluded-worlds names must be nonblank and contain no control characters");
            if (!worlds.add(name)) throw new IllegalArgumentException("excluded-worlds contains a duplicate name");
        }
        return worlds;
    }

    boolean allows(String world) {
        return enabled && !excludedWorlds.contains(world);
    }

    static double bounded(String name, double value, double minimum, double maximum) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " must be finite and between " + minimum + " and " + maximum);
        return value;
    }

    Settings coordinate(String name, double value) {
        if ("vertical".equals(name)) return new Settings(enabled, value, rearward, debug, excludedWorlds);
        if ("rearward".equals(name)) return new Settings(enabled, vertical, value, debug, excludedWorlds);
        throw new IllegalArgumentException("Choose vertical or rearward");
    }

    void write(FileConfiguration config) {
        config.set("enabled", enabled);
        config.set("vertical", vertical);
        config.set("rearward", rearward);
        config.set("debug", debug);
        config.set("supported-horse-types", HORSE_TYPES);
        config.set("excluded-worlds", new ArrayList<String>(excludedWorlds));
    }
}
