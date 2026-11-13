/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy;

import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/** Server-owned rides; game objects never leave their owning server thread. */
public final class LegacyRuntime {
    private static LegacyRuntime instance;
    private static final Set<Object> SEATS = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    private static final Set<Object> LEGACY_DISMOUNTS = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    private final Map<Object, Ride> rides = new IdentityHashMap<Object, Ride>();
    private final Object server;
    private final Path configPath;
    private SeatConfig config;
    private final boolean multi = Names.minecraft().equals("1.12.2");
    private final boolean oldest = Names.minecraft().equals("1.7.10");
    private static final class Ride {
        final Object horse, driver, passenger, seat, world;
        Ride(Object h, Object d, Object p, Object s, Object w) { horse=h; driver=d; passenger=p; seat=s; world=w; }
    }
    private LegacyRuntime(Object server, File configDirectory) throws Exception {
        this.server = server;
        configPath = new File(configDirectory, "tandemount.properties").toPath();
        config = SeatConfig.load(configPath);
        if (!configPath.toFile().exists()) config.save(configPath);
    }
    public static void start(Object event, File configDirectory) {
        try {
            Object server = Reflect.call(event, "getServer");
            LegacyRuntime runtime = new LegacyRuntime(server, configDirectory);
            instance = runtime;
            Class<?> commandInterface = Reflect.type("net.minecraft.command.ICommand");
            Object command = Proxy.newProxyInstance(commandInterface.getClassLoader(), new Class<?>[] {commandInterface}, runtime.new Command());
            Reflect.call(event, "registerServerCommand", command);
            runtime.registerPermission();
            log("Ready for Minecraft " + Names.minecraft() + ". Detached native seats require client visual validation.");
        } catch (Exception ex) { instance = null; throw new IllegalStateException("Tandemount could not initialize safely", ex); }
    }
    private void registerPermission() {
        if (!multi) return;
        Class<?> defaults = Reflect.type("net.minecraftforge.server.permission.DefaultPermissionLevel");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object op = Enum.valueOf((Class) defaults, "OP");
        Reflect.call(Reflect.type("net.minecraftforge.server.permission.PermissionAPI"), "registerNode", "tandemount.admin", op, "Manage Tandemount server settings");
    }
    public static boolean isSeat(Object entity) { return entity != null && SEATS.contains(entity); }
    public static boolean collision(Object a, Object b) {
        if (isSeat(a) || isSeat(b)) return true;
        LegacyRuntime runtime=instance;
        if (runtime != null) for (Ride ride : runtime.rides.values())
            if ((a==ride.horse || a==ride.driver || a==ride.passenger) && (b==ride.horse || b==ride.driver || b==ride.passenger)) return true;
        return false;
    }
    public static void stop() {
        LegacyRuntime runtime = instance;
        if (runtime != null) { for (Ride r : new ArrayList<Ride>(runtime.rides.values())) runtime.remove(r); }
        instance = null; SEATS.clear(); LEGACY_DISMOUNTS.clear();
    }
    /** Correct the 1.8.9 outer mount(null) path, which otherwise ignores its nested dismount cancellation. */
    public static boolean legacyMountPermission(Object entity, Object target, boolean mounting) {
        LegacyRuntime runtime=instance;
        if (runtime != null && Names.minecraft().equals("1.8.9")) {
            if (!mounting && LEGACY_DISMOUNTS.remove(entity)) return true;
            if (mounting && target == null) {
                Object current=runtime.vehicle(entity);
                if (isSeat(current)) {
                    boolean allowed=Boolean.TRUE.equals(Reflect.call(Reflect.type("net.minecraftforge.event.ForgeEventFactory"),"canMountEntity",entity,current,false));
                    if (allowed) LEGACY_DISMOUNTS.add(entity);
                    return allowed;
                }
            }
        }
        return Boolean.TRUE.equals(Reflect.call(Reflect.type("net.minecraftforge.event.ForgeEventFactory"),"canMountEntity",entity,target,mounting));
    }
    public static void finishLegacyMount(Object entity) { LEGACY_DISMOUNTS.remove(entity); }
    public static void entityTeleport(Object entity) {
        LegacyRuntime runtime=instance;
        if (runtime == null) return;
        for (Ride ride : new ArrayList<Ride>(runtime.rides.values()))
            if (entity==ride.horse || entity==ride.driver || entity==ride.passenger) runtime.remove(ride);
    }
    public static void entityWorldChange(Object entity,Object targetWorld) {
        if (instance != null && Reflect.get(entity,"world|worldObj") != targetWorld) entityTeleport(entity);
    }
    /** Forge 1.7.10 has no server vanilla-handshake completion path. Use vanilla login only
     * when FML itself confirms that every loaded network mod accepts an unmodified client. */
    public static boolean legacyVanillaLogin(Object players, Object network, Object player) {
        if (instance == null || !Names.minecraft().equals("1.7.10")) return false;
        Class<?> side=Reflect.type("cpw.mods.fml.relauncher.Side");
        @SuppressWarnings({"unchecked","rawtypes"}) Object client=Enum.valueOf((Class)side,"CLIENT");
        Object registry=Reflect.enumValue("cpw.mods.fml.common.network.NetworkRegistry","INSTANCE");
        if (!Boolean.TRUE.equals(Reflect.call(registry,"isVanillaAccepted",client))) return false;
        Object connection=Reflect.create("net.minecraft.network.NetHandlerPlayServer",instance.server,network,player);
        Reflect.call(players,"initializeConnectionToPlayer",network,player,connection);
        Object common=Reflect.call(Reflect.type("cpw.mods.fml.common.FMLCommonHandler"),"instance");
        Object event=Reflect.create("cpw.mods.fml.common.network.FMLNetworkEvent$ServerConnectionFromClientEvent",network);
        Reflect.call(Reflect.call(common,"bus"),"post",event);
        return true;
    }
    /** Called inside the horse interaction after Forge has completed its cancellable event. */
    public static boolean interact(Object horse, Object player) {
        LegacyRuntime runtime = instance;
        if (runtime == null) return false;
        try { return runtime.join(horse, player); }
        catch (RuntimeException ex) {
            log("A ride was refused after a native API error: " + ex.getClass().getSimpleName());
            if (runtime.config.debug) ex.printStackTrace();
            return true;
        }
    }
    private boolean join(Object horse, Object player) {
        if (!config.enabled || Reflect.bool(Reflect.get(horse, "world|worldObj"), "isRemote")) return false;
        if (!horse.getClass().getName().equals("net.minecraft.entity.passive.EntityHorse")) return false;
        Object driver = first(horse);
        if (driver == null || !Reflect.type("net.minecraft.entity.player.EntityPlayerMP").isInstance(driver)) return false;
        if (driver == player) return false;
        if (!Boolean.TRUE.equals(Reflect.call(horse, "isTame|isHorseTamed")) || Boolean.TRUE.equals(Reflect.call(horse, "isChild"))
                || !Boolean.TRUE.equals(Reflect.call(horse, "isHorseSaddled"))) return false;
        if (!multi && ((Number) Reflect.call(horse, "getHorseType")).intValue() != 0) return false;
        if (rides.containsKey(horse)) return true; // Never replace or disturb an existing ride.
        if (vehicle(player) != null || Boolean.TRUE.equals(Reflect.call(player, "isSneaking"))) return false;
        Object world = Reflect.get(horse, "world|worldObj");
        if (Reflect.get(player, "world|worldObj") != world || dead(player) || dead(driver) || dead(horse)) return true;
        double dx = Reflect.number(horse,"posX") - Reflect.number(player,"posX");
        double dy = Reflect.number(horse,"posY") - Reflect.number(player,"posY");
        double dz = Reflect.number(horse,"posZ") - Reflect.number(player,"posZ");
        if (dx*dx+dy*dy+dz*dz > 36.0 || !Boolean.TRUE.equals(Reflect.call(player,"canEntityBeSeen",horse))) return true;
        // All three vanilla clients treat a living vehicle as the passenger's body-yaw
        // authority. A non-living falling block whose state is air keeps the player's
        // body/head yaw and has no visible block model on an unmodified client.
        Object air = Reflect.get(Reflect.type("net.minecraft.init.Blocks"), multi ? "AIR" : "air");
        double sx = Reflect.number(horse,"posX"), sy = Reflect.number(horse,"posY"), sz = Reflect.number(horse,"posZ");
        Object seat = oldest
                ? Reflect.create("net.minecraft.entity.item.EntityFallingBlock", world, sx, sy, sz, air)
                : Reflect.create("net.minecraft.entity.item.EntityFallingBlock", world, sx, sy, sz, Reflect.call(air,"getDefaultState"));
        SEATS.add(seat);
        Ride ride = new Ride(horse, driver, player, seat, world);
        try {
            Reflect.call(seat, "setInvisible", true);
            Reflect.maybe(seat, "setEntityInvulnerable", true);
            Reflect.set(seat, "invulnerable", true);
            Reflect.maybe(seat, "setSilent", true);
            Reflect.maybe(seat, "setNoAI", true);
            Reflect.maybe(seat, "setNoGravity", true);
            Reflect.set(seat, "noClip", true);
            Reflect.call(seat, "setSize", 0.0F, 0.0F);
            pose(ride);
            if (!Boolean.TRUE.equals(Reflect.call(world, "spawnEntity|spawnEntityInWorld", seat))) { discard(seat); return true; }
            if (multi) Reflect.call(player, "startRiding", seat, false);
            else Reflect.call(player, "mountEntity", seat);
            if (vehicle(player) != seat) { discard(seat); return true; } // Native mount cancellation wins.
            rides.put(horse, ride);
            pose(ride);
            if (config.debug) log("A native seat was occupied; active rides=" + rides.size());
            return true;
        } catch (RuntimeException ex) { discard(seat); throw ex; }
    }
    private Object vehicle(Object entity) {
        return multi ? Reflect.call(entity, "getRidingEntity") : Reflect.get(entity, "ridingEntity");
    }
    private Object first(Object entity) {
        if (!multi) return Reflect.get(entity, "riddenByEntity");
        List<?> passengers = (List<?>) Reflect.call(entity, "getPassengers");
        return passengers.isEmpty() ? null : passengers.get(0);
    }
    private boolean dead(Object entity) {
        if (Reflect.bool(entity, "isDead")) return true;
        Object health = Reflect.maybe(entity, "getHealth");
        return health instanceof Number && ((Number)health).doubleValue() <= 0;
    }
    public static void worldTick(Object world) {
        LegacyRuntime runtime = instance;
        if (runtime == null || Reflect.bool(world,"isRemote")) return;
        for (Ride ride : new ArrayList<Ride>(runtime.rides.values())) {
            if (ride.world != world) continue;
            try {
                if (!runtime.valid(ride)) runtime.remove(ride);
                else runtime.pose(ride);
            } catch (RuntimeException ex) { runtime.remove(ride); log("Removed a ride after a native lifecycle error."); }
        }
    }
    private boolean valid(Ride r) {
        if (!config.enabled) return invalid("disabled");
        for (Object e : new Object[] {r.horse,r.driver,r.passenger,r.seat}) {
            if (dead(e)) return invalid("dead entity: "+e.getClass().getSimpleName());
            if (Reflect.get(e,"world|worldObj") != r.world) return invalid("world changed");
            if (Reflect.call(r.world,"getEntityByID",Reflect.call(e,"getEntityId")) != e) return invalid("entity no longer present: "+e.getClass().getSimpleName());
        }
        return (vehicle(r.driver) == r.horse && first(r.horse) == r.driver && vehicle(r.passenger) == r.seat && first(r.seat) == r.passenger) || invalid("native rider relationship changed");
    }
    private boolean invalid(String reason) {
        if (config.debug) log("Ride ended: "+reason);
        return false;
    }
    private void pose(Ride r) {
        double yaw = Math.toRadians(Reflect.number(r.horse,"rotationYaw"));
        double x = Reflect.number(r.horse,"posX") + Math.sin(yaw)*config.rear;
        double z = Reflect.number(r.horse,"posZ") - Math.cos(yaw)*config.rear;
        double y = Reflect.number(r.horse,"posY") + ((Number)Reflect.call(r.horse,"getMountedYOffset")).doubleValue()
                + config.vertical - ((Number)Reflect.call(r.seat,"getMountedYOffset")).doubleValue();
        Object provider=Reflect.call(r.world,"getChunkProvider");
        int cx=((int)Math.floor(x))>>4, cz=((int)Math.floor(z))>>4;
        boolean loaded=multi ? Reflect.call(provider,"getLoadedChunk",cx,cz)!=null : Boolean.TRUE.equals(Reflect.call(provider,"chunkExists",cx,cz));
        if (!loaded) throw new IllegalStateException("The rear seat would enter an unloaded chunk");
        Reflect.call(r.seat,"setPositionAndRotation",x,y,z,(float)Math.toDegrees(yaw),0.0F);
        Reflect.set(r.seat,"motionX",0.0D); Reflect.set(r.seat,"motionY",0.0D); Reflect.set(r.seat,"motionZ",0.0D);
        if (vehicle(r.passenger) == r.seat) {
            if (multi) Reflect.call(r.seat,"updatePassenger",r.passenger);
            else Reflect.call(r.seat,"updateRiderPosition");
            Reflect.set(r.passenger,"fallDistance",0.0F);
        }
    }
    private void remove(Ride r) {
        rides.remove(r.horse);
        discard(r.seat);
        if (config.debug) log("A native seat was removed; active rides=" + rides.size());
    }
    private void discard(Object seat) {
        Object passenger = first(seat);
        if (passenger != null && vehicle(passenger) == seat) {
            // Expired rides are detached without a native teleport or terrain query. In particular,
            // unloading a chunk must not reload it while native dismount searches for safe ground.
            // Ordinary user dismounts still use the full cancellable native path before this cleanup.
            Reflect.set(passenger,"ridingEntity",null);
            if (multi) Reflect.call(seat,"removePassenger",passenger);
            else Reflect.set(seat,"riddenByEntity",null);
        }
        Reflect.call(seat,"setDead");
        SEATS.remove(seat);
    }
    private boolean admin(Object sender) {
        if (multi && Reflect.type("net.minecraft.entity.player.EntityPlayer").isInstance(sender)) {
            return Boolean.TRUE.equals(Reflect.call(Reflect.type("net.minecraftforge.server.permission.PermissionAPI"),"hasPermission",sender,"tandemount.admin"));
        }
        return Boolean.TRUE.equals(Reflect.call(sender,"canUseCommand|canCommandSenderUseCommand",2,"tandemount"));
    }
    private static void log(String text) { System.out.println("[Tandemount] " + text); }
    private void message(Object sender,String text,String click) {
        String component = multi ? "net.minecraft.util.text.ITextComponent$Serializer" : "net.minecraft.util.IChatComponent$Serializer";
        String json = "{\"text\":\"" + escape(text) + "\"" + (click == null ? "" : ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\""+escape(click)+"\"}") + "}";
        Object chat = Reflect.call(Reflect.type(component),"jsonToComponent",json);
        Reflect.call(sender,"sendMessage|addChatMessage",chat);
    }
    private static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }
    private void command(Object sender,String[] args) {
        try {
            if (args.length == 0 || args[0].equals("help")) {
                message(sender,"Tandemount: right-click an occupied adult, tamed, saddled horse to join; sneak to leave.",null);
                message(sender,"/tandemount status | vertical <number|+|-> | rear <number|+|-> | enable | disable | reload | reset | types horse | debug <on|off>",null);
                if (admin(sender)) {
                    message(sender,"[Vertical +1/16]","/tandemount vertical +"); message(sender,"[Vertical -1/16]","/tandemount vertical -");
                    message(sender,"[Rear +1/16]","/tandemount rear +"); message(sender,"[Rear -1/16]","/tandemount rear -");
                }
                return;
            }
            if (args[0].equals("status")) {
                message(sender,"Tandemount: enabled="+config.enabled+", vertical="+config.vertical+", rear="+config.rear+", types=horse, rides="+rides.size(),null); return;
            }
            if (!admin(sender)) { message(sender,"Permission denied: tandemount.admin.",null); return; }
            SeatConfig next;
            String key=args[0];
            if (args.length == 1 && (key.equals("enable") || key.equals("disable"))) next=config.change("enabled",Boolean.toString(key.equals("enable")));
            else if (args.length == 1 && key.equals("reload")) next=SeatConfig.load(configPath);
            else if (args.length == 1 && key.equals("reset")) next=config.defaults();
            else if (args.length == 2 && (key.equals("vertical") || key.equals("rear"))) {
                String value=args[1];
                if (value.equals("+") || value.equals("-")) value=Double.toString((key.equals("vertical")?config.vertical:config.rear)+(value.equals("+")?0.0625:-0.0625));
                next=config.change(key,value);
            } else if (args.length == 2 && (key.equals("types") || key.equals("debug"))) next=config.change(key,args[1]);
            else { message(sender,"Invalid command. Use /tandemount help.",null); return; }
            next.save(configPath); config=next;
            for (Ride r : new ArrayList<Ride>(rides.values())) { if (!config.enabled) remove(r); else pose(r); }
            message(sender,"Tandemount settings saved.",null);
        } catch (Exception ex) { message(sender,"Settings unchanged: "+(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()),null); }
    }
    private final class Command implements InvocationHandler {
        @Override public Object invoke(Object proxy,Method method,Object[] args) {
            String n=Names.canonical(method.getName());
            if (n.equals("getCommandName") || n.equals("getName")) return "tandemount";
            if (n.equals("getCommandUsage") || n.equals("getUsage")) return "/tandemount help";
            if (n.equals("getCommandAliases") || n.equals("getAliases")) return Collections.emptyList();
            if (n.equals("canCommandSenderUseCommand") || n.equals("checkPermission")) return true;
            if (n.equals("processCommand") || n.equals("execute")) { command(args[args.length-2],(String[])args[args.length-1]); return null; }
            if (n.equals("addTabCompletionOptions") || n.equals("getTabCompletions")) return Collections.emptyList();
            if (n.equals("isUsernameIndex")) return false;
            if (n.equals("compareTo")) return 0;
            if (n.equals("toString")) return "Tandemount command";
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("equals")) return proxy == args[0];
            throw new UnsupportedOperationException("Unknown native command interface method: "+n);
        }
    }
}
