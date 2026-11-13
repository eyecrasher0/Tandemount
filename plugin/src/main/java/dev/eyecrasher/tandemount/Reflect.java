/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import java.lang.reflect.*;
import java.util.*;

final class Reflect {
    private Reflect() { }
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
