/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount.modern;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Exact native passenger geometry, with the documented early-version follower. */
public final class NativeAttachment {
    private static final double LLAMA_REAR = (double) 0.3F;
    private static final double MIN_SCALE = 1.0 / 16.0;
    final Object horse, rider, seat, anchor, lift;
    final Object[] helpers, carriers;
    private final boolean scaled;
    final boolean nativeGraph;
    private double lastHeight = Double.NaN, lastRear = Double.NaN;

    public static boolean nativeAvailable() { return Names.TABLE.containsKey("c.Interaction") && Names.TABLE.containsKey("c.BlockDisplay"); }
    public static boolean available() { return nativeAvailable() || Names.has("stand.marker"); }

    NativeAttachment(Object horse, Object rider, Object level) throws Exception {
        this.horse = horse;
        this.rider = rider;
        nativeGraph = nativeAvailable();
        scaled = Names.hasField("Attributes.SCALE");
        if (!nativeGraph) {
            // A vanilla marker stand has zero client dimensions and native
            // LivingEntity interpolation. Its nonliving child keeps the rider's
            // own head/body orientation independent from that interpolation root.
            anchor = create("ArmorStand", "ARMOR_STAND", level);
            seat = create("AreaEffectCloud", "AREA_EFFECT_CLOUD", level);
            lift = null; carriers = new Object[0]; helpers = new Object[]{anchor, seat};
            Names.call("stand.marker", anchor, true);
            Names.call("stand.basePlate", anchor, true);
            Names.call("cloud.radius", seat, 0F);
            Names.call("cloud.waitTime", seat, 0);
            Names.call("cloud.waiting", seat, false);
            Names.call("cloud.radiusOnUse", seat, 0F);
            Names.call("cloud.radiusPerTick", seat, 0F);
            if (Names.has("cloud.durationOnUse")) Names.call("cloud.durationOnUse", seat, 0);
            else Names.field("AreaEffectCloud.durationOnUse", seat, 0);
            Names.call("cloud.duration", seat, Integer.MAX_VALUE);
            prepareHelpers();
            return;
        }
        anchor = create("Interaction", "INTERACTION", level);
        lift = create("Interaction", "INTERACTION", level);
        seat = create("BlockDisplay", "BLOCK_DISPLAY", level);
        carriers = new Object[scaled ? 2 : 8];
        for (int i = 0; i < carriers.length; i++) carriers[i] = create("Llama", "LLAMA", level);
        List<Object> graph = new ArrayList<Object>();
        graph.add(anchor);
        for (int i = 0; i < carriers.length; i++) {
            graph.add(carriers[i]);
            // 1.20.2–1.20.4 native horse positioning copies body yaw to a
            // living passenger. A nonliving zero-height link keeps each
            // angular carrier's native attachment orientation independent.
            if (i + 1 < carriers.length) graph.add(create("Interaction", "INTERACTION", level));
        }
        graph.add(lift);
        graph.add(seat);
        helpers = graph.toArray();
        prepareHelpers();
        for (Object carrier : carriers) Names.call("mob.noAi", carrier, true);
        for (Object helper : helpers) {
            if (!Names.type("Interaction").isInstance(helper)) continue;
            Names.call("interaction.width", helper, 0F);
            height(helper, 0F);
            Names.call("interaction.response", helper, false);
        }
        // Living carrier raycast boxes stay far beneath the riders on both
        // server and vanilla client; the final nonliving seat stays near them.
        height(anchor, 1F);
        double heightFactor;
        if (Names.has("entity.passengerPoint")) {
            Object point = Names.call("entity.passengerPoint", anchor, carriers[0]);
            heightFactor = ((Number)Names.field("Vec3.y", point)).doubleValue() - number("entity.y", anchor);
        } else heightFactor = number("entity.passengerHeight", anchor);
        if (!Double.isFinite(heightFactor) || heightFactor <= 0) throw new IllegalStateException("Native Interaction passenger height unavailable");
        height(anchor, (float)(-64 / heightFactor));
        Names.call("display.block", seat, Names.call("block.state", Names.field("Blocks.BARRIER", null)));
    }

