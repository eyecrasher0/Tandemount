package dev.eyecrasher.tandemount.modern;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Bounded, immediately saved settings. Unknown keys survive updates. */
public final class Settings {
    public static final double DEFAULT_HEIGHT = 0.8125;
    public static final double DEFAULT_REAR = 0.5;
    public boolean enabled = true;
    public boolean debug = false;
    public double height = DEFAULT_HEIGHT;
    public double rear = DEFAULT_REAR;
    public final Path file;
    private Properties properties = new Properties();
    public Settings() { this(Paths.get(System.getProperty("tandemount.config", "config/tandemount.properties"))); }
    Settings(Path file) { this.file = file; }
    public void load() throws Exception {
        if (!Files.exists(file)) {
            enabled = true; debug = false; height = DEFAULT_HEIGHT; rear = DEFAULT_REAR;
            properties = new Properties(); save(); return;
        }
        Properties fresh = new Properties();
        try (InputStream input = Files.newInputStream(file)) { fresh.load(input); }
        boolean nextEnabled = bool(fresh, "enabled", true);
        boolean nextDebug = bool(fresh, "debug", false);
        double nextHeight = bounded(fresh.getProperty("height", Double.toString(DEFAULT_HEIGHT)), 0.0, 3.0);
        double nextRear = bounded(fresh.getProperty("rear", Double.toString(DEFAULT_REAR)), 0.0, 2.0);
        String horseTypes = fresh.getProperty("horse-types", "horse");
        if (!horseTypes.equals("horse")) throw new IllegalArgumentException("Only the verified horse type 'horse' is supported");
        enabled = nextEnabled; debug = nextDebug; height = nextHeight; rear = nextRear;
        properties = fresh;
    }
    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, Boolean.toString(fallback));
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean for " + key);
        return Boolean.parseBoolean(value);
    }
    public static double bounded(String value, double minimum, double maximum) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number) || number < minimum || number > maximum) throw new IllegalArgumentException("Value must be finite and in " + minimum + ".." + maximum);
        return number;
    }
    public void save() throws Exception {
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("debug", Boolean.toString(debug));
        properties.setProperty("height", Double.toString(height));
        properties.setProperty("rear", Double.toString(rear));
        properties.setProperty("horse-types", "horse");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temp)) { properties.store(output, "Tandemount server settings; height/rear use blocks. Only ordinary horses have verified behavior."); }
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
