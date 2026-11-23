import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

public class ClientSeatMotionProbe {
  static class MotionLevel extends OfficialClientSeatProbe.EmptyClientLevel {
    final net.minecraft.world.TickRateManager rate=new net.minecraft.world.TickRateManager();
    final net.minecraft.world.scores.Scoreboard scoreboard=new net.minecraft.world.scores.Scoreboard();
    final net.minecraft.world.attribute.EnvironmentAttributeSystem attributes;
    final Map<Long,net.minecraft.world.level.chunk.LevelChunk> chunks=new HashMap<>();
    int recordedSounds;
    MotionLevel(RegistryAccess r,Holder<DimensionType> d) {
      super(r,d);
      attributes=net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().addConstantLayer(d.value().attributes()).build();
    }
    public net.minecraft.world.TickRateManager tickRateManager() { return rate; }
    public net.minecraft.world.scores.Scoreboard getScoreboard() { return scoreboard; }
    public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes() { return attributes; }
    public void playSeededSound(Entity e,double x,double y,double z,Holder<net.minecraft.sounds.SoundEvent> s,net.minecraft.sounds.SoundSource source,float v,float pitch,long seed) { recordedSounds++; }
    public void playSeededSound(Entity e,Entity f,Holder<net.minecraft.sounds.SoundEvent> s,net.minecraft.sounds.SoundSource source,float v,float pitch,long seed) { recordedSounds++; }
    public net.minecraft.world.level.chunk.LevelChunk getChunk(int x,int z,net.minecraft.world.level.chunk.status.ChunkStatus status,boolean create) {
      long key=((long)x & 0xffffffffL) | ((long)z << 32);
      return chunks.computeIfAbsent(key,k->new net.minecraft.world.level.chunk.EmptyLevelChunk(this,new net.minecraft.world.level.ChunkPos(x,z),registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)));
    }
  }
  static Vec3 target(int tick,String scenario) {
    double x=scenario.equals("stationary")?0:tick*.25,z=0,y=65;
    if(scenario.equals("turn")||scenario.equals("irregular")){x=8*Math.sin(tick*.06);z=8*Math.cos(tick*.06);}
    if(scenario.equals("jump"))y+=Math.max(0,Math.sin(tick*.16))*2;
    return new Vec3(x,y,z);
  }
  static float yaw(int tick,String scenario) {
    if(scenario.equals("turn")||scenario.equals("irregular"))return (float)(tick*.06*180/Math.PI);
    return 0;
  }
  static Vec3 seatTarget(Vec3 horse,float yaw) { double a=Math.toRadians(yaw);return horse.add(Math.sin(a)*.6875,1.375,-Math.cos(a)*.6875); }
  public static void main(String[] args)throws Exception{
    net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    var lookup=VanillaRegistries.createWorldLookup();List<Registry<?>> list=new ArrayList<>();BuiltInRegistries.REGISTRY.forEach(list::add);
    list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(Registries.DAMAGE_TYPE)));
    list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(Registries.DIMENSION_TYPE)));
    list.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(Registries.BIOME)));
    RegistryAccess reg=new RegistryAccess.ImmutableRegistryAccess(list).freeze();
    Holder<DimensionType> dim=reg.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();
    String scenario=args[0];int duration=Integer.parseInt(args[1]);boolean full=Boolean.parseBoolean(args[2]);int limit=args.length>3?Integer.parseInt(args[3]):120;
    MotionLevel level=new MotionLevel(reg,dim);
    Entity horse=EntityTypes.HORSE.create(level,EntitySpawnReason.LOAD);
    FallingBlockEntity falling=new FallingBlockEntity(EntityTypes.FALLING_BLOCK,level);
    falling.recreateFromPacket(new ClientboundAddEntityPacket(42,UUID.randomUUID(),0,66.375,-.6875,0,0,EntityTypes.FALLING_BLOCK,14381,Vec3.ZERO,0));falling.setNoGravity(true);
    Display.BlockDisplay display=new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY,level);
    var setBlock=Display.BlockDisplay.class.getDeclaredMethod("setBlockState",net.minecraft.world.level.block.state.BlockState.class);setBlock.setAccessible(true);setBlock.invoke(display,Blocks.BARRIER.defaultBlockState());
    var setDuration=Display.class.getDeclaredMethod("setPosRotInterpolationDuration",int.class);setDuration.setAccessible(true);setDuration.invoke(display,duration);display.setNoGravity(true);
    Vec3 start=target(0,scenario);horse.snapTo(start,0,0);falling.snapTo(seatTarget(start,0),0,0);display.snapTo(seatTarget(start,0),0,0);
    double fallingMax=0,displayMax=0,fallingTotal=0,displayTotal=0;List<Map<String,Object>> rows=new ArrayList<>();
    for(int tick=1;tick<=limit;tick++){
      boolean update=scenario.equals("irregular")?tick%5==0||tick%7==0:tick%3==0;
      if(update){Vec3 p=target(tick,scenario);float rot=yaw(tick,scenario);horse.moveOrInterpolateTo(p,rot,0);falling.moveOrInterpolateTo(seatTarget(p,rot),rot,0);display.moveOrInterpolateTo(seatTarget(p,rot),rot,0);}
      horse.commonTick();if(full)horse.tick();falling.commonTick();falling.tick();display.commonTick();display.tick();
      Vec3 expected=seatTarget(horse.position(),horse.getYRot());double fe=falling.position().distanceTo(expected),de=display.position().distanceTo(expected);
      fallingMax=Math.max(fallingMax,fe);displayMax=Math.max(displayMax,de);fallingTotal+=fe;displayTotal+=de;
      rows.add(Map.of("tick",tick,"update",update,"horse",List.of(horse.getX(),horse.getY(),horse.getZ()),"falling",List.of(falling.getX(),falling.getY(),falling.getZ()),"display",List.of(display.getX(),display.getY(),display.getZ()),"fallingError",fe,"displayError",de));
    }
    Map<String,Object> result=new LinkedHashMap<>();result.put("scenario",scenario);result.put("ticks",limit);result.put("duration",duration);result.put("originalHorseTick",full);result.put("horseClass",horse.getClass().getName());result.put("horseInterpolation",horse.getInterpolation().getClass().getName());result.put("fallingInterpolation",falling.getInterpolation().getClass().getName());result.put("displayInterpolation",display.getInterpolation().getClass().getName());result.put("displayRemoved",display.isRemoved());result.put("displayFinalPosition",List.of(display.getX(),display.getY(),display.getZ()));result.put("displayNoGravity",display.isNoGravity());result.put("fallingMax",fallingMax);result.put("displayMax",displayMax);result.put("fallingMean",fallingTotal/limit);result.put("displayMean",displayTotal/limit);result.put("rows",rows);
    System.out.println("CLIENT_MOTION_RESULT "+new com.google.gson.Gson().toJson(result));
  }
}
