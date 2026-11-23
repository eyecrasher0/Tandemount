package dev.eyecrasher.tandemount.modern;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Bounded, immediately saved settings. Unknown keys survive updates. */
public final class Settings {
    public static final double DEFAULT_HEIGHT = 0.8125;
    public static final double DEFAULT_REAR = 0.5;
    public boolean enabled = true;
    public boolean debug = false;
    public double height = DEFAULT_HEIGHT;
    public double rear = DEFAULT_REAR;
    public Set<String> excludedDimensions = Collections.emptySet();
    public final Path file;
    private Properties properties = new Properties();
    public Settings() { this(Paths.get(System.getProperty("tandemount.config", "config/tandemount.properties"))); }
    public Settings(Path file) { this.file = file; }
    public void load() throws Exception {
        if (!Files.exists(file)) {
            enabled = true; debug = false; height = DEFAULT_HEIGHT; rear = DEFAULT_REAR; excludedDimensions = Collections.emptySet();
            properties = new Properties(); save(); return;
        }
        Properties fresh = new Properties();
        validateSyntax(file);
        try (InputStream input = Files.newInputStream(file)) { fresh.load(input); }
        boolean nextEnabled = bool(fresh, "enabled", true);
        boolean nextDebug = bool(fresh, "debug", false);
        double nextHeight = bounded(fresh.getProperty("height", Double.toString(DEFAULT_HEIGHT)), 0.0, 3.0);
        double nextRear = bounded(fresh.getProperty("rear", Double.toString(DEFAULT_REAR)), 0.0, 2.0);
        String horseTypes = fresh.getProperty("horse-types", "horse");
        if (!horseTypes.equals("horse")) throw new IllegalArgumentException("Only the verified horse type 'horse' is supported");
        if (!fresh.getProperty("config-version", "1").equals("1")) throw new IllegalArgumentException("Unsupported config-version; existing height/rear values use absolute blocks");
        Set<String> nextExcluded = exclusions(fresh.getProperty("excluded-dimensions", ""));
        enabled = nextEnabled; debug = nextDebug; height = nextHeight; rear = nextRear;
        excludedDimensions = nextExcluded;
        properties = fresh;
    }
    private static void validateSyntax(Path file) throws Exception {
        Set<String> keys = new LinkedHashSet<String>();
        // The saved format is one key=value per line. Reject silently accepted
        // bare keys and duplicate keys, while retaining ordinary Properties escapes.
        for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) continue;
            int delimiter = -1;
            boolean escaped = false;
            for (int i = 0; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (!escaped && (c == '=' || c == ':')) { delimiter = i; break; }
                if (c == '\\') escaped = !escaped; else escaped = false;
            }
            if (delimiter <= 0 || stripped.endsWith("\\")) throw new IllegalArgumentException("Expected one key=value setting per line");
            Properties entry = new Properties(); entry.load(new java.io.StringReader(stripped));
            for (String key : entry.stringPropertyNames()) if (!keys.add(key)) throw new IllegalArgumentException("Duplicate setting");
        }
    }
    public static Set<String> exclusions(String value) {
        if (value.trim().isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<String>();
        for (String part : value.split(",", -1)) {
            String dimension = part.trim();
            if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || !result.add(dimension)) throw new IllegalArgumentException("Invalid or duplicate dimension identifier");
        }
        return Collections.unmodifiableSet(result);
    }
    Settings copy() {
        Settings next = new Settings(file);
        next.enabled = enabled; next.debug = debug; next.height = height; next.rear = rear;
        next.excludedDimensions = excludedDimensions;
        next.properties = (Properties) properties.clone();
        return next;
    }
    void replace(Settings next) {
        enabled = next.enabled; debug = next.debug; height = next.height; rear = next.rear;
        excludedDimensions = next.excludedDimensions; properties = next.properties;
    }
    String excludedText() { return String.join(",", excludedDimensions); }
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
        properties.setProperty("config-version", "1");
        properties.setProperty("excluded-dimensions", excludedText());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temp)) { properties.store(output, "Tandemount server settings; height/rear use blocks. Only ordinary horses have verified behavior."); }
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
