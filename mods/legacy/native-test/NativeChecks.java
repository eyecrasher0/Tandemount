/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher
 * Test-only mod source; never included in a Tandemount distributable. */
package dev.eyecrasher.tandemount.nativetest;

import dev.eyecrasher.tandemount.legacy.LegacyRuntime;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** These are native server object checks, not protocol observers or visual checks. */
public final class NativeChecks {
    private static final Properties NAMES=new Properties();
    private static boolean multi, oldest, cancelInteraction, cancelMount, cancelDismount;
    private static Object server,world;
    private static int dismountEvents;
    private static Object observerHorse,observerDriver,observerRider,observerSeat,stopSeat,stopRider;
    private static final File RESTART_MARKER=new File("tandemount-native-restart-seat.txt");
    static {
        try(InputStream in=NativeChecks.class.getResourceAsStream("/test-names.properties")){NAMES.load(in);}
        catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    private static String canonical(String n){return NAMES.getProperty(n,n);}
    private static boolean compatible(Class<?> p,Object a){
        if(a==null)return !p.isPrimitive();
        if(p.isPrimitive())return (p==int.class&&a instanceof Integer)||(p==boolean.class&&a instanceof Boolean)||(p==float.class&&a instanceof Float)||(p==double.class&&a instanceof Double);
        return p.isInstance(a);
    }
    private static Object call(Object o,String names,Object...args){
        Class<?> type=o instanceof Class?(Class<?>)o:o.getClass();
        Set<String> ns=new HashSet<String>(Arrays.asList(names.split("\\|")));
        for(Class<?> c=type;c!=null;c=c.getSuperclass()){
            next:for(Method m:c.getDeclaredMethods()){
                if(!ns.contains(canonical(m.getName()))||m.getParameterTypes().length!=args.length)continue;
                for(int i=0;i<args.length;i++)if(!compatible(m.getParameterTypes()[i],args[i]))continue next;
                try{m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}
                catch(Exception ex){throw new IllegalStateException(type.getName()+"#"+names,ex);}
            }
        }
        throw new IllegalStateException("No native method "+type.getName()+"#"+names);
    }
    private static Class<?> type(String n){try{return Class.forName(n);}catch(Exception ex){throw new IllegalStateException(n,ex);}}
    private static Object make(String n,Object...args){
        next:for(Constructor<?> c:type(n).getConstructors()){
            if(c.getParameterTypes().length!=args.length)continue;
            for(int i=0;i<args.length;i++)if(!compatible(c.getParameterTypes()[i],args[i]))continue next;
            try{return c.newInstance(args);}catch(Exception ex){throw new IllegalStateException(n,ex);}
        }
        throw new IllegalStateException("No constructor "+n);
    }
    private static Field field(Object o,String names){
        Set<String> ns=new HashSet<String>(Arrays.asList(names.split("\\|")));
        for(Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();c!=null;c=c.getSuperclass())
            for(Field f:c.getDeclaredFields())if(ns.contains(canonical(f.getName()))){f.setAccessible(true);return f;}
        throw new IllegalStateException("No native field "+names+" on "+o);
    }
    private static Object get(Object o,String n){try{return field(o,n).get(o instanceof Class?null:o);}catch(Exception ex){throw new IllegalStateException(n,ex);}}
    private static void set(Object o,String n,Object v){try{field(o,n).set(o,v);}catch(Exception ex){throw new IllegalStateException(n,ex);}}
    private static double number(Object o,String n){return ((Number)get(o,n)).doubleValue();}
    private static double feet(Object entity){return number(entity,"posY")-(oldest?number(entity,"yOffset"):0.0D);}
    private static Object root(Object entity){
        Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        while(vehicle(entity)!=null&&visited.add(entity))entity=vehicle(entity);
        return entity;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static Object constant(String c,String e){return Enum.valueOf((Class)type(c),e);}
    private static void check(String name,boolean passed){
        System.out.println("[TandemountNative] {\"name\":\""+name+"\",\"passed\":"+passed+"}");
        if(!passed)throw new AssertionError(name);
    }
    public static void register(Object event){
        server=call(event,"getServer");multi="1.12.2".equals(NAMES.getProperty("minecraft"));oldest="1.7.10".equals(NAMES.getProperty("minecraft"));
        Class<?> command=type("net.minecraft.command.ICommand");
        Object proxy=Proxy.newProxyInstance(command.getClassLoader(),new Class<?>[]{command},new InvocationHandler(){
            public Object invoke(Object p,Method m,Object[] a){
                String n=canonical(m.getName());
                if(n.equals("getCommandName")||n.equals("getName"))return "tandemount_native_test";
                if(n.equals("getCommandUsage")||n.equals("getUsage"))return "tandemount_native_test";
                if(n.equals("getCommandAliases")||n.equals("getAliases")||n.equals("addTabCompletionOptions")||n.equals("getTabCompletions"))return Collections.emptyList();
                if(n.equals("canCommandSenderUseCommand")||n.equals("checkPermission"))return true;
                if(n.equals("processCommand")||n.equals("execute")){dispatch((String[])a[a.length-1]);return null;}
                if(n.equals("isUsernameIndex"))return false;
                if(n.equals("compareTo"))return 0;
                if(n.equals("hashCode"))return System.identityHashCode(p);
                if(n.equals("equals"))return p==a[0];
                if(n.equals("toString"))return "TandemountNativeTests";
                throw new UnsupportedOperationException(n);
            }
        });
        call(event,"registerServerCommand",proxy);
        verifyRestart();
        if(!multi&&Boolean.getBoolean("tandemount.native.requireClient"))
            check("client-required-mod-prevents-vanilla-bridge",!LegacyRuntime.legacyVanillaLogin(new Object(),new Object(),new Object()));
    }
    private static void selectWorld(){world=multi?call(server,"getWorld",0):call(server,"worldServerForDimension",0);}
    private static void dispatch(String[] args){
        if(args.length==0){run();return;}
        if(args[0].equals("observer-op")){
            command(server,"op VanillaEarly");System.out.println("[TandemountObserver] {\"phase\":\"admin-ready\"}");return;
        }
        if(args[0].equals("observer-prepare")){observerPrepare();return;}
        if(args[0].equals("observer-state")){observerState("state");return;}
        if(args[0].equals("observer-move")){
            call(observerHorse,"setPositionAndRotation",20.0D,number(observerHorse,"posY"),8.0D,90.0F,0.0F);observerState("moving");return;
        }
        if(args[0].equals("observer-disconnected")){
            check("real-vanilla-disconnect-cleans-seat",vehicle(observerRider)==null&&!LegacyRuntime.isSeat(observerSeat));
            call(world,"removeEntity",observerHorse);observerHorse=null;return;
        }
        if(args[0].equals("stop-fixture")){stopFixture();return;}
        throw new IllegalArgumentException("Unknown native test phase");
    }
    private static void verifyRestart(){
        if(!RESTART_MARKER.isFile())return;
        try{
            selectWorld();call(world,"getChunkFromChunkCoords|getChunk",0,0);
            String expected;
            try(BufferedReader reader=new BufferedReader(new FileReader(RESTART_MARKER))){expected=reader.readLine();}
            boolean found=false;
            for(Object e:(List<?>)get(world,"loadedEntityList"))if(expected.equals(call(e,"getUniqueID").toString()))found=true;
            check("restart-seat-absent-from-native-world",!found);
        }catch(IOException ex){throw new IllegalStateException(ex);}
    }
    private static void stopFixture(){
        selectWorld();Object players=call(server,"getPlayerList|getConfigurationManager");
        Object driver=call(players,"getPlayerByUsername","StopDriver");stopRider=call(players,"getPlayerByUsername","StopRider");
        stopSeat=ride(new ArrayList<Object>(),driver,stopRider,8.0D);
        try(PrintWriter out=new PrintWriter(RESTART_MARKER)){out.println(call(stopSeat,"getUniqueID"));}
        catch(IOException ex){throw new IllegalStateException(ex);}
        check("active-seat-at-native-stop",LegacyRuntime.isSeat(stopSeat));
    }
    public static void stopped(){
        if(stopSeat!=null)check("native-server-stop-cleans-seat",!LegacyRuntime.isSeat(stopSeat)&&vehicle(stopRider)==null&&Boolean.TRUE.equals(get(stopSeat,"isDead")));
    }
    private static void observerPrepare(){
        selectWorld();command(server,"tandemount reset");
        Object players=call(server,"getPlayerList|getConfigurationManager");
        observerDriver=call(players,"getPlayerByUsername","VanillaDriver");observerRider=call(players,"getPlayerByUsername","VanillaRider");
        dismount(observerDriver);dismount(observerRider); // Clear ordinary mount NBT from a previous disposable run.
        Object early=call(players,"getPlayerByUsername","VanillaEarly");
        call(world,"getChunkFromChunkCoords|getChunk",0,0);
        call(observerDriver,"setPositionAndUpdate",7.0D,5.0D,8.0D);
        call(observerRider,"setPositionAndUpdate",9.0D,5.0D,8.0D);
        call(early,"setPositionAndUpdate",12.0D,8.0D,8.0D);
        observerHorse=horse(8.0D);call(observerHorse,"setPositionAndRotation",8.0D,5.0D,8.0D,0.0F,0.0F);
        call(call(observerHorse,"getEntityAttribute",get(type("net.minecraft.entity.SharedMonsterAttributes"),"movementSpeed|MOVEMENT_SPEED")),"setBaseValue",0.0D);
        if(!oldest)call(observerHorse,"setNoAI",true);
        else {
            ((Collection<?>)get(get(observerHorse,"tasks"),"taskEntries")).clear();
            ((Collection<?>)get(get(observerHorse,"targetTasks"),"taskEntries")).clear();
            call(call(observerHorse,"getNavigator"),"clearPathEntity");
            call(observerHorse,"setAIMoveSpeed",0.0F);
            set(observerHorse,"moveForward",0.0F);set(observerHorse,"moveStrafing",0.0F);
            set(observerHorse,"motionX",0.0D);set(observerHorse,"motionZ",0.0D);
        }
        observerState("prepared");
    }
    private static void observerState(String phase){
        Object seat=vehicle(observerRider);if(LegacyRuntime.isSeat(seat))observerSeat=seat;
        boolean joined=LegacyRuntime.isSeat(seat)&&vehicle(observerDriver)==observerHorse;
        System.out.println("[TandemountObserver] {\"phase\":\""+phase+"\",\"joined\":"+joined+",\"horse\":"+call(observerHorse,"getEntityId")
                +",\"driver\":"+call(observerDriver,"getEntityId")+",\"rider\":"+call(observerRider,"getEntityId")+",\"seat\":"+(joined?call(seat,"getEntityId"):-1)
                +",\"horse_position\":["+number(observerHorse,"posX")+","+number(observerHorse,"posY")+","+number(observerHorse,"posZ")+"]"
                +",\"rider_feet\":["+number(observerRider,"posX")+","+feet(observerRider)+","+number(observerRider,"posZ")+"]"
                +",\"seat_root\":"+(joined?call(root(seat),"getEntityId"):-1)
                +",\"driver_root\":"+call(root(observerDriver),"getEntityId")
                +",\"position\":"+(joined?("["+number(seat,"posX")+","+number(seat,"posY")+","+number(seat,"posZ")+"]"):"null")+"}");
    }
    public static void interaction(Object event){if(cancelInteraction)call(event,"setCanceled",true);}
    public static void mountEvent(Object event){
        boolean mounting=Boolean.TRUE.equals(call(event,"isMounting"));
        if(!mounting)dismountEvents++;
        if((mounting&&cancelMount)||(!mounting&&cancelDismount))call(event,"setCanceled",true);
    }
    private static Object vehicle(Object player){return multi?call(player,"getRidingEntity"):get(player,"ridingEntity");}
    private static void mount(Object player,Object target){if(multi)call(player,"startRiding",target,false);else call(player,"mountEntity",target);}
    private static void dismount(Object player){if(multi)call(player,"dismountRidingEntity");else call(player,"mountEntity",(Object)null);}
    private static void interact(Object player,Object horse){if(multi)call(player,"interactOn",horse,constant("net.minecraft.util.EnumHand","MAIN_HAND"));else call(player,"interactWith",horse);}
    private static void command(Object sender,String c){call(call(server,"getCommandManager"),"executeCommand",sender,c);}
    private static Object airBlock(){return get(type("net.minecraft.init.Blocks"),multi?"AIR":"air");}
    private static Object blockAt(Object entity){
        int x=(int)Math.floor(number(entity,"posX")),y=(int)Math.floor(number(entity,"posY")),z=(int)Math.floor(number(entity,"posZ"));
        if(oldest)return call(world,"getBlock",x,y,z);
        Object pos=make(multi?"net.minecraft.util.math.BlockPos":"net.minecraft.util.BlockPos",x,y,z);
        return call(call(world,"getBlockState",pos),"getBlock");
    }
    private static Map<String,String> preservedState(Object entity,String...keys){
        Object nbt=make("net.minecraft.nbt.NBTTagCompound");call(entity,"writeToNBT",nbt);
        Map<String,String> values=new TreeMap<String,String>();
        for(String key:keys){Object value=call(nbt,"getTag",key);values.put(key,String.valueOf(value));}
        return values;
    }
    private static Object ride(List<Object> cleanup,Object driver,Object passenger,double x){
        Object h=horse(x);cleanup.add(h);call(driver,"setPosition",x,65.0D,8.0D);call(passenger,"setPosition",x,65.0D,8.0D);
        mount(driver,h);interact(passenger,h);Object seat=vehicle(passenger);
        if(!LegacyRuntime.isSeat(seat))throw new AssertionError("Native lifecycle fixture did not mount");
        return seat;
    }
    @SuppressWarnings("unchecked") private static Object player(String name){
        Object profile=make("com.mojang.authlib.GameProfile",UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);
        Object manager=make(multi?"net.minecraft.server.management.PlayerInteractionManager":"net.minecraft.server.management.ItemInWorldManager",world);
        Object p=make("net.minecraft.entity.player.EntityPlayerMP",server,world,profile,manager);
        Object net=make("net.minecraft.network.NetworkManager",oldest?Boolean.TRUE:constant("net.minecraft.network.EnumPacketDirection","SERVERBOUND"));
        Object connection=make("net.minecraft.network.NetHandlerPlayServer",server,net,p);
        set(p,"connection|playerNetServerHandler",connection);
        call(p,"setPosition",8.0D,65.0D,8.0D);call(p,"setHealth",20.0F);
        Object players=call(server,"getPlayerList|getConfigurationManager");
        ((List<Object>)get(players,"playerEntityList|playerEntityList|players")).add(p);
        call(world,"spawnEntity|spawnEntityInWorld",p);return p;
    }
    private static Object horse(double x){
        Object h=make("net.minecraft.entity.passive.EntityHorse",world);call(h,"setPositionAndRotation",x,65.0D,8.0D,0.0F,0.0F);
        call(h,"setHorseTamed",true);call(h,"setHorseSaddled",true);
        if(!multi)call(h,"setHorseType",0);
        call(world,"spawnEntity|spawnEntityInWorld",h);return h;
    }
    private static boolean hasNoPassengers(Object entity){return multi?((List<?>)call(entity,"getPassengers")).isEmpty():get(entity,"riddenByEntity")==null;}
    private static Properties configuration(){
        Properties p=new Properties();
        try(InputStream in=new FileInputStream("config/tandemount.properties")){p.load(in);return p;}
        catch(IOException ex){throw new IllegalStateException(ex);}
    }
    private static void writeConfiguration(Properties p){
        try(OutputStream out=new FileOutputStream("config/tandemount.properties")){p.store(out,"Native fixture settings");}
        catch(IOException ex){throw new IllegalStateException(ex);}
    }
    private static void defaultsAndMigration(Object horse,Object rider){
        check("fresh-config-uses-owner-defaults",Math.abs(feet(rider)-number(horse,"posY")-.8125)<1e-6
                &&Math.abs(number(rider,"posZ")-number(horse,"posZ")+.5)<1e-6);
        Properties p=configuration();p.remove("vertical");p.remove("rear");p.remove("vertical-origin");writeConfiguration(p);
        command(server,"tandemount reload");
        check("missing-settings-use-owner-defaults",Math.abs(feet(rider)-number(horse,"posY")-.8125)<1e-6
                &&Math.abs(number(rider,"posZ")-number(horse,"posZ")+.5)<1e-6);
        p=configuration();p.setProperty("vertical","0.0625");p.setProperty("rear","0.625");p.remove("vertical-origin");writeConfiguration(p);
        command(server,"tandemount reload");
        double nativeFeetOffset=((Number)call(rider,"getYOffset")).doubleValue()-(oldest?number(rider,"yOffset"):0.0D);
        double expected=((Number)call(horse,"getMountedYOffset")).doubleValue()+.0625+nativeFeetOffset;
        Properties migrated=configuration();
        check("saved-settings-remain-preserved",Math.abs(feet(rider)-number(horse,"posY")-expected)<1e-6
                &&Math.abs(number(rider,"posZ")-number(horse,"posZ")+.625)<1e-6
                &&"0.0625".equals(migrated.getProperty("vertical"))&&"0.625".equals(migrated.getProperty("rear"))
                &&"legacy-mounted-offset".equals(migrated.getProperty("vertical-origin")));
        command(server,"tandemount reset");
        check("reset-restores-owner-defaults",Math.abs(feet(rider)-number(horse,"posY")-.8125)<1e-6
                &&Math.abs(number(rider,"posZ")-number(horse,"posZ")+.5)<1e-6
                &&"horse-feet".equals(configuration().getProperty("vertical-origin")));
    }
    private static byte[] fileBytes() {
        try{return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("config/tandemount.properties"));}
        catch(IOException ex){throw new IllegalStateException(ex);}
    }
    private static void strictReloadAndExclusions(Object driver,Object rider,Object ordinary,Object otherDriver,Object otherRider,Object horse,Object otherHorse,Object seat,Object otherSeat) {
        Properties valid=configuration();double y=feet(rider),z=number(rider,"posZ");
        for(String[] invalid:new String[][]{{"enabled","maybe"},{"debug","1"},{"vertical","NaN"},{"rear","2.0625"},{"excluded-dimensions","minecraft:overworld"},{"excluded-dimensions","0,,1"},{"excluded-dimensions","2147483648"},{"types","donkey"},{"unknown-setting","true"}}) {
            Properties p=new Properties();p.putAll(valid);p.setProperty(invalid[0],invalid[1]);writeConfiguration(p);byte[] before=fileBytes();
            command(server,"tandemount reload");LegacyRuntime.worldTick(world);
            check("invalid-reload-preserves-active-and-file-"+invalid[0]+"-"+invalid[1].replace(':','_').replace(',','_'),
                    Arrays.equals(before,fileBytes())&&vehicle(rider)==seat&&vehicle(otherRider)==otherSeat&&feet(rider)==y&&number(rider,"posZ")==z);
        }
        writeConfiguration(valid);command(server,"tandemount reload");
        Properties excluded=new Properties();excluded.putAll(valid);excluded.setProperty("excluded-dimensions","0");writeConfiguration(excluded);
        byte[] before=fileBytes();command(ordinary,"tandemount reload");
        check("ordinary-player-cannot-reload-exclusions",Arrays.equals(before,fileBytes())&&vehicle(rider)==seat&&vehicle(otherRider)==otherSeat);
        command(server,"tandemount reload");
        check("exclusion-reload-cleans-only-extra-seats",vehicle(rider)==null&&vehicle(otherRider)==null&&!LegacyRuntime.isSeat(seat)&&!LegacyRuntime.isSeat(otherSeat)&&vehicle(driver)==horse&&vehicle(otherDriver)==otherHorse);
        interact(rider,horse);interact(otherRider,otherHorse);
        check("excluded-dimension-refuses-new-rides",vehicle(rider)==null&&vehicle(otherRider)==null&&vehicle(driver)==horse&&vehicle(otherDriver)==otherHorse);
        writeConfiguration(valid);command(server,"tandemount reload");
        call(rider,"setPosition",number(horse,"posX"),number(horse,"posY"),number(horse,"posZ"));
        call(otherRider,"setPosition",number(otherHorse,"posX"),number(otherHorse,"posY"),number(otherHorse,"posZ"));
        if(multi) {
            interact(rider,horse);
            check("native-remount-cooldown-retained-after-cleanup",vehicle(rider)==null&&number(rider,"rideCooldown")>0);
            // The native API sets a 60-tick remount cooldown on removal. Exercise
            // its ordinary countdown instead of forcing a new mount through it.
            for(int tick=0;tick<60;tick++){call(rider,"onEntityUpdate");call(otherRider,"onEntityUpdate");}
        }
        interact(rider,horse);interact(otherRider,otherHorse);
        check("removing-exclusion-allows-independent-retry",LegacyRuntime.isSeat(vehicle(rider))&&LegacyRuntime.isSeat(vehicle(otherRider))&&vehicle(rider)!=vehicle(otherRider));
    }
    private static void dimensionChanges(List<Object> cleanup){
        // Test setup only: Forge may have unloaded the empty End since server startup.
        // Initialize that standard dimension explicitly before either real player transfer.
        // The ordinary End teleporter prepares only a local landing platform in this disposable fixture.
        int dimension=1;
        Class<?> dimensions=type("net.minecraftforge.common.DimensionManager");
        Object target=call(dimensions,"getWorld",dimension);
        boolean initialized=target==null;
        if(initialized){call(dimensions,"initDimension",dimension);target=call(dimensions,"getWorld",dimension);}
        check(initialized?"native-world-change-target-initialized-for-fixture":"native-world-change-target-already-loaded",
                target!=null&&target!=world&&type("net.minecraft.world.WorldServer").isInstance(target));
        for(boolean driverChanges:new boolean[]{false,true}){
            String role=driverChanges?"driver":"passenger";
            Object driver=player(driverChanges?"WorldDriverTwo":"WorldDriverOne"),rider=player(driverChanges?"WorldRiderTwo":"WorldRiderOne");
            cleanup.add(driver);cleanup.add(rider);
            Object seat=ride(cleanup,driver,rider,8.0D),horse=vehicle(driver),moving=driverChanges?driver:rider;
            try{
                call(moving,multi?"changeDimension":"travelToDimension",dimension);
                LegacyRuntime.worldTick(world);
                check("native-"+role+"-dimension-transfer-completes",get(moving,"world|worldObj")==target&&number(moving,"dimension")==dimension
                        &&call(target,"getEntityByID",call(moving,"getEntityId"))==moving&&call(world,"getEntityByID",call(moving,"getEntityId"))!=moving);
                check("native-"+role+"-world-change-cleans-seat",vehicle(rider)==null&&hasNoPassengers(seat)&&!LegacyRuntime.isSeat(seat)&&Boolean.TRUE.equals(get(seat,"isDead")));
                check("native-"+role+"-world-change-preserves-source-horse",get(horse,"world|worldObj")==world
                        &&call(world,"getEntityByID",call(horse,"getEntityId"))==horse&&(driverChanges||vehicle(driver)==horse));
            }finally{
                // Native player dimension APIs do not unlink an ordinary horse; that link belongs to the caller.
                // Verify Tandemount's extra-seat cleanup first, then clear the fixture's ordinary riding link.
                if(driverChanges)dismount(driver);
            }
        }
    }
    private static void run(){
        List<Object> cleanup=new ArrayList<Object>();
        try{
            selectWorld();
            call(world,"getChunkFromChunkCoords|getChunk",0,0);
            Object a=player("NativeDriver"),b=player("NativeRider"),c=player("NativeThird"),d=player("NativeDriverTwo"),e=player("NativeRiderTwo");
            cleanup.addAll(Arrays.asList(a,b,c,d,e));
            Object horse=horse(8.0D),horseTwo=horse(10.0D);cleanup.add(horse);cleanup.add(horseTwo);
            call(get(b,"inventory"),"setInventorySlotContents",0,make("net.minecraft.item.ItemStack",get(type("net.minecraft.init.Items"),"apple|APPLE"),3));
            set(b,"experienceLevel",7);set(b,"experienceTotal",123);set(b,"experience",0.25F);
            String[] playerKeys={"Inventory","EnderItems","XpLevel","XpTotal","XpP","Health","HealF","UUIDMost","UUIDLeast"};
            String[] horseKeys={"OwnerUUID","OwnerName","Tame","SaddleItem","ArmorItem","Items","Health","HealF","UUIDMost","UUIDLeast","Attributes"};
            Map<String,String> riderBefore=preservedState(b,playerKeys),driverBefore=preservedState(a,playerKeys),horseBefore=preservedState(horse,horseKeys);
            command(server,"tandemount reset");command(server,"tandemount debug on");mount(a,horse);
            check("ordinary-native-driver-mounted",vehicle(a)==horse);
            cancelInteraction=true;interact(b,horse);cancelInteraction=false;
            check("cancelled-interaction-no-join",vehicle(b)==null);
            if(!oldest){
                cancelMount=true;interact(b,horse);cancelMount=false;
                check("cancelled-native-mount-no-seat",vehicle(b)==null);
            }
            interact(b,horse);Object seat=vehicle(b);
            check("uncancelled-native-interaction-joins",seat!=null&&LegacyRuntime.isSeat(seat)&&vehicle(a)==horse);
            defaultsAndMigration(horse,b);
            set(a,"moveForward",1.0F);set(a,"rotationYaw",90.0F);
            double beforeControlX=number(horse,"posX"),beforeControlZ=number(horse,"posZ");
            // A freshly constructed airborne horse initializes its movement speed
            // on the first travel call and uses it on the following native frame.
            for(int frame=0;frame<3;frame++) {
                if(multi)call(horse,"travel",0.0F,0.0F,0.0F);else call(horse,"moveEntityWithHeading",0.0F,0.0F);
            }
            // 1.12's local driver owns vehicle travel. The server consumes its
            // ordinary vehicle-move packets; it intentionally does not integrate
            // the client's movement a second time in AbstractHorse.travel.
            check("server-native-horse-keeps-first-driver-control",vehicle(a)==horse&&vehicle(b)==seat
                    &&Math.abs(number(horse,"rotationYaw")-90)<1e-6
                    &&(multi ? call(horse,"getControllingPassenger")==a&&Boolean.TRUE.equals(call(horse,"canBeSteered"))
                    : Math.abs(number(horse,"posX")-beforeControlX)+Math.abs(number(horse,"posZ")-beforeControlZ)>1e-6));
            set(a,"moveForward",0.0F);set(a,"rotationYaw",0.0F);
            call(horse,"setPositionAndRotation",8.0D,65.0D,8.0D,0.0F,0.0F);LegacyRuntime.worldTick(world);
            check("native-type-no-custom-registry",seat.getClass().getName().equals(multi?"net.minecraft.entity.EntityAreaEffectCloud":oldest?"net.minecraft.entity.monster.EntitySlime":"net.minecraft.entity.item.EntityArmorStand"));
            check("living-seat-yaw-follows-passenger",multi || Math.abs(number(seat,"rotationYaw")-number(b,"rotationYaw"))<1e-6);
            for(int i=0;i<5;i++)call(seat,"onUpdate");
            check("native-seat-does-not-place-block-or-fall",blockAt(seat)==airBlock()&&!Boolean.TRUE.equals(get(seat,"isDead"))&&vehicle(b)==seat);
            check("server-seat-cannot-intercept-clicks",Boolean.FALSE.equals(call(seat,"canBeCollidedWith")));
            check("seat-cannot-save",Boolean.FALSE.equals(call(seat,"writeToNBTOptional",make("net.minecraft.nbt.NBTTagCompound"))));
            Object damage=get(type("net.minecraft.util.DamageSource"),"generic|GENERIC");
            check("seat-cannot-take-damage",Boolean.FALSE.equals(call(seat,"attackEntityFrom",damage,5.0F)));
            interact(c,horse);check("third-rider-rejected-without-disruption",vehicle(c)==null&&vehicle(b)==seat&&vehicle(a)==horse);
            double motionX=number(horse,"motionX"),motionZ=number(horse,"motionZ");call(horse,"applyEntityCollision",b);
            check("managed-rider-does-not-push-horse",number(horse,"motionX")==motionX&&number(horse,"motionZ")==motionZ);
            double initialY=number(seat,"posY"),initialZ=number(seat,"posZ");
            command(server,"tandemount vertical +");
            check("vertical-independent-and-live",Math.abs(number(seat,"posY")-initialY-.0625)<1e-6&&number(seat,"posZ")==initialZ);
            double verticalY=number(seat,"posY");command(server,"tandemount rear +");
            check("rear-independent-and-live",number(seat,"posY")==verticalY&&Math.abs(number(seat,"posZ")-initialZ+.0625)<1e-6);
            double z=number(seat,"posZ");command(server,"tandemount vertical NaN");command(server,"tandemount rear Infinity");
            check("nonfinite-config-rejected",number(seat,"posY")==verticalY&&number(seat,"posZ")==z);
            command(c,"tandemount rear +");
            check("ordinary-player-settings-permission-denied",number(seat,"posZ")==z);
            call(horse,"setPositionAndRotation",9.0D,66.0D,9.0D,90.0F,0.0F);
            LegacyRuntime.worldTick(world);
            check("movement-and-turn-pose-with-driver-preserved",vehicle(a)==horse&&vehicle(b)==seat&&Math.abs(number(seat,"posX")-9.5625)<1e-5&&Math.abs(number(seat,"posZ")-9.0)<1e-5
                    &&Math.abs(feet(b)-66.0-.875)<1e-6);
            mount(d,horseTwo);interact(e,horseTwo);Object seatTwo=vehicle(e);
            check("multiple-rides-isolated",seatTwo!=null&&seatTwo!=seat&&LegacyRuntime.isSeat(seatTwo)&&vehicle(b)==seat);
            strictReloadAndExclusions(a,b,c,d,e,horse,horseTwo,seat,seatTwo);
            seat=vehicle(b);seatTwo=vehicle(e);
            if(!oldest){
                int before=dismountEvents;
                cancelDismount=true;dismount(b);cancelDismount=false;LegacyRuntime.worldTick(world);
                check("cancelled-dismount-stays-mounted",vehicle(b)==seat&&LegacyRuntime.isSeat(seat)&&dismountEvents==before+1);
            }
            dismount(b);LegacyRuntime.worldTick(world);
            check("normal-native-dismount-cleans-seat",vehicle(b)==null&&!LegacyRuntime.isSeat(seat)&&Boolean.TRUE.equals(get(seat,"isDead")));
            call(b,"setPosition",9.0D,66.0D,9.0D);interact(b,horse);Object retry=vehicle(b);
            check("retry-after-dismount",retry!=null&&retry!=seat&&LegacyRuntime.isSeat(retry));
            dismount(a);LegacyRuntime.worldTick(world);
            check("driver-dismount-cleans-passenger",vehicle(b)==null&&!LegacyRuntime.isSeat(retry)&&vehicle(e)==seatTwo);
            check("inventory-health-experience-and-horse-state-preserved",riderBefore.equals(preservedState(b,playerKeys))&&driverBefore.equals(preservedState(a,playerKeys))&&horseBefore.equals(preservedState(horse,horseKeys)));
            call(horseTwo,"setDead");LegacyRuntime.worldTick(world);
            check("horse-death-cleans-passenger",vehicle(e)==null&&!LegacyRuntime.isSeat(seatTwo));
            Object h3=horse(8.0D);cleanup.add(h3);call(a,"setPosition",8.0D,65.0D,8.0D);call(b,"setPosition",8.0D,65.0D,8.0D);mount(a,h3);interact(b,h3);Object s3=vehicle(b);
            command(server,"tandemount disable");
            check("disable-removes-active-seat",vehicle(b)==null&&!LegacyRuntime.isSeat(s3));
            command(server,"tandemount enable");interact(b,h3);Object s4=vehicle(b);
            call(b,"setHealth",0.0F);LegacyRuntime.worldTick(world);
            check("passenger-death-cleans-seat",!LegacyRuntime.isSeat(s4));
            Object lifeDriver=player("LifeDriver"),lifeRider=player("LifeRider");cleanup.add(lifeDriver);cleanup.add(lifeRider);
            Object driverDeath=ride(cleanup,lifeDriver,lifeRider,8.0D);call(lifeDriver,"setHealth",0.0F);LegacyRuntime.worldTick(world);
            check("driver-death-cleans-seat",vehicle(lifeRider)==null&&!LegacyRuntime.isSeat(driverDeath));
            Object quitDriver=player("QuitDriver"),quitRider=player("QuitRider");cleanup.add(quitDriver);cleanup.add(quitRider);
            Object disconnect=ride(cleanup,quitDriver,quitRider,8.0D);
            call(call(server,"getPlayerList|getConfigurationManager"),"playerLoggedOut",quitRider);LegacyRuntime.worldTick(world);
            check("native-disconnect-cleans-seat",vehicle(quitRider)==null&&!LegacyRuntime.isSeat(disconnect));
            Object reconnected=player("QuitRider");cleanup.add(reconnected);dismount(quitDriver);
            Object reconnectSeat=ride(cleanup,quitDriver,reconnected,8.0D);
            check("same-identity-reconnect-can-ride",LegacyRuntime.isSeat(reconnectSeat));
            call(world,"removeEntity",quitDriver);LegacyRuntime.worldTick(world);
            check("driver-removal-cleans-seat",vehicle(reconnected)==null&&!LegacyRuntime.isSeat(reconnectSeat));
            Object teleportDriver=player("TeleportDriver"),teleportRider=player("TeleportRider");cleanup.add(teleportDriver);cleanup.add(teleportRider);
            Object teleportSeat=ride(cleanup,teleportDriver,teleportRider,8.0D);
            call(teleportRider,"setPositionAndUpdate",32.0D,65.0D,8.0D);
            check("native-passenger-teleport-cleans-seat",vehicle(teleportRider)==null&&!LegacyRuntime.isSeat(teleportSeat));
            Object movedDriver=player("MovedDriver"),movedRider=player("MovedRider");cleanup.add(movedDriver);cleanup.add(movedRider);
            Object driverTeleportSeat=ride(cleanup,movedDriver,movedRider,8.0D);
            call(movedDriver,"setPositionAndUpdate",32.0D,65.0D,8.0D);
            check("native-driver-teleport-cleans-seat",vehicle(movedRider)==null&&!LegacyRuntime.isSeat(driverTeleportSeat));
            dimensionChanges(cleanup);
            Object remoteDriver=player("ChunkDriver"),remoteRider=player("ChunkRider");cleanup.add(remoteDriver);cleanup.add(remoteRider);
            Object chunk=call(world,"getChunkFromChunkCoords|getChunk",64,0);
            Object chunkSeat=ride(cleanup,remoteDriver,remoteRider,1032.0D);
            Object provider=call(world,"getChunkProvider");
            if(multi){call(provider,"queueUnload",chunk);call(provider,"tick");}
            else{call(provider,"dropChunk",64,0);call(provider,"unloadQueuedChunks");}
            LegacyRuntime.worldTick(world);
            boolean loaded=multi?call(provider,"getLoadedChunk",64,0)!=null:Boolean.TRUE.equals(call(provider,"chunkExists",64,0));
            check("native-chunk-unload-cleans-without-reloading",!loaded&&vehicle(remoteRider)==null&&!LegacyRuntime.isSeat(chunkSeat));
            command(server,"tandemount reset");
            check("suite-complete",true);
        }catch(Throwable ex){
            System.out.println("[TandemountNative] {\"name\":\"suite-exception\",\"passed\":false}");ex.printStackTrace();
        }finally{
            cancelInteraction=cancelMount=cancelDismount=false;
            for(Object entity:cleanup){try{
                Object currentWorld=get(entity,"world|worldObj");
                // Native dimension transfer registers the player in the destination chunk manager.
                if(currentWorld!=world&&type("net.minecraft.entity.player.EntityPlayerMP").isInstance(entity))
                    call(call(server,"getPlayerList|getConfigurationManager"),"playerLoggedOut",entity);
                call(currentWorld,"removeEntity",entity);
            }catch(Throwable ignored){}}
            try{((List<?>)get(call(server,"getPlayerList|getConfigurationManager"),"playerEntityList|players")).removeAll(cleanup);}catch(Throwable ignored){}
        }
    }
}
