import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/** Test native game types, metadata and original ticks; no custom entity. */
public class PoseAttachmentProbe {
  static class World extends ClientSeatMotionProbe.MotionLevel {
    int gameEvents;
    World(RegistryAccess r,Holder<DimensionType> d){super(r,d);}
    public void gameEvent(Holder<net.minecraft.world.level.gameevent.GameEvent> event,Vec3 point,net.minecraft.world.level.gameevent.GameEvent.Context context){gameEvents++;}
  }
  static List<Double> xyz(Vec3 v){return List.of(v.x,v.y,v.z);}
  static Map<String,Object> sample(World world,String type,Pose pose,double scale,boolean small,boolean marker,int id)throws Exception{
    EntityType<?> entityType=(EntityType<?>)EntityTypes.class.getField(type).get(null);
    Entity horse=EntityTypes.HORSE.create(world,EntitySpawnReason.LOAD);horse.setId(id++);horse.setNoGravity(true);horse.snapTo(8,64,8,0,0);
    Entity driver=EntityTypes.ARMOR_STAND.create(world,EntitySpawnReason.LOAD);driver.setId(id++);driver.setNoGravity(true);
    Entity carrier=entityType.create(world,EntitySpawnReason.LOAD);carrier.setId(id++);carrier.setNoGravity(true);
    Entity last=EntityTypes.BLOCK_DISPLAY.create(world,EntitySpawnReason.LOAD);last.setId(id++);last.setNoGravity(true);
    double initialScale=0,afterSetScale=0,afterMountScale=0;
    if(carrier instanceof LivingEntity living){var attribute=living.getAttribute(Attributes.SCALE);if(attribute==null)throw new IllegalStateException("scale attribute missing");initialScale=living.getScale();attribute.setBaseValue(scale);afterSetScale=living.getScale();}
    if(carrier instanceof ArmorStand){for(var entry:Map.of("setSmall",small,"setMarker",marker).entrySet()){var method=ArmorStand.class.getDeclaredMethod(entry.getKey(),boolean.class);method.setAccessible(true);method.invoke(carrier,entry.getValue());}}
    carrier.refreshDimensions();
    if(!driver.startRiding(horse,true,false)||!carrier.startRiding(horse,true,false)||!last.startRiding(carrier,true,false))throw new IllegalStateException("native graph mount refused");
    carrier.setPose(pose);carrier.refreshDimensions();
    if(carrier instanceof LivingEntity living)afterMountScale=living.getScale();
    horse.positionRider(driver);horse.positionRider(carrier);carrier.positionRider(last);
    Vec3 before=last.position().subtract(horse.position());
    for(int tick=0;tick<5;tick++){horse.commonTick();horse.tick();driver.commonTick();driver.rideTick();carrier.commonTick();carrier.rideTick();last.commonTick();last.rideTick();}
    Vec3 after=last.position().subtract(horse.position());Vec3 playerPoint=EntityTypes.PLAYER.getDimensions().attachments().getClamped(EntityAttachment.VEHICLE,0,0);
    Map<String,Object> out=new LinkedHashMap<>();out.put("type",type);out.put("requestedPose",pose.toString());out.put("actualPose",carrier.getPose().toString());out.put("scale",scale);out.put("small",small);out.put("marker",marker);out.put("vehiclePoint",xyz(carrier.getVehicleAttachmentPoint(horse)));out.put("childOffsetBeforeTicks",xyz(before));out.put("childOffsetAfterTicks",xyz(after));out.put("playerFeetOffset",xyz(after.subtract(playerPoint)));out.put("dimensions",List.of(carrier.getBbWidth(),carrier.getBbHeight()));out.put("actualScale",carrier instanceof LivingEntity l?l.getScale():0);out.put("rootVehicleIsHorse",last.getRootVehicle()==horse);out.put("carrierRemoved",carrier.isRemoved());out.put("lastNonLiving",!(last instanceof LivingEntity));
    out.put("scaleInitially",initialScale);out.put("scaleImmediatelyAfterSet",afterSetScale);out.put("scaleAfterMount",afterMountScale);out.put("attributeBaseAfterTicks",carrier instanceof LivingEntity l?l.getAttribute(Attributes.SCALE).getBaseValue():0);out.put("attributeValueAfterTicks",carrier instanceof LivingEntity l?l.getAttribute(Attributes.SCALE).getValue():0);
    return out;
  }
  public static void main(String[] args)throws Exception{
    net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    var lookup=VanillaRegistries.createWorldLookup();List<Registry<?>> list=new ArrayList<>();BuiltInRegistries.REGISTRY.forEach(list::add);
    for(var key:List.of(Registries.DAMAGE_TYPE,Registries.DIMENSION_TYPE,Registries.BIOME))list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(key)));
    RegistryAccess registry=new RegistryAccess.ImmutableRegistryAccess(list).freeze();Holder<DimensionType> dim=registry.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();World world=new World(registry,dim);
    List<Map<String,Object>> rows=new ArrayList<>();int id=100;
    for(String type:List.of("MANNEQUIN","ARMOR_STAND","LLAMA"))for(Pose pose:List.of(Pose.STANDING,Pose.CROUCHING,Pose.SWIMMING,Pose.FALL_FLYING,Pose.SLEEPING))for(double scale:List.of(.1,.5,1.,2.))for(boolean small:(type.equals("ARMOR_STAND")?List.of(false,true):List.of(false)))for(boolean marker:(type.equals("ARMOR_STAND")?List.of(false,true):List.of(false))){
      try{rows.add(sample(world,type,pose,scale,small,marker,id));}catch(Throwable failure){rows.add(Map.of("type",type,"pose",pose.toString(),"scale",scale,"small",small,"marker",marker,"error",failure.toString()));}id+=10;
    }
    System.out.println("POSE_ATTACHMENT_RESULT "+new com.google.gson.Gson().toJson(rows));
  }
}
