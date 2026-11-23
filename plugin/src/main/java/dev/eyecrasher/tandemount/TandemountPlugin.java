/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.yaml.snakeyaml.error.YAMLException;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class TandemountPlugin extends JavaPlugin implements Listener, TabCompleter {
    volatile Settings settings;
    NativeAccess nativeAccess;
    OwnerScheduler scheduler;
    PacketBridge packets;
    private volatile boolean stopping, packetHealthy = true;
    private final ConcurrentMap<UUID, Ride> horses = new ConcurrentHashMap<UUID, Ride>();
    private final ConcurrentMap<UUID, Ride> riders = new ConcurrentHashMap<UUID, Ride>();
    private final ConcurrentMap<UUID, PlayerInteractEntityEvent> pending = new ConcurrentHashMap<UUID, PlayerInteractEntityEvent>();
    private final AtomicBoolean packetErrorReported = new AtomicBoolean();
    private final Object settingsLock = new Object();
    private YamlConfiguration configuration;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            reloadSettings();
            scheduler = new OwnerScheduler(this);
            nativeAccess = new NativeAccess(getServer());
            packets = new PacketBridge(this);
            getServer().getPluginManager().registerEvents(this, this);
            registerPreciseInteractionEvents();
            PluginCommand command = getCommand("tandemount");
            if (command == null) throw new IllegalStateException("Missing /tandemount command declaration");
            command.setExecutor(this); command.setTabCompleter(this);
            for (final Player player : onlinePlayers()) scheduler.next(player, new Runnable() {
                public void run() { if (player.isOnline()) install(player); }
            }, null);
            getLogger().info("Tandemount 1.0.0 enabled; ordinary adult tamed saddled horses, server only.");
        } catch (Exception failure) {
            getLogger().severe("Tandemount could not initialize: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            debug("Initialization details", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerPreciseInteractionEvents() throws ReflectiveOperationException {
        Class<? extends PlayerInteractEntityEvent> precise = separatePreciseInteractionType();
        if (precise == null) return;
        PluginManager manager = getServer().getPluginManager();
        manager.registerEvent(precise, this, EventPriority.HIGHEST, (listener, event) -> {
            if (precise.isInstance(event)) interactSeat(precise.cast(event));
        }, this, true);
        manager.registerEvent(precise, this, EventPriority.MONITOR, (listener, event) -> {
            if (precise.isInstance(event)) interact(precise.cast(event));
        }, this, true);
    }

    private Class<? extends PlayerInteractEntityEvent> separatePreciseInteractionType() throws ReflectiveOperationException {
        // 26.1 sends only the precise packet. Earlier clients can follow it
        // with an ordinary interaction, whose cancellation controls mounting.
        if (!"26.1".equals(nativeAccess.minecraft)) return null;
        Class<?> type;
        try { type = Class.forName("org.bukkit.event.player.PlayerInteractAtEntityEvent"); }
        catch (ClassNotFoundException absent) { return null; }
        if (type.getMethod("getHandlerList").invoke(null) == PlayerInteractEntityEvent.getHandlerList()) return null;
        return type.asSubclass(PlayerInteractEntityEvent.class);
    }

    @Override public void onDisable() {
        stopping = true;
        // The native seat tick also sees stopping/isEnabled. On Folia it removes
        // itself on its owning region even when this callback runs globally.
        for (Ride ride : horses.values()) {
            ride.closing = true;
            if (scheduler != null && scheduler.owns(ride.seat)) close(ride);
        }
        if (packets != null) packets.close();
        pending.clear();
    }

    private void reloadSettings() throws IOException {
        ConfigYaml yaml = new ConfigYaml();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (InvalidConfigurationException | YAMLException invalid) {
            throw new IllegalArgumentException("config.yml contains invalid YAML");
        }
        applySettings(yaml, yaml.loadedSettings);
    }

    // Publish alongside ride registration without holding the region during
    // file I/O. The owning entity tick performs cleanup, including on Folia.
    private void applySettings(YamlConfiguration yaml, Settings next) {
        synchronized (horses) {
            for (Ride ride : horses.values()) if (!next.allows(ride.worldName)) ride.closing = true;
            configuration = yaml;
            settings = next;
        }
    }

    @SuppressWarnings("unchecked") private Collection<Player> onlinePlayers() throws ReflectiveOperationException {
        Object result = Reflect.call(getServer(), "getOnlinePlayers");
        return result instanceof Player[] ? Arrays.asList((Player[])result) : (Collection<Player>)result;
    }

    private void install(Player player) {
        try { packets.install(player); }
        catch (ReflectiveOperationException e) { packetFailure(e); }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void joined(PlayerJoinEvent event) { install(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Ride ride = riders.get(id);
        if (ride != null && (ride.rider == player || ride.driver == player)) ride.closing = true;
        pending.computeIfPresent(id, (key, request) -> request.getPlayer() == player ? null : request);
        packets.uninstall(player);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void died(EntityDeathEvent event) {
        Ride ride = horses.get(event.getEntity().getUniqueId());
        if (ride == null) ride = riders.get(event.getEntity().getUniqueId());
        if (ride != null) ride.closing = true;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void exited(org.bukkit.event.vehicle.VehicleExitEvent event) {
        Ride ride = riders.get(event.getExited().getUniqueId());
        if (ride == null || ride.closed) return;
        // Keep the event reference until dispatch completes, including later
        // MONITOR listeners, before applying its final cancellation state.
        if (event.getExited() == ride.driver && event.getVehicle() == ride.horse) ride.driverExit = event;
        else if (event.getExited() == ride.rider && event.getVehicle() == ride.seat) ride.riderExit = event;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(final PlayerTeleportEvent event) {
        final Ride ride = riders.get(event.getPlayer().getUniqueId());
        if (ride == null) return;
        final org.bukkit.event.vehicle.VehicleExitEvent exit = event.getPlayer() == ride.driver ? ride.driverExit : ride.riderExit;
        final Entity vehicle = event.getPlayer() == ride.driver ? ride.horse : ride.seat;
        // Spigot 1.12 also positions a living rider after a cancelled exit.
        // Its falling-block branch emits an UNKNOWN same-world teleport while
        // the original list still contains the half-detached passenger.
        final boolean cancelledExitCorrection = exit != null && exit.isCancelled()
                && event.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN && event.getTo() != null
                && event.getFrom().getWorld().equals(event.getTo().getWorld())
                && event.getPlayer().getVehicle() == null && nativeAccess.passengers(vehicle).contains(event.getPlayer());
        scheduler.next(ride.horse, new Runnable() { public void run() {
            // Older native player mounting emits a same-location UNKNOWN
            // teleport for its client correction. It does not end the ride.
            // Inspect the final event destination after all listeners ran.
            if (!event.isCancelled() && !ride.closed && !event.getFrom().equals(event.getTo())
                    && !(cancelledExitCorrection && exit.isCancelled() && event.getTo() != null
                        && event.getFrom().getWorld().equals(event.getTo().getWorld()))) close(ride);
        }}, () -> ride.closing = true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void unloading(ChunkUnloadEvent event) {
        // The save entry points return false even if a later listener cancels
        // unloading; no helper can ever enter a world save.
        for (Entity entity : event.getChunk().getEntities()) {
            Ride ride = horses.get(entity.getUniqueId());
            if (ride != null) { ride.closing = true; close(ride); }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void damage(EntityDamageEvent event) {
        if (isSeat(event.getEntity())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void interactSeat(PlayerInteractEntityEvent event) {
        if (isSeat(event.getRightClicked())) event.setCancelled(true);
    }
    private boolean isSeat(Entity entity) {
        try { return NativeSeat.RIDES.containsKey(Reflect.handle(entity)); }
        catch (ReflectiveOperationException e) { return false; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interact(final PlayerInteractEntityEvent event) {
        Settings active = settings;
        if (stopping || active == null || !packetHealthy || !(event.getRightClicked() instanceof Horse)) return;
        final Player player = event.getPlayer();
        final Horse horse = (Horse)event.getRightClicked();
        final World world = horse.getWorld();
        if (!active.allows(world.getName()) || !eligible(horse) || player.isInsideVehicle()) return;
        List<Entity> passengers = nativeAccess.passengers(horse);
        if (passengers.size() != 1 || !(passengers.get(0) instanceof Player)) return;
        final Player driver = (Player)passengers.get(0);
        final UUID id = player.getUniqueId();
        if (pending.putIfAbsent(id, event) != null) return;
        // The event object is checked after the COMPLETE synchronous dispatch,
        // including later MONITOR listeners. Cancelled interactions never mount.
        Runnable release = () -> pending.remove(id, event);
        try {
            scheduler.next(horse, () -> {
                try {
                    if (pending.get(id) == event && !event.isCancelled() && !stopping && isEnabled() && packetHealthy
                            && scheduler.owns(player) && horse.getWorld() == world) join(horse, driver, player);
                } finally { release.run(); }
            }, release);
        } catch (RuntimeException failure) {
            release.run();
            throw failure;
        }
    }

    private boolean eligible(Horse horse) {
        if (!horse.isValid() || horse.isDead() || !horse.isAdult() || !horse.isTamed()) return false;
        // Pre-1.11 Horse also represents donkeys/mules. They are not silently enabled.
        try { if (!"HORSE".equals(String.valueOf(Reflect.call(horse, "getVariant")))) return false; }
        catch (NoSuchMethodException modern) { /* Horse now means the ordinary species. */ }
        catch (ReflectiveOperationException failure) { return false; }
        ItemStack saddle = horse.getInventory().getSaddle();
        return saddle != null && saddle.getType() == Material.SADDLE;
    }

    private void join(Horse horse, Player driver, Player player) {
        if (!settings.allows(horse.getWorld().getName()) || !player.isOnline() || player.isDead()
                || player.isInsideVehicle() || !eligible(horse)
                || !player.getWorld().equals(horse.getWorld()) || player.getLocation().distanceSquared(horse.getLocation()) > 36.0
                || horses.containsKey(horse.getUniqueId()) || riders.containsKey(player.getUniqueId())) return;
        List<Entity> passengers = nativeAccess.passengers(horse);
        if (passengers.size() != 1 || passengers.get(0) != driver || !scheduler.owns(driver)
                || !driver.isOnline() || riders.containsKey(driver.getUniqueId())) return;
        Ride ride = null;
        Entity seat = null;
        try {
            if (!nativeAccess.canSee(player, horse) || !nativeAccess.canSee(player, driver)
                    || !packets.installed(player) || !packets.installed(driver)) return;
            seat = nativeAccess.createSeat(horse);
            ride = new Ride(this, horse, driver, player, seat, Reflect.handle(seat));
            if (!registerRide(ride)) { seat.remove(); return; }
            riders.put(driver.getUniqueId(), ride); riders.put(player.getUniqueId(), ride);
            NativeSeat.RIDES.put(ride.nativeSeat, ride);
            packets.addSeat(seat.getEntityId(), nativeAccess.legacySeatType);
            if (nativeAccess.attachment != null) {
                nativeAccess.attachment.create(ride);
                nativeAccess.attachment.mount(ride);
            } else {
                nativeAccess.addSeat(horse, seat);
                if (!nativeAccess.mount(seat, player) || player.getVehicle() != seat) { close(ride); return; }
                nativeAccess.place(seat);
                ride.passengerOffset = player.getLocation().getY() - seat.getLocation().getY();
                nativeAccess.place(seat);
                nativeAccess.broadcastSeatPose(seat);
            }
            follow(ride);
            debug("Started a shared ride", null);
        } catch (Throwable failure) {
            if (ride != null) close(ride); else if (seat != null) seat.remove();
            getLogger().warning("Could not create a shared ride: " + failure.getClass().getSimpleName());
            debug("Ride creation details", failure);
        }
    }

    private boolean registerRide(Ride ride) {
        UUID horseId = ride.horse.getUniqueId();
        synchronized (horses) {
            return settings.allows(ride.worldName) && horses.putIfAbsent(horseId, ride) == null;
        }
    }

    private void follow(final Ride ride) {
        scheduler.next(ride.horse, new Runnable() {
            public void run() {
                if (ride.closed) return;
                tickRide(ride);
                if (!ride.closed) follow(ride);
            }
        }, () -> ride.closing = true);
    }

    void tickNativeSeat(Ride ride) {
        if (stopping || ride.closing) {
            close(ride);
            return;
        }
        if (!nativeAccess.oldest || ride.closed) return;
        // The detached seat ticks after the horse has moved on 1.7.10.
        // Refresh here so its geometry uses that tick's native coordinates.
        try {
            nativeAccess.place(ride.seat);
            nativeAccess.broadcastSeatPose(ride.seat);
        } catch (ReflectiveOperationException failure) {
            debug("A legacy seat could not follow its horse", failure);
            close(ride);
        }
    }

    private void tickRide(Ride ride) {
        if (ride.closed) return;
        try {
            org.bukkit.event.vehicle.VehicleExitEvent driverExit = ride.driverExit, riderExit = ride.riderExit;
            ride.driverExit = null; ride.riderExit = null;
            if (driverExit != null && driverExit.isCancelled()) nativeAccess.preserveCancelledExit(ride.horse, ride.driver);
            if (riderExit != null && riderExit.isCancelled()) nativeAccess.preserveCancelledExit(ride.seat, ride.rider);
            if (stopping || !isEnabled() || ride.closing || !ride.horse.isValid()
                    || !settings.allows(ride.horse.getWorld().getName())
                    || ride.horse.isDead() || !ride.seat.isValid() || !ride.driver.isOnline() || !ride.rider.isOnline()
                    || ride.driver.isDead() || ride.rider.isDead() || ride.driver.getVehicle() != ride.horse
                    || ride.rider.getVehicle() != ride.seat || !ride.horse.getWorld().equals(ride.rider.getWorld())
                    || (nativeAccess.attachment != null && !nativeAccess.attachment.valid(ride))) { close(ride); return; }
            if (nativeAccess.attachment != null) nativeAccess.attachment.place(ride);
            else if (!nativeAccess.oldest) {
                nativeAccess.place(ride.seat);
                nativeAccess.broadcastSeatPose(ride.seat);
            }
        } catch (Throwable failure) {
            debug("A shared ride ended because native validation failed", failure);
            close(ride);
        }
    }

    boolean portalTeleport(Ride ride, Object anchor, Object transition) {
        try {
            Object riderTransition = nativeAccess.attachment == null || scheduler.folia ? null
                    : nativeAccess.attachment.portalTransition(ride, anchor, transition);
            // No helper enters the target world or becomes a vanilla replacement.
            // Let the rider's own native teleport fire its ordinary cancellable
            // PlayerTeleportEvent; a cancellation never forces a later retry.
            close(ride);
            Object rider = Reflect.handle(ride.rider);
            if (riderTransition != null && Reflect.call(rider, "teleport", riderTransition) != null)
                Reflect.call(rider, "setPortalCooldown");
        } catch (Throwable failure) {
            close(ride);
            debug("A shared portal ride ended because native transfer failed", failure);
        }
        return true;
    }

    private void close(Ride ride) {
        if (ride.closed) return;
        ride.closed = true;
        try {
            for (int i = ride.helpers.length - 1; i >= 0; i--)
                if (ride.helpers[i].isValid()) ride.helpers[i].remove();
        }
        catch (RuntimeException failure) { debug("Native seat removal details", failure); }
        finally {
            horses.remove(ride.horse.getUniqueId(), ride);
            riders.remove(ride.driver.getUniqueId(), ride); riders.remove(ride.rider.getUniqueId(), ride);
            NativeSeat.RIDES.remove(ride.nativeSeat, ride);
            for (Entity helper : ride.helpers) try {
                NativeSeat.RIDES.remove(Reflect.handle(helper), ride);
                if (packets != null) packets.removeHelper(helper.getEntityId());
            }
            catch (ReflectiveOperationException failure) { debug("Native attachment retirement details", failure); }
            if (packets != null) {
                packets.removeSeat(ride.seat.getEntityId());
            }
        }
    }

    void packetFailure(Throwable failure) {
        packetHealthy = false;
        for (Ride ride : horses.values()) ride.closing = true;
        if (packetErrorReported.compareAndSet(false, true)) {
            getLogger().severe("Seat network adapter failed; shared riding is stopped to preserve client state.");
            debug("Network adapter details", failure);
        }
    }
    void debug(String message, Throwable failure) {
        if (settings != null && settings.debug) {
            if (failure == null) getLogger().info(message);
            else getLogger().log(Level.WARNING, message, failure);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        if (!sender.hasPermission("tandemount.admin")) { sender.sendMessage("You need tandemount.admin to change server settings."); return true; }
        synchronized (settingsLock) {
            try {
                String action = args[0].toLowerCase(Locale.ROOT);
                if (action.equals("status")) { status(sender); return true; }
                if (action.equals("reload") && args.length == 1) {
                    reloadSettings();
                    status(sender);
                    return true;
                }
                Settings next;
                if (action.equals("reset") && args.length == 1)
                    next = new Settings(settings.enabled, Settings.DEFAULT_VERTICAL, Settings.DEFAULT_REARWARD,
                            settings.debug, settings.excludedWorlds);
                else if ((action.equals("enable") || action.equals("disable")) && args.length == 1)
                    next = new Settings(action.equals("enable"), settings.vertical, settings.rearward,
                            settings.debug, settings.excludedWorlds);
                else if (action.equals("debug") && args.length == 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off")))
                    next = new Settings(settings.enabled, settings.vertical, settings.rearward,
                            args[1].equalsIgnoreCase("on"), settings.excludedWorlds);
                else if ((action.equals("vertical") || action.equals("rearward")) && args.length == 2) {
                    double current = action.equals("vertical") ? settings.vertical : settings.rearward;
                    double value = args[1].equals("+") ? current + Settings.STEP
                            : args[1].equals("-") ? current - Settings.STEP : Double.parseDouble(args[1]);
                    next = settings.coordinate(action, value);
                } else { sender.sendMessage("Usage: /tandemount [help|status|vertical <+|-|number>|rearward <+|-|number>|enable|disable|reload|reset|debug <on|off>]"); return true; }
                YamlConfiguration nextYaml = new YamlConfiguration();
                nextYaml.loadFromString(configuration.saveToString());
                save(nextYaml, next);
                applySettings(nextYaml, next);
                status(sender);
            } catch (IllegalArgumentException invalid) { sender.sendMessage("Setting rejected: " + invalid.getMessage()); }
            catch (Exception failure) { sender.sendMessage("Could not save or reload settings; existing ride settings remain active."); debug("Configuration details", failure); }
        }
        return true;
    }

    private void save(YamlConfiguration yaml, Settings next) throws IOException {
        next.write(yaml);
        Path destination = new File(getDataFolder(), "config.yml").toPath();
        Path temporary = Files.createTempFile(getDataFolder().toPath(), "config-", ".tmp");
        try {
            yaml.save(temporary.toFile());
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private void status(CommandSender sender) {
        Settings active = settings;
        sender.sendMessage(ChatColor.GOLD + "Tandemount " + ChatColor.DARK_GRAY + "| "
                + (active.enabled ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Height: " + ChatColor.WHITE + active.vertical
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Back: " + ChatColor.WHITE + active.rearward);
        sender.sendMessage(ChatColor.GRAY + "Horse types: " + ChatColor.WHITE + "HORSE" + ChatColor.DARK_GRAY + " | "
                + ChatColor.GRAY + "Debug: " + ChatColor.WHITE + (active.debug ? "on" : "off"));
        sender.sendMessage(ChatColor.GRAY + "Excluded worlds: " + ChatColor.WHITE
                + (active.excludedWorlds.isEmpty() ? "(none)" : String.join(", ", active.excludedWorlds)));
    }
    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD.toString() + ChatColor.BOLD + "Tandemount" + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Share the ride");
        sender.sendMessage(ChatColor.GRAY + "Right-click an adult, tamed, saddled horse with a driver to join.");
        sender.sendMessage(ChatColor.GRAY + "The driver keeps control. Use normal dismount to leave. Maximum: two riders.");
        if (!sender.hasPermission("tandemount.admin")) return;
        status(sender);
        sender.sendMessage(ChatColor.YELLOW + "Seat adjustments " + ChatColor.GRAY + "(1/16 block per click)");
        if (sender instanceof Player) clickable((Player)sender);
        sender.sendMessage(ChatColor.AQUA + "/tandemount vertical <+|-|number> " + ChatColor.GRAY + "(0.25–3.0)");
        sender.sendMessage(ChatColor.AQUA + "/tandemount rearward <+|-|number> " + ChatColor.GRAY + "(0.0–2.0)");
        sender.sendMessage(ChatColor.GRAY + "/tandemount enable | disable | reload | reset | debug <on|off>");
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private void clickable(Player player) {
        try {
            Class<?> text = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> base = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> click = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
            Class<?> color = Class.forName("net.md_5.bungee.api.ChatColor");
            Class<? extends Enum> action = (Class<? extends Enum>)Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action");
            Object run = Enum.valueOf(action, "RUN_COMMAND");
            Object array = Array.newInstance(base, 4);
            String[] captions = {"[Higher] ", "[Lower] ", "[Further back] ", "[Further forward]"};
            String[] commands = {"vertical +", "vertical -", "rearward +", "rearward -"};
            for (int i = 0; i < 4; i++) {
                Object component = text.getConstructor(String.class).newInstance(captions[i]);
                Reflect.call(component, "setColor", color.getField(i < 2 ? "AQUA" : "GREEN").get(null));
                Reflect.call(component, "setBold", Boolean.TRUE);
                Reflect.call(component, "setClickEvent", click.getConstructor(action, String.class).newInstance(run, "/tandemount " + commands[i]));
                Array.set(array, i, component);
            }
            Object spigot = Reflect.call(player, "spigot");
            Reflect.call(spigot, "sendMessage", array);
        } catch (ReflectiveOperationException unavailable) { /* Text commands above are equivalent. */ }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        String[] options = sender.hasPermission("tandemount.admin")
                ? new String[] {"help", "status", "vertical", "rearward", "enable", "disable", "reload", "reset", "debug"}
                : new String[] {"help"};
        for (String option : options) if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(option);
        return result;
    }
}
