package dev.eyecrasher.tandemount.modern;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Opt-in fixture listeners on the loader's real cancellation event bus. */
final class NativeCancellation implements AutoCloseable {
    private final Object bus;
    private final Object listener;
    private final Method unregister;
    private final int[] calls;
    private NativeCancellation(Object bus, Object listener, Method unregister, int[] calls) { this.bus = bus; this.listener = listener; this.unregister = unregister; this.calls = calls; }
    boolean observed() { return calls[0] > 0; }
    private static boolean matches(Object value, Object player, boolean interaction, boolean mounting) throws Exception {
        Object actor = value.getClass().getMethod(interaction ? "getEntity" : "getEntityMounting").invoke(value);
        return actor == player && (interaction || Boolean.valueOf(mounting).equals(value.getClass().getMethod("isMounting").invoke(value)));
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) static NativeCancellation listen(Object player, boolean interaction, final boolean mounting) throws Exception {
        String platform = Names.TABLE.getProperty("platform");
        if (platform.equals("fabric")) return null;
        boolean forge = platform.equals("forge") || Names.TABLE.getProperty("minecraft").equals("1.20.1");
        String prefix = forge ? "net.minecraftforge" : "net.neoforged.neoforge";
        Class<?> event;
        try { event = Class.forName(prefix + (interaction ? ".event.entity.player.PlayerInteractEvent$EntityInteract" : ".event.entity.EntityMountEvent")); }
        catch (ClassNotFoundException absentLegacyInteraction) {
            if (!forge || !interaction) throw absentLegacyInteraction;
            // Forge 26.3's native Player.interactOn posts this replacement event.
            event = Class.forName(prefix + ".event.entity.player.PlayerInteractEvent$EntityInteractSpecific");
        }
        final int[] calls = {0};
        if (forge) {
            Object typedBus = null;
            try { typedBus = event.getDeclaredField("BUS").get(null); }
            catch (NoSuchFieldException legacyEventBus) { /* Older Forge uses MinecraftForge.EVENT_BUS. */ }
            if (typedBus != null) {
                // EventBus 7 keeps cancellation in its dispatch, including for
                // record events. A matching Predicate must return true to veto.
                Class<?> cancellableBus = Class.forName("net.minecraftforge.eventbus.api.bus.CancellableEventBus");
                if (!cancellableBus.isInstance(typedBus)) throw new IllegalStateException("Native cancellation fixture event is not cancellable");
                Predicate<Object> listener = value -> {
                    try {
                        if (!matches(value, player, interaction, mounting)) return false;
                        calls[0]++;
                        return true;
                    } catch (Exception failure) { throw new IllegalStateException("Native cancellation fixture listener failed", failure); }
                };
                Object handle = cancellableBus.getMethod("addListener", Predicate.class).invoke(typedBus, listener);
                Method remove = Class.forName("net.minecraftforge.eventbus.api.bus.EventBus").getMethod("removeListener", Class.forName("net.minecraftforge.eventbus.api.listener.EventListener"));
                return new NativeCancellation(typedBus, handle, remove, calls);
            }
        }
        Object bus = Class.forName(prefix + (forge ? ".common.MinecraftForge" : ".common.NeoForge")).getField("EVENT_BUS").get(null);
        Consumer<Object> listener = value -> {
            try {
                if (!matches(value, player, interaction, mounting)) return;
                calls[0]++;
                value.getClass().getMethod("setCanceled", boolean.class).invoke(value, true);
            } catch (Exception failure) { throw new IllegalStateException("Native cancellation fixture listener failed", failure); }
        };
        for (Method method : bus.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("addListener") && types.length == 4 && types[0].isEnum() && types[1] == boolean.class && types[2] == Class.class && Consumer.class.isAssignableFrom(types[3])) {
                Object priority = Enum.valueOf((Class<? extends Enum>) types[0], "NORMAL");
                method.invoke(bus, priority, false, event, listener);
                return new NativeCancellation(bus, listener, bus.getClass().getMethod("unregister", Object.class), calls);
            }
        }
        throw new NoSuchMethodException("Native event bus typed listener API");
    }
    public void close() throws Exception { unregister.invoke(bus, listener); }
}
