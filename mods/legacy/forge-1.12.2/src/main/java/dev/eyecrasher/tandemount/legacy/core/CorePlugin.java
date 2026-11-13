/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy.core;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;
@IFMLLoadingPlugin.Name("Tandemount server hooks")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.eyecrasher.tandemount.legacy.core."})
public final class CorePlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() { return new String[]{SeatTransformer.class.getName()}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String,Object> data) {}
    public String getAccessTransformerClass() { return null; }
}
