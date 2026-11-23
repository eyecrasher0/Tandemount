import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.*;

/** Original client raycast components with native entities, no renderer claim. */
public class NativeAttachmentRayProbe {
  static class World extends PoseAttachmentProbe.World {
    final List<Entity> all=new ArrayList<>();World(RegistryAccess r,Holder<DimensionType> d){super(r,d);}
    public List<Entity> getEntities(Entity ignored,AABB box,Predicate<? super Entity> predicate){return all.stream().filter(e->e!=ignored&&e.getBoundingBox().intersects(box)&&predicate.test(e)).toList();}
  }
  public static void main(String[] args)throws Exception{
    net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    var lookup=VanillaRegistries.createWorldLookup();List<Registry<?>> list=new ArrayList<>();BuiltInRegistries.REGISTRY.forEach(list::add);
    for(var key:List.of(Registries.DAMAGE_TYPE,Registries.DIMENSION_TYPE,Registries.BIOME))list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(key)));
    RegistryAccess reg=new RegistryAccess.ImmutableRegistryAccess(list).freeze();Holder<DimensionType> dim=reg.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();World world=new World(reg,dim);
    Entity horse=NativeGraphPositionProbe.create(world,"HORSE",10),driver=NativeGraphPositionProbe.create(world,"MANNEQUIN",1),rear=NativeGraphPositionProbe.create(world,"LLAMA",11),forward=NativeGraphPositionProbe.create(world,"LLAMA",12),seat=NativeGraphPositionProbe.create(world,"INTERACTION",13),rider=NativeGraphPositionProbe.create(world,"MANNEQUIN",8),observer=NativeGraphPositionProbe.create(world,"MANNEQUIN",9),spacer=NativeGraphPositionProbe.create(world,"INTERACTION",14),finalSeat=NativeGraphPositionProbe.create(world,"BLOCK_DISPLAY",15);
    boolean lowered=args.length>0&&args[0].equals("lowered");NativeGraphPositionProbe.width(spacer,0);NativeGraphPositionProbe.configureHeight(spacer,-64);
    horse.snapTo(8,64,8,0,0);horse.setInvisible(false);observer.snapTo(8,64,11,180,0);driver.setInvisible(false);rider.setInvisible(false);
    NativeGraphPositionProbe.scale(rear,NativeGraphPositionProbe.rearScale(.5));NativeGraphPositionProbe.scale(forward,1./16.);NativeGraphPositionProbe.width(seat,0);NativeGraphPositionProbe.configureHeight(seat,0);
    driver.startRiding(horse,true,false);if(lowered){spacer.startRiding(horse,true,false);rear.startRiding(spacer,true,false);}else rear.startRiding(horse,true,false);forward.startRiding(rear,true,false);seat.startRiding(forward,true,false);finalSeat.startRiding(seat,true,false);rider.startRiding(finalSeat,true,false);
    NativeGraphPositionProbe.rotate(rear,0);NativeGraphPositionProbe.rotate(forward,180);horse.positionRider(driver);if(lowered){horse.positionRider(spacer);spacer.positionRider(rear);}else horse.positionRider(rear);rear.positionRider(forward);forward.positionRider(seat);seat.positionRider(finalSeat);finalSeat.positionRider(rider);
    NativeGraphPositionProbe.configureHeight(seat,(float)(.8125-(rider.getY()-horse.getY())));seat.positionRider(finalSeat);finalSeat.positionRider(rider);
    world.all.addAll(List.of(horse,driver,rear,forward,seat,finalSeat,rider,observer));if(lowered)world.all.add(spacer);List<Map<String,Object>> rows=new ArrayList<>();
    for(Entity viewer:List.of(observer,driver,rider))for(double targetY:List.of(64.25,64.75,65.25,65.5,66.,67.)){
      Vec3 start=viewer.getEyePosition(1);Vec3 end=new Vec3(8,targetY,8);AABB search=new AABB(start,end).inflate(1);
      EntityHitResult hit=ProjectileUtil.getEntityHitResult(viewer,start,end,search,e->!e.isSpectator()&&e.isPickable(),100);
      rows.add(Map.of("viewer",viewer.getId(),"viewerRoot",viewer.getRootVehicle().getId(),"start",List.of(start.x,start.y,start.z),"targetY",targetY,"hitId",hit==null?-1:hit.getEntity().getId(),"hitType",hit==null?"none":hit.getEntity().getType().toString(),"hitInvisible",hit!=null&&hit.getEntity().isInvisible()));
    }
    for(Entity viewer:List.of(driver,rider))for(Vec3 direction:List.of(new Vec3(0,-1,0),new Vec3(0,1,0),new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,0,1),new Vec3(0,0,-1),new Vec3(1,-1,1).normalize(),new Vec3(-1,-1,-1).normalize())){
      Vec3 start=viewer.getEyePosition(1),end=start.add(direction.scale(4.5));EntityHitResult hit=ProjectileUtil.getEntityHitResult(viewer,start,end,new AABB(start,end).inflate(1),e->!e.isSpectator()&&e.isPickable(),4.5*4.5);
      rows.add(Map.of("viewer",viewer.getId(),"direction",List.of(direction.x,direction.y,direction.z),"hitId",hit==null?-1:hit.getEntity().getId(),"hitType",hit==null?"none":hit.getEntity().getType().toString(),"hitInvisible",hit!=null&&hit.getEntity().isInvisible()));
    }
    System.out.println("NATIVE_RAY_RESULT "+new com.google.gson.Gson().toJson(rows));
  }
}
