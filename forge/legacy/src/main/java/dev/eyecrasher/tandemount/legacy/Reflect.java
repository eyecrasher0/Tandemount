/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection keeps the distributable independent of obfuscated game binaries. */
public final class Reflect {
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<String, Field>();
    private Reflect() {}
    public static Class<?> type(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException ex) { throw new IllegalStateException(name, ex); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) public static Object enumValue(String type, String value) {
        return Enum.valueOf((Class)type(type),value);
    }
    private static boolean accepts(Class<?> expected, Object value) {
        if (value == null) return !expected.isPrimitive();
        if (expected.isPrimitive()) {
            if (expected == boolean.class) expected = Boolean.class;
            else if (expected == int.class) expected = Integer.class;
            else if (expected == long.class) expected = Long.class;
            else if (expected == double.class) expected = Double.class;
            else if (expected == float.class) expected = Float.class;
            else if (expected == byte.class) expected = Byte.class;
            else if (expected == short.class) expected = Short.class;
        }
        return expected.isInstance(value);
    }
    private static String key(Class<?> type, String names, Object[] args) {
        StringBuilder b = new StringBuilder(type.getName()).append('#').append(names);
        for (Object a : args) b.append(':').append(a == null ? "null" : a.getClass().getName());
        return b.toString();
    }
    public static Object call(Object target, String names, Object... args) {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        String key = key(type, names, args);
        Method m = METHODS.get(key);
        if (m == null) {
            Set<String> alternatives = new HashSet<String>(Arrays.asList(names.split("\\|")));
            outer: for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                candidateLoop: for (Method candidate : c.getDeclaredMethods()) {
                    if (!alternatives.contains(Names.canonical(candidate.getName()))) continue;
                    Class<?>[] p = candidate.getParameterTypes();
                    if (p.length != args.length) continue;
                    for (int i = 0; i < p.length; i++) if (!accepts(p[i], args[i])) continue candidateLoop;
                    candidate.setAccessible(true); m = candidate; break outer;
                }
            }
            if (m == null) throw new IllegalStateException("Missing method " + key);
            METHODS.put(key, m);
        }
        try { return m.invoke(target instanceof Class ? null : target, args); }
        catch (InvocationTargetException ex) { throw new IllegalStateException(key, ex.getCause()); }
        catch (ReflectiveOperationException ex) { throw new IllegalStateException(key, ex); }
    }
    public static Object maybe(Object target, String names, Object... args) {
        try { return call(target, names, args); }
        catch (IllegalStateException ex) { if (ex.getMessage().startsWith("Missing method ")) return null; throw ex; }
    }
    private static Field field(Class<?> type, String name) {
        String key = type.getName() + '#' + name;
        Field f = FIELDS.get(key);
        if (f != null) return f;
        Set<String> alternatives = new HashSet<String>(Arrays.asList(name.split("\\|")));
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field candidate : c.getDeclaredFields()) {
                if (alternatives.contains(Names.canonical(candidate.getName()))) {
                    candidate.setAccessible(true); FIELDS.put(key, candidate); return candidate;
                }
            }
        }
        throw new IllegalStateException("Missing field " + key);
    }
    public static Object get(Object target, String name) {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        try { return field(type, name).get(target instanceof Class ? null : target); }
        catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
    }
    public static void set(Object target, String name, Object value) {
        try { field(target.getClass(), name).set(target, value); }
        catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
    }
    public static double number(Object target, String name) { return ((Number) get(target, name)).doubleValue(); }
    public static boolean bool(Object target, String name) { return Boolean.TRUE.equals(get(target, name)); }
    public static Object create(String className, Object... args) {
        for (Constructor<?> c : type(className).getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < p.length; i++) if (!accepts(p[i], args[i])) ok = false;
            if (!ok) continue;
            try { c.setAccessible(true); return c.newInstance(args); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(className, ex); }
        }
        throw new IllegalStateException("Missing constructor " + className);
    }
}
