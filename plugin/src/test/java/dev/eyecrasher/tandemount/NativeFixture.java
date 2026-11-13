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
import java.util.*;

/** Disposable runtime fixture. It is never included in the release plugin. */
public final class NativeFixture extends JavaPlugin implements Listener {
    private TandemountPlugin tandem;
    private Horse horse;
    private Player driver, rider, third;
    private final Set<UUID> helperIds = new HashSet<UUID>();
    private boolean cancelTeleport;
    private boolean cancelInteraction, cancelDismount;
    private int failures;
    private double riderHealth, horseHealth, beforeVertical, beforeRearward;
    private UUID horseIdentity;
    private ItemStack[] riderInventory;
    private Location driveStart;

    @Override public void onEnable() {
        tandem = (TandemountPlugin)getServer().getPluginManager().getPlugin("Tandemount");
        getServer().getPluginManager().registerEvents(this, this);
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
    }
    @EventHandler(priority = EventPriority.MONITOR) public void cancelledExit(org.bukkit.event.vehicle.VehicleExitEvent event) {
        if (cancelDismount) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void cancelledTeleport(PlayerTeleportEvent event) {
        if (cancelTeleport && event.getPlayer() == rider) event.setCancelled(true);
    }
    @EventHandler public void locate(final PlayerJoinEvent event) {
        next(event.getPlayer(), new Runnable() { public void run() {
            Location at = new Location(Bukkit.getWorlds().get(0), 8.5, 64.0, 8.5);
            if (folia()) invoke(event.getPlayer(), "teleportAsync", at);
            else event.getPlayer().teleport(at);
        }});
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equals("tmfixture")) return false;
        if (args.length == 0) return true;
        try {
            if (args[0].equals("prepare")) prepare();
            if (args[0].equals("start")) start();
            if (args[0].equals("late")) late();
            if (args[0].equals("finish")) finish();
            if (args[0].equals("disconnected")) disconnected();
            if (args[0].equals("rejoined")) rejoined();
            if (args[0].equals("restartcheck")) restartcheck();
        } catch (Throwable failure) { failed("fixture-command", failure); }
        return true;
    }
    private void region(final Runnable work) throws ReflectiveOperationException {
        if (!folia()) { Bukkit.getScheduler().runTask(this, work); return; }
        Object region = Probe.call(getServer(), "getRegionScheduler");
        Probe.call(region, "execute", this, Bukkit.getWorlds().get(0), 0, 0, work);
    }
    private void prepare() throws ReflectiveOperationException {
        Bukkit.getWorlds().get(0).setGameRuleValue("spawnRadius", "0");
        region(new Runnable() { public void run() {
            try {
                World world = Bukkit.getWorlds().get(0);
                for (int x = 0; x <= 32; x++) for (int z = 0; z <= 32; z++) world.getBlockAt(x, 63, z).setType(Material.STONE);
                world.setSpawnLocation(8, 64, 8);
                state("prepared", "0");
            } catch (Throwable failure) { failed("prepare-world", failure); }
        }});
    }
    private void start() throws ReflectiveOperationException {
        region(new Runnable() { public void run() {
            try {
                driver = Bukkit.getPlayerExact("TmDriver"); rider = Bukkit.getPlayerExact("TmRider"); third = Bukkit.getPlayerExact("TmThird");
                require("three-real-loopback-players", driver != null && rider != null && third != null);
                if (driver == null || rider == null || third == null) { state("failed", "players"); return; }
                require("players-owned-by-region", owns(driver) && owns(rider) && owns(third));
                driver.setOp(false); rider.setOp(false); third.setOp(false);
                horse = (Horse)driver.getWorld().spawnEntity(new Location(driver.getWorld(), 8, 64, 8), org.bukkit.entity.EntityType.HORSE);
                ordinaryHorse(horse); horse.setAdult(); horse.setTamed(true); horse.setOwner(driver);
                horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                horseIdentity = horse.getUniqueId(); horseHealth = horse.getHealth(); riderHealth = rider.getHealth();
                riderInventory = rider.getInventory().getContents().clone();
                require("native-driver-mounted", mount(horse, driver));
                getLogger().info("START_LOCATIONS horse=" + horse.getLocation() + " rider=" + rider.getLocation() + " driver=" + driver.getLocation());
                cancelInteraction = true;
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
    private void interact(Player player) {
        getLogger().info("INTERACT_STATE player=" + player.getName() + " health=" + player.getHealth() + " at=" + player.getLocation()
                + " vehicle=" + player.getVehicle() + " horse=" + horse.getLocation() + " driverVehicle=" + driver.getVehicle()
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
            require("seat-is-nonliving-vanilla-falling-block", rider.getVehicle().getType() == EntityType.FALLING_BLOCK
                    && !(rider.getVehicle() instanceof LivingEntity));
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
    private void trackSeat() {
        if (rider.getVehicle() == null) return;
        helperIds.add(rider.getVehicle().getUniqueId());
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(new File(getDataFolder(), "seat-uuids.txt")), "UTF-8");
            for (UUID id : helperIds) out.write(id.toString() + "\n"); out.close();
            Properties graph = new Properties();
            graph.setProperty("horse", Integer.toString(horse.getEntityId()));
            graph.setProperty("driver", Integer.toString(driver.getEntityId()));
            graph.setProperty("rider", Integer.toString(rider.getEntityId()));
            graph.setProperty("seat", Integer.toString(rider.getVehicle().getEntityId()));
            graph.setProperty("seat_falling_block", rider.getVehicle().getType() == EntityType.FALLING_BLOCK ? "1" : "0");
            graph.setProperty("seat_nonliving", rider.getVehicle() instanceof LivingEntity ? "0" : "1");
            File initial = new File(getDataFolder(), "initial-graph.properties");
            if (!initial.exists()) { Writer writer = new FileWriter(initial); graph.store(writer, "Native IDs for loopback packet graph assertions"); writer.close(); }
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private Horse newHorse() {
        Horse next = (Horse)rider.getWorld().spawnEntity(new Location(rider.getWorld(), 8, 64, 8), org.bukkit.entity.EntityType.HORSE);
        ordinaryHorse(next); next.setAdult(); next.setTamed(true); next.setOwner(driver); next.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        return next;
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private void ordinaryHorse(Horse horse) {
        try {
            Object variant = Probe.call(horse, "getVariant");
            if (!variant.toString().equals("HORSE")) Probe.call(horse, "setVariant", Enum.valueOf((Class<? extends Enum>)variant.getClass(), "HORSE"));
        } catch (NoSuchMethodException modern) { }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private void transport(Player player, Location at) {
        if (folia()) invoke(player, "teleportAsync", at); else player.teleport(at);
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
            new Runnable() { public void run() { require("lifecycle-ride-started", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); configure(getServer().getConsoleSender(), "disable"); } },
            new Runnable() { public void run() { require("configuration-disable-cleans-active-seat", !rider.isInsideVehicle() && countSeats() == 0); configure(getServer().getConsoleSender(), "enable"); interact(rider); } },
            new Runnable() { public void run() { require("configuration-enable-allows-retry", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); cancelTeleport = true; transport(rider, new Location(horse.getWorld(), 8.5, 64, 8.5)); } },
            new Runnable() { public void run() {
                require(folia() ? "mounted-folia-teleport-api-preserves-ride" : "cancelled-teleport-keeps-ride", rider.isInsideVehicle() && countSeats() == 1);
                cancelTeleport = false;
                // Folia's public teleportAsync rejects an entity that is a
                // passenger before firing teleport events (native flags=3).
                // Test an accepted public API teleport after a normal dismount.
                if (folia()) rider.leaveVehicle();
                transport(rider, new Location(horse.getWorld(), 8.5, 64, 8.5));
            } },
            new Runnable() { public void run() { require("passenger-teleport-cleans-seat", !rider.isInsideVehicle() && countSeats() == 0); interact(rider); } },
            new Runnable() { public void run() { require("retry-after-teleport", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); horse.remove(); } },
            new Runnable() { public void run() { require("horse-removal-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle()); horse = newHorse(); mount(horse, driver); interact(rider); } },
            new Runnable() { public void run() { require("new-horse-independent-identity", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); horse.setHealth(0); } },
            new Runnable() { public void run() { require("horse-death-cleans-seat", countSeats() == 0 && !rider.isInsideVehicle()); horse = newHorse(); mount(horse, driver); interact(rider); } },
            new Runnable() { public void run() { require("ride-before-real-disconnect", rider.isInsideVehicle() && countSeats() == 1); trackSeat(); state("disconnect", "rider"); } }
        );
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
    private void finish() {
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
                    Object unbound;
                    try { unbound = protocols.getField(flow + "_TEMPLATE").get(null); }
                    catch (NoSuchFieldException earlier) { unbound = protocols.getField(flow).get(null); }
                    try {
                        Object details = Probe.call(unbound, "details");
                        Method visit = Probe.named(details.getClass(), "listPackets");
                        Class<?> visitorType = visit.getParameterTypes()[0];
                        Object visitor = Proxy.newProxyInstance(visitorType.getClassLoader(), new Class<?>[] {visitorType}, new InvocationHandler() {
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                if (method.getName().equals("accept")) ids.setProperty(prefix + Probe.call(args[0], "id"), args[1].toString());
                                return null;
                            }
                        });
                        Probe.call(visit, details, visitor);
                    } catch (NoSuchMethodException earlier) {
                        Object info = unbound;
                        try { info = Probe.call(unbound, "bind", new java.util.function.Function<Object, Object>() {
                            public Object apply(Object value) { return value; }
                        }); } catch (NoSuchMethodException alreadyBound) { }
                        Object codec = Probe.call(info, "codec");
                        for (Field f : Probe.fields(codec.getClass())) if (Map.class.isAssignableFrom(f.getType()))
                            for (Map.Entry<?, ?> entry : ((Map<?, ?>)Probe.get(f, codec)).entrySet())
                                if (entry.getValue() instanceof Number) ids.setProperty(prefix + Probe.call(entry.getKey(), "id"), entry.getValue().toString());
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
