/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount.clienttest;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Untouched original-client metadata components; not a client tick or visual test. */
public final class InteractionPoseProbe {
    static final Properties names = new Properties();
    static Class<?> type(String key) throws Exception { return Class.forName(names.getProperty("c." + key)); }
    static Object field(String key, Object receiver) throws Exception {
        String[] value = names.getProperty("f." + key).split("#");
        Field field = Class.forName(value[0]).getDeclaredField(value[1]); field.setAccessible(true); return field.get(receiver);
    }
    static Method method(String key) throws Exception {
        String[] value = names.getProperty("m." + key).split("#");
        for (Method method : Class.forName(value[0]).getDeclaredMethods())
            if (method.getName().equals(value[1]) && descriptor(method).equals(value[2])) { method.setAccessible(true); return method; }
        throw new NoSuchMethodException(key);
    }
    static String descriptor(Method method) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) out.append(descriptor(parameter));
        return out.append(')').append(descriptor(method.getReturnType())).toString();
    }
    static String descriptor(Class<?> type) {
        if (!type.isPrimitive()) return type.isArray() ? type.getName().replace('.','/') : "L"+type.getName().replace('.','/')+";";
        if (type==void.class) return "V"; if (type==boolean.class) return "Z"; if (type==byte.class) return "B"; if (type==short.class) return "S";
        if (type==int.class) return "I"; if (type==long.class) return "J"; if (type==float.class) return "F"; if (type==double.class) return "D"; return "C";
    }
    static Object call(String key, Object receiver, Object... args) throws Exception { return method(key).invoke(receiver,args); }
    static Object interaction(Object level) throws Exception {
        return type("Interaction").getConstructor(type("EntityType"),type("Level")).newInstance(field("EntityType.INTERACTION",null),level);
    }
    static Object clientFlagLevel() throws Exception {
        // This world shell is used only to satisfy refreshDimensions' client
        // flag branch. No level method, tick, chunk, rendering, or game loop is
        // claimed by this component probe.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field instance=unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        Object level=unsafe.getMethod("allocateInstance",Class.class).invoke(instance.get(null),type("ClientLevel"));
        String[] value=names.getProperty("f.Level.isClientSide").split("#");
        Field client=Class.forName(value[0]).getDeclaredField(value[1]);client.setAccessible(true);client.setBoolean(level,true);
        return level;
    }
    static double cachedHeight(Object entity) throws Exception { return ((Number)call("entity.bbHeight",entity)).doubleValue(); }
    static Object point(Object entity, Object rider) throws Exception {
        return names.containsKey("m.entity.passengerPoint") ? call("entity.passengerPoint",entity,rider) : call("entity.passengerHeight",entity);
    }
    public static void main(String[] args) throws Exception {
        try (var stream=Files.newInputStream(Path.of(args[0]))) { names.load(stream); }
        call("shared.detect",null); call("bootstrap",null);
        Object level=clientFlagLevel(), rider=interaction(level), pose=field("Pose.SITTING",null);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(float height:new float[]{1,0,-64,2}) {
            Object source=interaction(level);
            call("interaction.width",source,0F);call("interaction.height",source,height);
            double before=cachedHeight(source);
            call("entity.pose",source,pose);
            double after=cachedHeight(source);
            Object sourceData=call("entity.data",source);
            Object values=call("data.nonDefault",sourceData);
            Object target=interaction(level), targetData=call("entity.data",target);
            call("data.assign",targetData,values);
            double snapshot=cachedHeight(target);
            Object poseValue=call("value.create",null,field("Entity.DATA_POSE",null),pose);
            call("data.assign",targetData,Collections.singletonList(poseValue));
            Map<String,Object> row=new LinkedHashMap<>();row.put("height",height);row.put("beforePose",before);row.put("afterNormalSetPose",after);
            row.put("snapshotCachedHeight",snapshot);row.put("afterSamePoseDataValue",cachedHeight(target));row.put("passengerPoint",point(target,rider).toString());
            rows.add(row);
        }
        boolean passed=rows.stream().allMatch(row-> Math.abs(((Number)row.get("height")).doubleValue()-((Number)row.get("afterNormalSetPose")).doubleValue())<1e-6
                && Math.abs(((Number)row.get("height")).doubleValue()-((Number)row.get("afterSamePoseDataValue")).doubleValue())<1e-6);
        Map<String,Object> result=new LinkedHashMap<>();result.put("kind","original-client-metadata-component");result.put("world","uninitialized client level shell; metadata and passenger components only");
        result.put("manualRefresh",false);result.put("nativeTicks",0);result.put("humanVisual",false);result.put("passed",passed);result.put("rows",rows);
        Object gson=Class.forName("com.google.gson.Gson").getConstructor().newInstance();String json=(String)gson.getClass().getMethod("toJson",Object.class).invoke(gson,result);
        Files.writeString(Path.of(args[1]),json+"\n");System.out.println("POSE_COMPONENT_RESULT "+json);
        if(!passed)throw new AssertionError("Normal pose metadata did not refresh native client attachment dimensions");
    }
}
