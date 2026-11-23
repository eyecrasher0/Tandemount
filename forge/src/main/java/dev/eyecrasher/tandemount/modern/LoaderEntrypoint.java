package dev.eyecrasher.tandemount.modern;

import net.minecraftforge.fml.common.Mod;

@Mod("tandemount")
public final class LoaderEntrypoint {
    public LoaderEntrypoint() {
        LoaderCompatibility.initialize();
        TandemRuntime.initialize();
    }
}
