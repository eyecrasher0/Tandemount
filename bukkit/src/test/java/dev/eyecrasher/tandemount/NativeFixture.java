/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Disposable runtime fixture. It is never included in the release plugin. */
public final class NativeFixture extends JavaPlugin implements Listener {
    private final Method quietBlockSetter = findQuietBlockSetter();
    private TandemountPlugin tandem;
    private Horse horse;
    private Player driver, rider, third;
    private final Set<UUID> helperIds = new HashSet<UUID>();
    private boolean cancelTeleport;
    private boolean cancelledTeleportObserved;
    private java.util.concurrent.CompletableFuture<?> publicTeleport;
    private boolean cancelInteraction, cancelDismount;
    private int failures;
    private double riderHealth, horseHealth, beforeVertical, beforeRearward;
    private UUID horseIdentity;
    private ItemStack[] riderInventory;
    private Location driveStart;
    private volatile Location playerHome;
    private Location otherWorldDestination;
    private volatile World otherWorldSetup;
    private volatile int otherWorldX, otherWorldZ;
    private volatile World initialHomeWorld;
    private volatile boolean initialHomeRetained;
    private volatile World unloadWorld;
    private volatile int unloadX, unloadZ;
    private volatile boolean unloadObserved, unloadContainedHelper;
    private volatile String currentPhase;
    private boolean saveProbePending;
    private boolean wireMode, wirePreciseOnly;
    private int wireInteractions, wireCancelledInteractions, wireThirdInteractions;
    private String wireCancelledEvent;
    private org.bukkit.permissions.PermissionAttachment wireAdmin;
    private Snapshot wireSettings;

