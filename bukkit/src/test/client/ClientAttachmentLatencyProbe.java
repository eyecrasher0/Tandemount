/* SPDX-License-Identifier: GPL-3.0-only */
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/** Exact 26.3 attachment components, with independently delayed server yaw.
 *  It does not construct a LocalPlayer or run a rendered client. */
public final class ClientAttachmentLatencyProbe {
  static void tree(Entity entity) {
    entity.commonTick();
    if (entity.isPassenger()) entity.rideTick();
    else entity.tick();
    for (Entity child : entity.getPassengers()) tree(child);
  }
  static List<Double> xyz(Vec3 vector) { return List.of(vector.x, vector.y, vector.z); }
  static double angle(String scenario, int tick) {
    return switch(scenario) {
      case "straight" -> 0;
      case "curve" -> Math.max(0,tick-40)*2.0;
      case "quarter-turn" -> tick<60 ? 0 : 90;
      case "yaw-wrap" -> tick<60 ? 179 : -179;
      default -> throw new IllegalArgumentException(scenario);
    };
  }
  static Map<String,Object> run(PoseAttachmentProbe.World world, String scenario, int delayTicks) throws Exception {
    Entity horse=NativeGraphPositionProbe.create(world,"HORSE",10);
    Entity driver=NativeGraphPositionProbe.create(world,"MANNEQUIN",11);
    Entity anchor=NativeGraphPositionProbe.create(world,"INTERACTION",12);
    Entity rear=NativeGraphPositionProbe.create(world,"LLAMA",13);
    Entity forward=NativeGraphPositionProbe.create(world,"LLAMA",14);
    Entity lift=NativeGraphPositionProbe.create(world,"INTERACTION",15);
    Entity seat=NativeGraphPositionProbe.create(world,"BLOCK_DISPLAY",16);
    Entity rider=NativeGraphPositionProbe.create(world,"MANNEQUIN",17);
    final double back=.5, height=.8125;
    horse.snapTo(8,64,8,(float)angle(scenario,0),0);
    NativeGraphPositionProbe.scale(rear,NativeGraphPositionProbe.rearScale(back));
    NativeGraphPositionProbe.scale(forward,1./16.);
    NativeGraphPositionProbe.width(anchor,0); NativeGraphPositionProbe.width(lift,0);
    NativeGraphPositionProbe.configureHeight(anchor,-64); NativeGraphPositionProbe.configureHeight(lift,0);
    driver.startRiding(horse,true,false); anchor.startRiding(horse,true,false);
    rear.startRiding(anchor,true,false); forward.startRiding(rear,true,false);
    lift.startRiding(forward,true,false); seat.startRiding(lift,true,false); rider.startRiding(seat,true,false);
    NativeGraphPositionProbe.rotate(rear,(float)angle(scenario,0));
    NativeGraphPositionProbe.rotate(forward,(float)angle(scenario,0)+180);
    for(Entity parent:List.of(horse,anchor,rear,forward,lift,seat))for(Entity child:parent.getPassengers())parent.positionRider(child);
    NativeGraphPositionProbe.configureHeight(lift,(float)(height-(rider.getY()-horse.getY())));
    List<Map<String,Object>> rows=new ArrayList<>();
    double maxError=0,maxRenderedError=0,maxStraightTranslationError=0;
    for(int tick=0;tick<160;tick++) {
      horse.commonTick(); horse.tick();
      // This isolates the attachment from movement/input: the root is the
      // requested local prediction and original vanilla child ticks remain.
      horse.setPos(8,64,8+tick*.15); horse.setYRot((float)angle(scenario,tick));
      if(tick%3==0) {
        float serverYaw=(float)angle(scenario,Math.max(0,tick-delayTicks));
        rear.moveOrInterpolateTo(serverYaw,0);
        forward.moveOrInterpolateTo(serverYaw+180,0);
      }
      for(Entity child:horse.getPassengers())tree(child);
      double yaw=Math.toRadians(horse.getYRot());
      Vec3 expected=new Vec3(Math.sin(yaw)*back,height,-Math.cos(yaw)*back);
      Vec3 actual=rider.position().subtract(horse.position());
      double error=actual.distanceTo(expected);
      Vec3 rendered=rider.getPosition(.5F).subtract(horse.getPosition(.5F));
      double renderedError=rendered.distanceTo(expected);
      if(tick>=30) {
        maxError=Math.max(maxError,error);maxRenderedError=Math.max(maxRenderedError,renderedError);
        if(scenario.equals("straight"))maxStraightTranslationError=Math.max(maxStraightTranslationError,error);
      }
      rows.add(Map.of("tick",tick,"horseYaw",horse.getYRot(),"carrierYaw",rear.getYRot(),"offset",xyz(actual),"expected",xyz(expected),"error",error,"halfTickOffset",xyz(rendered),"halfTickError",renderedError));
    }
    Map<String,Object> result=new LinkedHashMap<>(); result.put("scenario",scenario);result.put("serverYawDelayTicks",delayTicks);
    result.put("maxRawOffsetError",maxError);result.put("maxHalfTickOffsetError",maxRenderedError);result.put("straightTranslationError",maxStraightTranslationError);
    result.put("horseRootRetained",rider.getRootVehicle()==horse);result.put("localPlayerConstructed",false);result.put("renderedClientRun",false);
    result.put("method","Original native five-helper graph and original rideTick recursion; requested root path injected, carrier rotation passed through original stepped interpolation every three ticks");result.put("rows",rows);
    for(Entity entity:List.of(rider,seat,lift,forward,rear,anchor,driver,horse))entity.discard();
    return result;
  }
  public static void main(String[] args) throws Exception {
    net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    var lookup=VanillaRegistries.createWorldLookup();List<Registry<?>> registries=new ArrayList<>();BuiltInRegistries.REGISTRY.forEach(registries::add);
    for(var key:List.of(Registries.DAMAGE_TYPE,Registries.DIMENSION_TYPE,Registries.BIOME))registries.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(key)));
    RegistryAccess registry=new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    Holder<DimensionType> dimension=registry.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();
    PoseAttachmentProbe.World world=new PoseAttachmentProbe.World(registry,dimension);
    List<Map<String,Object>> results=new ArrayList<>();
    for(String scenario:List.of("straight","curve","quarter-turn","yaw-wrap")) {
      double previousCurveError=-1;
      for(int delay:List.of(0,4,10,20)) {
        Map<String,Object> result=run(world,scenario,delay);results.add(result);
        if(!Boolean.TRUE.equals(result.get("horseRootRetained")))throw new AssertionError("Native horse root changed");
        double error=((Number)result.get("maxRawOffsetError")).doubleValue();
        if(scenario.equals("straight")&&error>.0001)throw new AssertionError("Pure translation lost its native attachment");
        if(scenario.equals("curve")) {
          if(error<=previousCurveError)throw new AssertionError("Delayed helper yaw failed to reproduce increased turn error");
          previousCurveError=error;
        }
      }
    }
    System.out.println("CLIENT_ATTACHMENT_LATENCY "+new com.google.gson.Gson().toJson(results));
  }
}
