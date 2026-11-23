/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher
 * Test-only source; never included in a Tandemount distributable. */
package dev.eyecrasher.tandemount.clienttest;

import java.io.*;
import java.lang.reflect.*;
import java.net.Proxy;
import java.util.*;

/** Runs original official client packets and ticks in a normally constructed WorldClient.
 * No game class, method, world implementation, or entity type is replaced. */
public final class ClientChecks {
    private static final Properties NAMES=new Properties();
    private static boolean oldest, multi;
    private static Class<?> type(String key) throws Exception {return Class.forName(NAMES.getProperty(key));}
    private static Class<?> boxed(Class<?> c) {
        return c==long.class?Long.class:c==int.class?Integer.class:c==boolean.class?Boolean.class:c==float.class?Float.class:c==double.class?Double.class:c;
    }
    private static Object create(Class<?> type,Object...args) throws Exception {
        next:for(Constructor<?> c:type.getConstructors()) {
            Class<?>[] ps=c.getParameterTypes();if(ps.length!=args.length)continue;
            for(int i=0;i<ps.length;i++)if(args[i]!=null&&!boxed(ps[i]).isInstance(args[i]))continue next;
            return c.newInstance(args);
        }
        throw new NoSuchMethodException(type.getName()+" constructor");
    }
    private static Field field(String owner,String key) throws Exception {
        Field f=type(owner).getDeclaredField(NAMES.getProperty(owner+"."+key));f.setAccessible(true);return f;
    }
    private static Object get(Object o,String owner,String key) throws Exception {return field(owner,key).get(o);}
    private static void set(Object o,String owner,String key,Object v) throws Exception {field(owner,key).set(o,v);}
    private static Object call(Object o,String owner,String key,Object...args) throws Exception {
        next:for(Method m:type(owner).getDeclaredMethods()) {
            if(!m.getName().equals(NAMES.getProperty(owner+"."+key+"@"+descriptor(m)))||m.getParameterTypes().length!=args.length)continue;
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!boxed(m.getParameterTypes()[i]).isInstance(args[i]))continue next;
            m.setAccessible(true);return m.invoke(o,args);
        }
        throw new NoSuchMethodException(owner+"#"+key);
    }
    private static String descriptor(Class<?> type) {
        if(type.isArray())return type.getName().replace('.','/');
        if(!type.isPrimitive())return "L"+type.getName().replace('.','/')+";";
        return type==void.class?"V":type==boolean.class?"Z":type==byte.class?"B":type==char.class?"C":type==short.class?"S":type==int.class?"I":type==long.class?"J":type==float.class?"F":"D";
    }
    private static String descriptor(Method method) {
        StringBuilder result=new StringBuilder("(");
        for(Class<?> parameter:method.getParameterTypes())result.append(descriptor(parameter));
        return result.append(')').append(descriptor(method.getReturnType())).toString();
    }
    private static double[] position(Object e) throws Exception {
        return new double[]{((Number)get(e,"Entity","posX")).doubleValue(),((Number)get(e,"Entity","posY")).doubleValue(),((Number)get(e,"Entity","posZ")).doubleValue()};
    }
    private static String vector(double[] p) {return Arrays.toString(p);}
    private static void require(boolean passed,String message) {if(!passed)throw new AssertionError(message);}

    private static Object minecraft(File folder) throws Exception {
        // Minecraft's ordinary constructor establishes its native game-thread identity.
        // Its run/startGame methods, graphics, login and authentication are never invoked.
        if(oldest) {
            // A nameless local fixture profile bypasses vanilla's optional skin
            // lookup; no graphics service or external profile request is needed.
            Object session=create(type("Session"),"","00000000000000000000000000000001","fixture","legacy");
            Object properties=Class.forName("com.google.common.collect.HashMultimap").getMethod("create").invoke(null);
            return create(type("Minecraft"),session,1,1,false,false,folder,folder,folder,Proxy.NO_PROXY,"1.7.10",properties,"release");
        }
        Class<?>[] configParts=type("GameConfiguration").getConstructors()[0].getParameterTypes();
        Constructor<?> userConstructor=configParts[0].getConstructors()[0];
        Class<?>[] userParts=userConstructor.getParameterTypes();
        Object session=create(userParts[0],"NativeFixture","00000000000000000000000000000001","fixture","legacy");
        Object user=create(configParts[0],session,create(userParts[1]),create(userParts[2]),Proxy.NO_PROXY);
        Object display=create(configParts[1],1,1,false,false);
        Object folders=create(configParts[2],folder,folder,folder,"");
        Object game=multi?create(configParts[3],false,NAMES.getProperty("minecraft"),"release"):create(configParts[3],false,NAMES.getProperty("minecraft"));
        Object server=create(configParts[4],null,0);
        return create(type("Minecraft"),create(type("GameConfiguration"),user,display,folders,game,server));
    }
    private static Object[] world(File folder) throws Exception {
        Object client=minecraft(folder);
        Object handler=oldest?create(type("Handler"),client,null,null):create(type("Handler"),client,null,null,
                create(Class.forName("com.mojang.authlib.GameProfile"),new UUID(0,1),"NativeFixture"));
        Object settings=create(type("WorldSettings"),0L,type("GameType").getEnumConstants()[0],false,false,get(null,"WorldType","DEFAULT"));
        Object world=create(type("WorldClient"),handler,settings,0,type("Difficulty").getEnumConstants()[0],create(type("Profiler")));
        if(oldest)set(world,"World","isRemote",true); // Same flag set by the official 1.7.10 login handler.
        set(handler,"Handler",multi?"world":"clientWorldController",world);
        set(client,"Minecraft",multi?"world":"theWorld",world);
        // WorldClient.getEntityByID first checks Minecraft's ordinary local
        // player. Establish the same native objects as the login controller;
        // an absent local player is not a valid packet-consumer environment.
        Object local;
        if(oldest)local=create(type("ClientPlayer"),client,world,call(client,"Minecraft","getSession"),handler,create(type("StatFileWriter")));
        else if(multi)local=create(type("LocalPlayer"),client,world,handler,create(type("StatisticsManager")),create(type("RecipeBook")));
        else local=create(type("LocalPlayer"),client,world,handler,create(type("StatFileWriter")));
        set(client,"Minecraft",multi?"player":"thePlayer",local);
        require(Boolean.TRUE.equals(get(world,"World","isRemote")),"A real client-side WorldClient is required");
        return new Object[]{world,handler,local};
    }
    private static byte[] unhex(String s) {
        byte[] data=new byte[s.length()/2];for(int i=0;i<data.length;i++)data[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return data;
    }
    private static void packet(Object handler,String alias,String hex) throws Exception {
        Object buffer=Class.forName("io.netty.buffer.Unpooled").getMethod("wrappedBuffer",byte[].class).invoke(null,(Object)unhex(hex));
        Object nativeBuffer=create(type("PacketBuffer"),buffer),packet=create(type(alias));
        call(packet,alias,"readPacketData",nativeBuffer);
        call(handler,"Handler","handle"+alias,packet);
    }
    private static Object entity(Object world,int id) throws Exception {return call(world,"World","getEntityByID",id);}
    private static boolean dead(Object e) throws Exception {return Boolean.TRUE.equals(get(e,"Entity","isDead"));}
    private static void tick(Object seat) throws Exception {
        Class<?> carrier = seat.getClass();
        String alias = carrier == type("FallingBlock") ? "FallingBlock"
                : oldest ? "Slime" : carrier == type("ArmorStand") ? "ArmorStand" : "Cloud";
        call(seat, alias, "onUpdate");
    }
    private static Object vehicle(Object entity) throws Exception {return multi?call(entity,"Entity","getRidingEntity"):get(entity,"Entity","ridingEntity");}
    private static Object first(Object entity) throws Exception {
        if(!multi)return get(entity,"Entity","riddenByEntity");
        List<?> passengers=(List<?>)call(entity,"Entity","getPassengers");return passengers.isEmpty()?null:passengers.get(0);
    }
    private static void mount(Object entity,Object parent) throws Exception {
        if(multi) {
            if(parent==null)call(entity,"Entity","dismountRidingEntity");else call(entity,"Entity","startRiding",parent,true);
        }else call(entity,"Entity","mountEntity",parent);
    }
    private static Object root(Object entity) throws Exception {
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        while(vehicle(entity)!=null&&seen.add(entity))entity=vehicle(entity);
        return entity;
    }
    private static void position(Object entity,double x,double y,double z) throws Exception {call(entity,"Entity","setPosition",x,y,z);}
    private static Object player(Object world,String name,int id) throws Exception {
        Object profile=create(Class.forName("com.mojang.authlib.GameProfile"),new UUID(0,id),oldest?"":name);
        Object player=create(type("OtherPlayer"),world,profile);
        return player;
    }
    private static void updateRider(Object parent,Object rider) throws Exception {
        if(multi)call(parent,parent.getClass()==type("Horse")?"AbstractHorse":"Entity","updatePassenger",rider);
        else call(parent,parent.getClass()==type("Horse")?"Horse":"Entity","updateRiderPosition");
    }
    private static boolean driverControl(Object world,Object driver) throws Exception {
        Object horse=create(type("Horse"),world);
        String owner=multi?"AbstractHorse":"Horse";
        call(horse,owner,"setHorseTamed",true);call(horse,owner,"setHorseSaddled",true);
        position(horse,8,65,8);mount(driver,horse);
        set(driver,"LivingBase","moveForward",1.0F);set(driver,"Entity","rotationYaw",90.0F);
        double[] before=position(horse);
        for(int frame=0;frame<4;frame++) {
            if(multi)call(horse,owner,"travel",0.0F,0.0F,0.0F);
            else call(horse,owner,"moveEntityWithHeading",0.0F,0.0F);
        }
        double[] after=position(horse);
        boolean passed=vehicle(driver)==horse&&first(horse)==driver
                &&Math.abs(((Number)get(horse,"Entity","rotationYaw")).doubleValue()-90)<1e-6
                &&(!multi||Math.abs(after[0]-before[0])+Math.abs(after[2]-before[2])>1e-6);
        mount(driver,null);set(driver,"LivingBase","moveForward",0.0F);
        return passed;
    }
    private static void graph(Object world,File report) throws Exception {
        Object horse=create(type("Horse"),world),driver=player(world,"GraphDriver",1002),passenger=player(world,"GraphPassenger",1003);
        position(horse,8,65,8);
        Object block=get(null,"Blocks",oldest?"piston_extension":multi?"BARRIER":"barrier");
        Object seat=create(type("FallingBlock"),world,8D,65D,8D,oldest?block:call(block,"Block","getDefaultState"));
        mount(driver,horse);mount(seat,horse);
        boolean directPreservesDriver=vehicle(driver)==horse&&first(horse)==driver;
        position(seat,8,65.5775,7.5);updateRider(horse,seat);
        double[] direct=position(seat);boolean directRearSurvives=Math.abs(direct[2]-7.5)<.00001;
        // Older clients expose one passenger slot. A chain can retain the driver,
        // but the driver's native generic passenger update removes the rear offset.
        mount(seat,null);mount(driver,null);mount(driver,horse);mount(seat,driver);mount(passenger,seat);
        updateRider(horse,driver);position(seat,8,65.5775,7.5);updateRider(driver,seat);updateRider(seat,passenger);
        double[] chain=position(seat);boolean chainRearSurvives=Math.abs(chain[2]-7.5)<.00001;
        boolean sharedRoot=root(seat)==horse&&root(passenger)==horse&&vehicle(driver)==horse&&first(horse)==driver;
        for(int i=0;i<1200;i++) {
            if(vehicle(seat)==null)throw new AssertionError("Seat lost native vehicle before mounted tick "+i+"; dead="+dead(seat)+"; parent dead="+dead(driver));
            call(seat,"Entity","updateRidden");updateRider(seat,passenger);
            require(!dead(seat),"An attached native non-AIR falling block must survive its original mounted tick");
        }
        String result="{\"status\":\"measured\",\"minecraft\":\""+NAMES.getProperty("minecraft")+"\",\"native_mounted_ticks\":1200"
                +",\"direct_second_child_preserves_first_driver\":"+directPreservesDriver+",\"direct_second_child_keeps_requested_rear\":"+directRearSurvives
                +",\"direct_second_child_position\":"+vector(direct)+",\"driver_chain_shares_horse_root\":"+sharedRoot
                +",\"driver_chain_keeps_requested_rear\":"+chainRearSurvives+",\"driver_chain_position\":"+vector(chain)
                +",\"original_client_seat_survives_mounted_ticks\":"+!dead(seat)
                +",\"scope\":\"exact vanilla direct-child and horse-driver-fallingblock-player adapters; other native helper graphs are not excluded by this probe\""
                +",\"environment\":\"untouched-official-client; normal WorldClient, OtherPlayer, Horse and FallingBlock constructors; original native mounting and updateRidden; no graphics\"}";
        try(PrintWriter out=new PrintWriter(report)){out.println(result);}System.out.println("[TandemountClientGraph] "+result);
    }
    private static Object vector(double x,double y,double z) throws Exception {
        return oldest?call(null,"Vec3","createVectorHelper",x,y,z):create(type("Vec3"),x,y,z);
    }
    private static int rayHits(Object seat) throws Exception {
        Object box=oldest?get(seat,"Entity","boundingBox"):call(seat,"Entity","getEntityBoundingBox");
        double border=((Number)call(seat,"Entity","getCollisionBorderSize")).doubleValue();
        box=call(box,"AABB",multi?"grow":"expand",border,border,border);
        double[] pos=position(seat);int hits=0;
        for(int axis=0;axis<3;axis++) {
            double[] a=pos.clone(),b=pos.clone();a[axis]-=4;b[axis]+=4;
            if(call(box,"AABB","calculateIntercept",vector(a[0],a[1],a[2]),vector(b[0],b[1],b[2]))!=null)hits++;
        }
        return hits;
    }
    private static void carriers(Object world,File report) throws Exception {
        List<String> rows=new ArrayList<String>();
        String[] choices=oldest?new String[]{"SlimeZero","SlimeNegative"}:multi?new String[]{"Cloud"}:new String[]{"ArmorStand"};
        for(String choice:choices) {
            String alias=choice.startsWith("Slime")?"Slime":choice;
            Object seat=create(type(alias),world);position(seat,8,65,8);
            call(seat,alias.equals("ArmorStand")?alias:"Entity","setInvisible",true);
            if(alias.equals("Slime")) {
                call(seat,"Slime","setSlimeSize",choice.equals("SlimeZero")?0:-1);
                call(call(seat,"LivingBase","getEntityAttribute",get(null,"Attributes","maxHealth")),"AttributeInstance","setBaseValue",20.0D);
                call(seat,"LivingBase","setHealth",20.0F);
            }
            if(alias.equals("ArmorStand")){call(seat,alias,"setMarker",true);call(seat,alias,"setNoGravity",true);}
            if(alias.equals("Cloud"))call(seat,alias,"setRadius",0.0F);
            boolean collidable=Boolean.TRUE.equals(call(seat,alias.equals("ArmorStand")?alias:"Entity","canBeCollidedWith"));
            int beforeHits=rayHits(seat);double drift=0;
            for(int i=0;i<1200;i++) {
                double[] before=position(seat);
                if(alias.equals("Slime")) {
                    call(seat,"LivingBase","setPositionAndRotation2",8.0D,65.0D,8.0D,0.0F,0.0F,3);
                    set(seat,"Entity","motionX",0.0D);set(seat,"Entity","motionY",0.0D);set(seat,"Entity","motionZ",0.0D);
                }
                call(seat,alias,"onUpdate");
                double[] after=position(seat);for(int a=0;a<3;a++)drift=Math.max(drift,Math.abs(after[a]-before[a]));
                require(!dead(seat),choice+" original native tick lifetime");
            }
            rows.add("{\"carrier\":\""+choice+"\",\"living\":"+type("LivingBase").isInstance(seat)+",\"can_be_collided_with\":"+collidable
                    +",\"initial_ray_hits\":"+beforeHits+",\"final_ray_hits\":"+rayHits(seat)+",\"ticks\":1200,\"dead\":"+dead(seat)
                    +",\"tick_drift\":"+drift+",\"width\":"+get(seat,"Entity","width")+",\"height\":"+get(seat,"Entity","height")+"}");
        }
        String result="{\"status\":\"measured\",\"minecraft\":\""+NAMES.getProperty("minecraft")+"\",\"carriers\":["+String.join(",",rows)+"]}";
        try(PrintWriter out=new PrintWriter(report)){out.println(result);}System.out.println(result);
    }

    public static void main(String[] args) throws Exception {
        try(InputStream in=new FileInputStream(args[0])){NAMES.load(in);}
        oldest="1.7.10".equals(NAMES.getProperty("minecraft"));multi="1.12.2".equals(NAMES.getProperty("minecraft"));
        call(null,"Bootstrap","register");
        Object[] setup=world(new File(args[2]));Object world=setup[0],handler=setup[1];
        if(args[1].equals("--carriers")){carriers(world,new File(args[3]));return;}
        if(args[1].equals("--graph")){graph(world,new File(args[3]));return;}
        int seatId=Integer.parseInt(NAMES.getProperty("seat_id")),targetTicks=Integer.parseInt(NAMES.getProperty("ticks"));
        List<String[]> lines=new ArrayList<String[]>();
        try(BufferedReader reader=new BufferedReader(new FileReader(args[1]))) {
            for(String line;(line=reader.readLine())!=null;)if(!line.startsWith("#"))lines.add(line.split("\t"));
        }
        Object air=get(null,"Blocks",multi?"AIR":"air");
        Object airSeat=create(type("FallingBlock"),world,8D,65D,8D,oldest?air:call(air,"Block","getDefaultState"));
        tick(airSeat);require(dead(airSeat),"Original AIR regression must die on its first native client tick");

        boolean nativeDriverControl=driverControl(world,setup[2]);
        require(nativeDriverControl,"The original local client must retain ordinary native horse control");
        Object seat=null;int ticks=0,frames=0,packets=0;double maximumTickDrift=0,maximumSettlingDrift=0;
        double[] initial=null,minimum={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},maximum={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(String[] line:lines) {
            if(line[0].equals("tick")) {
                seat=entity(world,seatId);require(seat!=null,"Observed vanilla spawn must create a real client entity");
                double[] before=position(seat);if(initial==null)initial=before;
                tick(seat);require(!dead(seat),"Seat was removed by an original native client tick");
                double[] after=position(seat);
                for(int i=0;i<3;i++) {
                    if(ticks<24)maximumSettlingDrift=Math.max(maximumSettlingDrift,Math.abs(after[i]-before[i]));
                    else maximumTickDrift=Math.max(maximumTickDrift,Math.abs(after[i]-before[i]));
                    minimum[i]=Math.min(minimum[i],after[i]);maximum[i]=Math.max(maximum[i],after[i]);
                }
                ticks++;frames++;
            } else {
                packet(handler,line[0],line[1]);packets++;
            }
        }
        require(seat!=null&&ticks>=targetTicks+24,"Insufficient native client tick input after initial native spawn interpolation");
        String carrier;
        if (type("Slime").isInstance(seat)) {
            require(Boolean.TRUE.equals(call(seat,"Entity","isInvisible")) && ((Number)call(seat,"Slime","getSlimeSize")).intValue() == -1,
                    "The received slime must be invisible with its signed size intact");
            carrier="signed-size-slime";
        } else if (type("FallingBlock").isInstance(seat)) {
            Object barrier=get(null,"Blocks",multi?"BARRIER":"barrier");
            Object state=call(seat,"FallingBlock","getBlock");
            require(call(state,"BlockState","getBlock")==barrier,"The received falling block must retain the invisible barrier");
            carrier="barrier-falling-block";
        } else if (!oldest && type("ArmorStand").isInstance(seat)) {
            require(Boolean.TRUE.equals(call(seat,"Entity","isInvisible"))
                    && Boolean.TRUE.equals(call(seat,"ArmorStand","hasMarker")),
                    "The received armor stand must be an invisible marker");
            carrier="marker-armor-stand";
        } else carrier="area-effect-cloud";
        boolean collidable=Boolean.TRUE.equals(call(seat,carrier.equals("marker-armor-stand")?"ArmorStand":"Entity","canBeCollidedWith"));
        int hits=rayHits(seat);
        require(!collidable||hits==0,"Original vanilla picking must not select the helper");
        require(maximumTickDrift<0.000126,"Steady original native tick drift: "+maximumTickDrift+" (initial interpolation "+maximumSettlingDrift+")");
        double[] last=position(seat);
        // This is a measured transport limit, not a claim that absent packets preserve gravity cancellation.
        tick(seat);double[] unrefreshed=position(seat);double unrefreshedDrift=Math.abs(unrefreshed[1]-last[1]);
        boolean noGravity=multi&&Boolean.TRUE.equals(call(seat,"Entity","hasNoGravity"));
        String result="{\"status\":\"passed\",\"ticks\":"+ticks+",\"removed\":"+dead(seat)+",\"initial_position\":"+vector(initial)
                +",\"final_position\":"+vector(last)+",\"position_min\":"+vector(minimum)+",\"position_max\":"+vector(maximum)
                +",\"maximum_native_tick_drift\":"+maximumTickDrift+",\"one_tick_without_refresh_drift\":"+unrefreshedDrift
                +",\"spawn_settling_ticks\":24,\"maximum_spawn_settling_drift\":"+maximumSettlingDrift
                +",\"carrier\":\""+carrier+"\",\"client_can_be_collided_with\":"+collidable+",\"client_ray_intersections\":"+hits+",\"unobstructed_picking\":true"
                +",\"received_no_gravity\":"+(multi?String.valueOf(noGravity):"null")+",\"native_packets_consumed\":"+packets+",\"observed_correction_frames\":"+frames
                +",\"single_driver_client_control_baseline\":"+nativeDriverControl+",\"native_control_mode\":\""+(multi?"local-client-travel":"server-travel; client-yaw-only")+"\",\"air_regression_removed_first_tick\":true,\"environment\":\"untouched-official-client; normal WorldClient and packet handlers; original carrier onUpdate and native picking; no graphics\"}";
        try(PrintWriter out=new PrintWriter(args[3])){out.println(result);}
        System.out.println("[TandemountClient] "+result);
    }
}
