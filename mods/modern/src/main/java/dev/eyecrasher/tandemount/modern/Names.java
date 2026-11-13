package dev.eyecrasher.tandemount.modern;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Descriptors generated solely from the exact upstream mapping artifacts. */
public final class Names {
    public static final Properties TABLE = new Properties();
    private static final Map<String, Method> METHODS = new HashMap<String, Method>();
    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    static {
        try (InputStream stream = Names.class.getResourceAsStream("/tandemount-names.properties")) {
            if (stream == null) throw new IllegalStateException("Missing exact-version names");
            TABLE.load(stream);
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    private Names() {}
    public static Class<?> type(String key) throws Exception { return Class.forName(TABLE.getProperty("c." + key)); }
    public static boolean has(String key) { return TABLE.containsKey("m." + key); }
    public static Method method(String key) throws Exception {
        Method method = METHODS.get(key);
        if (method == null) {
            String spec = TABLE.getProperty("m." + key);
            if (spec == null) throw new NoSuchMethodException(key);
            String[] parts = spec.split("#");
            Class<?> owner = Class.forName(parts[0]);
            for (String name : parts[1].split("\\|")) {
                try { method = owner.getDeclaredMethod(name, arguments(parts[2])); break; }
                catch (NoSuchMethodException absentNamespace) { /* Exact alternate official/SRG namespace. */ }
            }
            if (method == null) throw new NoSuchMethodException(key);
            method.setAccessible(true);
            METHODS.put(key, method);
        }
        return method;
    }
    public static Object call(String key, Object target, Object... args) throws Exception {
        if (!has(key)) {
            if (key.equals("entity.level")) return field("Entity.level", target);
            if (key.equals("entity.yaw")) return field("Entity.yRot", target);
            if (key.equals("entity.rotation")) { field("Entity.yRot", target, args[0]); return null; }
        }
        return method(key).invoke(target, args);
    }
    /** Invokes only the exact-version mapping entry; false means no callable mapped member is present. */
    public static boolean callIfPresent(String key, Object target, Object... args) throws Exception {
        if (!has(key)) return false;
        try { method(key).invoke(target, args); return true; }
        catch (NoSuchMethodException absentMappedMember) { return false; }
    }
    public static boolean mount(Object player, Object vehicle) throws Exception {
        Method method = method("entity.mount");
        return Boolean.TRUE.equals(method.getParameterCount() == 1 ? method.invoke(player, vehicle) : method.invoke(player, vehicle, false));
    }
    public static Object field(String key, Object target) throws Exception { return getField(key).get(target); }
    public static void field(String key, Object target, Object value) throws Exception { getField(key).set(target, value); }
    public static boolean hasField(String key) { return TABLE.containsKey("f." + key); }
    public static Class<?> fieldType(String key) throws Exception { return getField(key).getType(); }
    private static Field getField(String key) throws Exception {
        Field field = FIELDS.get(key);
        if (field == null) {
            String[] parts = TABLE.getProperty("f." + key).split("#");
            Class<?> owner = Class.forName(parts[0]);
            for (String name : parts[1].split("\\|")) {
                try { field = owner.getDeclaredField(name); break; }
                catch (NoSuchFieldException absentNamespace) { /* Exact alternate official/SRG namespace. */ }
            }
            if (field == null) throw new NoSuchFieldException(key);
            field.setAccessible(true);
            FIELDS.put(key, field);
        }
        return field;
    }
    public static Class<?>[] arguments(String descriptor) throws Exception {
        List<Class<?>> types = new ArrayList<Class<?>>();
        int[] index = {1};
        while (descriptor.charAt(index[0]) != ')') types.add(readType(descriptor, index));
        return types.toArray(new Class<?>[types.size()]);
    }
    private static Class<?> readType(String descriptor, int[] index) throws Exception {
        char value = descriptor.charAt(index[0]++);
        switch(value) {
            case 'Z': return boolean.class; case 'B': return byte.class; case 'C': return char.class;
            case 'S': return short.class; case 'I': return int.class; case 'J': return long.class;
            case 'F': return float.class; case 'D': return double.class; case 'V': return void.class;
            case 'L': {
                int end = descriptor.indexOf(';', index[0]);
                String name = descriptor.substring(index[0], end).replace('/', '.');
                index[0] = end + 1;
                return Class.forName(name);
            }
            case '[': {
                int start = index[0] - 1;
                readType(descriptor, index);
                return Class.forName(descriptor.substring(start, index[0]).replace('/', '.'));
            }
            default: throw new IllegalArgumentException(descriptor);
        }
    }
}
