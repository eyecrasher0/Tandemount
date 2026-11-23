/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.*;
import java.io.File;
@Mod(modid="tandemount",name="Tandemount",version="@VERSION@",acceptedMinecraftVersions="[1.7.10]",acceptableRemoteVersions="*")
public final class TandemountMod {
    @Mod.EventHandler public void start(FMLServerStartingEvent event) { LegacyRuntime.start(event,new File("config")); }
    @Mod.EventHandler public void stop(FMLServerStoppingEvent event) { LegacyRuntime.stop(); }
}
