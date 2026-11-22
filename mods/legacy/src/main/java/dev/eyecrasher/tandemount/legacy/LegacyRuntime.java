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
        final Set<Object> synchronizedObservers=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        final Map<Object,Integer> observerSettlement=new IdentityHashMap<Object,Integer>();
        Ride(Object h, Object d, Object p, Object s, Object w) { horse=h; driver=d; passenger=p; seat=s; world=w; }
    }
    private LegacyRuntime(Object server, File configDirectory) throws Exception {
        this.server = server;
        configPath = new File(configDirectory, "tandemount.properties").toPath();
        config = SeatConfig.load(configPath);
        config.save(configPath); // Explicitly record the units of older saved vertical values.
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
            log("Ready for Minecraft " + Names.minecraft() + ". Legacy separate following seats; server settings in config/tandemount.properties.");
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
    /** Use native login only when FML confirms every loaded network mod accepts vanilla.
     * 1.7.10 has no vanilla completion; 1.8.9 has an asynchronous handlerAdded race. */
    public static boolean legacyVanillaLogin(Object players, Object network, Object player) {
        if (instance == null || Names.minecraft().equals("1.12.2")) return false;
        String fml=Names.minecraft().equals("1.7.10")?"cpw.mods.fml":"net.minecraftforge.fml";
        Class<?> registryType=Reflect.type(fml+".common.network.NetworkRegistry");
        Class<?> side=Reflect.type(fml+".relauncher.Side");
        @SuppressWarnings({"unchecked","rawtypes"}) Object client=Enum.valueOf((Class)side,"CLIENT");
        Object registry=Reflect.enumValue(fml+".common.network.NetworkRegistry","INSTANCE");
        if (!Boolean.TRUE.equals(Reflect.call(registry,"isVanillaAccepted",client))) return false;
        if (Names.minecraft().equals("1.8.9")) {
            Object channel=Reflect.call(network,"channel");
            Object marker=Reflect.call(Reflect.call(channel,"attr",Reflect.get(registryType,"FML_MARKER")),"get");
            if (Boolean.TRUE.equals(marker)) return false; // Keep actual Forge clients on their handshake.
        }
        Object connection=Reflect.create("net.minecraft.network.NetHandlerPlayServer",instance.server,network,player);
        Object common=Reflect.call(Reflect.type(fml+".common.FMLCommonHandler"),"instance");
        Object event=Reflect.create(fml+".common.network.FMLNetworkEvent$ServerConnectionFromClientEvent",network);
        if (Names.minecraft().equals("1.8.9")) Reflect.call(Reflect.call(common,"bus"),"post",event);
        Reflect.call(players,"initializeConnectionToPlayer",network,player,connection);
        if (Names.minecraft().equals("1.7.10"))
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
        if (excluded(Reflect.get(horse, "world|worldObj"))) return false;
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
        Object seat = Reflect.create(multi ? "net.minecraft.entity.EntityAreaEffectCloud"
                : oldest ? "net.minecraft.entity.monster.EntitySlime" : "net.minecraft.entity.item.EntityArmorStand", world);
        SEATS.add(seat);
        Ride ride = new Ride(horse, driver, player, seat, world);
        try {
            Reflect.call(seat, "setInvisible", true);
            if (multi) Reflect.call(seat, "setRadius", 0.0F);
            else if (oldest) {
                // This exact old client accepts signed slime size metadata. -1
                // creates an inverted box, including its 0.1 picking border:
                // original vanilla ray tests cannot select it at any seat offset.
                // Zero is insufficient because its generic picking border remains.
                Reflect.call(seat, "setSlimeSize", -1);
            } else {
                Reflect.call(seat, "setMarker", true);
                Reflect.call(seat, "setSize", 0.0F, 0.0F); // The marker's synchronized client dimensions.
            }
            Reflect.maybe(seat, "setEntityInvulnerable", true);
            Reflect.set(seat, "invulnerable", true);
            Reflect.maybe(seat, "setSilent", true);
            Reflect.maybe(seat, "setNoAI", true);
            Reflect.maybe(seat, "setNoGravity", true);
            Reflect.set(seat, "noClip", true);
            pose(ride);
            if (!Boolean.TRUE.equals(Reflect.call(world, "spawnEntity|spawnEntityInWorld", seat))) { discard(seat); return true; }
            if (multi) Reflect.call(player, "startRiding", seat, false);
            else Reflect.call(player, "mountEntity", seat);
            if (vehicle(player) != seat) { discard(seat); return true; } // Native mount cancellation wins.
            rides.put(horse, ride);
            pose(ride);
            if (config.debug) log("A separate seat was occupied; active rides=" + rides.size());
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
        if (excluded(r.world)) return invalid("excluded dimension");
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
    private boolean excluded(Object world) {
        Object provider = Reflect.get(world, "provider");
        Object dimension = multi ? Reflect.call(provider, "getDimension") : Reflect.get(provider, "dimensionId");
        return config.excludedDimensions.contains(((Number) dimension).intValue());
    }
    private void pose(Ride r) {
        double yaw = Math.toRadians(Reflect.number(r.horse,"rotationYaw"));
        double x = Reflect.number(r.horse,"posX") + Math.sin(yaw)*config.rear;
        double z = Reflect.number(r.horse,"posZ") - Math.cos(yaw)*config.rear;
        double passengerFeetOffset = ((Number)Reflect.call(r.passenger,"getYOffset")).doubleValue()
                - (oldest ? Reflect.number(r.passenger,"yOffset") : 0.0D);
        double y = Reflect.number(r.horse,"posY") + config.vertical
                + (config.horseFeet ? -passengerFeetOffset : ((Number)Reflect.call(r.horse,"getMountedYOffset")).doubleValue())
                - ((Number)Reflect.call(r.seat,"getMountedYOffset")).doubleValue();
        Object provider=Reflect.call(r.world,"getChunkProvider");
        int cx=((int)Math.floor(x))>>4, cz=((int)Math.floor(z))>>4;
        boolean loaded=multi ? Reflect.call(provider,"getLoadedChunk",cx,cz)!=null : Boolean.TRUE.equals(Reflect.call(provider,"chunkExists",cx,cz));
        if (!loaded) throw new IllegalStateException("The rear seat would enter an unloaded chunk");
        // Living legacy helpers must follow the passenger's own look, not the
        // horse's heading, because vanilla uses a living vehicle's body yaw.
        float seatYaw=(float)Reflect.number(multi ? r.horse : r.passenger,"rotationYaw");
        Reflect.call(r.seat,"setPositionAndRotation",x,y,z,seatYaw,0.0F);
        if (!multi) {
            Reflect.set(r.seat,"rotationYawHead",seatYaw);
            Reflect.set(r.seat,"renderYawOffset",seatYaw);
        }
        // Preserve the native dimensions and mounted offset used by the client.
        // Only server collision bounds are empty; changing size server-side alone
        // gives vanilla clients a different rider location.
        if (oldest) Reflect.call(Reflect.get(r.seat,"boundingBox"),"setBounds",x,y,z,x,y,z);
        else Reflect.call(r.seat,"setEntityBoundingBox",Reflect.create(multi ? "net.minecraft.util.math.AxisAlignedBB" : "net.minecraft.util.AxisAlignedBB",x,y,z,x,y,z));
        Reflect.set(r.seat,"motionX",0.0D); Reflect.set(r.seat,"motionY",0.0D); Reflect.set(r.seat,"motionZ",0.0D);
        if (vehicle(r.passenger) == r.seat) {
            if (multi) Reflect.call(r.seat,"updatePassenger",r.passenger);
            else Reflect.call(r.seat,"updateRiderPosition");
            Reflect.set(r.passenger,"fallDistance",0.0F);
        }
        Object tracker=Reflect.call(r.world,"getEntityTracker");
        String packets="net.minecraft.network.play.server.";
        Reflect.call(tracker,"sendToTracking|sendToAllTrackingEntity",r.seat,Reflect.create(packets+(multi?"SPacketEntityTeleport":"S18PacketEntityTeleport"),r.seat));
        if (!multi) Reflect.call(tracker,"sendToAllTrackingEntity",r.seat,Reflect.create(packets+"S19PacketEntityHeadLook",r.seat,(byte)Math.floor(seatYaw*256.0/360.0)));
        // 1.7.10 has no synchronized NoGravity. Its original living-entity tick
        // integrates the current velocity before gravity; a native zero-velocity
        // refresh prevents an artificial vertical sawtooth while packets arrive.
        if (oldest) Reflect.call(tracker,"sendToAllTrackingEntity",r.seat,Reflect.create(packets+"S12PacketEntityVelocity",r.seat));
        synchronizeLegacyLinks(r,tracker);
    }
    private void synchronizeLegacyLinks(Ride r,Object tracker) {
        // A player may start tracking the already-mounted rider before its
        // vehicle's chunk/spawn has reached that observer. Send the accepted
        // relationship once all four native trackers already know the observer.
        // Never add a tracker, spawn an entity or override a visibility decision.
        Object table=Reflect.get(tracker,"trackedEntityHashTable");
        Set<Object> observers=null;
        for (Object entity:new Object[]{r.horse,r.driver,r.passenger,r.seat}) {
            Object entry=Reflect.call(table,"lookup",Reflect.call(entity,"getEntityId"));
            Set<Object> known=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            if (entry!=null) known.addAll((Set<?>)Reflect.get(entry,"trackingPlayers"));
            if (Reflect.type("net.minecraft.entity.player.EntityPlayerMP").isInstance(entity)) known.add(entity);
            if (observers==null) observers=known; else observers.retainAll(known);
        }
        if (observers==null) return;
        r.synchronizedObservers.retainAll(observers);
        r.observerSettlement.keySet().retainAll(observers);
        for (Object observer:observers) if (!r.synchronizedObservers.contains(observer)) {
            Integer before=r.observerSettlement.get(observer);
            int settled=before==null?1:before+1;
            r.observerSettlement.put(observer,settled);
            // Native tracker updates run after world entities. Let their queued
            // spawn/initial-mount frames settle before one accepted link refresh.
            if (settled<3) continue;
            Object connection=Reflect.get(observer,"connection|playerNetServerHandler");
            if (multi) {
                Reflect.call(connection,"sendPacket",Reflect.create("net.minecraft.network.play.server.SPacketSetPassengers",r.horse));
                Reflect.call(connection,"sendPacket",Reflect.create("net.minecraft.network.play.server.SPacketSetPassengers",r.seat));
            } else {
                Reflect.call(connection,"sendPacket",Reflect.create("net.minecraft.network.play.server.S1BPacketEntityAttach",0,r.driver,r.horse));
                Reflect.call(connection,"sendPacket",Reflect.create("net.minecraft.network.play.server.S1BPacketEntityAttach",0,r.passenger,r.seat));
            }
            r.synchronizedObservers.add(observer);
        }
    }
    private void remove(Ride r) {
        rides.remove(r.horse);
        discard(r.seat);
        if (config.debug) log("A separate seat was removed; active rides=" + rides.size());
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
        messageJson(sender,"{\"text\":\"" + escape(text) + "\",\"color\":\""+(click==null?"gray":"aqua")+"\"" + (click == null ? "" : ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\""+escape(click)+"\"}") + "}");
    }
    private void messageJson(Object sender,String json) {
        String component = multi ? "net.minecraft.util.text.ITextComponent$Serializer" : "net.minecraft.util.IChatComponent$Serializer";
        Object chat = Reflect.call(Reflect.type(component),"jsonToComponent",json);
        Reflect.call(sender,"sendMessage|addChatMessage",chat);
    }
    private String verticalUnits() { return config.horseFeet ? "rider feet above horse feet" : "legacy offset above horse mounted position"; }
    private static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }
    private void command(Object sender,String[] args) {
        try {
            if (args.length == 0 || (args.length == 1 && args[0].equals("help"))) {
                messageJson(sender,"{\"text\":\"Tandemount\",\"color\":\"gold\",\"bold\":true}");
                message(sender,"Right-click an occupied adult, tamed, saddled horse to join; sneak to leave.",null);
                message(sender,"/tandemount status | vertical <number|+|-> | rear <number|+|-> | enable | disable | reload | reset | types horse | debug <on|off>",null);
                if (admin(sender)) {
                    message(sender,"Vertical "+config.vertical+" ("+verticalUnits()+"); rear "+config.rear+" blocks.",null);
                    message(sender,"[Vertical +1/16]","/tandemount vertical +"); message(sender,"[Vertical -1/16]","/tandemount vertical -");
                    message(sender,"[Rear +1/16]","/tandemount rear +"); message(sender,"[Rear -1/16]","/tandemount rear -");
                    message(sender,"[Reload saved settings]","/tandemount reload"); message(sender,"[Reset to owner defaults]","/tandemount reset");
                    message(sender,"Changes save immediately. Reset uses feet above horse feet: vertical 0.8125, rear 0.5.",null);
                    message(sender,"Set excluded-dimensions in config/tandemount.properties to comma-separated Forge dimension IDs, then reload.",null);
                }
                return;
            }
            if (!admin(sender)) { message(sender,"Permission denied: tandemount.admin.",null); return; }
            if (args.length == 1 && args[0].equals("status")) {
                message(sender,"Tandemount: enabled="+config.enabled+", debug="+config.debug+", vertical="+config.vertical+" ("+verticalUnits()+"), rear="+config.rear+", types=horse, excluded-dimensions=["+config.dimensionList()+"], rides="+rides.size(),null); return;
            }
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
            for (Ride r : new ArrayList<Ride>(rides.values())) { if (!config.enabled || excluded(r.world)) remove(r); else pose(r); }
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
