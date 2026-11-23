/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import java.io.File;
@Mod(modid="tandemount",name="Tandemount",version="@VERSION@",acceptedMinecraftVersions="[1.8.9]",acceptableRemoteVersions="*",serverSideOnly=true)
public final class TandemountMod {
    @Mod.EventHandler public void start(FMLServerStartingEvent event) { LegacyRuntime.start(event,new File("config")); }
    @Mod.EventHandler public void stop(FMLServerStoppingEvent event) { LegacyRuntime.stop(); }
}
