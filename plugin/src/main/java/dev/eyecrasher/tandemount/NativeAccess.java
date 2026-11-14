/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import java.lang.reflect.*;
import java.util.*;

/** Narrow adapter discovered from the loaded upstream server's real signatures. */
final class NativeAccess {
    final boolean oldest;
    final boolean interpolatedSeat;
    final NativeAttachment attachment;
    final Class<?> entityClass, seatBase;
    final Constructor<?> seatConstructor;
    private final Constructor<?> vehicleWrapper;
    private final Object craftServer;
    private final Object seatBlock, seatState;
    private final Object vanillaSeatType;
    private final Field bounds;
    private final Constructor<?> boxConstructor;
    private final Method boxMutator;
    final Method position;
    final int legacySeatType;
    private final String oldPackage;
    private final Method positionPacketFactory;
    private final Constructor<?> positionPacketConstructor;
    private final Map<Class<?>, Method> riderPositionMethods = new HashMap<Class<?>, Method>();

    NativeAccess(Object server) throws ReflectiveOperationException, java.io.IOException {
        craftServer = server;
        String craft = server.getClass().getPackage().getName();
        String bukkitVersion = org.bukkit.Bukkit.getBukkitVersion();
        String minecraft = bukkitVersion.split("-", 2)[0];
        interpolatedSeat = minecraft.equals("26.3") || minecraft.startsWith("26.3.build.");
        Class<?> craftSeat;
        try { craftSeat = Class.forName(craft + (interpolatedSeat ? ".entity.CraftBlockDisplay" : ".entity.CraftFallingBlock")); }
        catch (ClassNotFoundException oldName) { craftSeat = Class.forName(craft + ".entity.CraftFallingSand"); }
        boolean legacy;
        try { Class.forName(craft + ".entity.CraftArmorStand"); legacy = false; }
        catch (ClassNotFoundException noArmorStand) { legacy = true; }
        oldest = legacy;
        Method getHandle = null;
        for (Method m : craftSeat.getMethods())
            if (m.getName().equals("getHandle") && m.getParameterTypes().length == 0 && !m.isBridge()) getHandle = m;
        if (getHandle == null) throw new NoSuchMethodException("Craft seat native handle");
        seatBase = getHandle.getReturnType();
        Class<?> stateType = null;
        if (interpolatedSeat) stateType = seatBase.getMethod("getBlockState").getReturnType();
        for (Constructor<?> c : seatBase.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 5 && p[1] == double.class && p[2] == double.class && p[3] == double.class
                    && (p[0].getSimpleName().equals("World") || p[0].getSimpleName().equals("Level"))) {
                stateType = p[4]; break;
            }
        }
        Class<?> magic = Class.forName(craft + ".util.CraftMagicNumbers");
        // Vanilla clients discard an AIR falling block in their first entity
        // tick. BARRIER has invisible render shape while remaining non-AIR.
        // Keep this owner regression repair tied to its exact audited client.
        org.bukkit.Material material = (bukkitVersion.equals("26.3") || bukkitVersion.startsWith("26.3-") || bukkitVersion.startsWith("26.3.build."))
                ? org.bukkit.Material.valueOf("BARRIER") : org.bukkit.Material.AIR;
        seatBlock = Reflect.call(magic.getMethod("getBlock", org.bukkit.Material.class), (Object)null, material);
        Object state = null;
        if (stateType != null) {
            Method stateGetter = null;
            for (Method m : seatBlock.getClass().getMethods())
                if (!Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                        && stateType.isAssignableFrom(m.getReturnType())) {
                    if (stateGetter != null) throw new NoSuchMethodException("Unambiguous native seat block state getter");
                    stateGetter = m;
                }
            if (stateGetter != null) state = Reflect.call(stateGetter, seatBlock);
        }
        if (state == null && stateType != null) for (Field f : Reflect.fields(seatBlock.getClass()))
            if (!Modifier.isStatic(f.getModifiers()) && stateType.isAssignableFrom(f.getType())) {
                state = Reflect.get(f, seatBlock); break;
            }
        if (stateType != null && !stateType.isInstance(state)) throw new NoSuchFieldException("Native seat block state");
        seatState = state;
        Class<?> root = seatBase;
        while (root.getSuperclass() != Object.class) root = root.getSuperclass();
        entityClass = root;
        Class<?> boxType = null;
        for (Field f : entityClass.getDeclaredFields())
            if (f.getType().getSimpleName().equals("AABB") || f.getType().getSimpleName().equals("AxisAlignedBB")) {
                boxType = f.getType(); break;
            }
        Field box = null;
        if (boxType != null) for (Field f : entityClass.getDeclaredFields()) {
            int modifiers = f.getModifiers();
            if (f.getType() == boxType && !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                box = f; box.setAccessible(true); break;
            }
        }
        // 1.7 keeps one final, mutable AxisAlignedBB on each entity. Never
        // replace that final reference or select a newer shared empty box.
        Method mutate = null;
        if (box == null && oldest && boxType != null) {
            Field legacyBox = entityClass.getDeclaredField("boundingBox");
            if (legacyBox.getType() == boxType && !Modifier.isStatic(legacyBox.getModifiers())) {
                mutate = boxType.getMethod("b", double.class, double.class, double.class,
                        double.class, double.class, double.class);
                if (Modifier.isStatic(mutate.getModifiers()) || mutate.getReturnType() != boxType)
                    throw new NoSuchMethodException("Mutable legacy entity bounds");
                box = legacyBox; box.setAccessible(true);
            }
        }
        if (box == null) throw new NoSuchFieldException("Native entity bounds");
        if (Modifier.isStatic(box.getModifiers()) || (Modifier.isFinal(box.getModifiers()) && mutate == null))
            throw new IllegalStateException("Native entity bounds field is not mutable instance state");
        bounds = box;
        boxMutator = mutate;
        boxConstructor = mutate == null ? box.getType().getConstructor(double.class, double.class,
                double.class, double.class, double.class, double.class) : null;
        oldPackage = entityClass.getPackage().getName();
        int numericType = -1;
        if (oldPackage.startsWith("net.minecraft.server.v")) {
            Class<?> types = Class.forName(oldPackage + ".EntityTypes");
            for (Field f : types.getDeclaredFields()) if (Modifier.isStatic(f.getModifiers())) {
                Object registry = Reflect.get(f, null);
                if (registry instanceof Map) {
                    Object value = ((Map<?, ?>)registry).get(seatBase);
                    if (value instanceof Number) numericType = ((Number)value).intValue();
                } else if (registry != null && f.getType().getSimpleName().equals("RegistryMaterials")) {
                    for (Method m : registry.getClass().getMethods()) if (m.getReturnType() == int.class
                            && Arrays.equals(m.getParameterTypes(), new Class<?>[] {Object.class})) {
                        Object value = Reflect.call(m, registry, seatBase);
                        if (((Number)value).intValue() > 0) numericType = ((Number)value).intValue();
                    }
                }
            }
        }
        legacySeatType = numericType;
        Method pos = null;
        // In the exact 1.19.3 Spigot mappings e(DDD) updates VecDeltaCodec;
        // f(DDD) is the real position setter. 1.19.4 swaps those aliases.
        String[] positionNames = minecraft.equals("1.19.3")
                ? new String[] {"setPos", "setPosition", "a_", "f"}
                : new String[] {"setPos", "setPosition", "a_", "e"};
        for (String name : positionNames) {
            try {
                Method candidate = entityClass.getMethod(name, double.class, double.class, double.class);
                int modifiers = candidate.getModifiers();
                // Spigot-mapped 1.19.4/1.20.1/1.20.2 expose this exact
                // setPosRaw(DDD,true) delegate as e(DDD), including Folia.
                if (candidate.getReturnType() == void.class && !Modifier.isStatic(modifiers)
                        && !Modifier.isFinal(modifiers)) { pos = candidate; break; }
            }
            catch (NoSuchMethodException absent) { /* Try the next official mapping family. */ }
        }
        if (pos == null) throw new NoSuchMethodException("Overrideable native entity position method");
        position = pos;
        Constructor<?> parentConstructor = null, typeConstructor = null;
        for (Constructor<?> c : seatBase.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (!Modifier.isPublic(c.getModifiers()) && !Modifier.isProtected(c.getModifiers())) continue;
            if (((p.length == 1 && (p[0].getSimpleName().equals("World") || p[0].getSimpleName().equals("Level")))
                    || (p.length == 5 && p[1] == double.class && p[2] == double.class && p[3] == double.class && seatState != null && p[4].isInstance(seatState)))) {
                parentConstructor = c; break;
            }
            if (p.length == 2 && (p[0].getSimpleName().equals("EntityTypes") || p[0].getSimpleName().equals("EntityType"))
                    && (p[1].getSimpleName().equals("World") || p[1].getSimpleName().equals("Level"))) typeConstructor = c;
        }
        if (parentConstructor == null) parentConstructor = typeConstructor;
        if (parentConstructor == null) throw new NoSuchMethodException("Vanilla seat world constructor");
        vanillaSeatType = parentConstructor.getParameterTypes().length == 2
                ? vanillaSeatType(parentConstructor.getParameterTypes()[0]) : null;
        Class<?> generated = SeatClass.create(seatBase, parentConstructor, pos, oldest);
        seatConstructor = generated.getConstructor(parentConstructor.getParameterTypes());
        boolean cancellableDismount = false;
        for (String event : new String[] {"org.bukkit.event.entity.EntityDismountEvent",
                "org.spigotmc.event.entity.EntityDismountEvent"}) try {
            if (org.bukkit.event.Cancellable.class.isAssignableFrom(Class.forName(event))) cancellableDismount = true;
        } catch (ClassNotFoundException absent) { /* Older platform event family. */ }
        // Some old Spigot APIs only offer cancellable VehicleExitEvent. Keep
        // their falling-block wrapper a Vehicle without making it living.
        vehicleWrapper = oldest || !cancellableDismount ? SeatClass.vehicleWrapper(craftSeat).getConstructors()[0] : null;
        Method factory = null;
        Constructor<?> constructor = null;
        Class<?> packet = null;
        try { packet = Class.forName("net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket"); }
        catch (ClassNotFoundException absent) { /* Older movement protocol. */ }
        if (packet != null) {
            for (Method m : packet.getDeclaredMethods()) if (Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == packet && Arrays.equals(m.getParameterTypes(), new Class<?>[] {entityClass})) {
                factory = m; break;
            }
        }
        if (factory == null) {
            for (String name : new String[] {"net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket",
                    "net.minecraft.network.protocol.game.PacketPlayOutEntityTeleport", oldPackage + ".PacketPlayOutEntityTeleport"}) {
                try { packet = Class.forName(name); constructor = packet.getConstructor(entityClass); break; }
                catch (ClassNotFoundException absent) { /* Next mapping family. */ }
                catch (NoSuchMethodException absent) { /* Newer position packet above is mandatory. */ }
            }
        }
        if (factory == null && constructor == null) throw new NoSuchMethodException("Native seat position synchronization packet");
        positionPacketFactory = factory;
        positionPacketConstructor = constructor;
        attachment = interpolatedSeat ? new NativeAttachment(this) : null;
    }

    Entity createSeat(Horse horse) throws ReflectiveOperationException {
        Location at = horse.getLocation();
        Object world = Reflect.handle(horse.getWorld());
        int arguments = seatConstructor.getParameterTypes().length;
        Object seat = arguments == 1 ? seatConstructor.newInstance(world)
                : arguments == 2 ? seatConstructor.newInstance(vanillaSeatType, world)
                : seatConstructor.newInstance(world, at.getX(), at.getY(), at.getZ(), seatState);
        if (interpolatedSeat) Reflect.call(seat, "setBlockState", seatState);
        for (Field f : Reflect.fields(seatBase)) if (!Modifier.isStatic(f.getModifiers())) {
            if (seatState != null && f.getType().isInstance(seatState)) { f.setAccessible(true); f.set(seat, seatState); }
            else if (oldest && f.getType().isInstance(seatBlock)) { f.setAccessible(true); f.set(seat, seatBlock); }
        }
        if (vehicleWrapper != null) for (Field f : Reflect.fields(entityClass)) if (f.getName().equals("bukkitEntity")) {
            f.setAccessible(true); f.set(seat, vehicleWrapper.newInstance(craftServer, seat)); break;
        }
        Entity bukkit = (Entity) Reflect.call(seat, "getBukkitEntity");
        // The client LivingEntityRenderer clamps passenger body yaw against a
        // LivingEntity vehicle. Keep this helper on the vanilla, nonliving
        // falling-block entity so a head-yaw wrap cannot steer the rider model.
        String requiredType = interpolatedSeat ? "BLOCK_DISPLAY" : "FALLING_BLOCK";
        if (bukkit instanceof LivingEntity || !bukkit.getType().name().equals(requiredType)) {
            bukkit.remove();
            throw new IllegalStateException("The shared seat must be the audited nonliving vanilla type");
        }
        if (interpolatedSeat) Reflect.call(bukkit, "setTeleportDuration", 0);
        Reflect.optional(bukkit, "setPersistent", false);
        Reflect.optional(bukkit, "setInvulnerable", true);
        Reflect.optional(bukkit, "setSilent", true);
        Reflect.optional(bukkit, "setGravity", false);
        Reflect.optional(bukkit, "setAI", false);
        Reflect.optional(bukkit, "setRemoveWhenFarAway", false);
        Reflect.optional(bukkit, "setCollidable", false);
        Reflect.optional(bukkit, "setCanTick", true);
        Reflect.optional(bukkit, "setDropItem", false);
        Reflect.optional(bukkit, "setHurtEntities", false);
        Reflect.optional(bukkit, "setDamagePerBlock", 0.0f);
        Reflect.optional(bukkit, "setMaxDamage", 0);
        for (String flag : new String[] {"noPhysics", "noclip"}) {
            try { entityClass.getField(flag).setBoolean(seat, true); }
            catch (NoSuchFieldException absent) { /* Next mapping family. */ }
        }
        Reflect.call(position, seat, at.getX(), at.getY(), at.getZ());
        shape(seat);
        return bukkit;
    }

    private Object vanillaSeatType(Class<?> type) throws ReflectiveOperationException {
        String identifier = interpolatedSeat ? "minecraft:block_display" : "minecraft:falling_block";
        String constant = interpolatedSeat ? "BLOCK_DISPLAY" : "FALLING_BLOCK";
        Object value = null;
        for (String name : new String[] {"byString", "a"}) try {
            Method lookup = type.getMethod(name, String.class);
            if (Modifier.isStatic(lookup.getModifiers()) && lookup.getReturnType() == Optional.class) {
                Optional<?> found = (Optional<?>)Reflect.call(lookup, (Object)null, identifier);
                if (found.isPresent()) { value = found.get(); break; }
            }
        } catch (NoSuchMethodException absent) { /* Next audited native mapping. */ }
        // 26.2 moved the existing vanilla constants out of EntityType. Read
        // that upstream holder; never construct a type or edit its registry.
        if (value == null) for (String name : new String[] {type.getName(), type.getPackage().getName() + ".EntityTypes"}) try {
            Field field = Class.forName(name).getField(constant);
            if (Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                value = Reflect.get(field, null); break;
            }
        } catch (ClassNotFoundException absent) { /* Pre-26.2 native holder. */ }
          catch (NoSuchFieldException absent) { /* Older obfuscated constants. */ }
        if (!type.isInstance(value)) throw new NoSuchFieldException("Existing vanilla seat entity type " + identifier);
        for (String name : new String[] {"getKey", "a"}) try {
            Method key = type.getMethod(name, type);
            String result = key.getReturnType().getSimpleName();
            if (Modifier.isStatic(key.getModifiers()) && (result.equals("Identifier")
                    || result.equals("ResourceLocation") || result.equals("MinecraftKey"))) {
                if (!identifier.equals(String.valueOf(Reflect.call(key, (Object)null, value))))
                    throw new IllegalStateException("Native seat registry identity mismatch");
                return value;
            }
        } catch (NoSuchMethodException absent) { /* Next audited native mapping. */ }
        throw new NoSuchMethodException("Native seat registry identity");
    }

    void shape(Object seat) throws ReflectiveOperationException {
        Entity bukkit = (Entity)Reflect.call(seat, "getBukkitEntity");
        Location at = bukkit.getLocation();
        if (boxMutator == null)
            bounds.set(seat, boxConstructor.newInstance(at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ()));
        else Reflect.call(boxMutator, Reflect.get(bounds, seat),
                at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ());
    }

    void addSeat(Horse horse, Entity bukkit) throws ReflectiveOperationException {
        Object seat = Reflect.handle(bukkit);
        Object world = Reflect.handle(horse.getWorld());
        boolean added = false;
        if (!oldest) {
            for (Method m : horse.getWorld().getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if ((m.getName().equals("addEntity") || m.getName().equals("addEntityToWorld"))
                        && p.length == 2 && p[0].isInstance(seat) && p[1] == SpawnReason.class) {
                    Object result = Reflect.call(m, horse.getWorld(), seat, SpawnReason.CUSTOM);
                    added = !(result instanceof Boolean) || (Boolean)result;
                    break;
                }
            }
        } else {
            for (Method m : world.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("addEntity") && p.length == 2 && p[0].isInstance(seat) && p[1] == SpawnReason.class) {
                    added = Boolean.TRUE.equals(Reflect.call(m, world, seat, SpawnReason.CUSTOM)); break;
                }
            }
        }
        if (!added || !bukkit.isValid()) {
            bukkit.remove(); throw new IllegalStateException("The server cancelled the seat's custom spawn");
        }
    }

    boolean mount(Entity vehicle, Entity passenger) throws ReflectiveOperationException {
        Object result;
        try { result = Reflect.call(vehicle, "addPassenger", passenger); }
        catch (NoSuchMethodException old) { result = Reflect.call(vehicle, "setPassenger", passenger); }
        return !(result instanceof Boolean) || (Boolean)result;
    }

    void preserveCancelledExit(Entity vehicle, Entity passenger) throws ReflectiveOperationException {
        // Spigot 1.12 clears the passenger's pointer before VehicleExitEvent,
        // then returns on cancellation while retaining the vehicle's list.
        // Restore only that exact half-detached state; never perform a remount
        // or replace a vehicle chosen by another listener.
        if (passenger.getVehicle() != null || !passengers(vehicle).contains(passenger)) return;
        Object handle = Reflect.handle(passenger), nativeVehicle = Reflect.handle(vehicle);
        Field pointer = null;
        for (String name : new String[] {"vehicle", "au"}) try {
            Field candidate = entityClass.getDeclaredField(name);
            if (candidate.getType() == entityClass && !Modifier.isStatic(candidate.getModifiers())
                    && !Modifier.isFinal(candidate.getModifiers())) { pointer = candidate; break; }
        } catch (NoSuchFieldException absent) { /* Next verified legacy mapping. */ }
        if (pointer == null) throw new NoSuchFieldException("Cancelled legacy vehicle pointer");
        pointer.setAccessible(true);
        if (pointer.get(handle) != null) return;
        pointer.set(handle, nativeVehicle);
        Object packet = null;
        for (String name : new String[] {"net.minecraft.network.protocol.game.ClientboundSetPassengersPacket",
                "net.minecraft.network.protocol.game.PacketPlayOutMount", oldPackage + ".PacketPlayOutMount"}) try {
            packet = Class.forName(name).getConstructor(entityClass).newInstance(nativeVehicle); break;
        } catch (ClassNotFoundException absent) { /* Next verified packet family. */ }
        if (packet == null) packet = Class.forName(oldPackage + ".PacketPlayOutAttachEntity")
                .getConstructor(int.class, entityClass, entityClass).newInstance(0, handle, nativeVehicle);
        broadcast(vehicle, packet);
    }

    List<Entity> passengers(Entity entity) {
        try {
            try {
                Object result = Reflect.call(entity, "getPassengers");
                @SuppressWarnings("unchecked") List<Entity> passengers = (List<Entity>) result;
                return new ArrayList<Entity>(passengers);
            } catch (NoSuchMethodException old) {
                Entity passenger = (Entity) Reflect.call(entity, "getPassenger");
                return passenger == null ? Collections.<Entity>emptyList() : Collections.singletonList(passenger);
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect native passengers", e); }
    }

    void place(Entity seat) throws ReflectiveOperationException {
        Object nativeSeat = Reflect.handle(seat);
        Location at = seat.getLocation();
        Reflect.call(position, nativeSeat, at.getX(), at.getY(), at.getZ());
        // For a detached 1.7 seat, explicitly perform its native passenger update.
        // Newer seats are updated once more here so configuration applies immediately.
        List<Entity> passengers = passengers(seat);
        for (Entity rider : passengers) positionRider(nativeSeat, Reflect.handle(rider));
    }

    void positionRider(Object seat, Object rider) throws ReflectiveOperationException {
        if (oldest) {
            // Entity.ac(): vanilla 1.7 passenger position update.
            Method m = entityClass.getMethod("ac"); Reflect.call(m, seat); return;
        }
        Method selected = riderPositionMethods.get(seat.getClass());
        if (selected == null) {
            for (Method m : Reflect.methods(entityClass)) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 2 && p[0] == entityClass
                        && p[1].isInterface() && p[1].getEnclosingClass() == entityClass
                        && p[1].getSimpleName().contains("MoveFunction")) { selected = m; break; }
            }
            outer: for (String name : new String[] {"positionRider", "positionPassenger", "updatePassenger", "syncPositionOf", "i", "k", "j"})
                for (Method m : Reflect.methods(entityClass)) if (m.getName().equals(name)
                        && m.getReturnType() == void.class && Arrays.equals(m.getParameterTypes(), new Class<?>[] {entityClass})) {
                    if (selected == null) selected = m; break outer;
                }
            if (selected == null) {
                // Older Bukkit mappings expose this as a(Entity); verified by native probe.
                throw new NoSuchMethodException("Native passenger positioning entry point");
            }
            riderPositionMethods.put(seat.getClass(), selected);
        }
        if (selected.getParameterTypes().length == 1) Reflect.call(selected, seat, rider);
        else {
            Class<?> mover = selected.getParameterTypes()[1];
            Object callback = Proxy.newProxyInstance(mover.getClassLoader(), new Class<?>[] {mover}, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if (args != null && args.length == 4) return Reflect.call(position, args[0], args[1], args[2], args[3]);
                    if (method.getName().equals("toString")) return "Tandemount native passenger positioning";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return null;
                }
            });
            Reflect.call(selected, seat, rider, callback);
        }
    }

    Object positionPacket(Entity seat) throws ReflectiveOperationException {
        Object handle = Reflect.handle(seat);
        return positionPacketFactory == null ? positionPacketConstructor.newInstance(handle)
                : Reflect.call(positionPacketFactory, (Object)null, handle);
    }

    void broadcast(Entity seat, Object packet) throws ReflectiveOperationException {
        Object world = Reflect.handle(seat.getWorld());
        Object tracker = null;
        for (String name : new String[] {"getTracker", "getChunkSource", "getChunkProvider"}) {
            try { tracker = Reflect.call(world, name); break; }
            catch (NoSuchMethodException absent) { /* Next upstream mapping family. */ }
        }
        if (tracker == null) for (Method m : world.getClass().getMethods()) if (m.getParameterTypes().length == 0
                && (m.getReturnType().getSimpleName().equals("ServerChunkCache")
                    || m.getReturnType().getSimpleName().equals("ChunkProviderServer"))) {
            tracker = Reflect.call(m, world); break;
        }
        if (tracker == null) throw new NoSuchMethodException("Native observer tracker");
        Object handle = Reflect.handle(seat);
        Method send = null;
        for (Method m : Reflect.methods(tracker.getClass())) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0].isInstance(handle) && p[1].isInstance(packet)
                    && m.getReturnType() == void.class) {
                send = m;
                if (m.getName().equals("broadcast") || m.getName().equals("a")) break;
            }
        }
        if (send == null) throw new NoSuchMethodException("Tracker broadcast(Entity,Packet)");
        Reflect.call(send, tracker, handle, packet);
    }

    boolean canSee(Entity viewer, Entity entity) throws ReflectiveOperationException {
        try { return Boolean.TRUE.equals(Reflect.call(viewer, "canSee", entity)); }
        catch (NoSuchMethodException old) {
            if (entity instanceof org.bukkit.entity.Player)
                return ((org.bukkit.entity.Player)viewer).canSee((org.bukkit.entity.Player)entity);
            return true;
        }
    }
}
