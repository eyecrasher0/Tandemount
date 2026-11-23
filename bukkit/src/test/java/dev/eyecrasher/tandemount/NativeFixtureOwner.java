/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Disposable acceptance checks driven by real native-encoded socket packets. */
public final class NativeFixtureOwner implements Listener {
    private static NativeFixtureOwner active;
    private final JavaPlugin fixture;
    private final TandemountPlugin tandem;
    private Player driver, rider, admin;
    private Horse horse;
    private int tick;
    private boolean cancel;
    private NativeFixtureOwner(JavaPlugin fixture) {
        this.fixture = fixture;
        tandem = (TandemountPlugin)fixture.getServer().getPluginManager().getPlugin("Tandemount");
    }
    public static void command(JavaPlugin fixture, String command) throws Exception {
        if (active == null) { active = new NativeFixtureOwner(fixture); fixture.getServer().getPluginManager().registerEvents(active, fixture); }
        if (command.equals("owner-start")) active.start();
        if (command.equals("owner-snapshot")) active.snapshot();
        if (command.equals("owner-cancel")) active.cancel = true;
        if (command.equals("owner-allow")) active.cancel = false;
        if (command.equals("owner-admin")) active.admin.setOp(true);
        if (command.equals("owner-decodespawn")) active.decodeSpawn();
        if (command.equals("owner-decodemetadata")) active.decodeMetadata();
        if (command.equals("owner-stop")) { active.driver.leaveVehicle(); active.rider.leaveVehicle(); }
    }
    private void start() throws Exception {
        driver = Bukkit.getPlayerExact("TmDriver"); rider = Bukkit.getPlayerExact("TmRider"); admin = Bukkit.getPlayerExact("TmThird");
        driver.setOp(false); rider.setOp(false); admin.setOp(false);
        horse = (Horse)driver.getWorld().spawnEntity(new Location(driver.getWorld(), 8, 64, 8), EntityType.HORSE);
        horse.setAdult(); horse.setTamed(true); horse.setOwner(driver); horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        horse.setAI(false);
        if (!Boolean.TRUE.equals(call(field(tandem, "nativeAccess"), "mount", horse, driver))) throw new IllegalStateException("Driver failed to mount ordinary horse");
        Properties values = new Properties();
        values.setProperty("horse", Integer.toString(horse.getEntityId()));
        values.setProperty("driver", Integer.toString(driver.getEntityId())); values.setProperty("rider", Integer.toString(rider.getEntityId()));
        Class<?> hand = Class.forName("net.minecraft.world.InteractionHand");
        Class<?> vector = Class.forName("net.minecraft.world.phys.Vec3");
        Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ServerboundInteractPacket");
        for (Object value : hand.getEnumConstants()) {
            Object interact = packet.getConstructor(int.class, hand, vector, boolean.class).newInstance(horse.getEntityId(), value,
                    vector.getConstructor(double.class,double.class,double.class).newInstance(0.0,0.8,0.0), false);
            values.setProperty("interact." + value, encode(packet, interact));
        }
        Class<?> loaded = Class.forName("net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket");
        Constructor<?> loadedConstructor = loaded.getDeclaredConstructor(); loadedConstructor.setAccessible(true);
        Object ready = loadedConstructor.newInstance();
        values.setProperty("loaded", encode(loaded, ready));
        Class<?> rotation = Class.forName("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Rot");
        Constructor<?> rotationConstructor = rotation.getConstructor(float.class, float.class, boolean.class, boolean.class);
        for (int step = 0; step < 120; step++) {
            float yaw = (float)-Math.toDegrees(step * .02);
            values.setProperty("rotation." + step, encode(rotation, rotationConstructor.newInstance(yaw, 0F, true, false)));
        }
        try (Writer output = new FileWriter(new File(fixture.getDataFolder(), "owner-wire.properties"))) { values.store(output, "Real upstream STREAM_CODEC bodies"); }
        Bukkit.getScheduler().runTaskTimer(fixture, new Runnable() { public void run() { try { tick++; snapshot(); } catch (Exception failure) { fixture.getLogger().warning(failure.toString()); } }}, 1L, 1L);
        snapshot();
    }
    private String encode(Class<?> type, Object packet) throws Exception {
        Object codec = type.getField("STREAM_CODEC").get(null);
        Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled");
        Object raw = unpooled.getMethod("buffer").invoke(null);
        Class<?> friendly = Class.forName("net.minecraft.network.FriendlyByteBuf");
        Object buffer = friendly.getConstructor(Class.forName("io.netty.buffer.ByteBuf")).newInstance(raw);
        try {
            call(codec, "encode", buffer, packet);
            int count = ((Number)call(raw, "readableBytes")).intValue();
            byte[] bytes = new byte[count]; call(raw, "readBytes", (Object)bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } finally { call(raw, "release"); }
    }
    private void snapshot() throws Exception {
        if (horse == null) return;
        Entity seat = rider.getVehicle();
        boolean mounted = seat != null && seat != horse && driver.getVehicle() == horse;
        int blockStateId = -1; boolean noGravity = false;
        String helpers = "[]"; int root = -1, riderRoot = -1, controller = -1;
        if (mounted) {
            Object nativeSeat=call(seat,"getHandle"), blockState=call(nativeSeat,"getBlockState");
            Class<?> blockClass=Class.forName("net.minecraft.world.level.block.Block");
            for(Method method:blockClass.getMethods())if(method.getName().equals("getId")&&method.getParameterTypes().length==1)
                blockStateId=((Number)method.invoke(null,blockState)).intValue();
            noGravity=Boolean.TRUE.equals(call(nativeSeat,"isNoGravity"));
            root=((Number)call(call(nativeSeat,"getRootVehicle"),"getId")).intValue();
            riderRoot=((Number)call(call(call(rider,"getHandle"),"getRootVehicle"),"getId")).intValue();
            controller=((Number)call(call(call(horse,"getHandle"),"getControllingPassenger"),"getId")).intValue();
            Object ride=((Map<?,?>)field(tandem,"horses")).get(horse.getUniqueId());
            StringBuilder chain=new StringBuilder("[");
            for(Entity helper:(Entity[])field(ride,"helpers")) {
                Object handle=call(helper,"getHandle");
                if(chain.length()>1)chain.append(',');
                chain.append("{\"id\":").append(helper.getEntityId()).append(",\"type\":\"").append(helper.getType().name()).append("\",\"x\":").append(helper.getLocation().getX())
                    .append(",\"y\":").append(helper.getLocation().getY()).append(",\"z\":").append(helper.getLocation().getZ())
                    .append(",\"yaw\":").append(helper.getLocation().getYaw())
                    .append(",\"parent\":").append(helper.getVehicle() == null ? -1 : helper.getVehicle().getEntityId())
                    .append(",\"noGravity\":").append(call(handle,"isNoGravity"));
                if(helper.getType().name().equals("LLAMA"))chain.append(",\"scale\":").append(call(handle,"getScale")).append(",\"invisible\":").append(call(handle,"isInvisible"));
                if(helper.getType().name().equals("INTERACTION"))chain.append(",\"height\":").append(call(helper,"getInteractionHeight")).append(",\"width\":").append(call(helper,"getInteractionWidth"));
                chain.append('}');
            }
            helpers=chain.append(']').toString();
        }
        String record = "{\"tick\":" + tick + ",\"nanoTime\":" + System.nanoTime() + ",\"mounted\":" + mounted + ",\"riderVehicle\":" + (seat == null ? -1 : seat.getEntityId())
                + ",\"horse\":" + horse.getEntityId() + ",\"driverVehicle\":" + (driver.getVehicle() == null ? -1 : driver.getVehicle().getEntityId())
                + ",\"horseX\":" + horse.getLocation().getX() + ",\"horseY\":" + horse.getLocation().getY() + ",\"horseZ\":" + horse.getLocation().getZ()
                + ",\"horseYaw\":" + horse.getLocation().getYaw()
                + ",\"driverX\":" + driver.getLocation().getX() + ",\"driverY\":" + driver.getLocation().getY() + ",\"driverZ\":" + driver.getLocation().getZ()
                + ",\"driverYaw\":" + driver.getLocation().getYaw() + ",\"driverPitch\":" + driver.getLocation().getPitch()
                + ",\"riderX\":" + rider.getLocation().getX() + ",\"riderY\":" + rider.getLocation().getY() + ",\"riderZ\":" + rider.getLocation().getZ()
                + ",\"seatX\":" + (seat == null ? 0 : seat.getLocation().getX()) + ",\"seatY\":" + (seat == null ? 0 : seat.getLocation().getY()) + ",\"seatZ\":" + (seat == null ? 0 : seat.getLocation().getZ())
                + ",\"seatBlockStateId\":"+blockStateId+",\"seatNoGravity\":"+noGravity
                + ",\"seatRoot\":"+root+",\"riderRoot\":"+riderRoot+",\"horseController\":"+controller+",\"helpers\":"+helpers
                + ",\"thirdVehicle\":"+(admin.getVehicle()==null?-1:admin.getVehicle().getEntityId())
                + ",\"seatType\":\"" + (seat == null ? "NONE" : seat.getType().name()) + "\""
                + ",\"nonOpRiders\":" + (!driver.isOp() && !rider.isOp()) + ",\"vertical\":" + field(field(tandem,"settings"),"vertical") + ",\"rearward\":" + field(field(tandem,"settings"),"rearward") + "}";
        try (Writer output = new FileWriter(new File(fixture.getDataFolder(), "owner-state.json"))) { output.write(record); }
        try (Writer output = new FileWriter(new File(fixture.getDataFolder(), "owner-ticks.jsonl"), true)) { output.write(record + "\n"); }
    }
    private void decodeSpawn() throws Exception {
        byte[] bytes=java.nio.file.Files.readAllBytes(new File(fixture.getDataFolder(),"owner-spawn.bin").toPath());
        Object raw=Class.forName("io.netty.buffer.Unpooled").getMethod("wrappedBuffer",byte[].class).invoke(null,(Object)bytes);
        Class<?> friendly=Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");Object nativeWorld=call(horse.getWorld(),"getHandle");Object registries=call(nativeWorld,"registryAccess");Object buffer=null;
        for(Constructor<?> constructor:friendly.getConstructors())if(constructor.getParameterTypes().length==2
                &&constructor.getParameterTypes()[0].isInstance(raw)&&constructor.getParameterTypes()[1].isInstance(registries))buffer=constructor.newInstance(raw,registries);
        if(buffer==null)throw new NoSuchMethodException("Native registry packet buffer");
        Class<?> type=Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");Object packet=call(type.getField("STREAM_CODEC").get(null),"decode",buffer);
        int id=((Number)call(packet,"getId")).intValue(), data=((Number)call(packet,"getData")).intValue();
        Object entityType=call(packet,"getType");
        Object key=Class.forName("net.minecraft.world.entity.EntityType").getMethod("getKey",Class.forName("net.minecraft.world.entity.EntityType")).invoke(null,entityType);
        String record="{\"entityId\":"+id+",\"blockStateId\":"+data+",\"entityType\":\""+key+"\",\"remainingBytes\":"+call(raw,"readableBytes")+"}";
        try(Writer output=new FileWriter(new File(fixture.getDataFolder(),"owner-spawn-decoded.json"))){output.write(record);}
        call(raw,"release");
    }
    private void decodeMetadata() throws Exception {
        byte[] bytes=java.nio.file.Files.readAllBytes(new File(fixture.getDataFolder(),"owner-metadata.bin").toPath());
        Object raw=Class.forName("io.netty.buffer.Unpooled").getMethod("wrappedBuffer",byte[].class).invoke(null,(Object)bytes);
        Class<?> friendly=Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");Object registries=call(call(horse.getWorld(),"getHandle"),"registryAccess");Object buffer=null;
        for(Constructor<?> constructor:friendly.getConstructors())if(constructor.getParameterTypes().length==2
                &&constructor.getParameterTypes()[0].isInstance(raw)&&constructor.getParameterTypes()[1].isInstance(registries))buffer=constructor.newInstance(raw,registries);
        if(buffer==null)throw new NoSuchMethodException("Native registry packet buffer");
        Class<?> type=Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");Object packet=call(type.getField("STREAM_CODEC").get(null),"decode",buffer);
        Field gravity=Class.forName("net.minecraft.world.entity.Entity").getDeclaredField("DATA_NO_GRAVITY");gravity.setAccessible(true);Object accessor=gravity.get(null);int gravityId=((Number)call(accessor,"id")).intValue();
        Field duration=Class.forName("net.minecraft.world.entity.Display").getDeclaredField("DATA_POS_ROT_INTERPOLATION_DURATION_ID");duration.setAccessible(true);
        Field state=Class.forName("net.minecraft.world.entity.Display$BlockDisplay").getDeclaredField("DATA_BLOCK_STATE_ID");state.setAccessible(true);
        int durationId=((Number)call(duration.get(null),"id")).intValue(),stateId=((Number)call(state.get(null),"id")).intValue();
        Object noGravity=null,teleportDuration=null;Integer blockStateId=null;
        for(Object value:(List<?>)call(packet,"packedItems")) {
            int entry=((Number)call(value,"id")).intValue();Object decoded=call(value,"value");
            if(entry==gravityId)noGravity=decoded;
            if(entry==durationId)teleportDuration=decoded;
            if(entry==stateId)for(Method m:Class.forName("net.minecraft.world.level.block.Block").getMethods())
                if(m.getName().equals("getId")&&m.getParameterTypes().length==1)blockStateId=((Number)m.invoke(null,decoded)).intValue();
        }
        String record="{\"entityId\":"+call(packet,"id")+",\"noGravity\":"+noGravity+",\"blockStateId\":"+blockStateId+",\"teleportDuration\":"+teleportDuration+",\"nativeAccessorId\":"+gravityId+",\"remainingBytes\":"+call(raw,"readableBytes")+"}";
        try(Writer output=new FileWriter(new File(fixture.getDataFolder(),"owner-metadata-decoded.json"))){output.write(record);}
        call(raw,"release");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void teleport(PlayerTeleportEvent event) {
        if (event.getPlayer() == rider || event.getPlayer() == driver)
            log("teleport", event.getPlayer(), "cause=" + event.getCause() + "; cancelled=" + event.isCancelled() + "; from=" + event.getFrom() + "; to=" + event.getTo());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void exit(VehicleExitEvent event) {
        if (event.getExited() == rider || event.getExited() == driver) log("exit", event.getExited(), "cancelled=" + event.isCancelled());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void at(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() != horse) return;
        if (cancel) event.setCancelled(true);
        log("interact-at", event.getPlayer(), "hand=" + event.getHand() + "; cancelled=" + event.isCancelled());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() != horse) return;
        if (cancel) event.setCancelled(true);
        log("interact", event.getPlayer(), "hand=" + event.getHand() + "; cancelled=" + event.isCancelled());
    }
    private void log(String kind, Entity entity, String detail) {
        String record = "{\"tick\":" + tick + ",\"event\":\"" + kind + "\",\"entity\":\"" + entity.getUniqueId()
                + "\",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}\n";
        try (Writer output = new FileWriter(new File(fixture.getDataFolder(), "owner-events.jsonl"), true)) { output.write(record); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        fixture.getLogger().info("OWNER_EVENT " + kind + " " + detail);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) for (Method method:type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length!=args.length) continue;
            boolean compatible=true;Class<?>[] parameters=method.getParameterTypes();
            for(int i=0;i<parameters.length;i++) if (args[i]!=null && !parameters[i].isInstance(args[i]) && !parameters[i].isPrimitive()) compatible=false;
            if (!compatible) continue;
            method.setAccessible(true);return method.invoke(target,args);
        }
        throw new NoSuchMethodException(target.getClass()+"."+name);
    }
}
