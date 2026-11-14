package dev.eyecrasher.tandemount.modern;

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
    private static final Set<Object> DIMENSIONS_DIRTY = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private static final Map<Object, Object> TRACKERS = new IdentityHashMap<Object, Object>();
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
    static void dimensionsChanged(Object entity) { if (isSeat(entity)) DIMENSIONS_DIRTY.add(entity); }
    private static Object posePacket(Object entity) throws Exception {
        Object value = Names.call("data.value", null, Names.field("Entity.DATA_POSE", null), Names.call("entity.getPose", entity));
        return Names.type("EntityDataPacket").getConstructor(int.class, List.class)
                .newInstance(((Number)Names.call("entity.id", entity)).intValue(), Collections.singletonList(value));
    }
    @SuppressWarnings("unchecked") public static void pairingTail(Object tracker, Object collector) {
        try {
            Object entity = Names.field("ServerEntity.entity", tracker);
            if (!isSeat(entity) || !Names.TABLE.containsKey("c.Interaction") || !Names.type("Interaction").isInstance(entity)) return;
            Object packet = posePacket(entity);
            if (collector instanceof java.util.function.Consumer) ((java.util.function.Consumer<Object>)collector).accept(packet);
            else Names.call("pairing.accept", collector, packet);
        } catch (Exception failure) { error("Native pairing dimensions", failure); }
    }
    public static void dirtyMetadataTail(Object tracker) {
        try {
            Object entity = Names.field("ServerEntity.entity", tracker);
            if (!DIMENSIONS_DIRTY.remove(entity)) return;
            Object packet = posePacket(entity);
            if (Names.has("tracker.broadcast")) Names.call("tracker.broadcast", tracker, packet);
            else Names.call("synchronizer.broadcast", Names.field("ServerEntity.synchronizer", tracker), packet);
        } catch (Exception failure) { error("Native tracked dimensions", failure); }
    }
    static void flushHelperMetadata(Object entity) throws Exception {
        Object tracker = TRACKERS.get(entity);
        if (tracker != null) Names.call("tracker.flushMetadata", tracker);
    }
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
            // A stale slot may be reclaimed synchronously, never on a deferred retry.
            Ride prior = RIDES.get(horse);
            if (prior != null) {
                if (valid(prior)) return original;
                remove(prior);
            }
            List<?> passengers = (List<?>) Names.call("entity.passengers", horse);
            if (passengers.size() != 1 || !Names.type("ServerPlayer").isInstance(passengers.get(0))) return original;
            for (Ride ride : RIDES.values()) if (ride.rider == player || ride.driver == player) return original;
            Object level = Names.call("entity.level", horse);
            if (!loaded(level, Names.call("entity.blockPosition", horse))) return original;
            if (!NativeAttachment.available()) {
                // This capability difference is explicit; an independent follower
                // is never substituted for a native passenger attachment.
                debug("Native attachment unavailable: pre-Interaction height capability unresolved");
                return original;
            }
            if (!destinationLoaded(horse, level)) return original;
            NativeAttachment attachment = new NativeAttachment(horse, player, level);
            Ride ride = new Ride(horse, passengers.get(0), player, attachment, level);
            for (Object helper : attachment.helpers) SEATS.add(helper);
            RIDES.put(horse, ride); // Only this ride's registered anchor receives capacity.
            try {
                if (!destinationLoaded(ride)) { remove(ride); return original; }
                attachment.mount(level);
                if (!valid(ride)) { remove(ride); return original; }
                debug("Ride opened");
                return Names.field("InteractionResult.SUCCESS", null);
            } catch (Exception exception) {
                remove(ride);
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
        return ride.attachment.valid();
    }
    private static boolean destinationLoaded(Ride ride) throws Exception {
        return destinationLoaded(ride.horse, ride.level);
    }
    private static boolean destinationLoaded(Object horse, Object level) throws Exception {
        // Guard every possible native helper destination before native position
        // calls. Fixed-size carriers span at most 2.4 blocks in either horizontal
        // direction; scaled carriers span at most rear + .04. Native 1.16.5
        // setters can load chunks, so no graph primitive bypasses this check.
        double x = number("entity.x", horse), y = number("entity.y", horse), z = number("entity.z", horse);
        double yaw = Math.toRadians(number("entity.yaw", horse));
        double rearX = x + Math.sin(yaw) * SETTINGS.rear;
        double rearZ = z - Math.cos(yaw) * SETTINGS.rear;
        if (!loaded(level, blockPosition(rearX, y + SETTINGS.height, rearZ))) return false;
        if (Names.hasField("Attributes.SCALE")) {
            double reach = SETTINGS.rear + (double)0.3F / 16;
            if (!loaded(level, blockPosition(x + Math.sin(yaw)*reach, y, z-Math.cos(yaw)*reach))) return false;
        }
        if (!Names.hasField("Attributes.SCALE")) {
            // A paired angular chain has a bounded temporary lateral excursion.
            double spread = Math.acos(SETTINGS.rear / (8 * (double) 0.3F));
            double dx = Math.sin(yaw + spread) * (double) 0.3F;
            double dz = -Math.cos(yaw + spread) * (double) 0.3F;
            for (int pair = 0; pair < 4; pair++) {
                double fraction = pair / 4.0;
                if (!loaded(level, blockPosition(x + (rearX-x)*fraction + dx, y, z + (rearZ-z)*fraction + dz))) return false;
            }
        }
        return true;
    }
    private static Object blockPosition(double x, double y, double z) throws Exception {
        return Names.type("BlockPos").getConstructor(int.class, int.class, int.class)
                .newInstance((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
    private static boolean update(Ride ride) throws Exception {
        if (!destinationLoaded(ride)) return false;
        ride.attachment.update(false);
        return true;
    }
    public static boolean allowHelperPassenger(Object horse, Object passenger) {
        Ride ride = RIDES.get(horse);
        if (ride == null || ride.attachment.anchor != passenger) return false;
        try {
            List<?> occupants = (List<?>) Names.call("entity.passengers", horse);
            return occupants.size() == 1 && occupants.get(0) == ride.driver;
        } catch (Exception failure) { return false; }
    }
    public static void entityTick(Object entity) {
        Ride ride = RIDES.get(entity);
        if (ride == null) return;
        try { if (!valid(ride) || !update(ride)) remove(ride); }
        catch (Exception exception) { error("Ride cleanup", exception); remove(ride); }
    }
    public static void tracker(Object tracker, Object entity) {
        if (!isSeat(entity)) return;
        try {
            TRACKERS.put(entity, tracker);
            Names.field("ServerEntity.updateInterval", tracker, Names.fieldType("ServerEntity.updateInterval") == int.class ? 1 : Names.call("update.periodic", null, 1));
        }
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
        if (Boolean.getBoolean("tandemount.networktest")) ModernWireTests.tick(server, ticks);
    }
    public static void stop(Object server) {
        for (Ride ride : new ArrayList<Ride>(RIDES.values())) remove(ride);
        SERVERS.remove(server);
    }
    private static void remove(Ride ride) {
        RIDES.remove(ride.horse);
        for (int index = ride.attachment.helpers.length - 1; index >= 0; index--) discardSeat(ride.attachment.helpers[index]);
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
        finally { SEATS.remove(seat); DIMENSIONS_DIRTY.remove(seat); TRACKERS.remove(seat); }
    }
    public static int rideCount() { return RIDES.size(); }
    public static int seatCount() { return RIDES.size(); }
    public static int helperCount() { return SEATS.size(); }
    static Object[] helpersFor(Object horse) { Ride ride = RIDES.get(horse); return ride == null ? new Object[0] : ride.attachment.helpers.clone(); }
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
        final NativeAttachment attachment;
        Ride(Object horse, Object driver, Object rider, NativeAttachment attachment, Object level) { this.horse=horse; this.driver=driver; this.rider=rider; this.attachment=attachment; this.seat=attachment.seat; this.level=level; }
    }
}
