/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.entity.Player;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Preserves native tracking and orders the attachment's native metadata. */
final class PacketBridge {
    private final TandemountPlugin plugin;
    private final DriverCarrierRotation driverCarrierRotation;
    private final ConcurrentMap<Integer, Integer> seatTypes = new ConcurrentHashMap<Integer, Integer>();
    private final ConcurrentMap<Integer, Object> dimensionRefresh = new ConcurrentHashMap<Integer, Object>();
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<UUID, Session>();
    private static final String HANDLER = "tandemount_seat";

    private static final class Session {
        final Player player;
        final Object pipeline, handler;
        volatile boolean ready;
        Session(Player player, Object pipeline, Object handler) {
            this.player = player;
            this.pipeline = pipeline;
            this.handler = handler;
        }
    }

    PacketBridge(TandemountPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        driverCarrierRotation = "26.3".equals(plugin.nativeAccess.minecraft) && "Paper".equals(org.bukkit.Bukkit.getName())
                ? new DriverCarrierRotation() : null;
    }
    void removeSeat(final int seat) {
        final List<Session> active = new ArrayList<Session>(sessions.values());
        final AtomicInteger pending = new AtomicInteger(active.size() + 1);
        final Runnable retired = new Runnable() { public void run() {
            if (pending.decrementAndGet() == 0) seatTypes.remove(seat);
        }};
        // Native removal may have already queued mount packets on Netty. Keep
        // filtering until each channel has consumed its preceding writes.
        for (Session session : active) try {
            Object loop = Reflect.call(Reflect.call(session.pipeline, "channel"), "eventLoop");
            Reflect.call(loop, "execute", retired);
        } catch (ReflectiveOperationException disconnected) { retired.run(); }
        retired.run();
    }
    void addSeat(int seat, int type) { seatTypes.put(seat, type); }
    void refreshDimensions(int entity, Object posePacket) { dimensionRefresh.put(entity, posePacket); }
    boolean hasDimensionRefresh(int entity) { return dimensionRefresh.containsKey(entity); }
    void trackCarrierRotation(UUID driver, int first, int second) {
        if (driverCarrierRotation == null) return;
        driverCarrierRotation.track(driver, first);
        driverCarrierRotation.track(driver, second);
    }
    void removeHelper(int entity) {
        dimensionRefresh.remove(entity);
        if (driverCarrierRotation != null) driverCarrierRotation.remove(entity);
    }