    private void prepareHelpers() throws Exception {
        double x = number("entity.x", horse), y = number("entity.y", horse), z = number("entity.z", horse);
        for (Object helper : helpers) {
            Names.call("entity.invisible", helper, true);
            Names.call("entity.gravity", helper, true);
            Names.call("entity.invulnerable", helper, true);
            Names.call("entity.silent", helper, true);
            if (Names.hasField("Entity.noPhysics")) Names.field("Entity.noPhysics", helper, true);
            Names.call("entity.position", helper, x, y, z); shape(helper);
        }
    }

    private static Object create(String type, String registry, Object level) throws Exception {
        return Names.type(type).getDeclaredConstructor(Names.type("EntityType"), Names.type("Level"))
                .newInstance(Names.field("EntityType." + registry, null), level);
    }

    void mount(Object level) throws Exception {
        // Pairing can send the follower's spawn position as soon as it enters
        // the level. Seed both legacy helpers at their final native offsets.
        configureGeometry();
        for (Object helper : helpers) if (!Boolean.TRUE.equals(Names.call("level.add", level, helper)))
            throw new IllegalStateException("Native helper spawn cancelled");
        Object parent = nativeGraph ? horse : null;
        for (Object helper : helpers) {
            if (parent != null && !Names.mount(helper, parent)) throw new IllegalStateException("Native helper mount cancelled");
            parent = helper;
        }
        update(true);
        // Queue final native metadata before the real player's mount packet.
        // Calibration never temporarily relocates the player to an intermediate
        // helper height, including during active settings adjustments.
        for (Object helper : helpers) TandemRuntime.flushHelperMetadata(helper);
        if (!Names.mount(rider, seat)) throw new IllegalStateException("Native rider mount cancelled");
        Names.call("entity.positionRider", seat, rider);
    }

    private void configureGeometry() throws Exception {
        Settings settings = TandemRuntime.SETTINGS;
        if (!nativeGraph) {
            double yaw = Math.toRadians(number("entity.yaw", horse));
            double x = number("entity.x", horse) + Math.sin(yaw) * settings.rear;
            double z = number("entity.z", horse) - Math.cos(yaw) * settings.rear;
            double anchorToSeat = number("entity.passengerHeight", anchor) + riderOffset(seat, anchor);
            double seatY = number("entity.y", horse) + settings.height
                    - number("entity.passengerHeight", seat) - riderOffset(rider, seat);
            Names.call("entity.position", anchor, x, seatY - anchorToSeat, z);
            float heading = (float) Math.toDegrees(yaw);
            Names.call("entity.rotation", anchor, heading);
            Names.call("entity.bodyRotation", anchor, heading);
            Names.call("entity.headRotation", anchor, heading);
            if (Names.call("entity.vehicle", seat) == anchor) Names.call("entity.positionRider", anchor, seat);
            else Names.call("entity.position", seat, x, seatY, z);
            shape(anchor); shape(seat);
            if (Names.call("entity.vehicle", rider) == seat) Names.call("entity.positionRider", seat, rider);
            return;
        }
        if (scaled && lastRear != settings.rear) {
            scale(carriers[0], settings.rear / LLAMA_REAR + MIN_SCALE);
            scale(carriers[1], MIN_SCALE);
        }
        // Rotation is native metadata. Opposing lateral components cancel in
        // the fixed-size chain used before the SCALE attribute existed.
        double yaw = number("entity.yaw", horse);
        double spread = scaled ? 0 : Math.toDegrees(Math.acos(settings.rear / (carriers.length * LLAMA_REAR)));
        for (int i = 0; i < carriers.length; i++) {
            float angle = (float) (yaw + (scaled ? (i == 0 ? 0 : 180) : ((i & 1) == 0 ? spread : -spread)));
            Names.call("entity.rotation", carriers[i], angle);
            Names.call("entity.headRotation", carriers[i], angle);
            Names.call("entity.bodyRotation", carriers[i], angle);
        }
    }

    void update(boolean settingsChanged) throws Exception {
        Settings settings = TandemRuntime.SETTINGS;
        configureGeometry();
        if (!nativeGraph) return;
        if (settingsChanged || lastHeight != settings.height || lastRear != settings.rear) {
            // Calibrate using the exact runtime's native passenger methods.
            // Interaction height contributes .75 before 1.20.2 and 1 afterward;
            // player attachment offsets also changed. Only synced local height
            // is changed, so the vanilla client computes the identical graph.
            height(lift, 0F);
            place();
            double base = riderFeet() - number("entity.y", horse);
            height(lift, 1F);
            place();
            double factor = riderFeet() - number("entity.y", horse) - base;
            if (!Double.isFinite(factor) || Math.abs(factor) < 0.001) throw new IllegalStateException("Native Interaction height has no passenger effect");
            height(lift, (float) ((settings.height - base) / factor));
            lastHeight = settings.height;
            lastRear = settings.rear;
        }
        place();
        if (Names.call("entity.vehicle", rider) == seat) Names.call("entity.positionRider", seat, rider);
    }

