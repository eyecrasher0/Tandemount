/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.List;

final class Settings {
    static final double STEP = 1.0 / 16.0;
    static final double DEFAULT_VERTICAL = 1.375;
    static final double DEFAULT_REARWARD = 0.6875;
    final boolean enabled, debug;
    final double vertical, rearward;

    Settings(boolean enabled, double vertical, double rearward, boolean debug) {
        this.enabled = enabled;
        this.vertical = bounded("vertical", vertical, 0.25, 3.0);
        this.rearward = bounded("rearward", rearward, 0.0, 2.0);
        this.debug = debug;
    }

    static Settings read(FileConfiguration config) {
        List<String> types = config.getStringList("supported-horse-types");
        if (types.isEmpty() || types.size() != 1 || !"HORSE".equals(types.get(0)))
            throw new IllegalArgumentException("supported-horse-types must contain only HORSE");
        return new Settings(config.getBoolean("enabled", true), number(config, "vertical"),
                number(config, "rearward"), config.getBoolean("debug", false));
    }

    private static double number(FileConfiguration config, String name) {
        Object value = config.get(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " must be a number");
        return ((Number) value).doubleValue();
    }

    static double bounded(String name, double value, double minimum, double maximum) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " must be finite and between " + minimum + " and " + maximum);
        return value;
    }

    Settings coordinate(String name, double value) {
        if ("vertical".equals(name)) return new Settings(enabled, value, rearward, debug);
        if ("rearward".equals(name)) return new Settings(enabled, vertical, value, debug);
        throw new IllegalArgumentException("Choose vertical or rearward");
    }

    void write(FileConfiguration config) {
        config.set("enabled", enabled);
        config.set("vertical", vertical);
        config.set("rearward", rearward);
        config.set("debug", debug);
        config.set("supported-horse-types", java.util.Collections.singletonList("HORSE"));
    }
}
