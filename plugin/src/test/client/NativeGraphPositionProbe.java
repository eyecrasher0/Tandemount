import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/** Native SDK component test only, not a server implementation. */
public class NativeGraphPositionProbe {
  static List<Double> xyz(Vec3 v){return List.of(v.x,v.y,v.z);}
  static double rearScale(double back){return back/(double).3F+1./16.;}
  static Entity create(PoseAttachmentProbe.World w,String type,int id)throws Exception{EntityType<?> t=(EntityType<?>)EntityTypes.class.getField(type).get(null);Entity e=t.create(w,EntitySpawnReason.LOAD);e.setId(id);e.setNoGravity(true);e.setSilent(true);e.setInvisible(true);if(e instanceof net.minecraft.world.entity.Mob m)m.setNoAi(true);return e;}
  static void scale(Entity e,double v){LivingEntity l=(LivingEntity)e;l.getAttribute(Attributes.SCALE).setBaseValue(v);l.getScale();l.refreshDimensions();}
  static void rotate(Entity e,float yaw){e.setYRot(yaw);if(e instanceof LivingEntity l){l.yBodyRot=yaw;l.setYHeadRot(yaw);}}
  static void configureHeight(Entity seat,float height)throws Exception{var m=Interaction.class.getDeclaredMethod("setHeight",float.class);m.setAccessible(true);m.invoke(seat,height);}
  static void width(Entity seat,float width)throws Exception{var m=Interaction.class.getDeclaredMethod("setWidth",float.class);m.setAccessible(true);m.invoke(seat,width);}
  public static void main(String[] args)throws Exception{
    net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    var lookup=VanillaRegistries.createWorldLookup();List<Registry<?>> registries=new ArrayList<>();BuiltInRegistries.REGISTRY.forEach(registries::add);
    for(var key:List.of(Registries.DAMAGE_TYPE,Registries.DIMENSION_TYPE,Registries.BIOME))registries.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(key)));
    RegistryAccess registry=new RegistryAccess.ImmutableRegistryAccess(registries).freeze();Holder<DimensionType> dim=registry.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();
    PoseAttachmentProbe.World world=new PoseAttachmentProbe.World(registry,dim);
    List<Map<String,Object>> rows=new ArrayList<>();int id=100;boolean lowered=args.length>0&&args[0].equals("lowered");int limit=args.length>1?Integer.parseInt(args[1]):120;
    for(double height:List.of(.25,.8125,1.375,3.))for(double back:List.of(0.,.0625,.5,1.,2.)){
      Entity horse=create(world,"HORSE",id++),driver=create(world,"ARMOR_STAND",id++),rear=create(world,"LLAMA",id++),forward=create(world,"LLAMA",id++),seat=create(world,"INTERACTION",id++),rider=create(world,"MANNEQUIN",id++),anchor=create(world,"INTERACTION",id++),finalSeat=create(world,"BLOCK_DISPLAY",id++);
      double baseY=lowered?world.getMinY():64;horse.setInvisible(false);horse.snapTo(8,baseY,8,0,0);scale(rear,rearScale(back));scale(forward,1./16.);width(seat,0);configureHeight(seat,0);width(anchor,0);configureHeight(anchor,-64);
      driver.startRiding(horse,true,false);if(lowered){anchor.startRiding(horse,true,false);rear.startRiding(anchor,true,false);}else rear.startRiding(horse,true,false);forward.startRiding(rear,true,false);seat.startRiding(forward,true,false);finalSeat.startRiding(seat,true,false);rider.startRiding(finalSeat,true,false);
      rotate(rear,0);rotate(forward,180);if(lowered){horse.positionRider(anchor);anchor.positionRider(rear);}else horse.positionRider(rear);rear.positionRider(forward);forward.positionRider(seat);seat.positionRider(finalSeat);finalSeat.positionRider(rider);
      double initial=rider.getY()-horse.getY();
      configureHeight(seat,(float)(height-initial));
      double maxError=0;List<Map<String,Object>> samples=new ArrayList<>();
      for(int tick=0;tick<limit;tick++){
        float yaw=(float)(Math.sin(tick*.05)*180);double a=Math.toRadians(yaw);horse.snapTo(8+Math.sin(tick*.05)*4,baseY+Math.max(0,Math.sin(tick*.15)),8+tick*.04,yaw,0);horse.commonTick();horse.tick();horse.positionRider(driver);
        rotate(rear,yaw);rotate(forward,yaw+180);if(lowered){horse.positionRider(anchor);anchor.commonTick();anchor.rideTick();anchor.positionRider(rear);}else horse.positionRider(rear);
        rear.commonTick();rear.rideTick();rear.positionRider(forward);forward.commonTick();forward.rideTick();forward.positionRider(seat);seat.commonTick();seat.rideTick();seat.positionRider(finalSeat);finalSeat.commonTick();finalSeat.rideTick();finalSeat.positionRider(rider);rider.commonTick();rider.rideTick();
        Vec3 actual=rider.position().subtract(horse.position()),expected=new Vec3(Math.sin(a)*back,height,-Math.cos(a)*back);double error=actual.distanceTo(expected);maxError=Math.max(maxError,error);
        if(tick%30==0)samples.add(Map.of("tick",tick,"yaw",yaw,"actual",xyz(actual),"expected",xyz(expected),"rearYaw",rear.getYRot(),"forwardYaw",forward.getYRot(),"error",error));
      }
      Map<String,Object> row=new LinkedHashMap<>();row.put("height",height);row.put("back",back);row.put("nativeInteractionHeight",((Interaction)seat).getDimensions(seat.getPose()).height());row.put("rootHorse",rider.getRootVehicle()==horse);row.put("maxError",maxError);row.put("carrierPickable",List.of(rear.isPickable(),forward.isPickable(),seat.isPickable()));row.put("clientCarrierWidths",List.of(rear.getBbWidth(),forward.getBbWidth(),seat.getBbWidth()));row.put("samples",samples);row.put("ticks",limit);row.put("loweredAnchor",lowered);row.put("baseY",baseY);row.put("worldMinY",world.getMinY());row.put("removed",List.of(horse,driver,anchor,rear,forward,seat,finalSeat,rider).stream().anyMatch(Entity::isRemoved));row.put("finalVehicleIsNonliving",!(rider.getVehicle() instanceof LivingEntity));row.put("finalVehicleDistance",rider.position().distanceTo(finalSeat.position()));row.put("nativeDismountPointDistance",finalSeat.getDismountLocationForPassenger((LivingEntity)rider).distanceTo(rider.position()));rows.add(row);
    }
    System.out.println("NATIVE_GRAPH_POSITION_RESULT "+new com.google.gson.Gson().toJson(rows));
  }
}
