/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.entity.Player;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Changes one mount edge, never creates a client entity or changes visibility. */
final class PacketBridge {
    private final TandemountPlugin plugin;
    private final ConcurrentMap<Integer, Integer> edges = new ConcurrentHashMap<Integer, Integer>();
    private final ConcurrentMap<Integer, Integer> seatTypes = new ConcurrentHashMap<Integer, Integer>();
    private final ConcurrentMap<UUID, Object> pipelines = new ConcurrentHashMap<UUID, Object>();
    private final ConcurrentMap<Class<?>, MountCodec> codecs = new ConcurrentHashMap<Class<?>, MountCodec>();
    private static final String HANDLER = "tandemount_seat";

    PacketBridge(TandemountPlugin plugin) { this.plugin = plugin; }
    void add(int horse, int seat) { edges.put(horse, seat); }
    void remove(final int horse, final int seat) {
        final List<Object> active = new ArrayList<Object>(pipelines.values());
        final AtomicInteger pending = new AtomicInteger(active.size() + 1);
        final Runnable retired = new Runnable() { public void run() {
            if (pending.decrementAndGet() == 0) { edges.remove(horse, seat); seatTypes.remove(seat); }
        }};
        // Native removal may have already queued mount packets on Netty. Keep
        // filtering until each channel has consumed its preceding writes.
        for (Object pipeline : active) try {
            Object loop = Reflect.call(Reflect.call(pipeline, "channel"), "eventLoop");
            Reflect.call(loop, "execute", retired);
        } catch (ReflectiveOperationException disconnected) { retired.run(); }
        retired.run();
    }
    void addSeat(int seat, int type) { seatTypes.put(seat, type); }

    void install(final Player player) throws ReflectiveOperationException {
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
                    try { forwarded[0] = filter(forwarded[0]); }
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
        final UUID id = player.getUniqueId();
        Reflect.call(loop, "execute", new Runnable() {
            public void run() {
                try {
                    if (Reflect.call(pipeline, "get", HANDLER) != null) return;
                    @SuppressWarnings("unchecked") List<String> names = (List<String>) Reflect.call(pipeline, "names");
                    // Outbound events flow from the pipeline tail toward its head.
                    // Install after the encoder so this handler receives packet
                    // objects before encoding, including initial tracking packets.
                    if (names.contains("encoder")) Reflect.call(pipeline, "addAfter", "encoder", HANDLER, handler);
                    else if (names.contains("packet_handler")) Reflect.call(pipeline, "addBefore", "packet_handler", HANDLER, handler);
                    else throw new IllegalStateException("No upstream packet encoder in " + names);
                    pipelines.put(id, pipeline);
                } catch (Throwable e) { plugin.packetFailure(e); }
            }
        });
    }

    boolean installed(Player player) { return pipelines.containsKey(player.getUniqueId()); }

    private Object filter(Object packet) throws ReflectiveOperationException {
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
                for (Object nested : (Iterable<?>)Reflect.call(m, packet)) changed.add(filter(nested));
                for (Constructor<?> c : packet.getClass().getDeclaredConstructors())
                    if (c.getParameterTypes().length == 1 && c.getParameterTypes()[0].isInstance(changed)) {
                        c.setAccessible(true); return c.newInstance(changed);
                    }
                throw new NoSuchMethodException("Native bundle packet constructor");
            }
        }
        if (!name.equals("PacketPlayOutMount") && !name.equals("ClientboundSetPassengersPacket")) return packet;
        MountCodec codec = codecs.get(packet.getClass());
        if (codec == null) {
            codec = new MountCodec(packet.getClass());
            MountCodec previous = codecs.putIfAbsent(packet.getClass(), codec);
            if (previous != null) codec = previous;
        }
        int vehicle = codec.vehicle.getInt(packet);
        Integer removed = edges.get(vehicle);
        if (removed == null) return packet;
        int[] before = (int[])codec.passengers.get(packet);
        int count = 0;
        for (int passenger : before) if (passenger != removed.intValue()) count++;
        if (count == before.length) return packet;
        int[] after = new int[count]; int i = 0;
        for (int passenger : before) if (passenger != removed.intValue()) after[i++] = passenger;
        return codec.create(vehicle, after);
    }

    void uninstall(Player player) { uninstall(player.getUniqueId()); }
    private void uninstall(UUID id) {
        final Object pipeline = pipelines.remove(id);
        if (pipeline == null) return;
        try {
            Object channel = Reflect.call(pipeline, "channel");
            Object loop = Reflect.call(channel, "eventLoop");
            Reflect.call(loop, "execute", new Runnable() {
                public void run() {
                    try { if (Reflect.call(pipeline, "get", HANDLER) != null) Reflect.call(pipeline, "remove", HANDLER); }
                    catch (ReflectiveOperationException e) { plugin.debug("Could not remove a disconnected network filter", e); }
                }
            });
        } catch (ReflectiveOperationException e) { plugin.debug("Could not reach a disconnected network filter", e); }
    }
    void close() { for (UUID id : pipelines.keySet()) uninstall(id); edges.clear(); seatTypes.clear(); }

    private static final class MountCodec {
        final Field vehicle, passengers;
        final Constructor<?> empty, reader, bufferConstructor;
        final Method bufferFactory;
        MountCodec(Class<?> type) throws ReflectiveOperationException {
            Field v = null, p = null;
            for (Field f : type.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) {
                if (f.getType() == int.class) v = f;
                if (f.getType() == int[].class) p = f;
            }
            if (v == null || p == null) throw new NoSuchFieldException("Upstream mount packet IDs");
            v.setAccessible(true); p.setAccessible(true); vehicle = v; passengers = p;
            Constructor<?> e = null, r = null, b = null;
            Method factory = null;
            try { e = type.getDeclaredConstructor(); e.setAccessible(true); }
            catch (NoSuchMethodException modern) {
                for (Constructor<?> c : type.getDeclaredConstructors()) if (c.getParameterTypes().length == 1) {
                    Class<?> arg = c.getParameterTypes()[0];
                    if (arg.getSimpleName().equals("FriendlyByteBuf") || arg.getSimpleName().equals("PacketDataSerializer")) {
                        r = c; r.setAccessible(true);
                        for (Constructor<?> bc : arg.getConstructors()) if (bc.getParameterTypes().length == 1
                                && bc.getParameterTypes()[0].getName().endsWith(".buffer.ByteBuf")) { b = bc; break; }
                        if (b != null) {
                            String prefix = b.getParameterTypes()[0].getName().replace(".buffer.ByteBuf", "");
                            factory = Class.forName(prefix + ".buffer.Unpooled").getMethod("wrappedBuffer", byte[].class);
                        }
                        break;
                    }
                }
            }
            if (e == null && (r == null || b == null || factory == null)) throw new NoSuchMethodException("Upstream mount packet reader");
            empty = e; reader = r; bufferConstructor = b; bufferFactory = factory;
        }
        Object create(int id, int[] ids) throws ReflectiveOperationException {
            if (empty != null) {
                Object packet = empty.newInstance(); vehicle.setInt(packet, id); passengers.set(packet, ids); return packet;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            varint(bytes, id); varint(bytes, ids.length); for (int value : ids) varint(bytes, value);
            Object raw = Reflect.call(bufferFactory, (Object)null, (Object)bytes.toByteArray());
            try { return reader.newInstance(bufferConstructor.newInstance(raw)); }
            finally { Reflect.call(raw, "release"); }
        }
        private static void varint(ByteArrayOutputStream out, int value) {
            do { int part = value & 127; value >>>= 7; out.write(value == 0 ? part : part | 128); } while (value != 0);
        }
    }
}
