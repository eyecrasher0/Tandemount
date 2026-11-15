/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/** Native attachment geometry from exact upstream mappings; no follower packets. */
final class NativeAttachment {
    private static final double MIN_SCALE = 1.0 / 16.0;
    private static final double LLAMA_REAR = (double)0.3F;
    private static final float CARRIER_DEPTH = -64F;
    private final NativeAccess access;
    private final Constructor<?> carrier, spacer;
    private final Object llamaType, interactionType, scale;
    private final int carriers, helperCount;
    private final Properties mappings = new Properties();

    NativeAttachment(NativeAccess access) throws ReflectiveOperationException, java.io.IOException {
        this.access = access;
        InputStream input = NativeAttachment.class.getResourceAsStream("/attachment-mappings.properties");
        if (input == null) throw new IOException("Official attachment mappings unavailable");
        try { mappings.load(input); } finally { input.close(); }
        String craft = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
        Class<?> base = handleType(Class.forName(craft + ".entity.CraftLlama"));
        Class<?> entityType = access.seatConstructor.getParameterTypes()[0];
        Class<?> level = access.seatConstructor.getParameterTypes()[1];
        Constructor<?> nativeConstructor = base.getConstructor(entityType, level);
        String tick = null;
        for (String name : new String[] {"tick", "l", "g"}) try {
            Method method = access.seatBase.getSuperclass().getDeclaredMethod(name);
            if (method.getReturnType() == void.class && !Modifier.isStatic(method.getModifiers())) { tick = name; break; }
        } catch (NoSuchMethodException absent) { }
        if (tick == null) throw new NoSuchMethodException("Audited native display tick");
        carrier = SeatClass.create(base, nativeConstructor, access.position, false, base.getMethod(tick)).getConstructor(entityType, level);
        Class<?> interaction = handleType(Class.forName(craft + ".entity.CraftInteraction"));
        Constructor<?> interactionConstructor = interaction.getConstructor(entityType, level);
        spacer = SeatClass.create(interaction, interactionConstructor, access.position, false, interaction.getMethod(tick)).getConstructor(entityType, level);
        llamaType = NativeAccess.vanillaEntityType(entityType, "minecraft:llama", "LLAMA");
        interactionType = NativeAccess.vanillaEntityType(entityType, "minecraft:interaction", "INTERACTION");
        Object scaleAttribute = null;
        Class<?> attributes = Class.forName("org.bukkit.attribute.Attribute");
        for (String name : new String[] {"SCALE", "GENERIC_SCALE"}) try { scaleAttribute = attributes.getField(name).get(null); break; }
        catch (NoSuchFieldException beforeScale) { }
        scale = scaleAttribute;
        carriers = scale == null ? 8 : 2;
        helperCount = scale == null ? 18 : 5;
    }

    private Class<?> handleType(Class<?> wrapper) throws NoSuchMethodException {
        for (Method method : wrapper.getMethods()) if (method.getName().equals("getHandle") && method.getParameterTypes().length == 0 && !method.isBridge())
            return method.getReturnType();
        throw new NoSuchMethodException("Native Craft wrapper handle");
    }

    private String alias(String key) { return mappings.getProperty(access.minecraft + "." + key); }
    Object root(Entity entity) throws ReflectiveOperationException { return mapped(Reflect.handle(entity), "getRootVehicle", "rootVehicle"); }
    Object controller(Entity entity) throws ReflectiveOperationException { return mapped(Reflect.handle(entity), "getControllingPassenger", "controller"); }

    private Object mapped(Object target, String name, String key, Object... args) throws ReflectiveOperationException {
        try { return Reflect.call(target, name, args); }
        catch (NoSuchMethodException absent) {
            String alias = alias(key);
            if (alias == null) throw absent;
            return Reflect.call(target, alias, args);
        }
    }

    void create(Ride ride) throws ReflectiveOperationException {
        Object world = Reflect.handle(ride.horse.getWorld());
        List<Entity> created = new ArrayList<Entity>();
        try {
            Entity anchor = wrapper(spacer.newInstance(interactionType, world)); created.add(anchor);
            for (int i = 0; i < carriers; i++) {
                created.add(wrapper(carrier.newInstance(llamaType, world)));
                // Older LivingEntity attachment positions use body yaw. A
                // nonliving direct vehicle prevents the native horse rider
                // body clamp from erasing alternating carrier directions.
                if (scale == null && i + 1 < carriers) created.add(wrapper(spacer.newInstance(interactionType, world)));
            }
            Entity lift = wrapper(spacer.newInstance(interactionType, world)); created.add(lift);
            created.add(ride.seat);
            ride.helpers = created.toArray(new Entity[created.size()]);
            for (Entity helper : ride.helpers) NativeSeat.RIDES.put(Reflect.handle(helper), ride);
            for (Entity helper : ride.helpers) {
                if (helper.getType().name().equals("LLAMA")) configureCarrier(helper);
                else if (helper.getType().name().equals("INTERACTION")) configureSpacer(helper);
            }
            Location at = ride.horse.getLocation();
            for (Entity helper : ride.helpers) Reflect.call(access.position, Reflect.handle(helper), at.getX(), at.getY(), at.getZ());
            update(ride);
        } catch (ReflectiveOperationException failure) {
            for (Entity helper : created) helper.remove();
            throw failure;
        }
    }

