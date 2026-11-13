package dev.eyecrasher.tandemount.modern;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Opt-in disposable-server native fixture. Never enabled in ordinary operation. */
public final class NativeTests {
    private static boolean done;
    private static final List<String> checks = new ArrayList<String>();
    private NativeTests() {}
    public static void tick(Object server, long ticks) {
        if (done || ticks < 20) return;
        done = true;
        try {
            if (Boolean.getBoolean("tandemount.selftest.restart")) {
                Object level = Names.call("server.overworld", server);
                if (Names.has("level.chunk")) Names.call("level.chunk", level, 0, 0);
                int clouds = 0;
                for (Object entity : (Iterable<?>) Names.call("level.entities", level)) if (Names.type("AreaEffectCloud").isInstance(entity)) clouds++;
                check(clouds == 0, "restart-has-no-persisted-seat-artifact");
                empty("restart-has-no-stale-ride-state");
            } else run(server);
            record(true, "");
        } catch (Throwable failure) {
            failure.printStackTrace();
            record(false, failure.getClass().getSimpleName());
        } finally {
            try { if (!Boolean.getBoolean("tandemount.networktest")) Names.call("server.stop", server, false); }
            catch (Exception exception) { exception.printStackTrace(); }
        }
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks.add(name);
        TandemRuntime.log("NATIVE CHECK " + name);
    }
    private static Object player(Object server, Object level, int index) throws Exception {
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileClass.getConstructor(UUID.class, String.class).newInstance(UUID.nameUUIDFromBytes(("TandemountFixture" + index).getBytes(StandardCharsets.UTF_8)), "TandemTest" + index);
        // The generated descriptor is validated against the exact server JAR.
        // Four arguments alone do not identify the constructor: 1.19–1.19.2
        // take a nullable profile key, not the old game-mode or new client info.
        String[] declaration = Names.TABLE.getProperty("constructor.player").split("#");
        Class<?>[] types = Names.arguments(declaration[2]);
        if (types.length < 3 || types.length > 4 || types[0] != Names.type("MinecraftServer")
                || types[1] != Names.type("ServerLevel") || types[2] != profileClass) {
            throw new IllegalStateException("Unsupported exact native fixture player constructor");
        }
        Object[] args = new Object[types.length];
        args[0] = server; args[1] = level; args[2] = profile;
        if (types.length == 4) {
            if (Names.TABLE.containsKey("c.ClientInformation") && types[3] == Names.type("ClientInformation")) {
                args[3] = Names.call("client.default", null);
            } else if (Names.TABLE.containsKey("c.ProfilePublicKey") && types[3] == Names.type("ProfilePublicKey")) {
                args[3] = null; // Exact vanilla parameter is @Nullable for offline fixture players.
            } else if (Names.TABLE.containsKey("c.ServerPlayerGameMode") && types[3] == Names.type("ServerPlayerGameMode")) {
                args[3] = types[3].getConstructor(Names.type("ServerLevel")).newInstance(level);
            } else throw new IllegalStateException("Unsupported exact native fixture player argument " + types[3].getName());
        }
        Constructor<?> constructor = Names.type("ServerPlayer").getConstructor(types);
        Object player = constructor.newInstance(args);
        Object connection = Names.type("Connection").getConstructor(Names.type("PacketFlow")).newInstance(Names.field("PacketFlow.SERVERBOUND", null));
        Class<?> channelClass = Class.forName("io.netty.channel.embedded.EmbeddedChannel");
        Object channel = channelClass.getConstructor().newInstance();
        Names.field("Connection.channel", connection, channel);
        if (Names.has("cookie.initial")) {
            Object cookie = Names.call("cookie.initial", null, profile, false);
            Names.type("ServerGamePacketListenerImpl").getConstructor(Names.type("MinecraftServer"), Names.type("Connection"), Names.type("ServerPlayer"), Names.type("CommonListenerCookie")).newInstance(server, connection, player, cookie);
        } else {
            Names.type("ServerGamePacketListenerImpl").getConstructor(Names.type("MinecraftServer"), Names.type("Connection"), Names.type("ServerPlayer")).newInstance(server, connection, player);
        }
        Names.call("entity.position", player, 0.5, 80.0, 8.5);
        return player;
    }
    private static Object horse(Object level, double x) throws Exception {
        Object entityType = Names.field("EntityType.HORSE", null);
        Object horse = Names.type("Horse").getConstructor(Names.type("EntityType"), Names.type("Level")).newInstance(entityType, level);
        Names.call("entity.position", horse, x, 80.0, 8.5);
        Names.call("horse.tame", horse, true);
        Names.call("horse.age", horse, 0);
        // Native saddle API changed to equipment slots in recent releases.
        if (Names.has("horse.saddle")) {
            Object source = Names.field("SoundSource.PLAYERS", null);
            if (Names.method("horse.saddle").getParameterCount() == 1) Names.call("horse.saddle", horse, source);
            else {
                Object saddle = Names.type("ItemStack").getConstructor(Names.type("ItemLike")).newInstance(Names.field("Items.SADDLE", null));
                Names.call("horse.saddle", horse, saddle, source);
            }
        } else if (Names.has("living.equip") && Names.hasField("EquipmentSlot.SADDLE")) {
            Object saddle = Names.type("ItemStack").getConstructor(Names.type("ItemLike")).newInstance(Names.field("Items.SADDLE", null));
            Names.call("living.equip", horse, Names.field("EquipmentSlot.SADDLE", null), saddle);
        } else throw new IllegalStateException("No exact native saddle fixture API");
        check(Boolean.TRUE.equals(Names.call("level.add", level, horse)), "horse-added");
        check(Boolean.TRUE.equals(Names.call("horse.tamed", horse)), "fixture-native-horse-tamed");
        check(Boolean.TRUE.equals(Names.call("horse.saddled", horse)), "fixture-native-horse-saddled");
        check(Boolean.FALSE.equals(Names.call("horse.baby", horse)), "fixture-native-horse-adult");
        return horse;
    }
    private static void empty(String checkName) {
        check(TandemRuntime.rideCount() == 0 && TandemRuntime.seatCount() == 0, checkName);
    }
    private static void setFixturePlayerLevel(Object player, Object level) throws Exception {
        // Exact vanilla setter moved from Entity to ServerPlayer in 1.17.
        if (Names.callIfPresent("entity.setLevel", player, level)) return;
        if (Names.callIfPresent("player.setLevel", player, level)) return;
        // Newer releases expose no mapped setter; this fixture mutates only the
        // exact mapped level field to exercise TandemRuntime's world-change guard.
        Names.field("Entity.level", player, level);
    }
    private static Object blockPosition(double x, double y, double z) throws Exception {
        return Names.type("BlockPos").getConstructor(int.class, int.class, int.class)
                .newInstance((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
    private static boolean loaded(Object level, double x, double y, double z) throws Exception {
        return Boolean.TRUE.equals(Names.call("level.loaded", level, blockPosition(x, y, z)));
    }
    private static void setFixturePositionWithoutChunkAccess(Object entity, double x, double y, double z) throws Exception {
        // Model unavailable-chunk state without calling a native teleport or
        // position setter: Forge 1.16.5 deliberately loads their target chunk.
        // Used only for an immediate lifecycle guard probe, never native ticking.
        Names.field("Entity.position", entity, Names.type("Vec3").getConstructor(double.class, double.class, double.class).newInstance(x, y, z));
        Names.field("Entity.blockPosition", entity, blockPosition(x, y, z));
        if (Names.hasField("Entity.chunkPosition")) Names.field("Entity.chunkPosition", entity,
                Names.type("ChunkPos").getConstructor(int.class, int.class).newInstance((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4));
    }
    private static void teleport(Object player, Object level, double x, double y, double z) throws Exception {
        check(loaded(level, x, y, z), "fixture-native-teleport-destination-already-loaded");
        Class<?>[] types = Names.method("player.teleport").getParameterTypes();
        Object[] args = new Object[types.length];
        int coordinate = 0;
        for (int index = 0; index < types.length; index++) {
            if (types[index] == Names.type("ServerLevel")) args[index] = level;
            else if (types[index] == double.class) args[index] = new double[]{x, y, z}[coordinate++];
            else if (types[index] == float.class) args[index] = 0.0f;
            else if (types[index] == boolean.class) args[index] = false;
            else if (Set.class.isAssignableFrom(types[index])) args[index] = Collections.emptySet();
            else throw new IllegalStateException("Unsupported exact player teleport fixture argument " + types[index].getName());
        }
        Object result = Names.call("player.teleport", player, args);
        check(!Boolean.FALSE.equals(result)
                && Names.call("entity.level", player) == level
                && Math.abs(((Number) Names.call("entity.x", player)).doubleValue() - x) < 0.000001
                && Math.abs(((Number) Names.call("entity.z", player)).doubleValue() - z) < 0.000001,
                "fixture-native-player-teleport-applied");
    }
    private static Object interact(Object player, Object horse, Object hand) throws Exception {
        if (Names.method("player.interact").getParameterCount() == 3) return Names.call("player.interact", player, horse, hand, null);
        return Names.call("player.interact", player, horse, hand);
    }
    private static String dimension(Object level) throws Exception {
        return Names.call("dimension.location", Names.call("level.dimension", level)).toString();
    }
    private static List<Object> pairingPackets(Object level, Object entity, Object observer) throws Exception {
        String[] declaration = Names.TABLE.getProperty("h.tracker").split("#");
        Class<?>[] types = Names.arguments(declaration[2]);
        Object[] args = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (type == Names.type("Entity")) args[index] = entity;
            else if (type == Names.type("ServerLevel")) args[index] = level;
            else if (type == int.class) args[index] = 3;
            else if (type == boolean.class) args[index] = false;
            else if (Names.TABLE.containsKey("c.UpdateInterval") && type == Names.type("UpdateInterval")) args[index] = Names.call("update.periodic", null, 3);
            else if (Consumer.class.isAssignableFrom(type)) args[index] = (Consumer<Object>) packet -> {};
            else if (type.isInterface()) args[index] = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, values) -> null);
            else throw new IllegalStateException("Unsupported exact tracker fixture argument " + type.getName());
        }
        Object tracker = Names.type("ServerEntity").getConstructor(types).newInstance(args);
        Object interval = Names.field("ServerEntity.updateInterval", tracker);
        if (TandemRuntime.isSeat(entity)) check(!(interval instanceof Number) || ((Number) interval).intValue() == 1, "seat-native-tracker-updates-every-tick");
        final List<Object> packets = new ArrayList<Object>();
        Consumer<Object> collect = packet -> packets.add(packet);
        if (Names.method("tracker.pairing").getParameterCount() == 1) Names.call("tracker.pairing", tracker, collect);
        else Names.call("tracker.pairing", tracker, observer, collect);
        return packets;
    }
    private static boolean passengerEdge(List<Object> packets, Object vehicle, Object passenger) throws Exception {
        int vehicleId = ((Number) Names.call("entity.id", vehicle)).intValue();
        int passengerId = ((Number) Names.call("entity.id", passenger)).intValue();
        boolean found = false;
        for (Object packet : packets) {
            if (!Names.type("PassengersPacket").isInstance(packet)
                    || ((Number) Names.field("PassengersPacket.vehicle", packet)).intValue() != vehicleId) continue;
            int[] passengers = (int[]) Names.field("PassengersPacket.passengers", packet);
            if (passengers.length != 1 || passengers[0] != passengerId) return false;
            found = true;
        }
        return found;
    }
    private static void pairing(Object level, Object seat, Object rider, Object horse, Object driver, Object observer) throws Exception {
        List<Object> packets = pairingPackets(level, seat, observer);
        int seatId = ((Number) Names.call("entity.id", seat)).intValue();
        boolean spawn = false, state = false;
        for (Object packet : packets) {
            if (Names.type("AddEntityPacket").isInstance(packet)
                    && ((Number) Names.field("AddEntityPacket.id", packet)).intValue() == seatId) {
                spawn = Math.abs(((Number) Names.field("AddEntityPacket.x", packet)).doubleValue() - ((Number) Names.call("entity.x", seat)).doubleValue()) < 0.000001
                        && Math.abs(((Number) Names.field("AddEntityPacket.y", packet)).doubleValue() - ((Number) Names.call("entity.y", seat)).doubleValue()) < 0.000001
                        && Math.abs(((Number) Names.field("AddEntityPacket.z", packet)).doubleValue() - ((Number) Names.call("entity.z", seat)).doubleValue()) < 0.000001;
            } else if (Names.type("EntityDataPacket").isInstance(packet)
                    && ((Number) Names.field("EntityDataPacket.id", packet)).intValue() == seatId) {
                Object items = Names.field("EntityDataPacket.packedItems", packet);
                state = items instanceof List && !((List<?>) items).isEmpty();
            }
        }
        check(spawn, "late-observer-native-seat-spawn-matches-location");
        check(spawn && state, "late-observer-native-pairing-includes-state");
        check(passengerEdge(packets, seat, rider), "late-observer-native-seat-passenger-edge");
        check(passengerEdge(pairingPackets(level, horse, observer), horse, driver), "late-observer-native-horse-driver-edge");
    }
    private static void run(Object server) throws Exception {
        Object level = Names.call("server.overworld", server);
        // Acquire an already-generated spawn chunk for this disposable fixture only.
        if (Names.has("level.chunk")) Names.call("level.chunk", level, 0, 0);
        Object driver = player(server, level, 1), rider = player(server, level, 2), third = player(server, level, 3);
        Object horse = horse(level, 0.5);
        Object hand = Names.field("InteractionHand.MAIN_HAND", null);
        Object pass = TandemRuntime.pass();
        check(Names.mount(driver, horse), "vanilla-driver-mount");
        Object originalUuid = Names.call("entity.uuid", horse);
        Object originalHorseHealth = Names.call("living.health", horse);
        Object originalRiderHealth = Names.call("living.health", rider);
        if (!Names.TABLE.getProperty("platform").equals("fabric")) {
            try (NativeCancellation veto = NativeCancellation.listen(rider, true, false)) {
                interact(rider, horse, hand);
                check(veto.observed(), "loader-native-interaction-veto-observed");
                empty("loader-cancelled-interaction-stays-cancelled");
            }
            try (NativeCancellation veto = NativeCancellation.listen(rider, false, true)) {
                interact(rider, horse, hand);
                check(veto.observed(), "loader-native-mount-veto-observed");
                empty("loader-cancelled-mount-stays-cancelled");
            }
        }
        Object result = interact(rider, horse, hand);
        if (TandemRuntime.rideCount() == 0) TandemRuntime.log("Native interaction diagnostics: pass=" + (result == pass) + ", horseAlive=" + Names.call("entity.alive", horse) + ", playerAlive=" + Names.call("entity.alive", rider) + ", sameLevel=" + (Names.call("entity.level", horse) == Names.call("entity.level", rider)) + ", playerUnmounted=" + (Names.call("entity.vehicle", rider) == null) + ", passengers=" + ((List<?>) Names.call("entity.passengers", horse)).size());
        check(TandemRuntime.rideCount() == 1 && TandemRuntime.seatCount() == 1, "native-interaction-opens-second-seat");
        Object seat = TandemRuntime.seatFor(horse);
        check(Names.type("AreaEffectCloud").isInstance(seat), "seat-uses-vanilla-area-effect-cloud");
        check(!Names.type("LivingEntity").isInstance(seat), "seat-skips-living-vehicle-renderer-branch");
        check(Math.abs(((Number) Names.call("cloud.getRadius", seat)).floatValue()) < 0.000001f, "seat-cloud-emits-no-radius-particles");
        check(!Boolean.TRUE.equals(Names.call("cloud.isWaiting", seat)), "seat-cloud-does-not-emit-wait-particles");
        check(Boolean.TRUE.equals(Names.call("entity.noGravity", seat)), "seat-cloud-has-no-gravity");
        int expectedDuration = Names.hasField("AreaEffectCloud.INFINITE_DURATION")
                ? ((Number) Names.field("AreaEffectCloud.INFINITE_DURATION", null)).intValue()
                : Integer.MAX_VALUE;
        check(((Number) Names.call("cloud.getDuration", seat)).intValue() == expectedDuration, "seat-cloud-does-not-expire-while-mounted");
        if (Names.hasField("Entity.noPhysics")) check(Boolean.TRUE.equals(Names.field("Entity.noPhysics", seat)), "seat-cloud-has-no-physics");
        if (Names.hasField("AreaEffectCloud.effects")) {
            check(((List<?>) Names.field("AreaEffectCloud.effects", seat)).isEmpty(), "seat-cloud-has-no-status-effects");
        } else if (Names.hasField("AreaEffectCloud.potionContents")) {
            Object contents = Names.field("AreaEffectCloud.potionContents", seat);
            check(!Boolean.TRUE.equals(Names.call("potion.hasEffects", contents)), "seat-cloud-has-no-status-effects");
        }
        if (!Names.TABLE.getProperty("platform").equals("fabric")) {
            try (NativeCancellation veto = NativeCancellation.listen(rider, false, false)) {
                Names.call("entity.dismount", rider);
                check(veto.observed(), "loader-native-dismount-veto-observed");
                TandemRuntime.entityTick(horse);
                check(TandemRuntime.rideCount() == 1 && Names.call("entity.vehicle", rider) == seat, "loader-cancelled-dismount-retains-ride");
            }
        }
        check(Names.call("entity.vehicle", driver) == horse, "driver-control-vehicle-preserved");
        check(Names.call("entity.vehicle", rider) == seat, "second-rider-uses-native-vehicle");
        check(((List<?>) Names.call("entity.passengers", horse)).size() == 1, "horse-keeps-only-driver");
        check(originalHorseHealth.equals(Names.call("living.health", horse)) && originalRiderHealth.equals(Names.call("living.health", rider)), "mount-preserves-native-health");
        if (Names.has("seat.hurt")) {
            Object[] args = new Object[Names.method("seat.hurt").getParameterCount()];
            Class<?>[] types = Names.method("seat.hurt").getParameterTypes();
            for (int index=0; index<args.length; index++) {
                if (types[index] == float.class) args[index] = 100.0f;
                else if (types[index] == Names.type("ServerLevel")) args[index] = level;
            }
            check(Boolean.FALSE.equals(Names.method("seat.hurt").invoke(seat, args)), "seat-rejects-native-damage");
        }
        pairing(level, seat, rider, horse, driver, third);
        interact(third, horse, hand);
        check(TandemRuntime.rideCount() == 1 && Names.call("entity.vehicle", third) == null, "third-rider-rejected");
        if (Names.method("entity.save").getParameterTypes()[0] == Names.type("CompoundTag")) {
            Object tag = Names.type("CompoundTag").getConstructor().newInstance();
            check(!Boolean.TRUE.equals(Names.call("entity.save", seat, tag)), "seat-cannot-save");
            check(!Boolean.TRUE.equals(Names.call("entity.savePassenger", seat, tag)), "seat-cannot-save-as-passenger");
        } else check(!Boolean.TRUE.equals(Names.call("entity.shouldSave", seat)), "seat-cannot-save");
        double rear = TandemRuntime.SETTINGS.rear;
        double height = TandemRuntime.SETTINGS.height;
        TandemRuntime.SETTINGS.height = height + 0.0625;
        TandemRuntime.applySettings();
        check(TandemRuntime.SETTINGS.rear == rear, "height-adjustment-independent");
        double riderHeight = ((Number) Names.call("entity.y", rider)).doubleValue() - ((Number) Names.call("entity.y", horse)).doubleValue();
        check(Math.abs(riderHeight - height - 0.0625) < 0.000001, "native-player-seat-height");
        TandemRuntime.SETTINGS.rear = rear + 0.0625;
        TandemRuntime.applySettings();
        check(TandemRuntime.SETTINGS.height == height + 0.0625, "rear-adjustment-independent");
        boolean rejected = false;
        try { Settings.bounded("NaN", 0, 3); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "nonfinite-config-rejected");
        Names.call("entity.position", horse, 5.0, 81.0, 5.0);
        TandemRuntime.entityTick(horse);
        check(Math.abs(((Number) Names.call("entity.x", seat)).doubleValue() - 5.0) < 2.01, "native-horse-movement-follows");
        check(originalUuid.equals(Names.call("entity.uuid", horse)), "horse-identity-preserved");
        // Turning follows the body, not the independent look direction.
        if (Names.hasField("LivingEntity.yBodyRot")) {
            Names.field("LivingEntity.yBodyRot", horse, 90.0f);
            Names.call("entity.rotation", horse, -90.0f);
            TandemRuntime.entityTick(horse);
            check(Math.abs(((Number) Names.call("entity.x", seat)).doubleValue() - 5.0 - TandemRuntime.SETTINGS.rear) < 0.000001, "turning-follows-body-without-look-oscillation");
        }
        Names.call("entity.dismount", rider);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 0 && TandemRuntime.seatCount() == 0, "rider-dismount-cleans-seat");
        // A consumed or cancelled interaction never opens a deferred ride.
        TandemRuntime.interact(horse, rider, hand, Names.field("InteractionResult.SUCCESS", null));
        check(TandemRuntime.rideCount() == 0, "consumed-interaction-not-overridden");
        interact(rider, horse, hand);
        check(TandemRuntime.rideCount() == 1, "clean-retry-after-dismount");
        Names.call("entity.dismount", driver);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 0 && TandemRuntime.seatCount() == 0, "driver-dismount-cleans-seat");
        check(Names.call("entity.vehicle", rider) == null, "driver-departure-detaches-rider-immediately");
        check(Names.mount(driver, horse), "driver-remount");
        interact(rider, horse, hand);
        TandemRuntime.SETTINGS.enabled = false;
        TandemRuntime.applySettings();
        check(TandemRuntime.rideCount() == 0 && TandemRuntime.seatCount() == 0, "disable-cleans-active-ride");
        TandemRuntime.SETTINGS.enabled = true;
        TandemRuntime.SETTINGS.height = height;
        TandemRuntime.SETTINGS.rear = rear;
        interact(rider, horse, hand);
        Names.call("entity.discard", horse);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 0 && TandemRuntime.seatCount() == 0, "horse-removal-cleans-seat");
        check(AdminCommands.allowed(Names.call("server.source", server)), "console-admin-permission");
        check(!AdminCommands.allowed(Names.call("entity.source", third)), "ordinary-player-admin-denied");
        Object console = Names.call("server.source", server);
        check(AdminCommands.execute(Names.call("entity.source", third), "disable") == 0 && TandemRuntime.SETTINGS.enabled, "permission-denial-preserves-settings");
        check(AdminCommands.execute(console, "height +") == 1 && TandemRuntime.SETTINGS.height == height + 0.0625 && TandemRuntime.SETTINGS.rear == rear, "admin-step-saves-independent-height");
        check(Files.exists(TandemRuntime.SETTINGS.file), "admin-settings-saved-immediately");
        check(AdminCommands.execute(console, "rear Infinity") == 0 && TandemRuntime.SETTINGS.rear == rear, "admin-rejects-nonfinite-without-mutation");
        check(AdminCommands.execute(console, "reload") == 1 && TandemRuntime.SETTINGS.height == height + 0.0625, "admin-reload-restores-saved-settings");
        check(AdminCommands.execute(console, "reset") == 1 && TandemRuntime.SETTINGS.height == Settings.DEFAULT_HEIGHT && TandemRuntime.SETTINGS.rear == Settings.DEFAULT_REAR, "admin-reset-restores-defaults");
        Object otherDriver = player(server, level, 4), otherRider = player(server, level, 5);
        horse = horse(level, 0.5);
        Object otherHorse = horse(level, 8.5);
        check(Names.mount(driver, horse) && Names.mount(otherDriver, otherHorse), "independent-drivers-mount");
        interact(rider, horse, hand);
        interact(otherRider, otherHorse, hand);
        check(TandemRuntime.rideCount() == 2 && TandemRuntime.seatFor(horse) != TandemRuntime.seatFor(otherHorse), "multiple-rides-own-independent-seats");
        Names.call("living.setHealth", rider, 0.0f);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 1 && TandemRuntime.seatFor(otherHorse) != null, "rider-death-cleans-only-own-ride");
        Names.call("living.setHealth", rider, originalRiderHealth);
        interact(rider, horse, hand);
        check(TandemRuntime.rideCount() == 2, "rider-death-cleanup-allows-fresh-retry");
        Names.call("living.setHealth", driver, 0.0f);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 1, "driver-death-cleans-only-own-ride");
        Names.call("living.setHealth", driver, originalRiderHealth);
        interact(rider, horse, hand);
        Object anotherLevel = null;
        for (Object candidate : (Iterable<?>) Names.call("server.levels", server)) if (candidate != level && dimension(candidate).equals("minecraft:the_nether")) { anotherLevel = candidate; break; }
        check(anotherLevel != null, "fixture-second-world-available");
        setFixturePlayerLevel(rider, anotherLevel);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 1, "rider-world-change-cleans-own-ride");
        setFixturePlayerLevel(rider, level);
        interact(rider, horse, hand);
        check(TandemRuntime.rideCount() == 2, "fixture-native-teleport-starts-with-active-ride");
        teleport(rider, level, 8.5, 80.0, 8.5);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 1 && TandemRuntime.seatFor(horse) == null
                && Names.call("entity.vehicle", rider) == null, "native-player-teleport-cleans-own-ride");
        // Prepare only the disposable fixture's chosen destination. Production
        // TandemRuntime never acquires a world or chunk to maintain a ride.
        Names.call("level.chunk", anotherLevel, 0, 0);
        check(loaded(anotherLevel, 8.5, 80.0, 8.5), "fixture-native-world-change-destination-prepared");
        Object transferDriver = player(server, level, 8), transferRider = player(server, level, 9);
        Names.call("level.addPlayer", level, transferRider);
        boolean registered = false;
        for (Object entity : (Iterable<?>) Names.call("level.entities", level)) if (entity == transferRider) { registered = true; break; }
        check(registered && Names.call("entity.level", transferRider) == level, "fixture-native-world-change-player-registered");
        Object transferHorse = horse(level, 4.5);
        check(Names.mount(transferDriver, transferHorse), "fixture-native-world-change-driver-mounted");
        interact(transferRider, transferHorse, hand);
        check(TandemRuntime.rideCount() == 2 && TandemRuntime.seatFor(transferHorse) != null, "fixture-native-world-change-starts-with-active-ride");
        teleport(transferRider, anotherLevel, 8.5, 80.0, 8.5);
        check(Names.call("entity.level", transferRider) == anotherLevel
                && dimension(Names.call("entity.level", transferRider)).equals("minecraft:the_nether"), "fixture-native-player-world-transfer-applied");
        TandemRuntime.entityTick(transferHorse);
        check(TandemRuntime.rideCount() == 1 && TandemRuntime.seatCount() == 1
                && TandemRuntime.seatFor(transferHorse) == null && TandemRuntime.seatFor(otherHorse) != null
                && Names.call("entity.vehicle", transferRider) == null, "native-player-world-change-cleans-own-ride");
        teleport(transferRider, level, 8.5, 80.0, 8.5);
        check(Names.call("entity.level", transferRider) == level, "fixture-native-world-transfer-returned-to-source");
        Names.call("entity.discard", transferHorse);
        Names.call("entity.discard", transferDriver);
        Names.call("entity.discard", transferRider);
        interact(rider, horse, hand);
        check(TandemRuntime.rideCount() == 2, "fixture-unloaded-seat-starts-with-active-ride");
        // Find a loaded edge using non-loading queries only. A seat behind a
        // horse facing +Z would cross from this edge into an absent chunk.
        double boundary = Double.NaN;
        for (int z = 0; z >= -2048; z -= 16) {
            if (loaded(level, 0.5, 80.0, z + 0.25) && !loaded(level, 0.5, 80.0, z - 0.25)) { boundary = z; break; }
        }
        check(Double.isFinite(boundary), "fixture-loaded-edge-borders-unloaded-seat-chunk");
        Names.call("entity.position", horse, 0.5, 80.0, boundary + 0.25);
        Names.call("entity.rotation", horse, 0.0f);
        if (Names.hasField("LivingEntity.yBodyRot")) Names.field("LivingEntity.yBodyRot", horse, 0.0f);
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 1 && TandemRuntime.seatFor(horse) == null
                && !loaded(level, 0.5, 80.0, boundary - 0.25), "unloaded-seat-destination-cleans-without-force-loading");
        interact(rider, horse, hand);
        check(TandemRuntime.rideCount() == 1 && TandemRuntime.seatCount() == 1
                && !loaded(level, 0.5, 80.0, boundary - 0.25), "unloaded-seat-destination-rejects-mount-without-force-loading");
        Names.call("entity.position", horse, 0.5, 80.0, 8.5);
        interact(rider, horse, hand);
        check(TandemRuntime.rideCount() == 2, "fixture-unloaded-horse-starts-with-active-ride");
        check(!loaded(level, 1000000.5, 80.0, 1000000.5), "fixture-unloaded-horse-destination-is-absent");
        setFixturePositionWithoutChunkAccess(horse, 1000000.5, 80.0, 1000000.5);
        check(!loaded(level, 1000000.5, 80.0, 1000000.5), "fixture-unloaded-horse-setup-does-not-load-chunk");
        TandemRuntime.entityTick(horse);
        check(TandemRuntime.rideCount() == 1 && TandemRuntime.seatFor(horse) == null
                && !loaded(level, 1000000.5, 80.0, 1000000.5), "unloaded-chunk-cleans-without-force-loading");
        // Restore the fixture's coherent native position before disposal.
        Names.call("entity.position", horse, 0.5, 80.0, 8.5);
        Names.call("player.nativeDisconnect", otherRider);
        TandemRuntime.entityTick(otherHorse);
        empty("native-rider-disconnect-cleans-seat");
        Names.call("entity.discard", otherRider);
        otherRider = player(server, level, 5);
        interact(otherRider, otherHorse, hand);
        check(TandemRuntime.rideCount() == 1, "same-identity-reconnect-uses-new-session");
        TandemRuntime.stop(server);
        empty("server-stop-cleans-all-active-seats");
        Names.call("entity.discard", horse);
        Names.call("entity.discard", otherHorse);
        Names.call("entity.discard", otherDriver);
        Names.call("entity.discard", otherRider);
        for (Object player : new Object[]{driver, rider, third}) Names.call("entity.discard", player);
        // Leave one active disposable ride to the actual native stop hook. Its
        // saved world is reopened by --restart-test to inspect persisted entities.
        driver = player(server, level, 6);
        rider = player(server, level, 7);
        horse = horse(level, 0.5);
        check(Names.mount(driver, horse), "shutdown-fixture-driver-mount");
        interact(rider, horse, hand);
        check(TandemRuntime.rideCount() == 1, "shutdown-fixture-leaves-active-ride");
    }
    private static void record(boolean passed, String error) {
        StringBuilder json = new StringBuilder("{\n  \"minecraft\":\"").append(Names.TABLE.getProperty("minecraft")).append("\",\n  \"platform\":\"").append(Names.TABLE.getProperty("platform")).append("\",\n  \"native_fixture\":").append(passed).append(",\n  \"error\":\"").append(error).append("\",\n  \"connected_clients\":false,\n  \"visual\":\"not-run\",\n  \"checks\":[");
        for (int i=0; i<checks.size(); i++) { if (i>0) json.append(','); json.append('"').append(checks.get(i)).append('"'); }
        json.append("],\n  \"world_change_fixture\":\"native-player-transfer-and-separate-mapped-level-guard\",\n  \"unloaded_chunk_fixture\":\"mapped-position-guard\",\n  \"loader_cancellation\":{");
        if (Names.TABLE.getProperty("platform").equals("fabric")) {
            json.append("\"status\":\"not-applicable\",\"reason\":\"Fabric Loader-only distribution supplies no native gameplay cancellation event bus; Fabric API and third-party protection mixins are not installed or tested\"");
        } else {
            boolean interaction = checks.contains("loader-native-interaction-veto-observed") && checks.contains("loader-cancelled-interaction-stays-cancelled");
            boolean mount = checks.contains("loader-native-mount-veto-observed") && checks.contains("loader-cancelled-mount-stays-cancelled");
            boolean dismount = checks.contains("loader-native-dismount-veto-observed") && checks.contains("loader-cancelled-dismount-retains-ride");
            json.append("\"status\":\"").append(interaction && mount && dismount ? "passed" : "incomplete")
                    .append("\",\"interaction\":").append(interaction).append(",\"mount\":").append(mount).append(",\"dismount\":").append(dismount);
        }
        json.append("}\n}\n");
        try { Files.write(Paths.get(System.getProperty("tandemount.selftest.result", "tandemount-native-result.json")), json.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception failure) { failure.printStackTrace(); }
        TandemRuntime.log("NATIVE RESULT " + passed);
    }
}