    synchronized void install(final Player player) throws ReflectiveOperationException {
        final UUID id = player.getUniqueId();
        Session current = sessions.get(id);
        if (current != null && current.player == player) return;
        Object nativePlayer = Reflect.handle(player);
        Object listener = null, connection = null, channel = null;
        for (Field f : Reflect.fields(nativePlayer.getClass())) {
            String name = f.getType().getSimpleName();
            if (name.equals("PlayerConnection") || name.equals("ServerGamePacketListenerImpl")) {
                listener = Reflect.get(f, nativePlayer); if (listener != null) break;
            }
        }
        if (listener == null) throw new NoSuchFieldException("Player packet listener");
        for (Field f : Reflect.fields(listener.getClass())) {
            String name = f.getType().getSimpleName();
            if (name.equals("NetworkManager") || name.equals("Connection")) {
                connection = Reflect.get(f, listener); if (connection != null) break;
            }
        }
        if (connection == null) throw new NoSuchFieldException("Player network connection");
        for (Field f : Reflect.fields(connection.getClass()))
            if (f.getType().getName().endsWith(".channel.Channel")) { channel = Reflect.get(f, connection); break; }
        if (channel == null) throw new NoSuchFieldException("Player Netty channel");
        final Object pipeline = Reflect.call(channel, "pipeline");
        Object loop = Reflect.call(channel, "eventLoop");
        final ClassLoader loader = channel.getClass().getClassLoader();
        final String prefix = channel.getClass().getName().startsWith("net.minecraft.util.") ? "net.minecraft.util.io.netty" : "io.netty";
        final Class<?> outbound = Class.forName(prefix + ".channel.ChannelOutboundHandler", true, loader);
        final Object handler = Proxy.newProxyInstance(loader, new Class<?>[] {outbound}, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.equals("toString")) return "Tandemount seat mount filter";
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return proxy == args[0];
                if (name.equals("handlerAdded") || name.equals("handlerRemoved")) return null;
                if (name.equals("exceptionCaught")) { Reflect.call(args[0], "fireExceptionCaught", args[1]); return null; }
                Object[] forwarded = Arrays.copyOfRange(args, 1, args.length);
                if (name.equals("write")) {
                    try {
                        forwarded[0] = filter(id, forwarded[0]);
                        Object refresh = dimensionRefresh(forwarded[0]);
                        if (refresh != null) {
                            // This handler may run after vanilla's bundle
                            // unpacker. Forward two native packets in order,
                            // rather than introducing an unencodable bundle.
                            Reflect.call(args[0], "write", forwarded[0], Reflect.call(args[0], "newPromise"));
                            forwarded[0] = refresh;
                        }
                    }
                    catch (Throwable failure) {
                        // Never send the wrong mount graph: stop this ride on its owner.
                        plugin.packetFailure(failure);
                        Object promise = args[2];
                        Reflect.call(promise, "tryFailure", failure);
                        return null;
                    }
                }
                Reflect.call(args[0], name, forwarded);
                return null;
            }
        });
        final Session session = new Session(player, pipeline, handler);
        Session previous = sessions.put(id, session);
        if (previous != null) uninstall(previous);
        Reflect.call(loop, "execute", new Runnable() {
            public void run() {
                try {
                    if (sessions.get(id) != session) return;
                    @SuppressWarnings("unchecked") List<String> names = (List<String>) Reflect.call(pipeline, "names");
                    // Outbound events flow from the pipeline tail toward its head.
                    // Install after the encoder so this handler receives packet
                    // objects before encoding, including initial tracking packets.
                    if (names.contains("encoder")) Reflect.call(pipeline, "addAfter", "encoder", HANDLER, handler);
                    else if (names.contains("packet_handler")) Reflect.call(pipeline, "addBefore", "packet_handler", HANDLER, handler);
                    else throw new IllegalStateException("No upstream packet encoder in " + names);
                    session.ready = true;
                } catch (Throwable e) { plugin.packetFailure(e); }
            }
        });
    }

    boolean installed(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.player == player && session.ready;
    }

    private Object filter(UUID viewer, Object packet) throws ReflectiveOperationException {
        if (seatTypes.isEmpty()) return packet;
        String name = packet.getClass().getSimpleName();
        if (name.equals("PacketPlayOutSpawnEntityLiving")) {
            Field id = packet.getClass().getDeclaredField("a"); id.setAccessible(true);
            Integer type = seatTypes.get(id.getInt(packet));
            if (type != null && type.intValue() >= 0) {
                // Class-based vanilla registries predate EntityType. The original
                // subclass is intentionally unregistered; retain the upstream
                // spawn packet and supply its existing vanilla base type ID.
                boolean uuid = false;
                for (Field f : packet.getClass().getDeclaredFields()) if (f.getType() == UUID.class) uuid = true;
                Field species = packet.getClass().getDeclaredField(uuid ? "c" : "b");
                species.setAccessible(true); species.setInt(packet, type.intValue());
            }
            return packet;
        }
        if (name.equals("ClientboundBundlePacket") || name.equals("PacketPlayOutBundle")) {
            for (Method m : packet.getClass().getMethods()) if (m.getParameterTypes().length == 0
                    && Iterable.class.isAssignableFrom(m.getReturnType())) {
                List<Object> changed = new ArrayList<Object>();
                for (Object nested : (Iterable<?>)Reflect.call(m, packet)) {
                    Object filtered = filter(viewer, nested);
                    if (filtered.getClass().getSimpleName().equals("ClientboundBundlePacket")) {
                        boolean expanded = false;
                        for (Method contents : filtered.getClass().getMethods()) if (contents.getParameterTypes().length == 0
                                && Iterable.class.isAssignableFrom(contents.getReturnType())) {
                            for (Object value : (Iterable<?>)Reflect.call(contents, filtered)) changed.add(value);
                            expanded = true; break;
                        }
                        if (!expanded) throw new NoSuchMethodException("Native bundle contents");
                    } else {
                        changed.add(filtered);
                        Object refresh = dimensionRefresh(filtered);
                        if (refresh != null) changed.add(refresh);
                    }
                }
                for (Constructor<?> c : packet.getClass().getDeclaredConstructors())
                    if (c.getParameterTypes().length == 1 && c.getParameterTypes()[0].isInstance(changed)) {
                        c.setAccessible(true); return c.newInstance(changed);
                    }
                throw new NoSuchMethodException("Native bundle packet constructor");
            }
        }
        return driverCarrierRotation == null ? packet : driverCarrierRotation.shorten(viewer, packet);
    }

    private Object dimensionRefresh(Object packet) throws ReflectiveOperationException {
        if (dimensionRefresh.isEmpty()) return null;
        String name = packet.getClass().getSimpleName();
        if (!name.equals("PacketPlayOutEntityMetadata") && !name.equals("ClientboundSetEntityDataPacket")) return null;
        for (Field field : packet.getClass().getDeclaredFields())
            if (field.getType() == int.class && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true); return dimensionRefresh.get(field.getInt(packet));
            }
        throw new NoSuchFieldException("Native entity metadata identifier");
    }

    synchronized void uninstall(Player player) {
        UUID id = player.getUniqueId();
        Session session = sessions.get(id);
        if (session != null && session.player == player && sessions.remove(id, session)) uninstall(session);
    }
    private void uninstall(Session session) {
        final Object pipeline = session.pipeline;
        try {
            Object channel = Reflect.call(pipeline, "channel");
            Object loop = Reflect.call(channel, "eventLoop");
            Reflect.call(loop, "execute", new Runnable() {
                public void run() {
                    try { if (Reflect.call(pipeline, "get", HANDLER) == session.handler) Reflect.call(pipeline, "remove", HANDLER); }
                    catch (ReflectiveOperationException e) { plugin.debug("Could not remove a disconnected network filter", e); }
                }
            });
        } catch (ReflectiveOperationException e) { plugin.debug("Could not reach a disconnected network filter", e); }
    }
    synchronized void close() {
        List<Session> retired = new ArrayList<Session>(sessions.values());
        sessions.clear();
        for (Session session : retired) uninstall(session);
        seatTypes.clear();
        dimensionRefresh.clear();
        if (driverCarrierRotation != null) driverCarrierRotation.clear();
    }

}