    private Entity wrapper(Object handle) throws ReflectiveOperationException {
        return (Entity)Reflect.call(handle, "getBukkitEntity");
    }

    private void configureCarrier(Entity entity) throws ReflectiveOperationException {
        Reflect.call(entity, "setAI", false);
        Reflect.call(entity, "setInvisible", true);
        Reflect.call(entity, "setSilent", true);
        Reflect.call(entity, "setInvulnerable", true);
        Reflect.call(entity, "setGravity", false);
        Reflect.call(entity, "setPersistent", false);
        Reflect.call(entity, "setCollidable", false);
        Reflect.call(entity, "setRemoveWhenFarAway", false);
        Object handle = Reflect.handle(entity);
        noPhysics(handle);
        access.shape(handle);
    }

    private void configureSpacer(Entity entity) throws ReflectiveOperationException {
        Reflect.call(entity, "setInteractionWidth", 0F);
        Reflect.call(entity, "setInteractionHeight", 0F);
        Reflect.call(entity, "setResponsive", false);
        Reflect.call(entity, "setSilent", true);
        Reflect.call(entity, "setInvulnerable", true);
        Reflect.call(entity, "setGravity", false);
        Reflect.call(entity, "setPersistent", false);
        noPhysics(Reflect.handle(entity));
        access.shape(Reflect.handle(entity));
    }

    private void noPhysics(Object handle) throws ReflectiveOperationException {
        for (String name : new String[] {"noPhysics", "noclip", alias("noPhysics")}) try {
            if (name == null) continue;
            access.entityClass.getField(name).setBoolean(handle, true); return;
        }
        catch (NoSuchFieldException absent) { }
        throw new NoSuchFieldException("Native no-physics flag");
    }