    @Override public void onEnable() {
        tandem = (TandemountPlugin)getServer().getPluginManager().getPlugin("Tandemount");
        getServer().getPluginManager().registerEvents(this, this);
        Class<?> separate = (Class<?>)invoke(tandem, "separatePreciseInteractionType");
        if (separate != null) {
            Class<? extends PlayerInteractEntityEvent> precise = separate.asSubclass(PlayerInteractEntityEvent.class);
            getServer().getPluginManager().registerEvent(precise, this, EventPriority.MONITOR, (listener, event) -> {
                if (precise.isInstance(event)) cancelled(precise.cast(event));
            }, this, false);
        }
        getDataFolder().mkdirs();
        try {
            File saved = new File(getDataFolder(), "seat-uuids.txt");
            if (saved.isFile()) {
                BufferedReader input = new BufferedReader(new FileReader(saved));
                for (String line; (line = input.readLine()) != null;) helperIds.add(UUID.fromString(line));
                input.close();
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        state("ready", "0");
        getLogger().info("FIXTURE_RELEASE_JAR " + tandem.getClass().getProtectionDomain().getCodeSource().getLocation());
        try { dumpProtocols(); } catch (Throwable failure) { getLogger().warning("Protocol dump: " + failure); }
        try {
            Class<?> dismount;
            try { dismount = Class.forName("org.bukkit.event.entity.EntityDismountEvent"); }
            catch (ClassNotFoundException old) { dismount = Class.forName("org.spigotmc.event.entity.EntityDismountEvent"); }
            @SuppressWarnings("unchecked") Class<? extends Event> eventClass = (Class<? extends Event>)dismount;
            getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR,
                    new org.bukkit.plugin.EventExecutor() {
                        public void execute(Listener listener, Event event) throws EventException {
                            if (cancelDismount && event instanceof Cancellable) ((Cancellable)event).setCancelled(true);
                        }
                    }, this, false);
        } catch (ClassNotFoundException noEvent) { record("dismount-event-available", false, "No cancellable platform dismount event"); }
        getLogger().info("NATIVE_FIXTURE_READY");
    }

    @EventHandler(priority = EventPriority.MONITOR) public void cancelled(PlayerInteractEntityEvent event) {
        if (cancelInteraction && event.getRightClicked() == horse) event.setCancelled(true);
        if (wireMode && event.getPlayer() == rider && event.getRightClicked() == horse) {
            wireInteractions++;
            if (event.isCancelled()) {
                wireCancelledInteractions++;
                wireCancelledEvent = event.getClass().getSimpleName();
            }
        }
        if (wireMode && event.getPlayer() == third && event.getRightClicked() == horse) wireThirdInteractions++;
    }
    @EventHandler(priority = EventPriority.MONITOR) public void cancelledExit(org.bukkit.event.vehicle.VehicleExitEvent event) {
        if (cancelDismount) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void cancelledTeleport(PlayerTeleportEvent event) {
        if (cancelTeleport && event.getPlayer() == rider) { cancelledTeleportObserved = true; event.setCancelled(true); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void nativeUnloaded(org.bukkit.event.world.ChunkUnloadEvent event) {
        if (event.getWorld() != unloadWorld || event.getChunk().getX() != unloadX || event.getChunk().getZ() != unloadZ) return;
        for (Entity entity : event.getChunk().getEntities())
            if (helperIds.contains(entity.getUniqueId()) && entity.isValid() && !entity.isDead()) unloadContainedHelper = true;
        unloadObserved = true;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void keepLegacyLandingLoaded(org.bukkit.event.world.ChunkUnloadEvent event) {
        // Old Bukkit lacks plugin chunk tickets. Retain only the controlled
        // landing chunk during this disposable fixture, without changing the
        // Tandemount plugin or any production-world loading policy.
        if (((event.getWorld() == otherWorldSetup && event.getChunk().getX() == otherWorldX
                    && event.getChunk().getZ() == otherWorldZ)
                || (initialHomeRetained && event.getWorld() == initialHomeWorld
                    && event.getChunk().getX() == 0 && event.getChunk().getZ() == 0))
                && event instanceof Cancellable)
            ((Cancellable)event).setCancelled(true);
    }
    @EventHandler public void locate(final PlayerJoinEvent event) {
        next(event.getPlayer(), new Runnable() { public void run() {
            Location at = playerHome == null ? new Location(Bukkit.getWorlds().get(0), 8.5, 64.0, 8.5) : playerHome.clone();
            if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                failed("player-home-loaded", new IllegalStateException("Fixture home chunk is not already loaded")); return;
            }
            if (folia()) invoke(event.getPlayer(), "teleportAsync", at);
            else event.getPlayer().teleport(at);
        }});
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equals("tmfixture")) return false;
        if (args.length == 0) return true;
        try {
            if (args[0].startsWith("owner-")) { NativeFixtureOwner.command(this, args[0]); return true; }
            if (args[0].equals("prepare")) prepare();
            if (args[0].equals("start")) start();
            if (args[0].equals("wire-start")) { wireMode = true; start(); }
            if (args[0].equals("wire-cancelled")) wireCancelled();
            if (args[0].equals("wire-mounted")) wireMounted();
            if (args[0].equals("wire-menu-finish")) wireMenuFinish();
            if (args[0].equals("late")) late();
            if (args[0].equals("saveprobe")) saveprobe();
            if (args[0].equals("finish")) finish();
            if (args[0].equals("disconnected")) disconnected();
            if (args[0].equals("rejoined")) rejoined();
            if (args[0].equals("restartcheck")) restartcheck();
        } catch (Throwable failure) { failed("fixture-command", failure); }
        return true;
    }
    private void region(final Runnable work) throws ReflectiveOperationException {
        region(Bukkit.getWorlds().get(0), 0, 0, work);
    }
    private void region(World world, int x, int z, final Runnable work) throws ReflectiveOperationException {
        if (!folia()) { Bukkit.getScheduler().runTask(this, work); return; }
        Object region = Probe.call(getServer(), "getRegionScheduler");
        Probe.call(region, "execute", this, world, x, z, work);
    }
    private void prepare() throws ReflectiveOperationException {
        final Runnable prepare = guarded("prepare-world-settings", new Runnable() { public void run() {
            try {
                final World world = Bukkit.getWorlds().get(0);
                setFixtureSpawnRadius(world);
                world.setKeepSpawnInMemory(false);
                world.setSpawnLocation(8, 64, 8);
                region(new Runnable() { public void run() {
                    try {
                        initialHomeWorld = world;
                        initialHomeRetained = true;
                        retainPreparedChunk(world, 0, 0);
                        // The legacy server drives the horse from steering input
                        // and it can coast beyond Z 32 during lifecycle checks.
                        int floorEndZ = Bukkit.getBukkitVersion().startsWith("1.7.10") ? 64 : 32;
                        for (int x = 0; x <= 32; x++) for (int z = 0; z <= floorEndZ; z++)
                            setFixtureBlock(world.getBlockAt(x, 63, z), Material.STONE);
                        prepareOtherWorld(world);
                    } catch (Throwable failure) { failed("prepare-world", failure); }
                }});
            } catch (Throwable failure) { failed("prepare-world", failure); }
        }});
        if (!folia()) prepare.run();
        else Probe.call(Probe.call(getServer(), "getGlobalRegionScheduler"), "execute", this, prepare);
    }
    private void prepareOtherWorld(World primary) throws ReflectiveOperationException {
        for (final World world : Bukkit.getWorlds()) if (world != primary) {
            Chunk[] loaded = world.getLoadedChunks();
            final int x = loaded.length == 0 ? 0 : loaded[0].getX(), z = loaded.length == 0 ? 0 : loaded[0].getZ();
            otherWorldSetup = world; otherWorldX = x; otherWorldZ = z;
            final Runnable prepare = guarded("prepare-other-world", new Runnable() { public void run() {
                if (!world.isChunkLoaded(x, z)) throw new IllegalStateException("Other-world chunk unloaded before fixture preparation");
                retainPreparedChunk(world, x, z);
                otherWorldDestination = prepareLanding(world, x, z);
                state("prepared", "0");
            }});
            prepareChunk(world, x, z, prepare);
            return;
        }
        record("world-change-loaded-destination-available", false,
                "No secondary disposable world exists; enable a native dimension before this fixture.");
        state("prepared", "no-other-loaded-world");
    }
    private void prepareChunk(final World world, final int x, final int z, final Runnable prepare) throws ReflectiveOperationException {
        if (folia()) {
            final java.util.function.Consumer<Object> loaded = new java.util.function.Consumer<Object>() {
                public void accept(Object chunk) {
                    if (chunk == null) { failed("prepare-chunk-async", new IllegalStateException("Async fixture chunk generation failed")); return; }
                    try {
                        // Upstream retires its temporary CHUNK_LOAD ticket as
                        // soon as this callback returns. Retain the fixture
                        // chunk before handing work to its owning region.
                        retainPreparedChunk(world, x, z);
                        region(world, x, z, guarded("prepare-chunk-region", new Runnable() { public void run() {
                            prepareWhenLoaded(world, x, z, 100, prepare);
                        }}));
                    } catch (Throwable problem) { failed("prepare-chunk-region", problem); }
                }
            };
            try { Probe.call(world, "getChunkAtAsync", x, z, true, false, loaded); }
            catch (NoSuchMethodException olderFolia) {
                // Folia 1.19.4 through 1.20.6 expose only the future overload.
                // Install our persistent setup ticket before requesting it,
                // so completion can safely hand work to a later region task.
                retainPreparedChunk(world, x, z);
                Object future = Probe.call(world, "getChunkAtAsync", x, z, true, false);
                ((java.util.concurrent.CompletableFuture<?>)future).whenComplete(new java.util.function.BiConsumer<Object, Throwable>() {
                    public void accept(Object chunk, Throwable failure) {
                        if (failure != null) failed("prepare-chunk-async", failure);
                        else loaded.accept(chunk);
                    }
                });
            }
        } else {
            // Explicit generation belongs to disposable test setup. Runtime
            // transfers below continue to reject unloaded destinations.
            world.getChunkAt(x, z);
            region(world, x, z, prepare);
        }
    }
    private void prepareWhenLoaded(final World world, final int x, final int z, final int attempts, final Runnable prepare) {
        if (world.isChunkLoaded(x, z)) { prepare.run(); return; }
        if (attempts == 0) throw new IllegalStateException("Retained fixture chunk did not reach native full loaded status");
        try {
            Object scheduler = Probe.call(getServer(), "getRegionScheduler");
            Probe.call(scheduler, "runDelayed", this, world, x, z, new java.util.function.Consumer<Object>() {
                public void accept(Object task) {
                    guarded("prepare-chunk-ready", new Runnable() { public void run() {
                        prepareWhenLoaded(world, x, z, attempts - 1, prepare);
                    }}).run();
                }
            }, 1L);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private Location prepareLanding(World world, int x, int z) {
        if (!world.isChunkLoaded(x, z)) throw new IllegalStateException("Fixture landing chunk is not already loaded");
        int centerX = (x << 4) + 8, centerZ = (z << 4) + 8;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            setFixtureBlock(world.getBlockAt(centerX + dx, 63, centerZ + dz), Material.STONE);
            for (int y = 64; y <= 67; y++) setFixtureBlock(world.getBlockAt(centerX + dx, y, centerZ + dz), Material.AIR);
        }
        return new Location(world, centerX + 0.5, 64, centerZ + 0.5);
    }
    private static Method findQuietBlockSetter() {
        try { return org.bukkit.block.Block.class.getMethod("setType", Material.class, boolean.class); }
        catch (NoSuchMethodException oldBukkit) { return null; }
    }
    private void setFixtureSpawnRadius(World world) throws ReflectiveOperationException {
        try { Probe.call(world, "setGameRuleValue", "spawnRadius", "0"); }
        catch (NoSuchMethodException currentApi) {
            Class<?> rules = Class.forName("org.bukkit.GameRule");
            Object radius;
            try { radius = rules.getField("RESPAWN_RADIUS").get(null); }
            catch (NoSuchFieldException oldName) { radius = rules.getField("SPAWN_RADIUS").get(null); }
            Probe.call(world, "setGameRule", radius, Integer.valueOf(0));
        }
    }
    private void setFixtureBlock(org.bukkit.block.Block block, Material material) {
        // These setup blocks need no neighbor physics. In early 1.18 Paper,
        // physics at the platform edge can request an unloaded neighbor chunk.
        // The 1.7 Bukkit API has only the ordinary material setter.
        if (quietBlockSetter == null) { block.setType(material); return; }
        try { Probe.call(quietBlockSetter, block, material, false); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private void retainPreparedChunk(World world, int x, int z) {
        try { Probe.call(world, "addPluginChunkTicket", x, z, NativeFixture.this); }
        catch (NoSuchMethodException oldApi) { /* The legacy unload listener retains the fixture landing/return chunks. */ }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private void start() throws ReflectiveOperationException {
        region(new Runnable() { public void run() {
            try {
                driver = Bukkit.getPlayerExact("TmDriver"); rider = Bukkit.getPlayerExact("TmRider"); third = Bukkit.getPlayerExact("TmThird");
                require("three-real-loopback-players", driver != null && rider != null && third != null);
                if (driver == null || rider == null || third == null) { state("failed", "players"); return; }
                require("players-owned-by-region", owns(driver) && owns(rider) && owns(third));
                verifyDefaults();
                // The real connected players now retain this chunk normally.
                // Drop the setup ticket before any ride or lifecycle check so
                // native evacuation and the actual unload stay observable.
                initialHomeRetained = false;
                try { Probe.call(initialHomeWorld, "removePluginChunkTicket", 0, 0, NativeFixture.this); }
                catch (NoSuchMethodException oldApi) { /* Legacy listener has just stopped retaining this home. */ }
                Field bounds = (Field)field(field(tandem, "nativeAccess"), "bounds");
                require("native-seat-shape-uses-mutable-instance-aabb",
                        !Modifier.isStatic(bounds.getModifiers())
                                && (!Modifier.isFinal(bounds.getModifiers())
                                    || ((Boolean)field(field(tandem, "nativeAccess"), "oldest")
                                        && bounds.getName().equals("boundingBox")
                                        && field(field(tandem, "nativeAccess"), "boxMutator") != null))
                                && (bounds.getType().getSimpleName().equals("AABB")
                                        || bounds.getType().getSimpleName().equals("AxisAlignedBB")));
                driver.setOp(false); rider.setOp(false); third.setOp(false);
                horse = (Horse)driver.getWorld().spawnEntity(new Location(driver.getWorld(), 8, 64, 8), org.bukkit.entity.EntityType.HORSE);
                ordinaryHorse(horse); horse.setAdult(); horse.setTamed(true); horse.setOwner(driver);
                horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                horseIdentity = horse.getUniqueId(); horseHealth = horse.getHealth(); riderHealth = rider.getHealth();
                riderInventory = rider.getInventory().getContents().clone();
                require("native-driver-mounted", mount(horse, driver));
                getLogger().info("START_LOCATIONS horse=" + horse.getLocation() + " rider=" + rider.getLocation() + " driver=" + driver.getLocation());
                cancelInteraction = true;
                if (wireMode) {
                    Properties wire = new NativeFixtureProtocol((Class<?>)field(field(tandem,"nativeAccess"),"entityClass"),
                            Probe.handle(horse)).export(getDataFolder(), Probe.handle(horse));
                    wirePreciseOnly = wire.containsKey("interact.0.at") && !wire.containsKey("interact.0.plain");
                    state("wire-cancel", Integer.toString(horse.getEntityId())); return;
                }
                interact(rider);
                later(3, new Runnable() { public void run() {
                    try {
                        require("cancelled-final-interaction-does-not-mount", !rider.isInsideVehicle() && countSeats() == 0);
                        cancelInteraction = false; interact(rider);
                        later(3, new Runnable() { public void run() { mounted(); }});
                    } catch (Throwable failure) { failed("cancelled-interaction", failure); }
                }});
            } catch (Throwable failure) { failed("native-start", failure); }
        }});
    }
    private void wireCancelled() {
        later(3,new Runnable(){public void run(){
            boolean preciseCancelled = !wirePreciseOnly || (wireInteractions == 1 && wireCancelledInteractions == 1
                    && "PlayerInteractAtEntityEvent".equals(wireCancelledEvent));
            record("cancelled-final-interaction-does-not-mount",
                    preciseCancelled && wireCancelledInteractions > 0 && !rider.isInsideVehicle() && countSeats() == 0,
                    "native callbacks=" + wireInteractions + ", cancelled=" + wireCancelledInteractions
                            + ", last cancelled event=" + wireCancelledEvent);
            cancelInteraction=false;state("wire-mount",Integer.toString(horse.getEntityId()));
        }});
    }
    private void wireMounted() {
        later(4,new Runnable(){public void run(){
            int accepted = wireInteractions - wireCancelledInteractions;
            require("real-client-right-click-mount-persists", accepted > 0 && (!wirePreciseOnly || accepted == 1)
                    && rider.isInsideVehicle() && rider.getVehicle() != horse && countSeats() == 1 && !rider.isOp());
            if (!rider.isInsideVehicle()) { state("failed","wire-no-seat");return; }
            Location player = rider.getLocation(), root = horse.getLocation(); double yaw = Math.toRadians(root.getYaw());
            require("default-native-rider-height", Math.abs(player.getY()-root.getY()-Settings.DEFAULT_VERTICAL)<0.02);
            require("default-native-rider-rearward", Math.abs(player.getX()-root.getX()-Math.sin(yaw)*Settings.DEFAULT_REARWARD)<0.02
                    && Math.abs(player.getZ()-root.getZ()+Math.cos(yaw)*Settings.DEFAULT_REARWARD)<0.02);
            trackSeat();
            next(third,new Runnable(){public void run(){
                wireAdmin=third.addAttachment(NativeFixture.this,"tandemount.admin",true);
                wireSettings=settings();state("wire-menu","0");
            }});
        }});
    }
    private void wireMenuFinish() {
        next(third,new Runnable(){public void run(){
            require("command-click-adjustment-applies",Math.abs(settings().vertical-wireSettings.vertical-1.0/16)<1.0e-9 && settings().rearward==wireSettings.rearward);
            require("real-client-third-rider-rejected", wireThirdInteractions > 0 && !third.isInsideVehicle() && rider.isInsideVehicle() && driver.getVehicle()==horse && countSeats()==1);
            third.removeAttachment(wireAdmin);wireAdmin=null;
            next(driver,new Runnable(){public void run(){mounted();}});
        }});
    }
    private void interact(Player player) {
        getLogger().info("INTERACT_STATE player=" + player.getName() + " health=" + player.getHealth() + " at=" + player.getLocation()
                + " vehicle=" + player.getVehicle() + " horse=" + horse.getLocation() + " driverVehicle=" + driver.getVehicle()
                + " horseValid=" + horse.isValid() + " dead=" + horse.isDead() + " adult=" + horse.isAdult()
                + " tamed=" + horse.isTamed() + " saddle=" + horse.getInventory().getSaddle()
                + " eligible=" + invoke(tandem, "eligible", horse) + " passengers=" + invoke(field(tandem, "nativeAccess"), "passengers", horse));
        getServer().getPluginManager().callEvent(new PlayerInteractEntityEvent(player, horse));
    }
    private int countSeats() { return ((Map<?, ?>)field(tandem, "horses")).size(); }
    private boolean folia() { return (Boolean)field(field(tandem, "scheduler"), "folia"); }
    private boolean owns(Entity entity) { return (Boolean)invoke(field(tandem, "scheduler"), "owns", entity); }
    private void next(Entity entity, final Runnable task) {
        if (!folia()) { Bukkit.getScheduler().runTaskLater(this, task, 1L); return; }
        invoke(invoke(entity, "getScheduler"), "runDelayed", this, new java.util.function.Consumer<Object>() {
            public void accept(Object ignored) { task.run(); }
        }, null, 1L);
    }
    private boolean mount(Entity vehicle, Entity passenger) { return (Boolean)invoke(field(tandem, "nativeAccess"), "mount", vehicle, passenger); }
    private Snapshot settings() { return new Snapshot(field(tandem, "settings")); }
    private void verifyDefaults() {
        require("fresh-config-uses-owner-defaults", settings().vertical == 0.8125 && settings().rearward == 0.5);
        Path file = tandem.getDataFolder().toPath().resolve("config.yml");
        byte[] original = configBytes(file);
        CommandSender console = getServer().getConsoleSender();
        String horseTypes = "supported-horse-types: [HORSE]\n";
        writeConfig(file, (horseTypes + "vertical: 1.375\nrearward: 0.6875\n").getBytes(StandardCharsets.UTF_8));
        configure(console, "reload");
        require("saved-settings-remain-preserved", settings().vertical == 1.375 && settings().rearward == 0.6875);
        writeConfig(file, horseTypes.getBytes(StandardCharsets.UTF_8));
        configure(console, "reload");
        require("missing-settings-use-owner-defaults", settings().vertical == 0.8125 && settings().rearward == 0.5);
        writeConfig(file, original);
        configure(console, "reload");
        configure(console, "vertical 1.5");
        configure(console, "rearward 1.0");
        configure(console, "reset");
        require("reset-restores-owner-defaults", settings().vertical == 0.8125 && settings().rearward == 0.5);
    }
    private void configure(CommandSender sender, String text) {
        tandem.onCommand(sender, tandem.getCommand("tandemount"), "tandemount", text.split(" "));
    }
    private static Object field(Object target, String name) {
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) try {
                Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target);
            } catch (NoSuchFieldException absent) { }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static Object invoke(Object target, String name, Object... args) {
        try {
            try { return Probe.call(target, name, args); } catch (NoSuchMethodException inaccessible) { }
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && Probe.compatible(m.getParameterTypes(), args))
                    return Probe.call(m, target, args);
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static final class Snapshot {
        final double vertical, rearward;
        Snapshot(Object settings) { vertical = (Double)field(settings, "vertical"); rearward = (Double)field(settings, "rearward"); }
    }

    private void mounted() {
        try {
            require("ordinary-non-op-passenger-mounted", rider.isInsideVehicle() && rider.getVehicle() != horse && !rider.isOp());
            require("driver-still-controls-original-horse", driver.getVehicle() == horse);
            require("one-native-vanilla-seat", countSeats() == 1);
            if (!rider.isInsideVehicle()) { state("failed", "no-seat"); return; }
            trackSeat();
            Object nativeSeat = Probe.handle(rider.getVehicle());
            require("seat-not-custom-registry-type", ((Class<?>)field(field(tandem, "nativeAccess"), "seatBase")).isInstance(nativeSeat));
            boolean display = (Boolean)field(field(tandem, "nativeAccess"), "interpolatedSeat");
            require(display ? "seat-is-nonliving-vanilla-block-display" : "seat-is-nonliving-vanilla-falling-block",
                    rider.getVehicle().getType().name().equals(display ? "BLOCK_DISPLAY" : "FALLING_BLOCK")
                    && !(rider.getVehicle() instanceof LivingEntity));
            if (display) {
                require("seat-gravity-remains-disabled", !((Boolean)Probe.call(rider.getVehicle(), "hasGravity")));
                require("seat-block-remains-native-invisible", Probe.call(Probe.call(rider.getVehicle(), "getBlock"), "getMaterial").toString().equals("BARRIER"));
                require("attachment-spacer-native-width-is-zero", ((Number)Probe.call(rider.getVehicle().getVehicle(), "getInteractionWidth")).floatValue() == 0);
                Object tracked = ((Map<?, ?>)field(tandem, "horses")).get(horse.getUniqueId());
                Entity[] helpers = (Entity[])field(tracked, "helpers");
                Object attachment = field(field(tandem, "nativeAccess"), "attachment");
                boolean protectedHelpers = helpers.length == ((Number)field(attachment, "helperCount")).intValue();
                for (Entity helper : helpers) {
                    Object box = Probe.call(helper, "getBoundingBox");
                    protectedHelpers &= !((Boolean)Probe.call(helper, "hasGravity"))
                            && ((Number)Probe.call(box, "getWidthX")).doubleValue() == 0
                            && ((Number)Probe.call(box, "getHeight")).doubleValue() == 0
                            && ((Number)Probe.call(box, "getWidthZ")).doubleValue() == 0;
                    if (helper.getType().name().equals("LLAMA"))
                        protectedHelpers &= (Boolean)Probe.call(helper, "isInvisible") && !((Boolean)Probe.call(helper, "hasAI"))
                                && helper.getLocation().getY() < horse.getLocation().getY() - 40;
                    if (helper.getType().name().equals("INTERACTION"))
                        protectedHelpers &= ((Number)Probe.call(helper, "getInteractionWidth")).floatValue() == 0;
                }
                require("native-attachment-helpers-bounded-invisible-no-ai-and-empty-bounds", protectedHelpers);
                require("server-native-attachment-shares-horse-root", invoke(attachment, "root", rider.getVehicle()) == Probe.handle(horse)
                        && invoke(attachment, "root", rider) == Probe.handle(horse));
                require("server-native-horse-keeps-first-driver-control", invoke(attachment, "controller", horse) == Probe.handle(driver));
            }
            interact(third);
            later(3, new Runnable() { public void run() {
                try {
                    require("third-rider-rejected", !third.isInsideVehicle() && rider.isInsideVehicle() && countSeats() == 1);
                    Snapshot original = settings();
                    beforeVertical = original.vertical; beforeRearward = original.rearward;
                    configure(getServer().getConsoleSender(), "vertical +");
                    require("vertical-independent-step", settings().vertical == beforeVertical + 1.0/16 && settings().rearward == beforeRearward);
                    configure(getServer().getConsoleSender(), "rearward +");
                    require("rearward-independent-step", settings().rearward == beforeRearward + 1.0/16 && settings().vertical == beforeVertical + 1.0/16);
                    double vertical = settings().vertical;
                    configure(getServer().getConsoleSender(), "vertical NaN");
                    require("nonfinite-setting-rejected", settings().vertical == vertical);
                    configure(driver, "vertical 2.0");
                    require("non-op-admin-denied", settings().vertical == vertical);
                    later(3, new Runnable() { public void run() {
                        try {
                            double yaw = Math.toRadians(horse.getLocation().getYaw());
                            Location at = rider.getLocation(); Location h = horse.getLocation();
                            getLogger().info("GEOMETRY horse=" + h + " rider=" + at + " seat=" + rider.getVehicle().getLocation());
                            require("active-rider-height", Math.abs(at.getY() - h.getY() - settings().vertical) < 0.02);
                            require("active-rider-rearward", Math.abs(at.getX() - h.getX() - Math.sin(yaw)*settings().rearward) < 0.02
                                    && Math.abs(at.getZ() - h.getZ() + Math.cos(yaw)*settings().rearward) < 0.02);
                            require("horse-identity-owner-health-preserved", horse.getUniqueId().equals(horseIdentity) && horse.getOwner().getUniqueId().equals(driver.getUniqueId()) && horse.getHealth() == horseHealth);
                            require("rider-health-inventory-preserved", rider.getHealth() == riderHealth && Arrays.equals(riderInventory, rider.getInventory().getContents()));
                            driveStart = horse.getLocation(); state("drive", horse.getEntityId() + "," + driveStart.getX() + "," + driveStart.getY() + "," + driveStart.getZ() + "," + driveStart.getYaw() + "," + driveStart.getPitch());
                        } catch (Throwable failure) { failed("geometry", failure); }
                    }});
                } catch (Throwable failure) { failed("mounted-assertions", failure); }
            }});
        } catch (Throwable failure) { failed("mounted", failure); }
    }
    private void late() {
        later(8, new Runnable() { public void run() {
            try {
                require("driver-input-moves-horse", driveStart != null && horse.getLocation().distanceSquared(driveStart) > 0.05);
                require("late-observer-real-player", Bukkit.getPlayerExact("TmLate") != null);
                require("late-observer-requires-no-remount", rider.isInsideVehicle() && driver.getVehicle() == horse && countSeats() == 1);
                trackSeat("late-attachment.json");
                if (wireMode) {
                    require("real-client-repeated-click-keeps-ride",wireInteractions-wireCancelledInteractions>=3 && rider.isInsideVehicle() && countSeats()==1);
                    require("real-client-mounted-movement-keeps-ride",rider.isInsideVehicle() && countSeats()==1 && driveStart != null && horse.getLocation().distanceSquared(driveStart)>0.05);
                }
                cancelDismount = true;
                rider.leaveVehicle();
                later(3, new Runnable() { public void run() {
                    require("cancelled-dismount-keeps-ride", rider.isInsideVehicle() && countSeats() == 1);
                    cancelDismount = false;
                    rider.leaveVehicle();
                    later(3, new Runnable() { public void run() {
                        require("passenger-dismount-cleans-seat", !rider.isInsideVehicle() && countSeats() == 0 && driver.getVehicle() == horse);
                        interact(rider);
                        later(3, new Runnable() { public void run() {
                            require("retry-after-dismount", rider.isInsideVehicle() && countSeats() == 1);
                            cancelDismount = true; driver.leaveVehicle();
                            later(3, new Runnable() { public void run() {
                                require("cancelled-driver-dismount-keeps-ride", driver.getVehicle() == horse && countSeats() == 1);
                                cancelDismount = false; driver.leaveVehicle();
                                later(3, new Runnable() { public void run() {
                                    require("driver-dismount-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle());
                                    lifecycle();
                                }});
                            }});
                        }});
                    }});
                }});
            } catch (Throwable failure) { failed("late-and-cleanup", failure); }
        }});
    }
    private void trackSeat() { trackSeat("initial-attachment.json"); }
    private void trackSeat(String captureName) {
        if (rider.getVehicle() == null) return;
        for (Entity playerSeat : new Entity[] {rider.getVehicle(), Bukkit.getPlayerExact("TmLate") == null ? null : Bukkit.getPlayerExact("TmLate").getVehicle()})
            for (Entity helper = playerSeat; helper != null && helper != horse && !(helper instanceof Player) && !(helper instanceof Horse); helper = helper.getVehicle())
                helperIds.add(helper.getUniqueId());
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(new File(getDataFolder(), "seat-uuids.txt")), "UTF-8");
            for (UUID id : helperIds) out.write(id.toString() + "\n"); out.close();
            Properties graph = new Properties();
            graph.setProperty("horse", Integer.toString(horse.getEntityId()));
            graph.setProperty("driver", Integer.toString(driver.getEntityId()));
            graph.setProperty("rider", Integer.toString(rider.getEntityId()));
            graph.setProperty("seat", Integer.toString(rider.getVehicle().getEntityId()));
            graph.setProperty("seat_falling_block", rider.getVehicle().getType() == EntityType.FALLING_BLOCK ? "1" : "0");
            graph.setProperty("seat_block_display", rider.getVehicle().getType().name().equals("BLOCK_DISPLAY") ? "1" : "0");
            graph.setProperty("seat_interaction", rider.getVehicle().getType().name().equals("INTERACTION") ? "1" : "0");
            if ((Boolean)field(field(tandem, "nativeAccess"), "interpolatedSeat")) {
                Object ride = ((Map<?, ?>)field(tandem, "horses")).get(horse.getUniqueId());
                Entity[] helpers = (Entity[])field(ride, "helpers");
                graph.setProperty("helper_count", Integer.toString(helpers.length));
                for (int i = 0; i < helpers.length; i++) graph.setProperty("helper_" + i, Integer.toString(helpers[i].getEntityId()));
                File capture = new File(getDataFolder(), captureName);
                if (!capture.exists()) {
                    Object attachment = field(field(tandem, "nativeAccess"), "attachment");
                    Object scale = field(attachment, "scale");
                    StringBuilder json = new StringBuilder("{\"minecraft\":\"").append(field(field(tandem,"nativeAccess"),"minecraft"))
                            .append("\",\"snapshot_monotonic_ns\":").append(System.nanoTime())
                            .append(",\"horse\":").append(horse.getEntityId()).append(",\"driver\":").append(driver.getEntityId())
                            .append(",\"horsePosition\":[").append(horse.getLocation().getX()).append(',').append(horse.getLocation().getY()).append(',').append(horse.getLocation().getZ()).append(']')
                            .append(",\"riderVehicle\":").append(rider.getVehicle().getEntityId())
                            .append(",\"horseYaw\":").append(horse.getLocation().getYaw())
                            .append(",\"rider\":").append(rider.getEntityId()).append(",\"vertical\":").append(settings().vertical)
                            .append(",\"rearward\":").append(settings().rearward).append(",\"helpers\":[");
                    for (Entity helper : helpers) {
                        if (json.charAt(json.length()-1) != '[') json.append(',');
                        Location location = helper.getLocation();
                        json.append("{\"id\":").append(helper.getEntityId()).append(",\"type\":\"").append(helper.getType().name())
                                .append("\",\"parent\":").append(helper.getVehicle().getEntityId())
                                .append(",\"x\":").append(location.getX()).append(",\"y\":").append(location.getY()).append(",\"z\":").append(location.getZ())
                                .append(",\"pose\":\"").append(invoke(helper,"getPose")).append('"')
                                .append(",\"yaw\":").append(location.getYaw()).append(",\"noGravity\":").append(!((Boolean)invoke(helper,"hasGravity")));
                        if (helper.getType().name().equals("LLAMA")) json.append(",\"scale\":").append(scale == null ? 1D : invoke(invoke(helper,"getAttribute",scale),"getBaseValue"));
                        if (helper.getType().name().equals("INTERACTION")) json.append(",\"height\":").append(invoke(helper,"getInteractionHeight")).append(",\"width\":").append(invoke(helper,"getInteractionWidth"));
                        json.append('}');
                    }
                    json.append("]}");
                    Writer snapshot = new FileWriter(capture); snapshot.write(json.toString()); snapshot.close();
                }
            }
            graph.setProperty("seat_nonliving", rider.getVehicle() instanceof LivingEntity ? "0" : "1");
            File initial = new File(getDataFolder(), "initial-graph.properties");
            if (!initial.exists()) { Writer writer = new FileWriter(initial); graph.store(writer, "Native IDs for loopback packet graph assertions"); writer.close(); }
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private Horse newHorse() {
        Location at = rider.getLocation().clone(); at.setY(64.0);
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4))
            throw new IllegalStateException("Fixture horse chunk is not already loaded");
        Horse next = (Horse)rider.getWorld().spawnEntity(at, org.bukkit.entity.EntityType.HORSE);
        ordinaryHorse(next); next.setAdult(); next.setTamed(true); next.setOwner(driver); next.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        playerHome = at.clone().add(0.5, 0, 0.5);
        return next;
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private void ordinaryHorse(Horse horse) {
        if (horse.getType() != EntityType.HORSE)
            throw new IllegalStateException("Fixture did not create an ordinary horse entity");
        // Since 1.11, EntityType.HORSE itself is the ordinary horse and the old
        // variant setter is unsupported by modern server implementations. Keep
        // the variant normalization only for APIs where horse variants existed.
        if (!legacyHorseVariants()) return;
        try {
            Object variant = Probe.call(horse, "getVariant");
            if (!variant.toString().equals("HORSE")) Probe.call(horse, "setVariant", Enum.valueOf((Class<? extends Enum>)variant.getClass(), "HORSE"));
        } catch (NoSuchMethodException modern) { }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private boolean legacyHorseVariants() {
        try {
            String[] version = Bukkit.getBukkitVersion().split("[.-]");
            int major = Integer.parseInt(version[0]);
            int minor = Integer.parseInt(version[1]);
            return major == 1 && minor < 11;
        } catch (RuntimeException unrecognized) {
            // Unknown versions must never fall through to a setter that may be
            // unsupported. Exact legacy targets use the version string above.
            return false;
        }
    }
    private void transport(Player player, Location at) {
        cancelledTeleportObserved = false;
        if (folia()) publicTeleport = (java.util.concurrent.CompletableFuture<?>)invoke(player, "teleportAsync", at);
        else player.teleport(at);
    }
    private void sequence(final Runnable... steps) { sequenceAt(steps, 0); }
    private void sequenceAt(final Runnable[] steps, final int index) {
        if (index >= steps.length) return;
        later(6, new Runnable() { public void run() {
            try { steps[index].run(); sequenceAt(steps, index + 1); }
            catch (Throwable failure) { failed("lifecycle-" + index, failure); }
        }});
    }
    private void lifecycle() {
        state("lifecycle", "0");
        sequence(
            new Runnable() { public void run() { mount(horse, driver); interact(rider); } },
            new Runnable() { public void run() {
                require("lifecycle-ride-started", rider.isInsideVehicle() && countSeats() == 1);
                trackSeat();
                verifyConfigurationReload();
            } }
        );
    }
    private void verifyConfigurationReload() {
        final Path file = tandem.getDataFolder().toPath().resolve("config.yml");
        final byte[] original = configBytes(file);
        final Entity seat = rider.getVehicle();
        String horseTypes = "supported-horse-types: [HORSE]\n";
        String[][] invalid = {
            {"malformed-yaml", horseTypes + "vertical: [0.8125\n"},
            {"duplicate-key", horseTypes + "vertical: 1.375\n'vertical': 1.25\n"},
            {"nested-duplicate-key", horseTypes + "metadata: {item: 1, 'item': 2}\n"},
            {"enabled-string", horseTypes + "enabled: 'true'\n"},
            {"debug-number", horseTypes + "debug: 1\n"},
            {"explicit-null", horseTypes + "enabled: null\n"},
            {"merged-null", horseTypes + "defaults: &defaults {vertical: ~}\n<<: *defaults\n"},
            {"nonfinite", horseTypes + "vertical: .nan\n"},
            {"out-of-bounds", horseTypes + "rearward: 3.0\n"},
            {"wrong-number-type", horseTypes + "rearward: '0.75'\n"},
            {"mixed-horse-types", "supported-horse-types: [HORSE, {}]\n"},
            {"wrong-exclusion-type", horseTypes + "excluded-worlds: world\n"},
            {"mixed-exclusions", horseTypes + "excluded-worlds: [world, 17]\n"},
            {"duplicate-exclusions", horseTypes + "excluded-worlds: [world, world]\n"},
            {"blank-exclusion", horseTypes + "excluded-worlds: [' ']\n"},
            {"null-exclusion", horseTypes + "excluded-worlds: [world, null]\n"}
        };
        CommandSender console = getServer().getConsoleSender();
        for (String[] sample : invalid) {
            Object active = field(tandem, "settings");
            byte[] contents = sample[1].getBytes(StandardCharsets.UTF_8);
            writeConfig(file, contents);
            configure(console, "reload");
            require("configuration-rejects-" + sample[0], field(tandem, "settings") == active
                    && Arrays.equals(contents, configBytes(file)) && rider.getVehicle() == seat);
        }
        final String world = horse.getWorld().getName();
        byte[] edited = (horseTypes + "# Keep this administrator comment\n"
                + "defaults: &seat {vertical: 1.375, rearward: 0.6875}\n<<: *seat\n"
                + "excluded-worlds: ['" + world.toUpperCase(Locale.ROOT) + "', 'Unloaded World']\n")
                .getBytes(StandardCharsets.UTF_8);
        writeConfig(file, edited);
        Object beforeUnauthorized = field(tandem, "settings");
        configure(driver, "reload");
        require("configuration-denies-non-op-reload", field(tandem, "settings") == beforeUnauthorized
                && Arrays.equals(edited, configBytes(file)));
        configure(console, "reload");
        require("configuration-reloads-without-rewriting-file", Arrays.equals(edited, configBytes(file))
                && settings().vertical == 1.375 && settings().rearward == 0.6875);
        later(6, guarded("configuration-exclusion-reload", new Runnable() { public void run() {
                require("configuration-reload-preserves-ride-with-different-world-case",
                        rider.getVehicle() == seat && driver.getVehicle() == horse && countSeats() == 1);
                Location at = rider.getLocation(), root = horse.getLocation();
                double yaw = Math.toRadians(root.getYaw());
                require("configuration-reload-applies-geometry-to-active-ride",
                        Math.abs(at.getY() - root.getY() - 1.375) < 0.02
                        && Math.abs(at.getX() - root.getX() - Math.sin(yaw) * 0.6875) < 0.02
                        && Math.abs(at.getZ() - root.getZ() + Math.cos(yaw) * 0.6875) < 0.02);
                final byte[] excluded = ("supported-horse-types: [HORSE]\nexcluded-worlds: ['" + world + "']\n")
                        .getBytes(StandardCharsets.UTF_8);
                writeConfig(file, excluded);
                Runnable reload = guarded("configuration-console-dispatch", new Runnable() { public void run() {
                    require("configuration-dispatches-console-reload", getServer().dispatchCommand(
                            getServer().getConsoleSender(), "tandemount reload"));
                    afterOwned(horse, 1, new Runnable() { public void run() {
                        verifyExclusion(file, original, excluded, seat);
                    }});
                }});
                if (folia()) invoke(invoke(getServer(), "getGlobalRegionScheduler"), "execute", NativeFixture.this, reload);
                else reload.run();
        }}));
    }
    private void verifyExclusion(final Path file, final byte[] original, final byte[] excluded, final Entity seat) {
        final CommandSender console = getServer().getConsoleSender();
        sequence(
            new Runnable() { public void run() {
                require("configuration-exclusion-cleans-active-seat",
                        !seat.isValid() && !rider.isInsideVehicle() && driver.getVehicle() == horse && countSeats() == 0);
                interact(rider);
            } },
            new Runnable() { public void run() {
                require("configuration-exclusion-blocks-new-share",
                        !rider.isInsideVehicle() && driver.getVehicle() == horse && countSeats() == 0);
                writeConfig(file, original);
                configure(console, "reload");
                if (!rider.isInsideVehicle()) interact(rider);
                writeConfig(file, excluded);
                configure(console, "reload");
            } },
            new Runnable() { public void run() {
                require("configuration-exclusion-blocks-deferred-share",
                        !rider.isInsideVehicle() && driver.getVehicle() == horse && countSeats() == 0);
                writeConfig(file, original);
                configure(console, "reload");
                if (!rider.isInsideVehicle()) interact(rider);
            } },
            new Runnable() { public void run() {
                require("configuration-removing-exclusion-allows-retry",
                        rider.isInsideVehicle() && driver.getVehicle() == horse && countSeats() == 1);
                trackSeat();
                lifecycleAfterReload();
            } }
        );
    }
    private byte[] configBytes(Path file) {
        try { return Files.readAllBytes(file); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void writeConfig(Path file, byte[] bytes) {
        try { Files.write(file, bytes); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void lifecycleAfterReload() {
        configure(getServer().getConsoleSender(), "disable");
        sequence(
            new Runnable() { public void run() { require("configuration-disable-cleans-active-seat", !rider.isInsideVehicle() && countSeats() == 0); configure(getServer().getConsoleSender(), "enable"); interact(rider); } },
            new Runnable() { public void run() { require("configuration-enable-allows-retry", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); cancelTeleport = true; transport(rider, horse.getLocation().add(0.5, 0, 0.5)); } },
            new Runnable() { public void run() {
                if (!folia() || cancelledTeleportObserved)
                    require("cancelled-teleport-keeps-ride", rider.isInsideVehicle() && countSeats() == 1);
                else {
                    if (publicTeleport == null || !publicTeleport.isDone() || publicTeleport.isCompletedExceptionally())
                        throw new IllegalStateException("Mounted public Folia teleport did not complete normally");
                    boolean accepted = Boolean.TRUE.equals(publicTeleport.getNow(null));
                    record(accepted ? "mounted-folia-teleport-api-unmounts-and-cleans" : "mounted-folia-teleport-api-preserves-ride",
                            accepted ? !rider.isInsideVehicle() && countSeats() == 0 : rider.isInsideVehicle() && countSeats() == 1,
                            accepted ? "The old native Folia public async API accepted the mounted transfer using its UNMOUNT path; no cancellable teleport event was emitted."
                                    : "The native Folia public async API rejected a mounted passenger before emitting a teleport event; the ride remains active.");
                }
                cancelTeleport = false;
                // Later Folia rejects mounted public transfers; older Folia
                // unmounts as part of transfer. Ensure the following accepted
                // public API request starts after an ordinary dismount.
                if (folia() && rider.isInsideVehicle()) rider.leaveVehicle();
                transport(rider, horse.getLocation().add(0.5, 0, 0.5));
            } },
            new Runnable() { public void run() { require("passenger-teleport-cleans-seat", !rider.isInsideVehicle() && countSeats() == 0); interact(rider); } },
            new Runnable() { public void run() {
                require("retry-after-teleport", rider.isInsideVehicle() && countSeats() == 1); trackSeat();
                worldAndChunkLifecycle(new Runnable() { public void run() { lifecycleRemoval(); }});
            } }
        );
    }
    private void lifecycleRemoval() {
        sequence(
            new Runnable() { public void run() { trackSeat(); horse.remove(); } },
            new Runnable() { public void run() { require("horse-removal-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle()); horse = newHorse(); mount(horse, driver); interact(rider); } },
            new Runnable() { public void run() { require("new-horse-independent-identity", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); horse.setHealth(0); } },
            new Runnable() { public void run() { require("horse-death-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle()); horse = newHorse(); mount(horse, driver); interact(rider); } },
            new Runnable() { public void run() { require("ride-before-real-disconnect", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); state("disconnect", "rider"); } }
        );
    }

    /** Native transfers and a real unload, confined to already-loaded disposable chunks. */
    private void worldAndChunkLifecycle(final Runnable complete) {
        if (otherWorldDestination == null) { complete.run(); return; }
        final World originalWorld = horse.getWorld();
        final Location back = horse.getLocation().add(0.5, 0, 0.5);
        require("ride-active-before-native-world-change", rider.isInsideVehicle() && countSeats() == 1);
        transfer(rider, otherWorldDestination, new Runnable() { public void run() {
            record("native-passenger-world-change-cleans-seat", rider.getWorld() != originalWorld
                            && rider.getWorld() == otherWorldDestination.getWorld() && !rider.isInsideVehicle() && countSeats() == 0,
                    folia() ? "Actual Folia native async transfer requested while mounted, UNMOUNT flag enabled, LOAD_CHUNK flag disabled; native unmount is part of world transfer."
                            : "Actual Bukkit cross-world teleport requested while mounted; native teleport/dismount lifecycle removed the helper.");
            transfer(rider, back, new Runnable() { public void run() {
                interact(rider);
                afterOwned(rider, 6, new Runnable() { public void run() {
                    require("retry-after-native-world-change", rider.isInsideVehicle() && countSeats() == 1);
                    trackSeat(); evacuateAndUnload(originalWorld, complete);
                }});
            }});
        }});
    }
    private void evacuateAndUnload(final World originalWorld, final Runnable complete) {
        Location origin = horse.getLocation();
        unloadX = origin.getBlockX() >> 4; unloadZ = origin.getBlockZ() >> 4;
        unloadObserved = false; unloadContainedHelper = false; unloadWorld = originalWorld;
        require("ride-active-before-native-chunk-evacuation", rider.isInsideVehicle() && countSeats() == 1);
        transferAll(otherWorldDestination, new Runnable() { public void run() {
            require("native-chunk-evacuation-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle());
            pollUnload(60, new Runnable() { public void run() {
                record("native-chunk-unload-after-evacuation", unloadObserved && !unloadContainedHelper
                                && !originalWorld.isChunkLoaded(unloadX, unloadZ) && countSeats() == 0,
                        "Actual ChunkUnloadEvent and unloaded-state observation after native world teleport/dismount evacuated all four loopback players; observed="
                                + unloadObserved + ", liveHelper=" + unloadContainedHelper + ", loaded=" + originalWorld.isChunkLoaded(unloadX, unloadZ)
                                + ". Removed/dead native references are not live helpers. This does not isolate unloading an occupied ride.");
                unloadWorld = null;
                afterOwned(driver, 1, new Runnable() { public void run() {
                    // Retry among the connected riders in the active landing
                    // world. The chunk under test remains unloaded.
                    require("retry-target-already-active", rider.getWorld() == otherWorldDestination.getWorld()
                            && driver.getWorld() == rider.getWorld() && !originalWorld.isChunkLoaded(unloadX, unloadZ));
                    horse = newHorse();
                    if (!horse.isValid()) throw new IllegalStateException("The retry horse did not become a native tracked entity");
                    mount(horse, driver); interact(rider);
                    afterOwned(rider, 6, new Runnable() { public void run() {
                        require("retry-after-native-chunk-unload", rider.isInsideVehicle() && countSeats() == 1);
                        complete.run();
                    }});
                }});
            }});
        }});
    }
    private void pollUnload(final int attempts, final Runnable complete) {
        if (!unloadWorld.isChunkLoaded(unloadX, unloadZ)) { complete.run(); return; }
        if (attempts == 0) throw new IllegalStateException("Native chunk remains loaded after evacuation; no ticket or loading policy was overridden");
        final World world = unloadWorld;
        try { region(world, unloadX, unloadZ, guarded("native-chunk-unload", new Runnable() { public void run() {
            if (world.isChunkLoaded(unloadX, unloadZ)) world.unloadChunk(unloadX, unloadZ, true);
            afterOwned(driver, 6, new Runnable() { public void run() { pollUnload(attempts - 1, complete); }});
        }})); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private void transferAll(final Location destination, final Runnable complete) {
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(4);
        for (String name : new String[] {"TmDriver", "TmRider", "TmThird", "TmLate"}) {
            final Player player = Bukkit.getPlayerExact(name);
            if (player == null || !player.isOnline() || player.isDead()) throw new IllegalStateException("All four live loopback players are required for native chunk evacuation");
            next(player, guarded("native-player-transfer", new Runnable() { public void run() {
                transfer(player, destination, new Runnable() { public void run() {
                    if (remaining.decrementAndGet() == 0) afterOwned(driver, 6, complete);
                }});
            }}));
        }
    }
    private void transfer(final Player player, final Location destination, final Runnable landed) {
        if (!owns(player)) throw new IllegalStateException("Native transfer must run on the player's owning entity scheduler");
        if (!destination.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4))
            throw new IllegalStateException("Native transfer destination is not already loaded; fixture will not load it");
        if (!folia()) {
            if (!player.teleport(destination)) throw new IllegalStateException("Native Bukkit world transfer was rejected");
            afterOwned(player, 6, landed); return;
        }
        try {
            Object handle = Probe.handle(player), world = Probe.handle(destination.getWorld());
            for (Method method : handle.getClass().getMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (method.getName().equals("teleportAsync") && args.length == 8 && args[0].isInstance(world)
                        && args[1] == args[4] && args[2] == Float.class && args[3] == Float.class
                        && args[5] == PlayerTeleportEvent.TeleportCause.class && args[6] == long.class
                        && args[7] == java.util.function.Consumer.class) {
                    // The exact upstream signature supplies Vec3D on the old
                    // Spigot mappings and Vec3 on the newer Mojang mappings.
                    Constructor<?> vector = args[1].getConstructor(double.class, double.class, double.class);
                    Object at = vector.newInstance(destination.getX(), destination.getY(), destination.getZ());
                    Object zero = vector.newInstance(0.0, 0.0, 0.0);
                    long unmount = method.getDeclaringClass().getField("TELEPORT_FLAG_UNMOUNT").getLong(null);
                    long load = method.getDeclaringClass().getField("TELEPORT_FLAG_LOAD_CHUNK").getLong(null);
                    if ((unmount & load) != 0) throw new IllegalStateException("Native transfer flags unexpectedly request chunk loading");
                    Object accepted = Probe.call(method, handle, world, at, Float.valueOf(destination.getYaw()), Float.valueOf(destination.getPitch()),
                            zero, PlayerTeleportEvent.TeleportCause.PLUGIN, Long.valueOf(unmount), new java.util.function.Consumer<Object>() {
                                public void accept(Object arrived) { afterOwned(player, 6, landed); }
                            });
                    if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("Native Folia mounted world transfer was rejected");
                    return;
                }
            }
            throw new NoSuchMethodException("Native Folia teleportAsync with explicit no-load flags");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private Runnable guarded(final String name, final Runnable work) {
        return new Runnable() { public void run() { try { work.run(); } catch (Throwable failure) { failed(name, failure); } }};
    }
    private void afterOwned(final Entity owner, final int ticks, final Runnable work) {
        next(owner, guarded("native-lifecycle-step", new Runnable() { public void run() {
            if (ticks <= 1) work.run(); else afterOwned(owner, ticks - 1, work);
        }}));
    }
    private void disconnected() {
        later(6, new Runnable() { public void run() {
            require("real-disconnect-cleans-seat", !rider.isOnline() && countSeats() == 0 && driver.getVehicle() == horse);
            state("reconnect", "rider");
        }});
    }
    private void rejoined() {
        next(driver, new Runnable() { public void run() {
            rider = Bukkit.getPlayerExact("TmRider");
            require("real-player-reconnected", rider != null && rider.isOnline());
            interact(rider);
            sequence(
                new Runnable() { public void run() {
                    require("reconnected-player-can-retry", rider.isInsideVehicle() && countSeats() == 1); trackSeat();
                    Entity seat = rider.getVehicle();
                    org.bukkit.event.entity.EntityDamageEvent damage = new org.bukkit.event.entity.EntityDamageEvent(seat, org.bukkit.event.entity.EntityDamageEvent.DamageCause.CUSTOM, 100.0);
                    getServer().getPluginManager().callEvent(damage);
                    require("seat-cannot-take-damage", seat.isValid() && damage.isCancelled());
                    PlayerInteractEntityEvent blocked = new PlayerInteractEntityEvent(third, seat); getServer().getPluginManager().callEvent(blocked);
                    require("seat-cannot-be-interacted-with", blocked.isCancelled());
                    rider.leaveVehicle();
                } },
                new Runnable() { public void run() {
                    final Player late = Bukkit.getPlayerExact("TmLate");
                    final Horse second = newHorse(); mount(second, third);
                    getServer().getPluginManager().callEvent(new PlayerInteractEntityEvent(late, second));
                    interact(rider);
                    later(4, new Runnable() { public void run() {
                        require("two-simultaneous-rides-independent", countSeats() == 2 && rider.isInsideVehicle() && late.isInsideVehicle());
                        helperIds.add(late.getVehicle().getUniqueId()); trackSeat(); second.remove();
                    }});
                } },
                new Runnable() { public void run() { require("secondary-removal-preserves-primary-ride", countSeats() == 1 && rider.isInsideVehicle() && driver.getVehicle() == horse); trackSeat(); rider.setHealth(0); } },
                new Runnable() { public void run() { require("passenger-death-cleans-seat", countSeats() == 0); rider = Bukkit.getPlayerExact("TmLate"); interact(rider); } },
                new Runnable() { public void run() { require("fresh-passenger-after-death", countSeats() == 1 && rider.isInsideVehicle()); trackSeat(); driver.setHealth(0); } },
                new Runnable() { public void run() { require("driver-death-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle()); driver = third; horse = newHorse(); mount(horse, driver); interact(rider); } },
                new Runnable() { public void run() { require("ride-before-save-and-disable", countSeats() == 1 && rider.isInsideVehicle()); trackSeat(); state("save", "0"); } }
            );
        }});
    }
    private void saveprobe() {
        if (!"save".equals(currentPhase) || saveProbePending)
            throw new IllegalStateException("Native serialization probe requires the active save phase exactly once");
        Object[] rides = ((Map<?, ?>)field(tandem, "horses")).values().toArray();
        if (rides.length != 1) throw new IllegalStateException("Native serialization probe requires exactly one tracked ride");
        final Object ride = rides[0];
        final Entity seat = (Entity)field(ride, "seat");
        saveProbePending = true;
        next(seat, guarded("active-seat-refuses-native-serialization", new Runnable() { public void run() {
            try {
                Object nativeSeat = Probe.handle(seat);
                activeSaveSeat(ride, seat, nativeSeat);
                StringBuilder checks = new StringBuilder();
                for (Entity helper : (Entity[])field(ride, "helpers")) {
                Object nativeHelper = Probe.handle(helper);
                Class<?> base = nativeHelper.getClass().getSuperclass();
                if (!helper.isValid() || !helperIds.contains(helper.getUniqueId())) throw new IllegalStateException("Untracked native attachment save probe");
                boolean entryPoint = false;
                for (Method method : Probe.methods(base)) {
                    Class<?>[] parameters = method.getParameterTypes();
                    String name = method.getName();
                    if (method.getReturnType() != boolean.class || Modifier.isStatic(method.getModifiers())
                            || Modifier.isPrivate(method.getModifiers()) || parameters.length == 0
                            || !(parameters[0].getSimpleName().equals("NBTTagCompound")
                                || parameters[0].getSimpleName().equals("CompoundTag")
                                || parameters[0].getSimpleName().equals("ValueOutput"))
                            || !(name.equals("save") || name.equals("saveAsPassenger") || name.equals("serializeEntity")
                                || name.equals("b") || name.equals("c") || name.equals("d") || name.equals("e")
                                || name.equals("f") || name.equals("g") || name.equals("a_"))) continue;
                    boolean supported = true;
                    for (int i = 1; i < parameters.length; i++) if (parameters[i] != boolean.class) supported = false;
                    if (!supported) continue;
                    Object output = nativeSaveOutput(parameters[0], nativeHelper);
                    if (!emptySaveOutput(output)) throw new IllegalStateException("Native serialization output was not initially empty");
                    Object[] arguments = new Object[parameters.length]; arguments[0] = output;
                    // Exercise upstream force-save flags as well as the ordinary
                    // entry points; the helper must refuse either request.
                    for (int i = 1; i < arguments.length; i++) arguments[i] = Boolean.TRUE;
                    Object saved = Probe.call(method, nativeHelper, arguments);
                    boolean empty = emptySaveOutput(output);
                    if (!Boolean.FALSE.equals(saved) || !empty)
                        throw new IllegalStateException(method.toGenericString() + " returned " + saved + ", empty=" + empty);
                    if (checks.length() != 0) checks.append("; ");
                    checks.append(method.toGenericString()).append(" -> false, empty ").append(output.getClass().getName());
                    if (parameters.length == 1 && !name.equals("saveAsPassenger")) entryPoint = true;
                }
                if (!entryPoint) throw new NoSuchMethodException("Exact native boolean save/serializeEntity entry point");
                }
                activeSaveSeat(ride, seat, nativeSeat);
                record("active-seat-refuses-native-serialization", true,
                        "Live tracked helper with its passenger mounted, on its entity owner, before and after exact native calls. " + checks);
                state("save-probed", "native-serialization-refused");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }}));
    }
    private void activeSaveSeat(Object ride, Entity seat, Object nativeSeat) throws ReflectiveOperationException {
        Class<?> bridge = Class.forName("dev.eyecrasher.tandemount.NativeSeat", false, tandem.getClass().getClassLoader());
        Map<?, ?> nativeRides = (Map<?, ?>)Probe.get(bridge.getDeclaredField("RIDES"), null);
        if (!"save".equals(currentPhase) || !owns(seat) || !owns(rider) || !owns(horse) || !owns(driver)
                || countSeats() != 1 || !seat.isValid() || seat.isDead() || !rider.isOnline() || rider.isDead()
                || !horse.isValid() || horse.isDead() || !driver.isOnline() || driver.isDead()
                || rider.getVehicle() != seat || driver.getVehicle() != horse || !helperIds.contains(seat.getUniqueId())
                || field(ride, "seat") != seat || field(ride, "nativeSeat") != nativeSeat
                || field(ride, "rider") != rider || field(ride, "driver") != driver || field(ride, "horse") != horse
                || (Boolean)field(ride, "closed") || (Boolean)field(ride, "closing")
                || ((Map<?, ?>)field(tandem, "horses")).get(horse.getUniqueId()) != ride
                || ((Map<?, ?>)field(tandem, "riders")).get(rider.getUniqueId()) != ride || nativeRides.get(nativeSeat) != ride)
            throw new IllegalStateException("Native save proof requires the same live, tracked, mounted helper on its entity owner");
    }
    private Object nativeSaveOutput(Class<?> type, Object nativeSeat) throws ReflectiveOperationException {
        if (!type.getSimpleName().equals("ValueOutput")) return type.getConstructor().newInstance();
        Class<?> reporter = Class.forName("net.minecraft.util.ProblemReporter", true, type.getClassLoader());
        Class<?> output = Class.forName("net.minecraft.world.level.storage.TagValueOutput", true, type.getClassLoader());
        Field discarded;
        try { discarded = reporter.getField("DISCARDING"); }
        catch (NoSuchFieldException spigot) { discarded = reporter.getField("a"); }
        if (!Modifier.isStatic(discarded.getModifiers()) || discarded.getType() != reporter)
            throw new NoSuchFieldException("Native discarding problem reporter");
        Object discarding = discarded.get(null);
        for (Method factory : output.getMethods()) if ((factory.getName().equals("createWithContext") || factory.getName().equals("a"))
                && Modifier.isStatic(factory.getModifiers()) && type.isAssignableFrom(factory.getReturnType())
                && factory.getParameterTypes().length == 2 && factory.getParameterTypes()[0] == reporter) {
            // The named and Spigot SDKs expose the same registry-context
            // factory with different names. Derive its provider from the real
            // native method's return type, without replacing that context.
            Class<?> provider = factory.getParameterTypes()[1];
            Method registryAccess = null;
            for (Method candidate : nativeSeat.getClass().getMethods()) if (!Modifier.isStatic(candidate.getModifiers())
                    && candidate.getParameterTypes().length == 0 && provider.isAssignableFrom(candidate.getReturnType())) {
                if (registryAccess != null) throw new NoSuchMethodException("Unambiguous native entity registry access");
                registryAccess = candidate;
            }
            if (registryAccess == null) throw new NoSuchMethodException("Native entity registry access");
            Object registries = Probe.call(registryAccess, nativeSeat);
            return Probe.call(factory, (Object)null, discarding, registries);
        }
        throw new NoSuchMethodException("Native TagValueOutput.createWithContext(ProblemReporter, HolderLookup.Provider)");
    }
    private boolean emptySaveOutput(Object output) throws ReflectiveOperationException {
        if (output.getClass().getSimpleName().equals("TagValueOutput")) {
            Object empty, compound;
            try { empty = Probe.call(output, "isEmpty"); }
            catch (NoSuchMethodException spigot) { empty = Probe.call(output, "a"); }
            if (!Boolean.TRUE.equals(empty)) return false;
            try { compound = Probe.call(output, "buildResult"); }
            catch (NoSuchMethodException spigot) { compound = Probe.call(output, "b"); }
            return emptySaveOutput(compound);
        }
        try { if (!Boolean.TRUE.equals(Probe.call(output, "isEmpty"))) return false; }
        catch (NoSuchMethodException mappedNbt) {
            // Older obfuscated NBT has no isEmpty name. Inspect its real tag
            // map, requiring one unambiguous native backing map.
            int maps = 0;
            for (Field tags : Probe.fields(output.getClass())) if (!Modifier.isStatic(tags.getModifiers())
                    && Map.class.isAssignableFrom(tags.getType())) {
                maps++;
                if (!((Map<?, ?>)Probe.get(tags, output)).isEmpty()) return false;
            }
            if (maps != 1) throw new NoSuchFieldException("Unambiguous native NBT tag map");
        }
        return true;
    }
    private void finish() {
        if (!"save-probed".equals(currentPhase)) throw new IllegalStateException("Native serialization must be proved before disabling the plugin");
        getServer().getPluginManager().disablePlugin(tandem);
        next(rider, new Runnable() { public void run() {
            later(6, new Runnable() { public void run() {
                require("plugin-disable-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle());
                boolean orphan = false;
                for (Entity entity : rider.getWorld().getEntities()) if (helperIds.contains(entity.getUniqueId())) orphan = true;
                require("no-native-helper-orphans", !orphan);
                state("complete", Integer.toString(failures));
            }});
        }});
    }
    private void restartcheck() throws ReflectiveOperationException {
        region(new Runnable() { public void run() {
            boolean orphan = false;
            for (Entity entity : Bukkit.getWorlds().get(0).getChunkAt(0, 0).getEntities())
                if (helperIds.contains(entity.getUniqueId())) orphan = true;
            require("native-restart-does-not-restore-helper", !orphan && countSeats() == 0);
            state("restart-complete", Integer.toString(failures));
        }});
    }
    private void later(final int remaining, final Runnable work) {
        Entity owner = driver != null && driver.isOnline() && !driver.isDead() ? driver
                : rider != null && rider.isOnline() && !rider.isDead() ? rider : third;
        next(owner, new Runnable() { public void run() {
            if (remaining <= 1) work.run(); else later(remaining - 1, work);
        }});
    }
    private void require(String name, boolean value) { record(name, value, "Actual native server with loopback clients"); }
    private void failed(String name, Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, name, failure); record(name, false, failure.toString()); state("failed", name);
    }
    private void record(String name, boolean passed, String detail) {
        if (!passed) failures++;
        String line = "{\"assertion\":\"" + escape(name) + "\",\"status\":\"" + (passed ? "passed" : "failed") + "\",\"detail\":\"" + escape(detail) + "\"}\n";
        try { Writer out = new OutputStreamWriter(new FileOutputStream(new File(getDataFolder(), "assertions.jsonl"), true), "UTF-8"); out.write(line); out.close(); }
        catch (IOException e) { throw new RuntimeException(e); }
        getLogger().info("ASSERT " + (passed ? "PASS " : "FAIL ") + name);
    }
    private void state(String phase, String value) {
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(new File(getDataFolder(), "phase.json")), "UTF-8");
            out.write("{\"phase\":\"" + escape(phase) + "\",\"value\":\"" + escape(value) + "\",\"failures\":" + failures + "}"); out.close();
            currentPhase = phase;
        } catch (IOException e) { throw new RuntimeException(e); }
    }
    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private void dumpProtocols() throws Exception {
        final Properties ids = new Properties();
        boolean templates = false;
        try {
            for (String stage : new String[] {"login", "configuration", "game"}) {
                String cap = Character.toUpperCase(stage.charAt(0)) + stage.substring(1);
                Class<?> protocols = Class.forName("net.minecraft.network.protocol." + stage + "." + cap + "Protocols");
                for (String flow : new String[] {"SERVERBOUND", "CLIENTBOUND"}) {
                    final String prefix = stage + "." + flow.toLowerCase(Locale.ROOT) + ".";
                    Object unbound = protocolTemplate(protocols, flow);
                    try {
                        Object details = unbound;
                        Method visit;
                        try { visit = protocolVisitor(details.getClass()); }
                        catch (NoSuchMethodException separateDetails) {
                            try { details = Probe.call(unbound, "details"); }
                            catch (NoSuchMethodException spigot) { details = Probe.call(unbound, "a"); }
                            visit = protocolVisitor(details.getClass());
                        }
                        Class<?> visitorType = visit.getParameterTypes()[0];
                        Object visitor = Proxy.newProxyInstance(visitorType.getClassLoader(), new Class<?>[] {visitorType}, new InvocationHandler() {
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                if (method.getName().equals("accept")) ids.setProperty(prefix + protocolPacketId(args[0]), args[1].toString());
                                return null;
                            }
                        });
                        Probe.call(visit, details, visitor);
                    } catch (NoSuchMethodException earlier) {
                        Object info = unbound;
                        try { info = Probe.call(unbound, "bind", new java.util.function.Function<Object, Object>() {
                            public Object apply(Object value) { return value; }
                        }); } catch (NoSuchMethodException alreadyBound) { }
                        Object codec;
                        try { codec = Probe.call(info, "codec"); }
                        catch (NoSuchMethodException spigot) { codec = Probe.call(info, "c"); }
                        for (Field f : Probe.fields(codec.getClass())) if (Map.class.isAssignableFrom(f.getType()))
                            for (Map.Entry<?, ?> entry : ((Map<?, ?>)Probe.get(f, codec)).entrySet())
                                if (entry.getValue() instanceof Number) ids.setProperty(prefix + protocolPacketId(entry.getKey()), entry.getValue().toString());
                    }
                }
            }
            templates = true;
        } catch (ClassNotFoundException older) { }
        if (!templates) {
            String versioned = ((Class<?>)field(field(tandem, "nativeAccess"), "entityClass")).getPackage().getName();
            Class<?> protocol = null;
            for (String name : new String[] {versioned + ".EnumProtocol", "net.minecraft.network.EnumProtocol", "net.minecraft.network.ConnectionProtocol"})
                try { protocol = Class.forName(name); break; } catch (ClassNotFoundException absent) { }
            if (protocol == null) throw new ClassNotFoundException("Native protocol registry");
            for (Object stage : protocol.getEnumConstants()) {
                String stageName = ((Enum<?>)stage).name().toLowerCase(Locale.ROOT);
                for (Method m : protocol.getMethods()) if (Map.class.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isEnum())
                    for (Object direction : m.getParameterTypes()[0].getEnumConstants())
                        extractIds(ids, stageName + "." + ((Enum<?>)direction).name().toLowerCase(Locale.ROOT) + ".", (Map<?, ?>)Probe.call(m, stage, direction));
                for (Field f : protocol.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                    Map<?, ?> map = (Map<?, ?>)Probe.get(f, stage);
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String prefix = stageName + "." + String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT) + ".";
                        if (entry.getValue() instanceof Map) extractIds(ids, prefix, (Map<?, ?>)entry.getValue());
                        else if (!(entry.getValue() instanceof Class) && entry.getValue() != null)
                            for (Field nested : Probe.fields(entry.getValue().getClass())) if (Map.class.isAssignableFrom(nested.getType()))
                                extractIds(ids, prefix, (Map<?, ?>)Probe.get(nested, entry.getValue()));
                    }
                    extractIds(ids, stageName + ".", map);
                }
            }
        }
        Writer writer = new OutputStreamWriter(new FileOutputStream(new File(getDataFolder(), "protocol.properties")), "UTF-8");
        ids.store(writer, "Exact packet identifiers obtained from loaded upstream native protocol registry"); writer.close();
    }
    private Object protocolTemplate(Class<?> protocols, String flow) throws ReflectiveOperationException {
        for (String name : new String[] {flow + "_TEMPLATE", flow}) try { return protocols.getField(name).get(null); }
        catch (NoSuchFieldException spigot) { /* The SDK retains listener generic types. */ }
        Field selected = null;
        int priority = 0;
        for (Field field : protocols.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !(field.getGenericType() instanceof ParameterizedType)) continue;
            Type listener = ((ParameterizedType)field.getGenericType()).getActualTypeArguments()[0];
            if (!(listener instanceof Class)) continue;
            String name = ((Class<?>)listener).getSimpleName();
            boolean serverbound = name.startsWith("Server") || name.contains("PlayIn") || name.contains("LoginIn");
            boolean clientbound = name.startsWith("Client") || name.contains("PlayOut") || name.contains("LoginOut");
            if (!(flow.equals("SERVERBOUND") ? serverbound : clientbound)) continue;
            int score = field.getType().getSimpleName().equals("ProtocolInfo") ? 1 : 2;
            if (score > priority) { selected = field; priority = score; }
            else if (score == priority) throw new NoSuchFieldException("Unambiguous upstream protocol template " + flow);
        }
        if (selected == null) throw new NoSuchFieldException("Native protocol template " + flow);
        return selected.get(null);
    }
    private Method protocolVisitor(Class<?> details) throws NoSuchMethodException {
        for (Method method : details.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() != void.class || parameters.length != 1 || !parameters[0].isInterface()) continue;
            for (Method callback : parameters[0].getMethods()) {
                Class<?>[] arguments = callback.getParameterTypes();
                if (callback.getName().equals("accept") && arguments.length == 2 && arguments[1] == int.class
                        && arguments[0].getSimpleName().equals("PacketType")) return method;
            }
        }
        throw new NoSuchMethodException("Native protocol packet visitor");
    }
    private String protocolPacketId(Object type) throws ReflectiveOperationException {
        for (String name : new String[] {"id", "b"}) try {
            Method method = type.getClass().getMethod(name);
            String result = method.getReturnType().getSimpleName();
            if (result.equals("Identifier") || result.equals("ResourceLocation") || result.equals("MinecraftKey"))
                return String.valueOf(Probe.call(method, type));
        } catch (NoSuchMethodException absent) { /* Next exact SDK mapping. */ }
        throw new NoSuchMethodException("Native packet type identifier");
    }
    private void extractIds(Properties ids, String prefix, Map<?, ?> map) {
        for (Map.Entry<?, ?> packet : map.entrySet()) {
            Class<?> type = packet.getValue() instanceof Class ? (Class<?>)packet.getValue() : packet.getKey() instanceof Class ? (Class<?>)packet.getKey() : null;
            Object id = packet.getValue() instanceof Number ? packet.getValue() : packet.getKey();
            if (type == null || !(id instanceof Number)) continue;
            String direction = type.getSimpleName().contains("In") || type.getSimpleName().startsWith("Serverbound") ? "serverbound" : "clientbound";
            ids.setProperty(prefix + (prefix.split("\\.").length < 2 ? direction + "." : "") + type.getSimpleName(), id.toString());
        }
    }
    private static final class Probe {
    private Probe() { }
    static List<Method> methods(Class<?> type) {
        LinkedHashMap<String, Method> result = new LinkedHashMap<String, Method>();
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods()) {
                String key = m.getName() + Arrays.toString(m.getParameterTypes());
                if (!result.containsKey(key)) result.put(key, m);
            }
        return new ArrayList<Method>(result.values());
    }
    static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<Field>();
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            result.addAll(Arrays.asList(c.getDeclaredFields()));
        return result;
    }
    static Object get(Field field, Object target) throws ReflectiveOperationException {
        field.setAccessible(true); return field.get(target);
    }
    static Object call(Method method, Object target, Object... args) throws ReflectiveOperationException {
        try { method.setAccessible(true); return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new ReflectiveOperationException(cause);
        }
    }
    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Method candidate = null;
        for (Method m : target.getClass().getMethods()) if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) {
            if (candidate == null || candidate.isBridge()) candidate = m;
        }
        if (candidate == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return call(candidate, target, args);
    }
    static boolean compatible(Class<?>[] params, Object[] args) {
        if (params.length != args.length) return false;
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            if (p.isPrimitive()) {
                if (p == boolean.class) p = Boolean.class;
                else if (p == int.class) p = Integer.class;
                else if (p == long.class) p = Long.class;
                else if (p == double.class) p = Double.class;
                else if (p == float.class) p = Float.class;
                else if (p == byte.class) p = Byte.class;
                else if (p == short.class) p = Short.class;
            }
            if (args[i] == null ? params[i].isPrimitive() : !p.isInstance(args[i])) return false;
        }
        return true;
    }
    static Method named(Class<?> type, String... names) throws NoSuchMethodException {
        for (String name : names) for (Method m : methods(type)) if (m.getName().equals(name)) return m;
        throw new NoSuchMethodException(type.getName() + Arrays.toString(names));
    }
    static Object handle(Object bukkit) throws ReflectiveOperationException { return call(bukkit, "getHandle"); }
    static void optional(Object target, String name, Object... values) throws ReflectiveOperationException {
        try { call(target, name, values); } catch (NoSuchMethodException unavailable) { /* Old API. */ }
    }
}

}
