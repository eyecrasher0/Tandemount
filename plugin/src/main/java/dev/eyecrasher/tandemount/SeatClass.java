/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Minimal, original class-file writer; no compiler, agents, Unsafe, or registry edits. */
final class SeatClass extends ClassLoader {
    private final List<byte[]> pool = new ArrayList<byte[]>();
    private final Map<String, Integer> constants = new HashMap<String, Integer>();
    private final List<byte[]> methods = new ArrayList<byte[]>();
    private final String owner, parent;
    private final List<String> interfaces = new ArrayList<String>();
    private int codeName;

    private SeatClass(Class<?> base) { this(base, "dev/eyecrasher/tandemount/runtime/Seat"); }
    private SeatClass(Class<?> base, String name) {
        super(NativeSeat.class.getClassLoader());
        owner = name;
        parent = base.getName().replace('.', '/');
    }

    static Class<?> vehicleWrapper(Class<?> craftSeat) throws IOException, NoSuchMethodException {
        SeatClass writer = new SeatClass(craftSeat, "dev/eyecrasher/tandemount/runtime/LegacyVehicle");
        Constructor<?> constructor = craftSeat.getConstructors()[0];
        writer.constructor(constructor);
        writer.interfaces.add("org/bukkit/entity/Vehicle");
        byte[] bytes = writer.bytes();
        return writer.defineClass(writer.owner.replace('/', '.'), bytes, 0, bytes.length);
    }

