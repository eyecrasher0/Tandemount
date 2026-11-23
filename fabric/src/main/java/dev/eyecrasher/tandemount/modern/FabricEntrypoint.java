package dev.eyecrasher.tandemount.modern;

/** Fabric's real server-only main entrypoint. */
public final class FabricEntrypoint implements net.fabricmc.api.ModInitializer {
    @Override public void onInitialize() {
        TandemRuntime.initialize();
    }
}
