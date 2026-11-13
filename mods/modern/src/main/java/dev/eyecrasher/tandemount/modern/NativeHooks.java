package dev.eyecrasher.tandemount.modern;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Narrow native hooks. Names and full descriptors are exact-version inputs. */
public final class NativeHooks implements IMixinConfigPlugin, Opcodes {
    private static final String RUNTIME = "dev/eyecrasher/tandemount/modern/TandemRuntime";
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    public void acceptTargets(Set<String> mine, Set<String> others) {}
    public List<String> getMixins() { return Collections.emptyList(); }
    public void postApply(String targetClassName, ClassNode node, String mixinClassName, IMixinInfo info) {}
    public void preApply(String targetClassName, ClassNode node, String mixinClassName, IMixinInfo info) {
        for (String key : Names.TABLE.stringPropertyNames()) {
            if (!key.startsWith("h.")) continue;
            String[] spec = Names.TABLE.getProperty(key).split("#");
            if (!spec[0].equals(targetClassName)) continue;
            MethodNode found = null;
            for (MethodNode method : node.methods) {
                for (String name : spec[1].split("\\|")) if (method.name.equals(name) && method.desc.equals(spec[2])) { found = method; break; }
                if (found != null) break;
            }
            if (found == null) throw new IllegalStateException("Tandemount exact upstream hook is absent: " + key + " " + Names.TABLE.getProperty("minecraft"));
            patch(found, key.substring(2));
        }
    }
    private static MethodInsnNode call(String method, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, RUNTIME, method, descriptor, false);
    }
    private static void patch(MethodNode method, String kind) {
        if (kind.equals("save") || kind.equals("savePassenger") || kind.equals("shouldSave") || kind.equals("hurt")) {
            InsnList guard = new InsnList();
            LabelNode ordinary = new LabelNode();
            guard.add(new VarInsnNode(ALOAD, 0));
            guard.add(call("isSeat", "(Ljava/lang/Object;)Z"));
            guard.add(new JumpInsnNode(IFEQ, ordinary));
            guard.add(new InsnNode(ICONST_0));
            guard.add(new InsnNode(IRETURN));
            guard.add(ordinary);
            guard.add(new FrameNode(F_SAME, 0, null, 0, null));
            method.instructions.insert(guard);
        } else if (kind.equals("stop")) {
            InsnList code = new InsnList();
            code.add(new VarInsnNode(ALOAD, 0));
            code.add(call("stop", "(Ljava/lang/Object;)V"));
            method.instructions.insert(code);
        } else {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (kind.equals("horse") && instruction.getOpcode() == ARETURN) {
                    int local = method.maxLocals++;
                    InsnList code = new InsnList();
                    code.add(new VarInsnNode(ASTORE, local));
                    code.add(new VarInsnNode(ALOAD, 0));
                    code.add(new VarInsnNode(ALOAD, 1));
                    code.add(new VarInsnNode(ALOAD, 2));
                    code.add(new VarInsnNode(ALOAD, local));
                    code.add(call("interact", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                    code.add(new TypeInsnNode(CHECKCAST, Type.getReturnType(method.desc).getInternalName()));
                    method.instructions.insertBefore(instruction, code);
                } else if (instruction.getOpcode() == RETURN) {
                    InsnList code = new InsnList();
                    if (kind.equals("horseRide")) {
                        code.add(new VarInsnNode(ALOAD, 0));
                        code.add(new VarInsnNode(ALOAD, 1));
                        code.add(call("vanillaRideAttempt", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    } else if (kind.equals("entityTick")) {
                        code.add(new VarInsnNode(ALOAD, 1));
                        code.add(call("entityTick", "(Ljava/lang/Object;)V"));
                    } else if (kind.equals("tick")) {
                        code.add(new VarInsnNode(ALOAD, 0));
                        code.add(call("tick", "(Ljava/lang/Object;)V"));
                    } else if (kind.equals("tracker")) {
                        code.add(new VarInsnNode(ALOAD, 0));
                        int slot = 1;
                        Type entity = Type.getObjectType(Names.TABLE.getProperty("c.Entity").replace('.', '/'));
                        for (Type arg : Type.getArgumentTypes(method.desc)) {
                            if (arg.equals(entity)) { code.add(new VarInsnNode(ALOAD, slot)); break; }
                            slot += arg.getSize();
                        }
                        code.add(call("tracker", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    }
                    method.instructions.insertBefore(instruction, code);
                }
            }
        }
        method.maxStack += 8;
    }
}