    private void height(Ride ride, Entity entity, float height) throws ReflectiveOperationException {
        if (ride.plugin.packets.hasDimensionRefresh(entity.getEntityId())
                && Float.compare(((Number)Reflect.call(entity, "getInteractionHeight")).floatValue(), height) == 0) return;
        Reflect.call(entity, "setInteractionHeight", height);
        Object handle = Reflect.handle(entity);
        Object pose = mapped(handle, "getPose", "getPose");
        Object[] poses = pose.getClass().getEnumConstants();
        Object next = poses[0];
        // Return to the ordinary standing pose before native mounting, which
        // itself restores that pose. Both transitions are native callbacks.
        mapped(handle, "setPose", "pose", poses[2]);
        mapped(handle, "setPose", "pose", next);
        // Build an immutable native pose DataValue. PacketBridge places it
        // after each existing tracker metadata snapshot, including late joins.
        Field accessor;
        try { accessor = access.entityClass.getDeclaredField("DATA_POSE"); }
        catch (NoSuchFieldException mapped) { accessor = access.entityClass.getDeclaredField(alias("poseAccessor")); }
        accessor.setAccessible(true);
        Object key = accessor.get(null), data = mapped(handle, "getEntityData", "data"), value = null;
        for (Class<?> nested : data.getClass().getDeclaredClasses()) for (Method factory : nested.getDeclaredMethods()) {
            Class<?>[] p = factory.getParameterTypes();
            if (Modifier.isStatic(factory.getModifiers()) && factory.getReturnType() == nested
                    && p.length == 2 && p[0].isInstance(key) && p[1] == Object.class) {
                value = Reflect.call(factory, (Object)null, key, next); break;
            }
        }
        if (value == null) throw new NoSuchMethodException("Native pose data value factory");
        Class<?> packet;
        try { packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket"); }
        catch (ClassNotFoundException mapped) { packet = Class.forName("net.minecraft.network.protocol.game.PacketPlayOutEntityMetadata"); }
        Object refresh = packet.getConstructor(int.class, List.class)
                .newInstance(entity.getEntityId(), Collections.singletonList(value));
        ride.plugin.packets.refreshDimensions(entity.getEntityId(), refresh);
        access.shape(handle);
    }

    void mount(Ride ride) throws ReflectiveOperationException {
        for (Entity helper : ride.helpers) access.addSeat(ride.horse, helper);
        Entity parent = ride.horse;
        for (Entity helper : ride.helpers) {
            if (!access.mount(parent, helper)) throw new IllegalStateException("Native attachment mount cancelled");
            parent = helper;
        }
        if (!access.mount(ride.seat, ride.rider)) throw new IllegalStateException("Native passenger mount cancelled");
        place(ride);
    }

    private void scale(Entity carrier, double value) throws ReflectiveOperationException {
        Object handle = Reflect.handle(carrier);
        Object attribute = Reflect.call(carrier, "getAttribute", scale);
        if (attribute == null) throw new IllegalStateException("Native scale attribute unavailable");
        Reflect.call(attribute, "setBaseValue", value);
        mapped(handle, "getScale", "scale");
        mapped(handle, "refreshDimensions", "refresh");
        access.shape(handle);
    }

    void update(Ride ride) throws ReflectiveOperationException {
        if (ride.helpers.length != helperCount) return;
        Settings settings = ride.plugin.settings;
        if (scale != null && ride.attachmentRearward != settings.rearward) {
            scale(carrier(ride, 0), settings.rearward / LLAMA_REAR + MIN_SCALE);
            scale(carrier(ride, 1), MIN_SCALE);
            ride.attachmentRearward = settings.rearward;
        }
        float yaw = ride.horse.getLocation().getYaw();
        for (int i = 0; i < carriers; i++) {
            float angle = scale != null ? yaw + (i == 0 ? 0F : 180F)
                    : yaw + (float)Math.toDegrees(Math.acos(settings.rearward / (carriers * LLAMA_REAR))) * (i % 2 == 0 ? 1F : -1F);
            Entity helper = carrier(ride, i);
            Reflect.call(helper, "setRotation", angle, 0F);
            mapped(Reflect.handle(helper), "setYBodyRot", "bodyYaw", angle);
            mapped(Reflect.handle(helper), "setYHeadRot", "headYaw", angle);
        }
        // Native attachment points supply all world positioning. The signed
        // Interaction height is synchronized and controls only the final
        // passenger's local Y offset, independent of the llama rear offsets.
        Entity lift = ride.helpers[ride.helpers.length - 2];
        if (Double.isNaN(ride.attachmentLiftRatio)) {
            height(ride, lift, 0F);
            double zero = passengerHeight(lift, ride.seat);
            height(ride, lift, 1F);
            ride.attachmentLiftRatio = passengerHeight(lift, ride.seat) - zero;
            if (!(ride.attachmentLiftRatio > 0 && ride.attachmentLiftRatio <= 1)) throw new IllegalStateException("Native interaction attachment ratio");
        }
        height(ride, ride.helpers[0], (float)(CARRIER_DEPTH / ride.attachmentLiftRatio));
        double anchor = passengerHeight(ride.horse, ride.helpers[0]);
        for (int i = 0; i < ride.helpers.length - 2; i++) anchor += passengerHeight(ride.helpers[i], ride.helpers[i + 1]);
        double riderOffset = passengerHeight(ride.seat, ride.rider);
        float height = (float)((settings.vertical - riderOffset - anchor) / ride.attachmentLiftRatio);
        height(ride, lift, height);
        ride.attachmentVertical = settings.vertical;
    }

    private Entity carrier(Ride ride, int index) { return ride.helpers[1 + index * (scale == null ? 2 : 1)]; }

    private double passengerHeight(Entity parent, Entity child) throws ReflectiveOperationException {
        Object nativeParent = Reflect.handle(parent), nativeChild = Reflect.handle(child);
        if (alias("passengerPosition") != null) {
            Object point = mapped(nativeParent, "getPassengerRidingPosition", "passengerPosition", nativeChild);
            double value = coordinate(point, "y") - parent.getLocation().getY();
            if (alias("vehiclePoint") != null)
                return value - coordinate(mapped(nativeChild, "getVehicleAttachmentPoint", "vehiclePoint", nativeParent), "y");
            return value + ((Number)mapped(nativeChild, "getMyRidingOffset", "ridingOffset1", nativeParent)).doubleValue();
        }
        return ((Number)mapped(nativeParent, "getPassengersRidingOffset", "passengersOffset")).doubleValue()
                + ((Number)mapped(nativeChild, "getMyRidingOffset", "ridingOffset0")).doubleValue();
    }

    private double coordinate(Object vector, String name) throws ReflectiveOperationException {
        try { return vector.getClass().getField(name).getDouble(vector); }
        catch (NoSuchFieldException mapped) { return vector.getClass().getField(alias(name)).getDouble(vector); }
    }

    void place(Ride ride) throws ReflectiveOperationException {
        update(ride);
        Object parent = Reflect.handle(ride.horse);
        for (Entity helper : ride.helpers) {
            Object child = Reflect.handle(helper);
            access.positionRider(parent, child);
            access.shape(child);
            parent = child;
        }
        access.positionRider(ride.nativeSeat, Reflect.handle(ride.rider));
    }

    boolean valid(Ride ride) {
        Entity parent = ride.horse;
        for (Entity helper : ride.helpers) {
            if (!helper.isValid() || helper.isDead() || helper.getVehicle() != parent) return false;
            parent = helper;
        }
        return true;
    }
}
