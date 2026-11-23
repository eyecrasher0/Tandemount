/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import java.io.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

/** Fixture-only native packet factories and codecs from the loaded exact SDK. */
final class NativeFixtureProtocol {
    private final Class<?> entity;
    private final Object context;
    private final String legacy;
    NativeFixtureProtocol(Class<?> entity, Object context) {
        this.entity=entity;this.context=context;legacy=entity.getPackage().getName();
    }
    Class<?> gameClass(String modern,String mapped) throws ClassNotFoundException {
        for(String name:new String[]{"net.minecraft.network.protocol.game."+modern,"net.minecraft.network.protocol.game."+mapped,legacy+"."+mapped})
            try{return Class.forName(name);}catch(ClassNotFoundException absent){/* Next audited mapping family. */}
        throw new ClassNotFoundException(modern);
    }
    Properties export(File directory,Object horse) throws ReflectiveOperationException,IOException {
        Properties properties=new Properties();Class<?> packet=gameClass("ServerboundInteractPacket","PacketPlayInUseEntity");
        properties.setProperty("interact.class",packet.getSimpleName());
        for(int hand=0;hand<2;hand++)for(boolean at:new boolean[]{false,true}) {
            Object value=interaction(packet,horse,hand,at);
            if(value!=null)properties.setProperty("interact."+hand+"."+(at?"at":"plain"),Base64.getEncoder().encodeToString(encode(value)));
        }
        String[] commands={"tandemount","tandemount vertical +","tandemount vertical -","tandemount rearward +","tandemount rearward -"};
        for(int i=0;i<commands.length;i++) {
            Object value=command(commands[i]);
            properties.setProperty("command.class",value.getClass().getSimpleName());
            properties.setProperty("command."+i+".text","/"+commands[i]);
            properties.setProperty("command."+i+".body",Base64.getEncoder().encodeToString(encode(value)));
        }
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(directory,"wire-packets.properties")),"UTF-8")){properties.store(writer,"Exact loaded upstream native factories and packet encoders");}
        return properties;
    }
    private Object interaction(Class<?> packet,Object horse,int hand,boolean at) throws ReflectiveOperationException {
        for(Constructor<?> constructor:packet.getDeclaredConstructors()) {
            Class<?>[] p=constructor.getParameterTypes();
            if(p.length==4&&p[0]==int.class&&p[1].isEnum()&&p[2].getSimpleName().equals("Vec3")&&p[3]==boolean.class) {
                if(!at)return null;
                return constructor.newInstance(entityId(horse),p[1].getEnumConstants()[hand],p[2].getConstructor(double.class,double.class,double.class).newInstance(0.0,0.8,0.0),false);
            }
        }
        for(Method factory:packet.getDeclaredMethods()) {
            Class<?>[] p=factory.getParameterTypes();
            if(!Modifier.isStatic(factory.getModifiers())||factory.getReturnType()!=packet||p.length!=(at?4:3)||p[0]!=entity||p[1]!=boolean.class||!p[2].isEnum())continue;
            Object[] args=at?new Object[]{horse,false,p[2].getEnumConstants()[hand],p[3].getConstructor(double.class,double.class,double.class).newInstance(0.0,0.8,0.0)}:new Object[]{horse,false,p[2].getEnumConstants()[hand]};
            return invoke(factory,null,args);
        }
        if(hand==1&&legacy.contains("v1_7_"))return null;
        Object value=packet.getConstructor().newInstance();boolean action=false;
        for(Field field:packet.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true);Class<?> type=field.getType();
            if(type==int.class)field.setInt(value,entityId(horse));
            else if(type.isEnum()&&type.getSimpleName().contains("EntityUseAction")) {
                Object chosen=null;for(Object candidate:type.getEnumConstants())if(candidate.toString().equals(at?"INTERACT_AT":"INTERACT"))chosen=candidate;
                if(chosen==null)return null;field.set(value,chosen);action=true;
            } else if(type.isEnum()&&type.getSimpleName().equals("EnumHand"))field.set(value,type.getEnumConstants()[hand]);
            else if(type.getSimpleName().equals("Vec3D"))field.set(value,type.getConstructor(double.class,double.class,double.class).newInstance(0.0,0.8,0.0));
        }
        if(!action)throw new NoSuchFieldException("Native interaction action");return value;
    }
    private int entityId(Object handle) throws ReflectiveOperationException {
        return ((org.bukkit.entity.Entity)call(handle,"getBukkitEntity")).getEntityId();
    }
    private Object command(String text) throws ReflectiveOperationException {
        Class<?> packet;
        try{packet=gameClass("ServerboundChatCommandPacket","ServerboundChatCommandPacket");}
        catch(ClassNotFoundException older){return gameClass("ServerboundChatPacket","PacketPlayInChat").getConstructor(String.class).newInstance("/"+text);}
        for(Constructor<?> constructor:packet.getDeclaredConstructors()) {
            Class<?>[] p=constructor.getParameterTypes();if(p.length==0||p[0]!=String.class)continue;
            Object[] values=new Object[p.length];values[0]=text;for(int i=1;i<p.length;i++)values[i]=empty(p[i],0);
            constructor.setAccessible(true);return constructor.newInstance(values);
        }
        throw new NoSuchMethodException("Native command packet constructor");
    }
    private Object empty(Class<?> type,int depth) throws ReflectiveOperationException {
        if(type==boolean.class)return false;if(type==long.class)return 0L;if(type==int.class)return 0;
        if(type==Instant.class)return Instant.now();if(type==BitSet.class)return new BitSet();
        if(type==Map.class)return Collections.emptyMap();
        if(type==Optional.class)return Optional.empty();
        if(List.class.isAssignableFrom(type))return Collections.emptyList();
        for(Field field:type.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&field.getType()==type){field.setAccessible(true);Object value=field.get(null);if(value!=null)return value;}
        if(depth>=3)throw new NoSuchMethodException("Native empty command argument "+type);
        for(Constructor<?> constructor:type.getDeclaredConstructors()) {
            Class<?>[] p=constructor.getParameterTypes();boolean unsupported=false;
            for(Class<?> item:p)if(item.getSimpleName().contains("ByteBuf")||item.getSimpleName().equals("PacketDataSerializer")||item==type)unsupported=true;
            if(unsupported)continue;
            Object[] values=new Object[p.length];for(int i=0;i<p.length;i++)values[i]=empty(p[i],depth+1);
            constructor.setAccessible(true);return constructor.newInstance(values);
        }
        throw new NoSuchMethodException("Native empty command argument "+type);
    }
    byte[] encode(Object packet) throws ReflectiveOperationException {
        Object codec=codec(packet.getClass());Class<?> serializer=null;Method writer=null;
        for(Method method:packet.getClass().getDeclaredMethods()) {
            Class<?>[] p=method.getParameterTypes();
            if(p.length==1&&method.getReturnType()==void.class&&(p[0].getSimpleName().equals("PacketDataSerializer")||p[0].getSimpleName().endsWith("ByteBuf"))) {
                if(writer==null||method.getName().equals("b")||method.getName().equals("write")){writer=method;serializer=p[0];}
            }
        }
        if(serializer==null)for(String name:new String[]{"net.minecraft.network.RegistryFriendlyByteBuf","net.minecraft.network.FriendlyByteBuf","net.minecraft.network.PacketDataSerializer",legacy+".PacketDataSerializer"})try{serializer=Class.forName(name);break;}catch(ClassNotFoundException absent){/* Next SDK. */}
        Buffer buffer=buffer(serializer,new byte[0]);
        try {
            if(codec==null){if(writer==null)throw new NoSuchMethodException("Native packet encoder");invoke(writer,packet,buffer.wrapped);}
            else call(codec,"encode",buffer.wrapped,packet);
            byte[] bytes=new byte[((Number)call(buffer.raw,"readableBytes")).intValue()];call(buffer.raw,"readBytes",(Object)bytes);return bytes;
        } finally{call(buffer.raw,"release");}
    }
    private Object codec(Class<?> type) throws ReflectiveOperationException {
        for(Field field:type.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&field.getType().getSimpleName().equals("StreamCodec")){field.setAccessible(true);return field.get(null);}return null;
    }
    private Buffer buffer(Class<?> type,byte[] bytes) throws ReflectiveOperationException {
        Class<?> rawType=null;for(Constructor<?> constructor:type.getConstructors())for(Class<?> parameter:constructor.getParameterTypes())if(parameter.getName().endsWith(".buffer.ByteBuf"))rawType=parameter;
        if(rawType==null)throw new NoSuchMethodException("Native packet buffer");String prefix=rawType.getName().replace(".buffer.ByteBuf","");
        Object raw=Class.forName(prefix+".buffer.Unpooled").getMethod("buffer").invoke(null);
        if(bytes.length!=0)call(raw,"writeBytes",(Object)bytes);
        for(Constructor<?> constructor:type.getConstructors()) {
            Class<?>[] p=constructor.getParameterTypes();if(p.length==1&&p[0].isInstance(raw))return new Buffer(raw,constructor.newInstance(raw));
            if(p.length==2&&p[0].isInstance(raw))for(Method access:context.getClass().getMethods())if(!Modifier.isStatic(access.getModifiers())&&access.getParameterTypes().length==0&&p[1].isAssignableFrom(access.getReturnType()))return new Buffer(raw,constructor.newInstance(raw,invoke(access,context)));
        }
        throw new NoSuchMethodException("Native packet buffer wrapper");
    }
    private static final class Buffer {final Object raw,wrapped;Buffer(Object raw,Object wrapped){this.raw=raw;this.wrapped=wrapped;}}
    static Object call(Object target,String name,Object...args) throws ReflectiveOperationException {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())for(Method method:type.getDeclaredMethods())if(method.getName().equals(name)&&compatible(method.getParameterTypes(),args))return invoke(method,target,args);
        throw new NoSuchMethodException(target.getClass()+"."+name);
    }
    private static boolean compatible(Class<?>[] parameters,Object[] args){
        if(parameters.length!=args.length)return false;
        for(int i=0;i<parameters.length;i++) {
            Class<?> type=parameters[i];
            if(type.isPrimitive())type=type==int.class?Integer.class:type==boolean.class?Boolean.class:type==long.class?Long.class:type==float.class?Float.class:type==double.class?Double.class:type==byte.class?Byte.class:type==short.class?Short.class:Character.class;
            if(args[i]==null?parameters[i].isPrimitive():!type.isInstance(args[i]))return false;
        }
        return true;
    }
    private static Object invoke(Method method,Object target,Object...args)throws ReflectiveOperationException{method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw new ReflectiveOperationException(failure.getCause());}}
}