    static Class<?> create(Class<?> base, Constructor<?> constructor, Method position, boolean oldest)
            throws IOException {
        SeatClass writer = new SeatClass(base);
        writer.constructor(constructor);
        writer.position(position);
        // Vanilla save entry points and damage entry points are identified by their
        // actual upstream signatures. A seat never serializes and never drops loot.
        Set<String> seen = new HashSet<String>();
        for (Method m : Reflect.methods(base)) {
            int modifiers = m.getModifiers();
            if (Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)
                    || Modifier.isPrivate(modifiers) || m.getReturnType() != boolean.class) continue;
            Class<?>[] args = m.getParameterTypes();
            boolean save = args.length >= 1 && (args[0].getSimpleName().equals("NBTTagCompound")
                    || args[0].getSimpleName().equals("CompoundTag")
                    || args[0].getSimpleName().equals("ValueOutput"))
                    && (m.getName().startsWith("save") || m.getName().equals("c") || m.getName().equals("d")
                        || m.getName().equals("e") || m.getName().equals("f"));
            boolean damage = false;
            for (Class<?> arg : args) if (arg.getSimpleName().equals("DamageSource")) damage = true;
            if ((save || damage) && seen.add(m.getName() + descriptor(args, boolean.class))) writer.constantFalse(m);
        }
        boolean tick = false;
        for (String name : new String[] {"tick", "B_", "l", "h"}) {
            try { Method m = base.getMethod(name); if (m.getReturnType() == void.class) { writer.tick(m); tick = true; break; } }
            catch (NoSuchMethodException absent) { /* Next upstream mapping family. */ }
        }
        if (!tick) throw new IOException("Native seat tick entry point unavailable");
        try { writer.tick(base.getMethod("inactiveTick")); } catch (NoSuchMethodException absent) { /* Vanilla Spigot. */ }
        byte[] bytes = writer.bytes();
        return writer.defineClass(writer.owner.replace('/', '.'), bytes, 0, bytes.length);
    }

    private void constructor(Constructor<?> c) throws IOException {
        Class<?>[] args = c.getParameterTypes();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream code = new DataOutputStream(raw);
        code.writeByte(0x2a); // aload_0
        int slot = 1, stack = 1;
        for (Class<?> arg : args) {
            load(code, arg, slot);
            int width = arg == double.class || arg == long.class ? 2 : 1;
            slot += width; stack += width;
        }
        code.writeByte(0xb7); code.writeShort(method(parent, "<init>", descriptor(args, void.class)));
        code.writeByte(0xb1);
        add("<init>", descriptor(args, void.class), raw.toByteArray(), stack, slot);
    }

    private void position(Method m) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream code = new DataOutputStream(raw);
        code.writeByte(0x2a);
        code.writeByte(0x27); // dload_1
        code.writeByte(0x29); // dload_3
        code.writeByte(0x18); code.writeByte(5);
        code.writeByte(0xb8); code.writeShort(method("dev/eyecrasher/tandemount/NativeSeat", "position", "(Ljava/lang/Object;DDD)[D"));
        code.writeByte(0x3a); code.writeByte(7); // astore 7
        code.writeByte(0x2a);
        for (int i = 0; i < 3; i++) {
            code.writeByte(0x19); code.writeByte(7);
            code.writeByte(0x03 + i); code.writeByte(0x31); // iconst_i; daload
        }
        code.writeByte(0xb7); code.writeShort(method(parent, m.getName(), "(DDD)V"));
        code.writeByte(0x2a); code.writeByte(0xb8);
        code.writeShort(method("dev/eyecrasher/tandemount/NativeSeat", "shape", "(Ljava/lang/Object;)V"));
        code.writeByte(0xb1);
        add(m.getName(), "(DDD)V", raw.toByteArray(), 7, 8);
    }

    private void constantFalse(Method m) throws IOException {
        int slots = 1;
        for (Class<?> arg : m.getParameterTypes()) slots += arg == double.class || arg == long.class ? 2 : 1;
        add(m.getName(), descriptor(m.getParameterTypes(), boolean.class), new byte[] {0x03, (byte)0xac}, 1, slots);
    }
    private void tick(Method m) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(); DataOutputStream code = new DataOutputStream(raw);
        code.writeByte(0x2a); code.writeByte(0xb8);
        code.writeShort(method("dev/eyecrasher/tandemount/NativeSeat", "tick", "(Ljava/lang/Object;)V"));
        code.writeByte(0xb1);
        add(m.getName(), "()V", raw.toByteArray(), 1, 1);
    }
    private void add(String name, String descriptor, byte[] code, int stack, int locals) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeShort(0x0001); out.writeShort(utf(name)); out.writeShort(utf(descriptor)); out.writeShort(1);
        if (codeName == 0) codeName = utf("Code");
        out.writeShort(codeName); out.writeInt(12 + code.length);
        out.writeShort(stack); out.writeShort(locals); out.writeInt(code.length); out.write(code);
        out.writeShort(0); out.writeShort(0);
        methods.add(raw.toByteArray());
    }
    private byte[] bytes() throws IOException {
        int thisClass = clazz(owner), superClass = clazz(parent);
        int[] interfaceClasses = new int[interfaces.size()];
        for (int i = 0; i < interfaces.size(); i++) interfaceClasses[i] = clazz(interfaces.get(i));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeInt(0xcafebabe); out.writeShort(0); out.writeShort(49);
        out.writeShort(pool.size() + 1);
        for (byte[] constant : pool) out.write(constant);
        out.writeShort(0x0031); out.writeShort(thisClass); out.writeShort(superClass);
        out.writeShort(interfaceClasses.length);
        for (int iface : interfaceClasses) out.writeShort(iface);
        out.writeShort(0); out.writeShort(methods.size());
        for (byte[] method : methods) out.write(method);
        out.writeShort(0);
        return raw.toByteArray();
    }
    private int utf(String s) throws IOException {
        String key = "U" + s;
        if (constants.containsKey(key)) return constants.get(key);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw); out.writeByte(1); out.writeUTF(s);
        pool.add(raw.toByteArray()); int index = pool.size(); constants.put(key, index); return index;
    }
    private int clazz(String name) throws IOException {
        String key = "C" + name;
        if (constants.containsKey(key)) return constants.get(key);
        int text = utf(name);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(7); out.writeShort(text); pool.add(raw.toByteArray());
        int index = pool.size(); constants.put(key, index); return index;
    }
    private int method(String type, String name, String desc) throws IOException {
        String key = "M" + type + name + desc;
        if (constants.containsKey(key)) return constants.get(key);
        int cls = clazz(type), n = utf(name), d = utf(desc);
        ByteArrayOutputStream pair = new ByteArrayOutputStream(); DataOutputStream pairOut = new DataOutputStream(pair);
        pairOut.writeByte(12); pairOut.writeShort(n); pairOut.writeShort(d); pool.add(pair.toByteArray()); int ndx = pool.size();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10); out.writeShort(cls); out.writeShort(ndx); pool.add(raw.toByteArray());
        int index = pool.size(); constants.put(key, index); return index;
    }
    private static void load(DataOutputStream out, Class<?> type, int index) throws IOException {
        int instruction = !type.isPrimitive() ? 0x19 : type == double.class ? 0x18
                : type == long.class ? 0x16 : type == float.class ? 0x17 : 0x15;
        out.writeByte(instruction); out.writeByte(index);
    }
    static String descriptor(Class<?>[] args, Class<?> result) {
        StringBuilder value = new StringBuilder("(");
        for (Class<?> arg : args) value.append(descriptor(arg));
        return value.append(')').append(descriptor(result)).toString();
    }
    private static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == int.class) return "I";
        if (type == double.class) return "D";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == byte.class) return "B";
        if (type == short.class) return "S";
        return "C";
    }
}
