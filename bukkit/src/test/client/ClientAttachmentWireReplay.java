import java.util.*;
import java.util.function.Predicate;
import java.nio.file.*;
import java.lang.reflect.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.*;
import io.netty.buffer.Unpooled;

/** Native client graph/attachment components driven by real captured packets. */
public class ClientAttachmentWireReplay {
  record Frame(long time,String kind,byte[] payload) { }
  static class World extends PoseAttachmentProbe.World {
    final Map<Integer,Entity> all=new HashMap<>();World(RegistryAccess r,Holder<DimensionType> d){super(r,d);}
    public Entity getEntity(int id){return all.get(id);}
    public List<Entity> getEntities(Entity except,AABB box,Predicate<? super Entity> p){return all.values().stream().filter(e->e!=except&&e.getBoundingBox().intersects(box)&&p.test(e)).toList();}
  }
  static final Map<String,String> TYPES=Map.ofEntries(Map.entry("add_entity","ClientboundAddEntityPacket"),Map.entry("entity_position_sync","ClientboundEntityPositionSyncPacket"),Map.entry("teleport_entity","ClientboundTeleportEntityPacket"),Map.entry("move_entity_pos","ClientboundMoveEntityPacket$Pos"),Map.entry("move_entity_pos_rot","ClientboundMoveEntityPacket$PosRot"),Map.entry("move_entity_rot","ClientboundMoveEntityPacket$Rot"),Map.entry("set_entity_data","ClientboundSetEntityDataPacket"),Map.entry("set_entity_motion","ClientboundSetEntityMotionPacket"),Map.entry("rotate_head","ClientboundRotateHeadPacket"),Map.entry("set_passengers","ClientboundSetPassengersPacket"),Map.entry("update_attributes","ClientboundUpdateAttributesPacket"));
  @SuppressWarnings({"rawtypes","unchecked"})
  static Object decode(Frame frame,RegistryAccess registry)throws Exception{
    var c=Class.forName("net.minecraft.network.protocol.game."+TYPES.get(frame.kind));var field=c.getDeclaredField("STREAM_CODEC");field.setAccessible(true);RegistryFriendlyByteBuf buf=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(frame.payload),registry);Object p=((StreamCodec)field.get(null)).decode(buf);if(buf.readableBytes()!=0)throw new IllegalStateException("Unread native packet bytes:"+frame.kind);return p;
  }
  static void apply(Object p,World world,int riderId,int driverId,Method teleport)throws Exception{
    if(p instanceof ClientboundAddEntityPacket a){
      Entity entity;
      if(a.getType()==EntityTypes.PLAYER){entity=EntityTypes.MANNEQUIN.create(world,EntitySpawnReason.LOAD);entity.setId(a.getId());entity.setUUID(a.getUUID());entity.snapTo(a.getX(),a.getY(),a.getZ(),a.getYRot(),a.getXRot());entity.syncPacketPositionCodec(a.getX(),a.getY(),a.getZ());}
      else{entity=a.getType().create(world,EntitySpawnReason.LOAD);if(entity==null)throw new IllegalStateException("Native entity factory refused "+a.getType());entity.recreateFromPacket(a);}
      world.all.put(a.getId(),entity);
    }else if(p instanceof ClientboundSetEntityDataPacket data){Entity e=world.getEntity(data.id());if(e!=null&&data.id()!=riderId&&data.id()!=driverId)e.getEntityData().assignValues(data.packedItems());}
    else if(p instanceof ClientboundUpdateAttributesPacket attrs){Entity e=world.getEntity(attrs.getEntityId());if(e instanceof LivingEntity living&&attrs.getEntityId()!=riderId&&attrs.getEntityId()!=driverId){for(var snapshot:attrs.getValues()){var attr=living.getAttribute(snapshot.attribute());if(attr!=null){attr.setBaseValue(snapshot.base());attr.removeModifiers();for(var modifier:snapshot.modifiers())attr.addTransientModifier(modifier);}}}}
    else if(p instanceof ClientboundSetPassengersPacket mounts){Entity vehicle=world.getEntity(mounts.getVehicle());if(vehicle==null)throw new IllegalStateException("Missing native vehicle "+mounts.getVehicle());vehicle.ejectPassengers();for(int id:mounts.getPassengers()){Entity rider=world.getEntity(id);if(rider==null)throw new IllegalStateException("Missing native passenger "+id);if(!rider.startRiding(vehicle,true,false))throw new IllegalStateException("Native passenger assignment refused");}}
    else if(p instanceof ClientboundEntityPositionSyncPacket move){Entity e=world.getEntity(move.id());if(e!=null){e.getPositionCodec().setBase(move.position().endPosition());e.moveOrInterpolateTo(move.position(),move.yRot(),move.xRot());e.setOnGround(move.onGround());}}
    else if(p instanceof ClientboundMoveEntityPacket move){Entity e=move.getEntity(world);if(e!=null){PositionPath path=null;if(move.hasPosition()){path=move.getPositionDelta().decode(e.getPositionCodec());e.getPositionCodec().setBase(path.endPosition());}if(path!=null){if(move.hasRotation())e.moveOrInterpolateTo(path,move.getYRot(),move.getXRot());else e.moveOrInterpolateTo(path);}else if(move.hasRotation())e.moveOrInterpolateTo(move.getYRot(),move.getXRot());e.setOnGround(move.isOnGround());}}
    else if(p instanceof ClientboundTeleportEntityPacket move){Entity e=world.getEntity(move.id());if(e!=null){teleport.invoke(null,move.change(),move.relatives(),e,true);e.setOnGround(move.onGround());}}
    else if(p instanceof ClientboundSetEntityMotionPacket move){Entity e=world.getEntity(move.id());if(e!=null)e.setDeltaMovement(move.movement());}
    else if(p instanceof ClientboundRotateHeadPacket turn){Entity e=turn.getEntity(world);if(e!=null)e.setYHeadRot(turn.getYHeadRot());}
  }
  static void tickTree(Entity e,Set<Integer> seen){if(!seen.add(e.getId()))throw new IllegalStateException("Vehicle graph cycle");e.commonTick();if(e.isPassenger())e.rideTick();else e.tick();for(Entity rider:e.getPassengers())tickTree(rider,seen);}
  static List<Double> xyz(Vec3 v){return List.of(v.x,v.y,v.z);}
  public static void main(String[] args)throws Exception{
    net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();var lookup=VanillaRegistries.createWorldLookup();List<Registry<?>> list=new ArrayList<>();BuiltInRegistries.REGISTRY.forEach(list::add);for(var key:List.of(Registries.DAMAGE_TYPE,Registries.DIMENSION_TYPE,Registries.BIOME))list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(key)));RegistryAccess registry=new RegistryAccess.ImmutableRegistryAccess(list).freeze();Holder<DimensionType> dim=registry.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();World world=new World(registry,dim);
    List<Frame> frames=new ArrayList<>();for(String line:Files.readAllLines(Path.of(args[0]))){String[] x=line.split("\t");frames.add(new Frame(Long.parseLong(x[0]),x[1],HexFormat.of().parseHex(x[2])));}
    int horseId=Integer.parseInt(args[1]),seatId=Integer.parseInt(args[2]),riderId=Integer.parseInt(args[3]),driverId=Integer.parseInt(args[4]);long start=Long.parseLong(args[5]),end=Long.parseLong(args[6]),phase=Long.parseLong(args[7]);double height=Double.parseDouble(args[8]),back=Double.parseDouble(args[9]);
    Method teleport=net.minecraft.client.multiplayer.ClientPacketListener.class.getDeclaredMethod("setValuesFromPositionPacket",PositionMoveRotation.class,Set.class,Entity.class,boolean.class);teleport.setAccessible(true);
    int i=0,ticks=0,rootFailures=0,helperHits=0,clientRays=0;double maxError=0,totalError=0,maxEyeRelativeStep=0;Vec3 oldRelativeEye=null;List<Map<String,Object>> rows=new ArrayList<>();
    for(long time=frames.get(0).time+phase;time<=end;time+=50_000_000L){while(i<frames.size()&&frames.get(i).time<=time){apply(decode(frames.get(i),registry),world,riderId,driverId,teleport);i++;}Set<Integer> seen=new HashSet<>();for(Entity e:new ArrayList<>(world.all.values()))if(!e.isPassenger())tickTree(e,seen);
      Entity horse=world.getEntity(horseId),rider=world.getEntity(riderId),seat=world.getEntity(seatId);if(time<start||horse==null||rider==null||seat==null)continue;
      boolean root=rider.getRootVehicle()==horse&&seat.getRootVehicle()==horse;if(!root)rootFailures++;
      double angle=Math.toRadians(horse.getYRot());Vec3 expected=new Vec3(Math.sin(angle)*back,height,-Math.cos(angle)*back),actual=rider.position().subtract(horse.position());double error=actual.distanceTo(expected);maxError=Math.max(maxError,error);totalError+=error;ticks++;
      Vec3 eye=rider.getEyePosition(.5F).subtract(horse.getPosition(.5F));if(oldRelativeEye!=null)maxEyeRelativeStep=Math.max(maxEyeRelativeStep,eye.distanceTo(oldRelativeEye));oldRelativeEye=eye;
      for(Entity viewer:List.of(rider,world.getEntity(driverId))){if(viewer==null)continue;for(Vec3 d:List.of(new Vec3(0,-1,0),new Vec3(0,1,0),new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,0,1),new Vec3(0,0,-1))){Vec3 eyeStart=viewer.getEyePosition(1),eyeEnd=eyeStart.add(d.scale(4.5));EntityHitResult hit=ProjectileUtil.getEntityHitResult(viewer,eyeStart,eyeEnd,new AABB(eyeStart,eyeEnd).inflate(1),e->!e.isSpectator()&&e.isPickable(),4.5*4.5);clientRays++;if(hit!=null&&hit.getEntity().getId()!=horseId&&hit.getEntity().getId()!=riderId&&hit.getEntity().getId()!=driverId)helperHits++;}}
      rows.add(Map.of("time",time,"horse",xyz(horse.position()),"rider",xyz(rider.position()),"offset",xyz(actual),"expected",xyz(expected),"error",error,"root",root,"relativeHalfTickEye",xyz(eye)));
    }
    List<Map<String,Object>> nodes=new ArrayList<>();for(Entity e:world.all.values()){Map<String,Object> r=new LinkedHashMap<>();r.put("id",e.getId());r.put("class",e.getClass().getName());r.put("vehicle",e.getVehicle()==null?-1:e.getVehicle().getId());r.put("root",e.getRootVehicle().getId());r.put("position",xyz(e.position()));r.put("noGravity",e.isNoGravity());r.put("invisible",e.isInvisible());r.put("removed",e.isRemoved());if(e instanceof LivingEntity l)r.put("scale",l.getScale());if(e instanceof Interaction)r.put("dimensions",List.of(e.getBbWidth(),e.getBbHeight()));nodes.add(r);}
    Map<String,Object> result=new LinkedHashMap<>();result.put("phaseNs",phase);result.put("ticks",ticks);result.put("framesApplied",i);result.put("rootFailures",rootFailures);result.put("maxError",maxError);result.put("meanError",totalError/ticks);result.put("clientRays",clientRays);result.put("helperRayHits",helperHits);result.put("maxRelativeHalfTickEyeStep",maxEyeRelativeStep);result.put("nodes",nodes);result.put("rows",rows);result.put("localPlayerConstructed",false);result.put("renderedCameraVerified",false);result.put("playerComponentSurrogate","Ordinary native Mannequin with vanilla player-sized dimensions and 0.6 vehicle attachment; player movement/input/rendering not substituted or claimed");System.out.println("CLIENT_ATTACHMENT_REPLAY "+new com.google.gson.Gson().toJson(result));
  }
}
