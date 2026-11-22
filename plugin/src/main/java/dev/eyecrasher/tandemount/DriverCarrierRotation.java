/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Paper 26.3 native carrier yaw updates for the player driving the horse. */
final class DriverCarrierRotation {
    private final ConcurrentMap<Integer, Carrier> carriers = new ConcurrentHashMap<Integer, Carrier>();
    private final Class<?> rotation;
    private final Field entityId, yaw, pitch;
    private final Method onGround;
    private final Constructor<?> positionRotation;
    private final Object stepUp, stepDown;

    DriverCarrierRotation() throws ReflectiveOperationException {
        String protocol = "net.minecraft.network.protocol.game.";
        Class<?> movement = Class.forName(protocol + "ClientboundMoveEntityPacket");
        rotation = Class.forName(movement.getName() + "$Rot");
        entityId = movement.getDeclaredField("entityId");
        yaw = movement.getDeclaredField("yRot");
        pitch = movement.getDeclaredField("xRot");
        for (Field field : new Field[] {entityId, yaw, pitch}) field.setAccessible(true);
        onGround = movement.getMethod("isOnGround");
        Class<?> delta = Class.forName(protocol + "VecDelta");
        positionRotation = Class.forName(movement.getName() + "$PosRot")
                .getConstructor(int.class, delta, byte.class, byte.class, boolean.class);
        Constructor<?> step = Class.forName(delta.getName() + "$Stepped$DeltaStep")
                .getConstructor(short.class, short.class, short.class, int.class);
        Constructor<?> path = Class.forName(delta.getName() + "$Stepped").getConstructor(List.class);
        stepUp = path.newInstance(Collections.singletonList(step.newInstance((short)0, (short)1, (short)0, 1)));
        stepDown = path.newInstance(Collections.singletonList(step.newInstance((short)0, (short)-1, (short)0, 1)));
    }

    void track(UUID driver, int entity) { carriers.put(entity, new Carrier(driver)); }
    void remove(int entity) { carriers.remove(entity); }
    void clear() { carriers.clear(); }

    Object shorten(UUID viewer, Object packet) throws ReflectiveOperationException {
        if (carriers.isEmpty() || !rotation.isInstance(packet)) return packet;
        int id = entityId.getInt(packet);
        Carrier carrier = carriers.get(id);
        if (carrier == null || !carrier.driver.equals(viewer)) return packet;
        // A distinct endpoint lets the client use the path's one-tick
        // duration. The position codec alternates one vertical quantum.
        // Native passenger placement resolves the carrier's world position.
        Object step = carrier.nextStepUp ? stepUp : stepDown;
        carrier.nextStepUp = !carrier.nextStepUp;
        return positionRotation.newInstance(id, step, yaw.getByte(packet), pitch.getByte(packet),
                Reflect.call(onGround, packet));
    }

    private static final class Carrier {
        final UUID driver;
        // Only the driver's outbound channel writes this phase.
        boolean nextStepUp = true;
        Carrier(UUID driver) { this.driver = driver; }
    }
}
