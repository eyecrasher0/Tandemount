/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import java.lang.reflect.*;

/** Exact 26.3 native attachment geometry; no independent position packets. */
final class NativeAttachment {
    private static final double MIN_SCALE = 1.0 / 16.0;
    private static final double LLAMA_REAR = (double)0.3F;
    private static final float CARRIER_DEPTH = -64F;
    private final NativeAccess access;
    private final Constructor<?> carrier, spacer;
    private final Object llamaType, interactionType, scale;

    NativeAttachment(NativeAccess access) throws ReflectiveOperationException, java.io.IOException {
        this.access = access;
        Class<?> base = Class.forName("net.minecraft.world.entity.animal.equine.Llama");
        Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
        Class<?> level = Class.forName("net.minecraft.world.level.Level");
        Constructor<?> nativeConstructor = base.getConstructor(entityType, level);
        carrier = SeatClass.create(base, nativeConstructor, access.position, false).getConstructor(entityType, level);
        Class<?> interaction = Class.forName("net.minecraft.world.entity.Interaction");
        Constructor<?> interactionConstructor = interaction.getConstructor(entityType, level);
        spacer = SeatClass.create(interaction, interactionConstructor, access.position, false).getConstructor(entityType, level);
        llamaType = Class.forName("net.minecraft.world.entity.EntityTypes").getField("LLAMA").get(null);
        interactionType = Class.forName("net.minecraft.world.entity.EntityTypes").getField("INTERACTION").get(null);
        Object key = Reflect.call(entityType.getMethod("getKey", entityType), (Object)null, llamaType);
        if (!"minecraft:llama".equals(String.valueOf(key))) throw new IllegalStateException("Native llama registry identity");
        scale = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes").getField("SCALE").get(null);
    }

    void create(Ride ride) throws ReflectiveOperationException {
        Object world = Reflect.handle(ride.horse.getWorld());
        Entity anchor = null, rear = null, forward = null, lift = null;
        try {
            anchor = wrapper(spacer.newInstance(interactionType, world));
            lift = wrapper(spacer.newInstance(interactionType, world));
            rear = wrapper(carrier.newInstance(llamaType, world));
            forward = wrapper(carrier.newInstance(llamaType, world));
            ride.helpers = new Entity[] {anchor, rear, forward, lift, ride.seat};
            for (Entity helper : ride.helpers) NativeSeat.RIDES.put(Reflect.handle(helper), ride);
            configureCarrier(rear); configureCarrier(forward);
            configureSpacer(anchor); configureSpacer(lift);
            Location at = ride.horse.getLocation();
            for (Entity helper : ride.helpers) Reflect.call(access.position, Reflect.handle(helper), at.getX(), at.getY(), at.getZ());
            Reflect.call(anchor, "setInteractionHeight", CARRIER_DEPTH);
            update(ride);
        } catch (ReflectiveOperationException failure) {
            if (anchor != null) anchor.remove();
            if (lift != null) lift.remove();
            if (rear != null) rear.remove();
            if (forward != null) forward.remove();
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
        access.entityClass.getField("noPhysics").setBoolean(handle, true);
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
        access.entityClass.getField("noPhysics").setBoolean(Reflect.handle(entity), true);
        access.shape(Reflect.handle(entity));
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
        Object attribute = Reflect.call(handle, "getAttribute", scale);
        if (attribute == null) throw new IllegalStateException("Native scale attribute unavailable");
        Reflect.call(attribute, "setBaseValue", value);
        Reflect.call(handle, "getScale");
        Reflect.call(handle, "refreshDimensions");
        access.shape(handle);
    }

    void update(Ride ride) throws ReflectiveOperationException {
        if (ride.helpers.length != 5) return;
        Settings settings = ride.plugin.settings;
        if (ride.attachmentRearward != settings.rearward) {
            scale(ride.helpers[1], settings.rearward / LLAMA_REAR + MIN_SCALE);
            scale(ride.helpers[2], MIN_SCALE);
            ride.attachmentRearward = settings.rearward;
        }
        float yaw = ride.horse.getLocation().getYaw();
        for (int i = 0; i < 2; i++) {
            Object handle = Reflect.handle(ride.helpers[i + 1]);
            float angle = yaw + (i == 0 ? 0F : 180F);
            Reflect.call(handle, "setYRot", angle);
            Reflect.call(handle, "setYHeadRot", angle);
            Reflect.call(handle, "setYBodyRot", angle);
        }
        // Native attachment points supply all world positioning. The signed
        // Interaction height is synchronized and controls only the final
        // passenger's local Y offset, independent of the llama rear offsets.
        Object horse = Reflect.handle(ride.horse), anchorEntity = Reflect.handle(ride.helpers[0]);
        Object rear = Reflect.handle(ride.helpers[1]), forward = Reflect.handle(ride.helpers[2]);
        Object horsePoint = Reflect.call(horse, "getPassengerRidingPosition", anchorEntity);
        double anchor = coordinate(horsePoint, "y") - ride.horse.getLocation().getY();
        anchor += CARRIER_DEPTH;
        anchor += passengerHeight(rear, forward);
        anchor += passengerHeight(forward, Reflect.handle(ride.helpers[3]));
        Object riderPoint = Reflect.call(Reflect.handle(ride.rider), "getVehicleAttachmentPoint", ride.nativeSeat);
        float height = (float)(settings.vertical + coordinate(riderPoint, "y") - anchor);
        Reflect.call(ride.helpers[3], "setInteractionHeight", height);
        ride.attachmentVertical = settings.vertical;
    }

    private double passengerHeight(Object parent, Object child) throws ReflectiveOperationException {
        Object point = Reflect.call(parent, "getPassengerRidingPosition", child);
        return coordinate(point, "y") - ((Number)Reflect.call(parent, "getY")).doubleValue();
    }

    private double coordinate(Object vector, String name) throws ReflectiveOperationException {
        return vector.getClass().getField(name).getDouble(vector);
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