    private double riderFeet() throws Exception {
        double result;
        if (Names.has("entity.passengerPoint")) result = ((Number) Names.field("Vec3.y", Names.call("entity.passengerPoint", seat, rider))).doubleValue();
        else result = number("entity.y", seat) + number("entity.passengerHeight", seat);
        if (Names.has("entity.vehiclePoint")) return result - ((Number) Names.field("Vec3.y", Names.call("entity.vehiclePoint", rider, seat))).doubleValue();
        return result + riderOffset(rider, seat);
    }

    private static double riderOffset(Object passenger, Object vehicle) throws Exception {
        return ((Number) (Names.method("entity.riderOffset").getParameterCount() == 0
                ? Names.call("entity.riderOffset", passenger) : Names.call("entity.riderOffset", passenger, vehicle))).doubleValue();
    }

    private void scale(Object carrier, double value) throws Exception {
        Object attribute = Names.call("living.attribute", carrier, Names.field("Attributes.SCALE", null));
        if (attribute == null) throw new IllegalStateException("Native SCALE unavailable");
        Names.call("attribute.base", attribute, value);
        Names.call("entity.refreshDimensions", carrier);
        shape(carrier);
    }

    private static void height(Object entity, float value) throws Exception {
        Names.call("interaction.height", entity, value);
        Object standing = Names.field("Pose.STANDING", null);
        Names.call("entity.pose", entity, Names.call("entity.getPose", entity) == standing ? Names.field("Pose.SITTING", null) : standing);
        TandemRuntime.dimensionsChanged(entity);
    }

    private void place() throws Exception {
        Object parent = horse;
        for (Object helper : helpers) {
            Names.call("entity.positionRider", parent, helper);
            shape(helper);
            parent = helper;
        }
    }

    Object passengerTransition(Object transition) throws Exception {
        // The accepted root transfer has reached the nonliving anchor. Restore
        // descendants before any living carrier can dismount and alter offsets.
        Object parent = anchor;
        for (int i = 1; i < helpers.length; i++) {
            Names.call("entity.positionRider", parent, helpers[i]); shape(helpers[i]); parent = helpers[i];
        }
        Names.call("entity.positionRider", seat, rider);
        Object passenger = Names.call("entity.passengerTransition", anchor, transition, rider);
        Object absolute = Names.call("movement.absolute", null, Names.call("movement.entity", null, rider),
                Names.call("movement.transition", null, passenger), Names.call("transition.relatives", passenger));
        return Names.type("TeleportTransition").getConstructor(Names.type("ServerLevel"), Names.type("Vec3"), Names.type("Vec3"),
                float.class, float.class, Set.class, Names.type("PostTeleportTransition"))
                .newInstance(Names.call("transition.level", passenger), Names.call("movement.position", absolute), Names.call("movement.velocity", absolute),
                        Names.call("movement.yaw", absolute), Names.call("movement.pitch", absolute), java.util.Collections.emptySet(), Names.call("transition.post", passenger));
    }

    static void shape(Object entity) throws Exception {
        double x = number("entity.x", entity), y = number("entity.y", entity), z = number("entity.z", entity);
        Names.call("entity.bounds", entity, Names.type("AABB").getConstructor(double.class, double.class, double.class,
                double.class, double.class, double.class).newInstance(x, y, z, x, y, z));
    }

    boolean valid() throws Exception {
        Object parent = nativeGraph ? horse : null;
        for (Object helper : helpers) {
            if (!Boolean.TRUE.equals(Names.call("entity.alive", helper)) || Names.call("entity.vehicle", helper) != parent
                    || Names.call("entity.level", helper) != Names.call("entity.level", horse)) return false;
            parent = helper;
        }
        return Names.call("entity.rootVehicle", rider) == (nativeGraph ? horse : anchor);
    }

    private static double number(String key, Object entity) throws Exception { return ((Number) Names.call(key, entity)).doubleValue(); }
}
