/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Entity ownership follows the vehicle tree across Folia region boundaries. */
final class OwnerScheduler {
    private final Plugin plugin;
    final boolean folia;
    OwnerScheduler(Plugin plugin) {
        this.plugin = plugin;
        boolean found;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); found = true; }
        catch (ClassNotFoundException absent) { found = false; }
        folia = found;
    }
    void next(Entity owner, final Runnable task, Runnable retired) {
        if (!folia) { Bukkit.getScheduler().runTaskLater(plugin, task, 1L); return; }
        try {
            Object scheduler = Reflect.call(owner, "getScheduler");
            Consumer<Object> callback = new Consumer<Object>() { public void accept(Object ignored) { task.run(); } };
            if (Reflect.call(scheduler, "runDelayed", plugin, callback, retired, 1L) == null && retired != null)
                retired.run();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Folia entity scheduler unavailable", e); }
    }
    boolean owns(Entity entity) {
        if (!folia) return true;
        try {
            for (Method m : Bukkit.class.getMethods()) if (m.getName().equals("isOwnedByCurrentRegion")
                    && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInstance(entity))
                return Boolean.TRUE.equals(Reflect.call(m, (Object)null, entity));
            throw new NoSuchMethodException("Folia entity ownership check");
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
