/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher. Test-only; never packaged in a release JAR. */
import java.lang.reflect.*;
import java.lang.invoke.MethodType;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import com.google.gson.*;
/** Untouched official client entities in a controlled normally constructed Level. */
public final class AttachmentClientProbe {
 static final Properties S=new Properties();static int tick;
 static Class<?> C(String name)throws Exception{return Class.forName(S.getProperty("class."+name,name));}
 static Method M(String owner,String name,String params)throws Exception{
  String value=S.getProperty("method."+owner+"#"+name+"#"+params);if(value==null)throw new NoSuchMethodException(owner+"#"+name+"#"+params);
  String[] p=value.split("#");MethodType desc=MethodType.fromMethodDescriptorString(p[2],AttachmentClientProbe.class.getClassLoader());
  for(Method m:Class.forName(p[0]).getDeclaredMethods())if(m.getName().equals(p[1])&&Arrays.equals(m.getParameterTypes(),desc.parameterArray())&&m.getReturnType()==desc.returnType()){m.setAccessible(true);return m;}
  throw new NoSuchMethodException(owner+"#"+name+" "+p[2]);
 }
 static Object call(String owner,String name,String params,Object target,Object...args)throws Exception{return M(owner,name,params).invoke(target,args);}
 static Object field(String owner,String name,Object target)throws Exception{String[] p=S.getProperty("field."+owner+"#"+name).split("#");Field f=Class.forName(p[0]).getDeclaredField(p[1]);f.setAccessible(true);return f.get(target);}
 static Object create(String owner,String parameters,Object...args)throws Exception{
  String[] p=S.getProperty("method."+owner+"#<init>#"+parameters).split("#");MethodType desc=MethodType.fromMethodDescriptorString(p[2],AttachmentClientProbe.class.getClassLoader());Constructor<?> constructor=Class.forName(p[0]).getDeclaredConstructor(desc.parameterArray());constructor.setAccessible(true);return constructor.newInstance(args);
 }
 static final String ENTITY="net.minecraft.world.entity.Entity",LEVEL="net.minecraft.world.level.Level",REGISTRY="net.minecraft.core.Registry",ACCESS="net.minecraft.core.RegistryAccess",LOOKUP="net.minecraft.core.HolderLookup$Provider",REF="net.minecraft.core.Holder$Reference";
 static final String PACKETS="net.minecraft.network.protocol.game.";
 static Object any(String owner,Object receiver,String...names)throws Exception{
  for(String name:names)if(S.containsKey("method."+owner+"#"+name+"#"))return call(owner,name,"",receiver);
  throw new NoSuchMethodException(owner+" "+Arrays.toString(names));
 }
 static Object decode(String kind,String hex,Object registry)throws Exception{
  String owner=PACKETS+kind;
  Object raw=Class.forName("io.netty.buffer.Unpooled").getMethod("wrappedBuffer",byte[].class).invoke(null,(Object)HexFormat.of().parseHex(hex));
  String buffer=S.containsKey("class.net.minecraft.network.RegistryFriendlyByteBuf")?"net.minecraft.network.RegistryFriendlyByteBuf":"net.minecraft.network.FriendlyByteBuf";
  Object buf=buffer.endsWith("RegistryFriendlyByteBuf")?create(buffer,"io.netty.buffer.ByteBuf,net.minecraft.core.RegistryAccess",raw,registry):create(buffer,"io.netty.buffer.ByteBuf",raw);
  Object result;
  try {
   if(S.containsKey("field."+owner+"#STREAM_CODEC")){
    Object codec=field(owner,"STREAM_CODEC",null);
    String decoder="net.minecraft.network.codec.StreamDecoder";
    result=call(decoder,"decode","java.lang.Object",codec,buf);
   }else result=create(owner,buffer,buf);
   int unread=((Number)Class.forName("io.netty.buffer.ByteBuf").getMethod("readableBytes").invoke(buf)).intValue();
   if(unread!=0)throw new AssertionError("Unread native packet bytes: "+kind+": "+unread);
   return result;
  }finally{Class.forName("io.netty.buffer.ByteBuf").getMethod("release").invoke(buf);}
 }
 static Object fromType(Object world,Object type,int id)throws Exception{
  Object e=call("net.minecraft.world.entity.EntityType","create",LEVEL,type,world);
  if(e==null)throw new AssertionError("Native entity factory refused captured type");
  call(ENTITY,"setId","int",e,id);return e;
 }
 static int packetId(Object p,String owner)throws Exception{return ((Number)any(owner,p,"getId","id","getEntityId")).intValue();}
 static void received(String kind,Object p,Object world,Map<Integer,Object> entities,Set<Integer> wanted,Set<Integer> players)throws Exception{
  String owner=PACKETS+kind;
  if(kind.equals("ClientboundAddEntityPacket")||kind.equals("ClientboundAddPlayerPacket")){
   int id=packetId(p,owner);if(!wanted.contains(id))return;
   Object e;
   if(players.contains(id)){
    e=entity(world,"armor_stand",id);
    position(e,new double[]{((Number)any(owner,p,"getX")).doubleValue(),((Number)any(owner,p,"getY")).doubleValue(),((Number)any(owner,p,"getZ")).doubleValue()});
   }else{
    e=fromType(world,any(owner,p,"getType"),id);call(ENTITY,"recreateFromPacket",PACKETS+"ClientboundAddEntityPacket",e,p);
   }
   entities.put(id,e);Field entries=world.getClass().getDeclaredField("entities");entries.setAccessible(true);((Map)entries.get(world)).put(id,e);
  }else if(kind.equals("ClientboundSetEntityDataPacket")){
   int id=packetId(p,owner);Object e=entities.get(id);if(e!=null&&!players.contains(id))call("net.minecraft.network.syncher.SynchedEntityData","assignValues","java.util.List",call(ENTITY,"getEntityData","",e),any(owner,p,"packedItems","getUnpackedData"));
  }else if(kind.equals("ClientboundUpdateAttributesPacket")){
   int id=packetId(p,owner);Object e=entities.get(id);if(e==null||players.contains(id))return;
   for(Object snapshot:(Iterable<?>)any(owner,p,"getValues")){
    String snapshotType=owner+"$AttributeSnapshot";Object attribute=any(snapshotType,snapshot,"attribute","getAttribute");
    String parameters=S.containsKey("method.net.minecraft.world.entity.LivingEntity#getAttribute#net.minecraft.core.Holder")?"net.minecraft.core.Holder":"net.minecraft.world.entity.ai.attributes.Attribute";
    Object instance=call("net.minecraft.world.entity.LivingEntity","getAttribute",parameters,e,attribute);
    if(instance==null)continue;
    call("net.minecraft.world.entity.ai.attributes.AttributeInstance","setBaseValue","double",instance,((Number)any(snapshotType,snapshot,"base","getBase")).doubleValue());
    call("net.minecraft.world.entity.ai.attributes.AttributeInstance","removeModifiers","",instance);
    for(Object modifier:(Iterable<?>)any(snapshotType,snapshot,"modifiers","getModifiers"))call("net.minecraft.world.entity.ai.attributes.AttributeInstance","addTransientModifier","net.minecraft.world.entity.ai.attributes.AttributeModifier",instance,modifier);
   }
  }else if(kind.equals("ClientboundSetPassengersPacket")){
   int vehicle=((Number)any(owner,p,"getVehicle")).intValue();Object e=entities.get(vehicle);if(e==null)return;
   call(ENTITY,"ejectPassengers","",e);
   for(int passenger:(int[])any(owner,p,"getPassengers"))if(wanted.contains(passenger)){
    Object rider=entities.get(passenger);if(rider!=null)mount(rider,e);
   }
  }else if(kind.equals("ClientboundRotateHeadPacket")){
   Object e=call(owner,"getEntity",LEVEL,p,world);if(e!=null&&!players.contains(((Number)call(ENTITY,"getId","",e)).intValue()))call(ENTITY,"setYHeadRot","float",e,((Number)any(owner,p,"getYHeadRot")).floatValue()*360F/256F);
  }
 }
 static Object reg(Object access,String key)throws Exception{return call(ACCESS,"registryOrThrow","net.minecraft.resources.ResourceKey",access,field("net.minecraft.core.registries.Registries",key,null));}
 static Object holder(Object registry,String owner,String key)throws Exception{return call(REGISTRY,"getHolderOrThrow","net.minecraft.resources.ResourceKey",registry,field(owner,key,null));}
 static Object registry()throws Exception{
  Object lookup=call("net.minecraft.data.registries.VanillaRegistries","createLookup","",null);
  List<Object> all=new ArrayList<>();for(Object r:(Iterable<?>)field("net.minecraft.core.registries.BuiltInRegistries","REGISTRY",null))all.add(r);
  Object lifecycle=Class.forName("com.mojang.serialization.Lifecycle").getMethod("stable").invoke(null);
  for(String name:List.of("DAMAGE_TYPE","DIMENSION_TYPE","BIOME")){
   Object key=field("net.minecraft.core.registries.Registries",name,null),source=call(LOOKUP,"lookupOrThrow","net.minecraft.resources.ResourceKey",lookup,key);
   Object target=create("net.minecraft.core.MappedRegistry","net.minecraft.resources.ResourceKey,com.mojang.serialization.Lifecycle",key,lifecycle);
   Stream<?> elements=(Stream<?>)call("net.minecraft.core.HolderLookup","listElements","",source);
   for(Object element:elements.toList()){
    Object k=call(REF,"key","",element),v=call(REF,"value","",element);
    if(S.containsKey("class.net.minecraft.core.RegistrationInfo"))call("net.minecraft.core.MappedRegistry","register","net.minecraft.resources.ResourceKey,java.lang.Object,net.minecraft.core.RegistrationInfo",target,k,v,field("net.minecraft.core.RegistrationInfo","BUILT_IN",null));
    else call("net.minecraft.core.MappedRegistry","register","net.minecraft.resources.ResourceKey,java.lang.Object,com.mojang.serialization.Lifecycle",target,k,v,lifecycle);
   }
   call("net.minecraft.core.MappedRegistry","bindTags","java.util.Map",target,Map.of());
   all.add(call("net.minecraft.core.MappedRegistry","freeze","",target));
  }
  Object result=create("net.minecraft.core.RegistryAccess$ImmutableRegistryAccess","java.util.List",all);
  return call(ACCESS,"freeze","",result);
 }
 static Object world()throws Exception{
  Object registry=registry(),dimension=holder(reg(registry,"DIMENSION_TYPE"),"net.minecraft.world.level.dimension.BuiltinDimensionTypes","OVERWORLD"),biome=holder(reg(registry,"BIOME"),"net.minecraft.world.level.biome.Biomes","PLAINS");
  Object rules=create("net.minecraft.world.level.GameRules","");
  Map<String,String> semantics=new HashMap<>();for(String key:S.stringPropertyNames())if(key.startsWith("method.net.minecraft.world.level.storage.LevelData#")){
   String[] canonical=key.split("#");String[] nativeName=S.getProperty(key).split("#");semantics.put(nativeName[1]+nativeName[2],canonical[1]);
  }
  Object data=Proxy.newProxyInstance(AttachmentClientProbe.class.getClassLoader(),new Class<?>[]{C("net.minecraft.world.level.storage.WritableLevelData")},(p,m,args)->{
   String desc=MethodType.methodType(m.getReturnType(),m.getParameterTypes()).toMethodDescriptorString();String meaning=semantics.getOrDefault(m.getName()+desc,"");
   if(meaning.equals("getGameRules"))return rules;
   if(meaning.equals("getDifficulty"))return C("net.minecraft.world.Difficulty").getEnumConstants()[2];
   if(meaning.equals("getSpawnPos"))return create("net.minecraft.core.BlockPos","int,int,int",0,64,0);
   if(meaning.equals("getGameTime")||meaning.equals("getDayTime"))return (long)tick;
   Class<?> t=m.getReturnType();return t==boolean.class?false:t==int.class?0:t==long.class?0L:t==float.class?0F:t==double.class?0D:null;
  });
  Constructor<?> constructor=Class.forName("World").getDeclaredConstructor(Object.class,Object.class,Object.class,Object.class);constructor.setAccessible(true);return constructor.newInstance(data,registry,dimension,biome);
 }
 static Object entity(Object world,String type,int id)throws Exception{
  Object t=field("net.minecraft.world.entity.EntityType",type.toUpperCase(),null);Object e=call("net.minecraft.world.entity.EntityType","create","net.minecraft.world.level.Level",t,world);
  call(ENTITY,"setId","int",e,id);call(ENTITY,"setNoGravity","boolean",e,true);call(ENTITY,"setSilent","boolean",e,true);call(ENTITY,"setInvisible","boolean",e,true);
  if(C("net.minecraft.world.entity.Mob").isInstance(e))call("net.minecraft.world.entity.Mob","setNoAi","boolean",e,true);
  Field entries=world.getClass().getDeclaredField("entities");entries.setAccessible(true);((Map)entries.get(world)).put(id,e);return e;
 }
 static void position(Object e,double[] v)throws Exception{call(ENTITY,"setPos","double,double,double",e,v[0],v[1],v[2]);}
 static void yaw(Object e,float yaw)throws Exception{call(ENTITY,"setYRot","float",e,yaw);call(ENTITY,"setYBodyRot","float",e,yaw);call(ENTITY,"setYHeadRot","float",e,yaw);}
 static double[] xyz(Object e)throws Exception{return new double[]{((Number)call(ENTITY,"getX","",e)).doubleValue(),((Number)call(ENTITY,"getY","",e)).doubleValue(),((Number)call(ENTITY,"getZ","",e)).doubleValue()};}
 static Object vector(double[] v)throws Exception{return create("net.minecraft.world.phys.Vec3","double,double,double",v[0],v[1],v[2]);}
 static Map<String,Object> rays(Object world,Object horse,Object driver,Object rider,Map<Integer,Object> entities)throws Exception{
  String vec="net.minecraft.world.phys.Vec3",box="net.minecraft.world.phys.AABB";
  Object observer=entity(world,"armor_stand",-987654);Set<Integer> ordinary=new HashSet<>();
  for(Object e:List.of(horse,driver,rider))ordinary.add(((Number)call(ENTITY,"getId","",e)).intValue());
  List<Map<String,Object>> cases=new ArrayList<>();int helperHits=0;
  double[] hp=xyz(horse);
  for(double[] view:new double[][]{{0,1.62,-4},{0,1.62,4},{-4,1.62,0},{4,1.62,0},{0,3,-4}}){
   double[] start={hp[0]+view[0],hp[1]+view[1],hp[2]+view[2]};position(observer,start);
   for(double targetHeight:new double[]{.6,1.1,1.6,2.1}){
    double[] end={hp[0],hp[1]+targetHeight,hp[2]};Object hit=ray(observer,start,end,world);int id=hit==null?-1:((Number)call(ENTITY,"getId","",call("net.minecraft.world.phys.EntityHitResult","getEntity","",hit))).intValue();
    boolean helper=entities.containsKey(id)&&!ordinary.contains(id);if(helper)helperHits++;cases.add(Map.of("kind","observer","from",start,"targetHeight",targetHeight,"entity",id,"helper",helper));
   }
  }
  for(Object viewer:List.of(driver,rider))for(double[] d:new double[][]{{0,-1,0},{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}){
   double[] start=xyz(viewer);start[1]+=1.62;double[] end={start[0]+4.5*d[0],start[1]+4.5*d[1],start[2]+4.5*d[2]};Object hit=ray(viewer,start,end,world);int id=hit==null?-1:((Number)call(ENTITY,"getId","",call("net.minecraft.world.phys.EntityHitResult","getEntity","",hit))).intValue();
   boolean helper=entities.containsKey(id)&&!ordinary.contains(id);if(helper)helperHits++;cases.add(Map.of("kind","rider-component","entity",id,"helper",helper));
  }
  if(helperHits!=0)throw new AssertionError("Original client interaction rays intercepted helpers: "+new Gson().toJson(cases));
  return Map.of("cases",cases.size(),"helperHits",helperHits,"details",cases,"scope","Original ProjectileUtil and native entity query/bounds; controlled observer and rider-component rays, no rendered LocalPlayer or terrain selection claim");
 }
 static Object ray(Object viewer,double[] start,double[] end,Object world)throws Exception{
  Object a=vector(start),b=vector(end);Object bounds=create("net.minecraft.world.phys.AABB","net.minecraft.world.phys.Vec3,net.minecraft.world.phys.Vec3",a,b);bounds=call("net.minecraft.world.phys.AABB","inflate","double",bounds,1D);
  java.util.function.Predicate<Object> pickable=e->{try{return Boolean.TRUE.equals(call(ENTITY,"isPickable","",e))&&!Boolean.TRUE.equals(call(ENTITY,"isSpectator","",e));}catch(Exception ex){throw new RuntimeException(ex);}};
  return call("net.minecraft.world.entity.projectile.ProjectileUtil","getEntityHitResult",ENTITY+",net.minecraft.world.phys.Vec3,net.minecraft.world.phys.Vec3,net.minecraft.world.phys.AABB,java.util.function.Predicate,double",null,viewer,a,b,bounds,pickable,100D);
 }
 static void mount(Object e,Object parent)throws Exception{if(!Boolean.TRUE.equals(call(ENTITY,"startRiding","net.minecraft.world.entity.Entity,boolean",e,parent,true)))throw new AssertionError("native mount refused");}
 static void setupHelper(Object e,JsonObject h)throws Exception{
  String type=h.get("type").getAsString();
  if(type.equals("interaction")){
   call("net.minecraft.world.entity.Interaction","setWidth","float",e,h.get("width").getAsFloat());call("net.minecraft.world.entity.Interaction","setHeight","float",e,h.get("height").getAsFloat());
   // The observed native metadata pose entry follows the height snapshot.
   Object pose=Arrays.stream(C("net.minecraft.world.entity.Pose").getEnumConstants()).filter(p->p.toString().equals(h.get("pose").getAsString())).findFirst().orElseThrow();
   Object accessor=field(ENTITY,"DATA_POSE",null);
   Object value=call("net.minecraft.network.syncher.SynchedEntityData$DataValue","create","net.minecraft.network.syncher.EntityDataAccessor,java.lang.Object",null,accessor,pose);
   Object data=call(ENTITY,"getEntityData","",e);call("net.minecraft.network.syncher.SynchedEntityData","assignValues","java.util.List",data,List.of(value));
  }
  if(type.equals("llama")&&S.containsKey("field.net.minecraft.world.entity.ai.attributes.Attributes#SCALE")){
   Object attr=field("net.minecraft.world.entity.ai.attributes.Attributes","SCALE",null);String params=S.containsKey("method.net.minecraft.world.entity.LivingEntity#getAttribute#net.minecraft.core.Holder")?"net.minecraft.core.Holder":"net.minecraft.world.entity.ai.attributes.Attribute";
   Object instance=call("net.minecraft.world.entity.LivingEntity","getAttribute",params,e,attr);call("net.minecraft.world.entity.ai.attributes.AttributeInstance","setBaseValue","double",instance,h.get("scale").getAsDouble());
  }
  if(type.equals("block_display")){Object state=call("net.minecraft.world.level.block.Block","defaultBlockState","",field("net.minecraft.world.level.block.Blocks","BARRIER",null));call("net.minecraft.world.entity.Display$BlockDisplay","setBlockState","net.minecraft.world.level.block.state.BlockState",e,state);}
  yaw(e,h.get("yaw").getAsFloat());
 }
 static void nested(Object parent,Map<Integer,Object> entities,Map<Integer,List<Integer>> children)throws Exception{
  for(int id:children.getOrDefault(((Number)call(ENTITY,"getId","",parent)).intValue(),List.of())){
   Object child=entities.get(id);call(ENTITY,"rideTick","",child);nested(child,entities,children);
  }
 }
 public static void main(String[] args)throws Exception{
  try(var input=Files.newInputStream(Path.of(args[0]))){S.load(input);}call("net.minecraft.SharedConstants","tryDetectVersion","",null);call("net.minecraft.server.Bootstrap","bootStrap","",null);
  Object world=world();System.out.println("NATIVE_WORLD_READY "+world.getClass());
  if(args.length<2)return;
  JsonObject state=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();if(state.has("mounted_state"))state=state.getAsJsonObject("mounted_state");
  Map<Integer,Object> entities=new LinkedHashMap<>();Map<Integer,List<Integer>> children=new HashMap<>();
  int horseId=state.get("horse").getAsInt(),driverId=state.get("driver").getAsInt(),riderId=state.get("rider").getAsInt();
  Object horse=entity(world,"horse",horseId),driver=entity(world,"armor_stand",driverId),rider=entity(world,"armor_stand",riderId);entities.put(horseId,horse);entities.put(driverId,driver);entities.put(riderId,rider);
  double[] origin=new Gson().fromJson(state.get("horsePosition"),double[].class);position(horse,origin);
  for(JsonElement entry:state.getAsJsonArray("helpers")){JsonObject h=entry.getAsJsonObject();Object e=entity(world,h.get("type").getAsString(),h.get("id").getAsInt());setupHelper(e,h);entities.put(h.get("id").getAsInt(),e);}
  mount(driver,horse);children.put(horseId,new ArrayList<>(List.of(driverId)));
  Set<Integer> mounted=new HashSet<>(List.of(horseId,driverId));
  for(int pass=0;pass<20;pass++)for(JsonElement entry:state.getAsJsonArray("helpers")){
   JsonObject h=entry.getAsJsonObject();int id=h.get("id").getAsInt(),vehicle=h.get("vehicle").getAsInt();if(!mounted.contains(id)&&mounted.contains(vehicle)){mount(entities.get(id),entities.get(vehicle));children.computeIfAbsent(vehicle,k->new ArrayList<>()).add(id);mounted.add(id);}
  }
  int last=state.get("riderVehicle").getAsInt();mount(rider,entities.get(last));children.computeIfAbsent(last,k->new ArrayList<>()).add(riderId);
  // Mount sets the ordinary pose; reapply the received pose-tail metadata after all edges.
  for(JsonElement entry:state.getAsJsonArray("helpers")){JsonObject h=entry.getAsJsonObject();setupHelper(entities.get(h.get("id").getAsInt()),h);}
  boolean receivedState=args.length>3;
  int receivedPackets=0;
  if(receivedState){
   entities.clear();children.clear();Field entries=world.getClass().getDeclaredField("entities");entries.setAccessible(true);((Map)entries.get(world)).clear();
   Set<Integer> wanted=new HashSet<>(List.of(horseId,driverId,riderId));for(JsonElement entry:state.getAsJsonArray("helpers"))wanted.add(entry.getAsJsonObject().get("id").getAsInt());
   Object registry=call(LEVEL,"registryAccess","",world);
   for(String line:Files.readAllLines(Path.of(args[3]))){if(line.isBlank())continue;String[] parts=line.split("\t");received(parts[0],decode(parts[0],parts[1],registry),world,entities,wanted,Set.of(driverId,riderId));receivedPackets++;}
   for(int id:wanted)if(!entities.containsKey(id))throw new AssertionError("Captured native state omitted entity "+id);
   horse=entities.get(horseId);driver=entities.get(driverId);rider=entities.get(riderId);
   for(var entry:entities.entrySet())for(Object child:(List<?>)call(ENTITY,"getPassengers","",entry.getValue()))children.computeIfAbsent(entry.getKey(),k->new ArrayList<>()).add(((Number)call(ENTITY,"getId","",child)).intValue());
   // Motion below is a controlled native tick component, starting with the
   // helper geometry actually decoded from received packet bytes.
   origin=xyz(horse);
  }
  Map<String,Object> receivedTickResult=new LinkedHashMap<>();
  if(receivedState){
   double[] firstRelative=null;double maximumDrift=0;int lostRoots=0,removedNodes=0;
   for(int step=0;step<1200;step++){
    call(ENTITY,"tick","",horse);nested(horse,entities,children);
    double angle=Math.toRadians(((Number)call(ENTITY,"getYRot","",horse)).doubleValue());double[] hp=xyz(horse),rp=xyz(rider);
    double dx=rp[0]-hp[0],dz=rp[2]-hp[2];double[] relative={Math.cos(angle)*dx+Math.sin(angle)*dz,rp[1]-hp[1],-Math.sin(angle)*dx+Math.cos(angle)*dz};
    if(firstRelative==null)firstRelative=relative;
    maximumDrift=Math.max(maximumDrift,Math.sqrt(Math.pow(relative[0]-firstRelative[0],2)+Math.pow(relative[1]-firstRelative[1],2)+Math.pow(relative[2]-firstRelative[2],2)));
    for(Object e:entities.values()){if(Boolean.TRUE.equals(call(ENTITY,"isRemoved","",e)))removedNodes++;if(call(ENTITY,"getRootVehicle","",e)!=horse)lostRoots++;}
   }
   receivedTickResult.put("ticks",1200);receivedTickResult.put("rootErrors",lostRoots);receivedTickResult.put("removedObservations",removedNodes);receivedTickResult.put("maximumRelativeDrift",maximumDrift);receivedTickResult.put("initialArmorStandLocalPosition",firstRelative);
   if(lostRoots!=0||removedNodes!=0||maximumDrift>1./16)throw new AssertionError("Unmodified received graph ticks failed: "+new Gson().toJson(receivedTickResult));
  }
  List<Map<String,Object>> samples=new ArrayList<>();double[] expected=null;double errorMax=0;int removed=0,rootErrors=0;
  for(tick=0;tick<1200;tick++){
   // Ordinary moving/turning/jumping root; unchanged original native descendant ticks.
   double angle=tick*.015;position(horse,new double[]{origin[0]+Math.sin(angle)*8,origin[1]+Math.max(0,Math.sin(tick*.1)),origin[2]+Math.cos(angle)*8});
   float heading=(float)(angle*180/Math.PI);yaw(horse,heading);
   for(JsonElement entry:state.getAsJsonArray("helpers")){JsonObject h=entry.getAsJsonObject();yaw(entities.get(h.get("id").getAsInt()),heading+h.get("yaw").getAsFloat());}
   call(ENTITY,"tick","",horse);nested(horse,entities,children);
   double[] hp=xyz(horse),rp=xyz(rider);double dx=rp[0]-hp[0],dy=rp[1]-hp[1],dz=rp[2]-hp[2];double[] local={Math.cos(angle)*dx+Math.sin(angle)*dz,dy,-Math.sin(angle)*dx+Math.cos(angle)*dz};
   if(expected==null)expected=local.clone();double err=Math.sqrt(Math.pow(local[0]-expected[0],2)+Math.pow(local[1]-expected[1],2)+Math.pow(local[2]-expected[2],2));errorMax=Math.max(errorMax,err);
   for(Object e:entities.values()){if(Boolean.TRUE.equals(call(ENTITY,"isRemoved","",e)))removed++;if(call(ENTITY,"getRootVehicle","",e)!=horse)rootErrors++;}
   if(tick%200==0)samples.add(Map.of("tick",tick,"armorStandLocalPosition",local,"error",err));
  }
  Map<String,Object> result=new LinkedHashMap<>();result.put("kind","original-client-native-graph-ticks");result.put("minecraft",S.getProperty("minecraft"));result.put("receivedNativeState",receivedState);result.put("receivedStateTicks",receivedTickResult);result.put("originalPacketCodecsConsumed",receivedPackets);result.put("ticks",tick);result.put("helperCount",state.getAsJsonArray("helpers").size());result.put("removedObservations",removed);result.put("rootErrors",rootErrors);result.put("maximumRelativeMotionError",errorMax);result.put("initialArmorStandLocalPosition",expected);result.put("samples",samples);result.put("environment","Normal Level subclass supplies controlled empty world, registry, scoreboard and chunks; all entity constructors and tick/rideTick/positionRider methods are original signed client classes");result.put("limitations","Driver/rider geometry subjects are original ArmorStands; this does not execute RemotePlayer/LocalPlayer or render graphics. Root motion and helper yaw are controlled component inputs, not a timed movement packet replay. Player offset and real controls require separate exact SDK/native server evidence.");
  result.put("interactionRays",rays(world,horse,driver,rider,entities));
  if(removed!=0||rootErrors!=0||errorMax>1./16)throw new AssertionError("Native client graph lifetime/position failed: "+new Gson().toJson(result));
  if(args.length>2)Files.writeString(Path.of(args[2]),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");System.out.println("NATIVE_GRAPH_RESULT "+new Gson().toJson(result));
 }
}
