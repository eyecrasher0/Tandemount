/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount.modern;

import java.util.ArrayList;
import java.util.List;

/** Vanilla passenger geometry. No independently followed entity or position packet. */
final class NativeAttachment {
    private static final double LLAMA_REAR = (double) 0.3F;
    private static final double MIN_SCALE = 1.0 / 16.0;
    final Object horse, rider, seat, anchor, lift;
    final Object[] helpers, carriers;
    private final boolean scaled;
    private double lastHeight = Double.NaN, lastRear = Double.NaN;

    static boolean available() { return Names.TABLE.containsKey("c.Interaction") && Names.TABLE.containsKey("c.BlockDisplay"); }

    NativeAttachment(Object horse, Object rider, Object level) throws Exception {
        this.horse = horse;
        this.rider = rider;
        scaled = Names.hasField("Attributes.SCALE");
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
            if (!scaled && i + 1 < carriers.length) graph.add(create("Interaction", "INTERACTION", level));
        }
        graph.add(lift);
        graph.add(seat);
        helpers = graph.toArray();
        for (Object helper : helpers) {
            Names.call("entity.invisible", helper, true);
            Names.call("entity.gravity", helper, true);
            Names.call("entity.invulnerable", helper, true);
            Names.call("entity.silent", helper, true);
            if (Names.hasField("Entity.noPhysics")) Names.field("Entity.noPhysics", helper, true);
        }
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
        double x = number("entity.x", horse), y = number("entity.y", horse), z = number("entity.z", horse);
        for (Object helper : helpers) { Names.call("entity.position", helper, x, y, z); shape(helper); }
    }

    private static Object create(String type, String registry, Object level) throws Exception {
        return Names.type(type).getDeclaredConstructor(Names.type("EntityType"), Names.type("Level"))
                .newInstance(Names.field("EntityType." + registry, null), level);
    }

    void mount(Object level) throws Exception {
        configureGeometry();
        for (Object helper : helpers) if (!Boolean.TRUE.equals(Names.call("level.add", level, helper)))
            throw new IllegalStateException("Native helper spawn cancelled");
        Object parent = horse;
        for (Object helper : helpers) {
            if (!Names.mount(helper, parent)) throw new IllegalStateException("Native helper mount cancelled");
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
        return result + ((Number)(Names.method("entity.riderOffset").getParameterCount() == 0
                ? Names.call("entity.riderOffset", rider) : Names.call("entity.riderOffset", rider, seat))).doubleValue();
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

    static void shape(Object entity) throws Exception {
        double x = number("entity.x", entity), y = number("entity.y", entity), z = number("entity.z", entity);
        Names.call("entity.bounds", entity, Names.type("AABB").getConstructor(double.class, double.class, double.class,
                double.class, double.class, double.class).newInstance(x, y, z, x, y, z));
    }

    boolean valid() throws Exception {
        Object parent = horse;
        for (Object helper : helpers) {
            if (!Boolean.TRUE.equals(Names.call("entity.alive", helper)) || Names.call("entity.vehicle", helper) != parent
                    || Names.call("entity.level", helper) != Names.call("entity.level", horse)) return false;
            parent = helper;
        }
        return Names.call("entity.rootVehicle", rider) == horse;
    }

    private static double number(String key, Object entity) throws Exception { return ((Number) Names.call(key, entity)).doubleValue(); }
}
