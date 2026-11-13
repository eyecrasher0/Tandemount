package dev.eyecrasher.tandemount.modern;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Opt-in fixture listeners on the loader's real cancellation event bus. */
final class NativeCancellation implements AutoCloseable {
    private final Object bus;
    private final Consumer<Object> listener;
    private final int[] calls;
    private NativeCancellation(Object bus, Consumer<Object> listener, int[] calls) { this.bus = bus; this.listener = listener; this.calls = calls; }
    boolean observed() { return calls[0] > 0; }
    @SuppressWarnings({"unchecked", "rawtypes"}) static NativeCancellation listen(Object player, boolean interaction, final boolean mounting) throws Exception {
        String platform = Names.TABLE.getProperty("platform");
        if (platform.equals("fabric")) return null;
        boolean forge = platform.equals("forge") || Names.TABLE.getProperty("minecraft").equals("1.20.1");
        String prefix = forge ? "net.minecraftforge" : "net.neoforged.neoforge";
        Class<?> event = Class.forName(prefix + (interaction ? ".event.entity.player.PlayerInteractEvent$EntityInteract" : ".event.entity.EntityMountEvent"));
        Object bus = Class.forName(prefix + (forge ? ".common.MinecraftForge" : ".common.NeoForge")).getField("EVENT_BUS").get(null);
        final int[] calls = {0};
        Consumer<Object> listener = value -> {
            try {
                Object actor = value.getClass().getMethod(interaction ? "getEntity" : "getEntityMounting").invoke(value);
                if (actor != player) return;
                if (!interaction && !Boolean.valueOf(mounting).equals(value.getClass().getMethod("isMounting").invoke(value))) return;
                calls[0]++;
                value.getClass().getMethod("setCanceled", boolean.class).invoke(value, true);
            } catch (Exception failure) { throw new IllegalStateException("Native cancellation fixture listener failed", failure); }
        };
        for (Method method : bus.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("addListener") && types.length == 4 && types[0].isEnum() && types[1] == boolean.class && types[2] == Class.class && Consumer.class.isAssignableFrom(types[3])) {
                Object priority = Enum.valueOf((Class<? extends Enum>) types[0], "NORMAL");
                method.invoke(bus, priority, false, event, listener);
                return new NativeCancellation(bus, listener, calls);
            }
        }
        throw new NoSuchMethodException("Native event bus typed listener API");
    }
    public void close() throws Exception { bus.getClass().getMethod("unregister", Object.class).invoke(bus, listener); }
}
