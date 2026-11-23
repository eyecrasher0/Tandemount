import java.util.*;
import java.nio.file.*;
import java.lang.reflect.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.*;
import io.netty.buffer.Unpooled;

public class ClientWireMotionReplay {
  record Frame(long time,String kind,byte[] payload) { }
  static class World extends ClientSeatMotionProbe.MotionLevel {
    final Map<Integer,Entity> entities=new HashMap<>();
    World(RegistryAccess reg,Holder<DimensionType> dim){super(reg,dim);}
    public Entity getEntity(int id){return entities.get(id);}
  }
  static final Map<String,String> TYPES=Map.ofEntries(
    Map.entry("add_entity","ClientboundAddEntityPacket"),Map.entry("entity_position_sync","ClientboundEntityPositionSyncPacket"),
    Map.entry("teleport_entity","ClientboundTeleportEntityPacket"),Map.entry("move_entity_pos","ClientboundMoveEntityPacket$Pos"),
    Map.entry("move_entity_pos_rot","ClientboundMoveEntityPacket$PosRot"),Map.entry("move_entity_rot","ClientboundMoveEntityPacket$Rot"),
    Map.entry("set_entity_data","ClientboundSetEntityDataPacket"),Map.entry("set_entity_motion","ClientboundSetEntityMotionPacket"),
    Map.entry("rotate_head","ClientboundRotateHeadPacket"));
  @SuppressWarnings({"unchecked","rawtypes"})
  static Object decode(Frame f,RegistryAccess reg)throws Exception{
    Class<?> c=Class.forName("net.minecraft.network.protocol.game."+TYPES.get(f.kind));
    Field field=c.getDeclaredField("STREAM_CODEC");field.setAccessible(true);
    RegistryFriendlyByteBuf buf=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(f.payload),reg);
    Object packet=((StreamCodec)field.get(null)).decode(buf);
    if(buf.readableBytes()!=0)throw new IllegalStateException("Unread native packet bytes: "+f.kind+" "+buf.readableBytes());
    return packet;
  }
  static void apply(Object p,World world,FallingBlockEntity control,int seatId,Method teleport)throws Exception{
    if(p instanceof ClientboundAddEntityPacket a){
      Entity e=a.getType().create(world,EntitySpawnReason.LOAD);if(e==null)throw new IllegalStateException("Native entity factory returned null");
      e.recreateFromPacket(a);world.entities.put(a.getId(),e);
      if(a.getId()==seatId){control.recreateFromPacket(new ClientboundAddEntityPacket(a.getId(),a.getUUID(),a.getX(),a.getY(),a.getZ(),a.getXRot(),a.getYRot(),EntityTypes.FALLING_BLOCK,14381,a.getMovement(),a.getYHeadRot()));control.setNoGravity(true);}
    } else if(p instanceof ClientboundSetEntityDataPacket d){Entity e=world.getEntity(d.id());if(e!=null)e.getEntityData().assignValues(d.packedItems());}
    else if(p instanceof ClientboundEntityPositionSyncPacket d){Entity e=world.getEntity(d.id());if(e!=null){e.getPositionCodec().setBase(d.position().endPosition());e.moveOrInterpolateTo(d.position(),d.yRot(),d.xRot());e.setOnGround(d.onGround());if(d.id()==seatId){control.moveOrInterpolateTo(d.position(),d.yRot(),d.xRot());control.setOnGround(d.onGround());}}}
    else if(p instanceof ClientboundMoveEntityPacket d){Entity e=d.getEntity(world);if(e!=null){
      PositionPath path=null;if(d.hasPosition()){path=d.getPositionDelta().decode(e.getPositionCodec());e.getPositionCodec().setBase(path.endPosition());}
      if(path!=null){if(d.hasRotation())e.moveOrInterpolateTo(path,d.getYRot(),d.getXRot());else e.moveOrInterpolateTo(path);if(e.getId()==seatId){if(d.hasRotation())control.moveOrInterpolateTo(path,d.getYRot(),d.getXRot());else control.moveOrInterpolateTo(path);}}
      else if(d.hasRotation()){e.moveOrInterpolateTo(d.getYRot(),d.getXRot());if(e.getId()==seatId)control.moveOrInterpolateTo(d.getYRot(),d.getXRot());}
      e.setOnGround(d.isOnGround());}}
    else if(p instanceof ClientboundTeleportEntityPacket d){Entity e=world.getEntity(d.id());if(e!=null){teleport.invoke(null,d.change(),d.relatives(),e,true);e.setOnGround(d.onGround());if(d.id()==seatId){teleport.invoke(null,d.change(),d.relatives(),control,true);control.setOnGround(d.onGround());}}}
    else if(p instanceof ClientboundSetEntityMotionPacket d){Entity e=world.getEntity(d.id());if(e!=null)e.setDeltaMovement(d.movement());if(d.id()==seatId)control.setDeltaMovement(d.movement());}
    else if(p instanceof ClientboundRotateHeadPacket d){Entity e=d.getEntity(world);if(e!=null)e.setYHeadRot(d.getYHeadRot());}
  }
  public static void main(String[] args)throws Exception{
    net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    var lookup=VanillaRegistries.createWorldLookup();List<Registry<?>> list=new ArrayList<>();BuiltInRegistries.REGISTRY.forEach(list::add);
    list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(Registries.DAMAGE_TYPE)));list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(Registries.DIMENSION_TYPE)));list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(Registries.BIOME)));
    RegistryAccess reg=new RegistryAccess.ImmutableRegistryAccess(list).freeze();Holder<DimensionType> dim=reg.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();
    World world=new World(reg,dim);FallingBlockEntity control=new FallingBlockEntity(EntityTypes.FALLING_BLOCK,world);
    List<Frame> frames=new ArrayList<>();for(String line:Files.readAllLines(Path.of(args[0]))){String[] x=line.split("\t");frames.add(new Frame(Long.parseLong(x[0]),x[1],HexFormat.of().parseHex(x[2])));}
    int horseId=Integer.parseInt(args[1]),seatId=Integer.parseInt(args[2]);long start=Long.parseLong(args[3]),end=Long.parseLong(args[4]),phase=Long.parseLong(args[5]);
    Method teleport=net.minecraft.client.multiplayer.ClientPacketListener.class.getDeclaredMethod("setValuesFromPositionPacket",PositionMoveRotation.class,Set.class,Entity.class,boolean.class);teleport.setAccessible(true);
    int i=0,measured=0;double max=0,sum=0,controlMax=0,controlSum=0;List<Map<String,Object>> rows=new ArrayList<>();
    long beginning=frames.get(0).time;long time=beginning+phase;
    for(;time<=end+50_000_000L;time+=50_000_000L){
      while(i<frames.size()&&frames.get(i).time<=time){apply(decode(frames.get(i),reg),world,control,seatId,teleport);i++;}
      Entity horse=world.getEntity(horseId),seat=world.getEntity(seatId);
      if(horse==null||seat==null)continue;
      horse.commonTick();horse.tick();seat.commonTick();seat.tick();control.commonTick();control.tick();
      if(time<start||time>end)continue;
      Vec3 expected=ClientSeatMotionProbe.seatTarget(horse.position(),horse.getYRot()).add(0,.6,0);double error=seat.position().distanceTo(expected),ce=control.position().distanceTo(expected);
      max=Math.max(max,error);sum+=error;controlMax=Math.max(controlMax,ce);controlSum+=ce;measured++;
      rows.add(Map.of("time",time,"horse",List.of(horse.getX(),horse.getY(),horse.getZ()),"seat",List.of(seat.getX(),seat.getY(),seat.getZ()),"horseYaw",horse.getYRot(),"horseBodyYaw",((LivingEntity)horse).yBodyRot,"error",error,"fallingControlError",ce));
    }
    Entity seat=world.getEntity(seatId);Map<String,Object> result=new LinkedHashMap<>();result.put("phaseNs",phase);result.put("ticks",measured);result.put("framesApplied",i);result.put("seatClass",seat.getClass().getName());result.put("maxError",max);result.put("meanError",sum/measured);result.put("fallingControlMaxError",controlMax);result.put("fallingControlMeanError",controlSum/measured);result.put("rows",rows);
    System.out.println("CLIENT_WIRE_REPLAY "+new com.google.gson.Gson().toJson(result));
  }
}
