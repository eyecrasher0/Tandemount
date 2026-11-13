/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy.core;

import dev.eyecrasher.tandemount.legacy.Names;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Narrow server hooks. No native class is replaced and no entity type is registered. */
public final class SeatTransformer implements IClassTransformer, Opcodes {
    private static final String HOOK = "dev/eyecrasher/tandemount/legacy/LegacyRuntime";
    private static final Set<String> SEAT_CLASSES = new HashSet<String>(Arrays.asList(
            "net.minecraft.entity.Entity", "net.minecraft.entity.EntityLivingBase", "net.minecraft.entity.EntityLiving",
            "net.minecraft.entity.EntityCreature", "net.minecraft.entity.monster.EntityMob",
            "net.minecraft.entity.monster.EntitySilverfish", "net.minecraft.entity.item.EntityArmorStand",
            "net.minecraft.entity.item.EntityFallingBlock"));
    private static InsnList predicate(String method, boolean two) {
        InsnList p=new InsnList(); p.add(new VarInsnNode(ALOAD,0));
        if (two) p.add(new VarInsnNode(ALOAD,1));
        p.add(new MethodInsnNode(INVOKESTATIC,HOOK,method,two?"(Ljava/lang/Object;Ljava/lang/Object;)Z":"(Ljava/lang/Object;)Z",false));
        return p;
    }
    private static void guard(MethodNode m, String predicate, boolean two, boolean result) {
        InsnList p=predicate(predicate,two); LabelNode normal=new LabelNode();
        p.add(new JumpInsnNode(IFEQ,normal));
        if (result) p.add(new InsnNode(ICONST_0));
        p.add(new InsnNode(result?IRETURN:RETURN)); p.add(normal); p.add(new FrameNode(F_SAME,0,null,0,null)); m.instructions.insert(p);
    }
    @Override public byte[] transform(String name,String transformedName,byte[] bytes) {
        if (bytes==null) return null;
        boolean horse=transformedName.equals("net.minecraft.entity.passive.EntityHorse");
        boolean world=transformedName.equals("net.minecraft.world.World") || transformedName.equals("net.minecraft.world.WorldServer");
        boolean player=transformedName.equals("net.minecraft.entity.player.EntityPlayerMP");
        boolean login=transformedName.equals("cpw.mods.fml.common.network.internal.FMLNetworkHandler") && Names.minecraft().equals("1.7.10");
        if (!horse && !world && !player && !login && !SEAT_CLASSES.contains(transformedName)) return bytes;
        ClassNode c=new ClassNode(); new ClassReader(bytes).accept(c,0); boolean changed=false;
        for (MethodNode m : c.methods) {
            String n=Names.canonical(m.name);
            if (n.equals("setPositionAndUpdate") && m.desc.equals("(DDD)V")) {
                InsnList p=new InsnList();p.add(new VarInsnNode(ALOAD,0));p.add(new MethodInsnNode(INVOKESTATIC,HOOK,"entityTeleport","(Ljava/lang/Object;)V",false));
                m.instructions.insert(p);changed=true;
            }
            if (n.equals("setWorld") && Type.getArgumentTypes(m.desc).length==1 && Type.getArgumentTypes(m.desc)[0].getSort()==Type.OBJECT) {
                InsnList p=new InsnList();p.add(new VarInsnNode(ALOAD,0));p.add(new VarInsnNode(ALOAD,1));p.add(new MethodInsnNode(INVOKESTATIC,HOOK,"entityWorldChange","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                m.instructions.insert(p);changed=true;
            }
            if (login && n.equals("fmlServerHandshake") && Type.getArgumentTypes(m.desc).length==3) {
                InsnList p=new InsnList();p.add(new VarInsnNode(ALOAD,0));p.add(new VarInsnNode(ALOAD,1));p.add(new VarInsnNode(ALOAD,2));
                p.add(new MethodInsnNode(INVOKESTATIC,HOOK,"legacyVanillaLogin","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",false));
                LabelNode normal=new LabelNode();p.add(new JumpInsnNode(IFEQ,normal));p.add(new InsnNode(RETURN));p.add(normal);p.add(new FrameNode(F_SAME,0,null,0,null));
                m.instructions.insert(p);changed=true;
            }
            if (Names.minecraft().equals("1.8.9") && transformedName.equals("net.minecraft.entity.EntityLivingBase")
                    && (n.equals("mountEntity") || n.equals("dismountEntity"))) {
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call=(MethodInsnNode)insn;
                        if (call.owner.equals("net/minecraftforge/event/ForgeEventFactory") && call.name.equals("canMountEntity")) {
                            call.owner=HOOK;call.name="legacyMountPermission";call.desc="(Ljava/lang/Object;Ljava/lang/Object;Z)Z";changed=true;
                        }
                    }
                    if (n.equals("mountEntity") && insn.getOpcode()==RETURN) {
                        InsnList p=new InsnList();p.add(new VarInsnNode(ALOAD,0));p.add(new MethodInsnNode(INVOKESTATIC,HOOK,"finishLegacyMount","(Ljava/lang/Object;)V",false));
                        m.instructions.insertBefore(insn,p);changed=true;
                    }
                }
            }
            if (horse && (n.equals("interact") || n.equals("processInteract")) && Type.getReturnType(m.desc).getSort()==Type.BOOLEAN
                    && Type.getArgumentTypes(m.desc).length>=1) {
                InsnList p=predicate("interact",true); LabelNode normal=new LabelNode(); p.add(new JumpInsnNode(IFEQ,normal));
                p.add(new InsnNode(ICONST_1));p.add(new InsnNode(IRETURN));p.add(normal);p.add(new FrameNode(F_SAME,0,null,0,null));m.instructions.insert(p);changed=true;
            }
            if (world && n.equals("updateEntities") && m.desc.equals("()V")) {
                for (AbstractInsnNode insn : m.instructions.toArray()) if (insn.getOpcode()==RETURN) {
                    InsnList p=new InsnList();p.add(new VarInsnNode(ALOAD,0));p.add(new MethodInsnNode(INVOKESTATIC,HOOK,"worldTick","(Ljava/lang/Object;)V",false));
                    m.instructions.insertBefore(insn,p);
                }
                changed=true;
            }
            if (!SEAT_CLASSES.contains(transformedName)) continue;
            if ((n.equals("writeToNBTOptional") || n.equals("writeMountToNBT") || n.equals("writeToNBTAtomically") || n.equals("attackEntityFrom"))
                    && Type.getReturnType(m.desc).getSort()==Type.BOOLEAN) {
                guard(m,"isSeat",false,true);changed=true;
            } else if (n.equals("onUpdate") && m.desc.equals("()V")) {
                guard(m,"isSeat",false,false);changed=true;
            } else if (n.equals("applyEntityCollision") && Type.getReturnType(m.desc).getSort()==Type.VOID) {
                guard(m,"collision",true,false);changed=true;
            } else if ((n.equals("interactFirst") || n.equals("processInitialInteract") || n.equals("processInteract") || n.equals("interactAt"))
                    && Type.getReturnType(m.desc).getSort()==Type.BOOLEAN) {
                guard(m,"isSeat",false,true);changed=true;
            }
        }
        if (!changed) return bytes;
        // Each injection leaves the original operand stack unchanged; preserve existing frames.
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);c.accept(out);return out.toByteArray();
    }
}
