package dev.eyecrasher.tandemount.modern;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-thread ride state, shared by the modern loader adapters. */
public final class TandemRuntime {
    private static final Map<Object, Ride> RIDES = new IdentityHashMap<Object, Ride>();
    private static final Set<Object> SEATS = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private static final Set<Object> SERVERS = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    public static final Settings SETTINGS = new Settings();
    private static boolean initialized;
    private static boolean failed;
    private static long ticks;
    private TandemRuntime() {}
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        try { SETTINGS.load(); log("Loaded for " + Names.TABLE.getProperty("platform") + " " + Names.TABLE.getProperty("minecraft")); }
        catch (Exception exception) { failed = true; log("Configuration rejected; riding disabled. " + exception.getClass().getSimpleName()); }
    }
    public static boolean isSeat(Object entity) { return SEATS.contains(entity); }
    public static Object pass() {
        try { return Names.field("InteractionResult.PASS", null); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public static void vanillaRideAttempt(Object horse, Object player) {
        // Older vanilla versions consume the occupied-horse interaction after
        // their ordinary mount attempt. This hook runs only on that native ride
        // path, after surrounding interaction/protection decisions have run.
        try { interact(horse, player, Names.field("InteractionHand.MAIN_HAND", null), pass()); }
        catch (Exception failure) { error("Native ride attempt", failure); }
    }
    public static Object interact(Object horse, Object player, Object hand, Object original) {
        initialize();
        if (failed || !SETTINGS.enabled || original != pass()) return original;
        try {
            if (hand != Names.field("InteractionHand.MAIN_HAND", null) || !Names.type("ServerPlayer").isInstance(player)) return original;
            if (horse.getClass() != Names.type("Horse") || Names.call("entity.level", horse) != Names.call("entity.level", player)) return original;
            if (!(Boolean) Names.call("horse.tamed", horse) || !(Boolean) Names.call("horse.saddled", horse) || (Boolean) Names.call("horse.baby", horse)) return original;
            if (!(Boolean) Names.call("entity.alive", horse) || !(Boolean) Names.call("entity.alive", player) || Names.call("entity.vehicle", player) != null) return original;
            List<?> passengers = (List<?>) Names.call("entity.passengers", horse);
            if (passengers.size() != 1 || !Names.type("ServerPlayer").isInstance(passengers.get(0))) return original;
            // A stale slot may be reclaimed synchronously, never on a deferred retry.
            Ride prior = RIDES.get(horse);
            if (prior != null) {
                if (valid(prior)) return original;
                remove(prior);
            }
            for (Ride ride : RIDES.values()) if (ride.rider == player || ride.driver == player) return original;
            Object level = Names.call("entity.level", horse);
            if (!loaded(level, Names.call("entity.blockPosition", horse))) return original;
            Constructor<?> constructor = Names.type("AreaEffectCloud").getConstructor(Names.type("Level"), double.class, double.class, double.class);
            Object seat = constructor.newInstance(level, number("entity.x", horse), number("entity.y", horse), number("entity.z", horse));
            SEATS.add(seat); // Before addFreshEntity: saving/tracking hooks recognize it immediately.
            try {
                Names.call("entity.invisible", seat, true);
                Names.call("entity.gravity", seat, true);
                Names.call("entity.invulnerable", seat, true);
                if (Names.hasField("Entity.noPhysics")) Names.field("Entity.noPhysics", seat, true);
                Names.call("cloud.radius", seat, 0.0f);
                Names.call("cloud.waitTime", seat, 0);
                Names.call("cloud.waiting", seat, false);
                Names.call("cloud.radiusOnUse", seat, 0.0f);
                Names.call("cloud.radiusPerTick", seat, 0.0f);
                // Vanilla initializes this adjustment to zero. Some exact
                // loader namespaces do not expose a callable mapped setter.
                Names.callIfPresent("cloud.durationOnUse", seat, 0);
                int duration = Names.hasField("AreaEffectCloud.INFINITE_DURATION")
                        ? ((Number) Names.field("AreaEffectCloud.INFINITE_DURATION", null)).intValue()
                        : Integer.MAX_VALUE;
                Names.call("cloud.duration", seat, duration);
                Ride ride = new Ride(horse, passengers.get(0), player, seat, level);
                if (!update(ride)) { discardSeat(seat); return original; }
                if (!Boolean.TRUE.equals(Names.call("level.add", level, seat))) { if (Boolean.getBoolean("tandemount.selftest")) log("Native seat insertion refused"); discardSeat(seat); return original; }
                // false retains normal native mount vetoes; it never overrides them.
                if (!Names.mount(player, seat)) { discardSeat(seat); return original; }
                Names.call("entity.positionRider", seat, player);
                ride.riderOffset = number("entity.y", player) - number("entity.y", seat);
                if (!update(ride)) { discardSeat(seat); return original; }
                RIDES.put(horse, ride);
                debug("Ride opened");
                return Names.field("InteractionResult.SUCCESS", null);
            } catch (Exception exception) {
                discardSeat(seat);
                throw exception;
            }
        } catch (Exception exception) { error("Mount rejected", exception); return original; }
    }
    private static double number(String method, Object entity) throws Exception { return ((Number) Names.call(method, entity)).doubleValue(); }
    private static boolean loaded(Object level, Object position) throws Exception {
        return !Names.has("level.loaded") || Boolean.TRUE.equals(Names.call("level.loaded", level, position));
    }
    private static boolean valid(Ride ride) throws Exception {
        if (!SETTINGS.enabled) return false;
        for (Object entity : new Object[] {ride.horse, ride.driver, ride.rider, ride.seat}) {
            if (!(Boolean) Names.call("entity.alive", entity)) return false;
            if (Names.has("entity.removed") && (Boolean) Names.call("entity.removed", entity)) return false;
            if (Names.call("entity.level", entity) != ride.level) return false;
        }
        if (Names.has("player.disconnect") && ((Boolean) Names.call("player.disconnect", ride.driver) || (Boolean) Names.call("player.disconnect", ride.rider))) return false;
        if (Names.call("entity.vehicle", ride.driver) != ride.horse || Names.call("entity.vehicle", ride.rider) != ride.seat) return false;
        if (!((List<?>) Names.call("entity.passengers", ride.horse)).contains(ride.driver)) return false;
        if (!((List<?>) Names.call("entity.passengers", ride.seat)).contains(ride.rider)) return false;
        if (!loaded(ride.level, Names.call("entity.blockPosition", ride.horse))) return false;
        return true;
    }
    private static boolean update(Ride ride) throws Exception {
        double yaw;
        if (Names.hasField("LivingEntity.yBodyRot")) yaw = ((Number) Names.field("LivingEntity.yBodyRot", ride.horse)).doubleValue();
        else yaw = number("entity.yaw", ride.horse);
        double radians = Math.toRadians(yaw);
        double x = number("entity.x", ride.horse) + Math.sin(radians) * SETTINGS.rear;
        double z = number("entity.z", ride.horse) - Math.cos(radians) * SETTINGS.rear;
        double y = number("entity.y", ride.horse) + SETTINGS.height - ride.riderOffset;
        // Forge 1.16.5's native position setter loads the target chunk for added
        // entities. Check the offset seat's chunk as well as the horse's before
        // any native position, rider-position, or world-insertion operation.
        Object destination = Names.type("BlockPos").getConstructor(int.class, int.class, int.class)
                .newInstance((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (!loaded(ride.level, destination)) return false;
        Names.call("entity.position", ride.seat, x, y, z);
        Names.call("entity.rotation", ride.seat, (float) yaw);
        if (Names.call("entity.vehicle", ride.rider) == ride.seat) Names.call("entity.positionRider", ride.seat, ride.rider);
        return true;
    }
    public static void entityTick(Object entity) {
        Ride ride = RIDES.get(entity);
        if (ride == null) return;
        try { if (!valid(ride) || !update(ride)) remove(ride); }
        catch (Exception exception) { error("Ride cleanup", exception); remove(ride); }
    }
    public static void tracker(Object tracker, Object entity) {
        if (!isSeat(entity)) return;
        try { Names.field("ServerEntity.updateInterval", tracker, Names.fieldType("ServerEntity.updateInterval") == int.class ? 1 : Names.call("update.periodic", null, 1)); }
        catch (Exception exception) { error("Seat tracking interval", exception); }
    }
    public static void tick(Object server) {
        initialize();
        ticks++;
        if (SERVERS.add(server)) {
            try { AdminCommands.register(server); }
            catch (Exception exception) { error("Command registration", exception); }
        }
        for (Ride ride : new ArrayList<Ride>(RIDES.values())) {
            try { if (!valid(ride)) remove(ride); }
            catch (Exception exception) { remove(ride); error("Ride lifecycle", exception); }
        }
        if (Boolean.getBoolean("tandemount.networktest")) ProtocolEvidence.tick(server, ticks);
        if (Boolean.getBoolean("tandemount.selftest")) NativeTests.tick(server, ticks);
    }
    public static void stop(Object server) {
        for (Ride ride : new ArrayList<Ride>(RIDES.values())) remove(ride);
        SERVERS.remove(server);
    }
    private static void remove(Ride ride) {
        RIDES.remove(ride.horse);
        discardSeat(ride.seat);
        debug("Ride closed");
    }
    private static void discardSeat(Object seat) {
        try {
            // Older native remove() leaves its passengers attached until their
            // next tick. Detach synchronously so an immediate retry is safe.
            for (Object passenger : new ArrayList<Object>((List<?>) Names.call("entity.passengers", seat))) Names.call("entity.dismount", passenger);
            Names.call("entity.discard", seat);
        }
        catch (Exception exception) { error("Seat removal", exception); }
        finally { SEATS.remove(seat); }
    }
    public static int rideCount() { return RIDES.size(); }
    public static int seatCount() { return SEATS.size(); }
    static Object seatFor(Object horse) { Ride ride = RIDES.get(horse); return ride == null ? null : ride.seat; }
    public static void applySettings() {
        failed = false;
        for (Ride ride : new ArrayList<Ride>(RIDES.values())) {
            try { if (!SETTINGS.enabled || !valid(ride) || !update(ride)) remove(ride); }
            catch (Exception exception) { remove(ride); error("Settings application", exception); }
        }
    }
    static void log(String message) { System.out.println("[Tandemount] " + message); }
    private static void debug(String message) { if (SETTINGS.debug) log(message); }
    static void error(String action, Exception exception) {
        // Do not log entity names, identifiers, positions, chat, credentials or authentication data.
        log(action + ": " + exception.getClass().getSimpleName());
        if (Boolean.getBoolean("tandemount.selftest")) exception.printStackTrace();
    }
    private static final class Ride {
        final Object horse, driver, rider, seat, level;
        double riderOffset;
        Ride(Object horse, Object driver, Object rider, Object seat, Object level) { this.horse=horse; this.driver=driver; this.rider=rider; this.seat=seat; this.level=level; }
    }
}
