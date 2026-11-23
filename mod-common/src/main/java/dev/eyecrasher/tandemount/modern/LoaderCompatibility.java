package dev.eyecrasher.tandemount.modern;

import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Older Forge requires a DisplayTest extension in addition to mod metadata. */
public final class LoaderCompatibility {
    private LoaderCompatibility() {}
    public static void initialize() {
        if (!Names.TABLE.getProperty("platform").equals("forge") && !Names.TABLE.getProperty("minecraft").equals("1.20.1")) return;
        try {
            Class<?> contextType = Class.forName("net.minecraftforge.fml.ModLoadingContext");
            Object context = contextType.getMethod("get").invoke(null);
            Supplier<String> version = () -> "IGNORESERVERONLY";
            BiPredicate<String, Boolean> accept = (remote, server) -> true;
            try {
                Class<?> extension = Class.forName("net.minecraftforge.fml.IExtensionPoint$DisplayTest");
                Object display = extension.getConstructor(Supplier.class, BiPredicate.class).newInstance(version, accept);
                contextType.getMethod("registerExtensionPoint", Class.class, Supplier.class).invoke(context, extension, (Supplier<Object>) () -> display);
            } catch (ClassNotFoundException oldForge) {
                Class<?> extension = Class.forName("net.minecraftforge.fml.ExtensionPoint");
                Object displayPoint = extension.getField("DISPLAYTEST").get(null);
                Class<?> pair = Class.forName("org.apache.commons.lang3.tuple.Pair");
                Object display = pair.getMethod("of", Object.class, Object.class).invoke(null, version, accept);
                contextType.getMethod("registerExtensionPoint", extension, Supplier.class).invoke(context, displayPoint, (Supplier<Object>) () -> display);
            }
        } catch (Exception exception) {
            // Recent Forge uses mods.toml displayTest instead of this legacy API.
            TandemRuntime.log("Using loader metadata for server-only display compatibility");
        }
    }
}
