import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/** Test only: untouched official client entity in a controlled empty client-side level. */
public class OfficialClientSeatProbe {
  static class Data implements WritableLevelData {
    public LevelData.RespawnData getRespawnData() { return LevelData.RespawnData.DEFAULT; }
    public long getGameTime() { return 0; }
    public boolean isHardcore() { return false; }
    public net.minecraft.world.Difficulty getDifficulty() { return net.minecraft.world.Difficulty.NORMAL; }
    public boolean isDifficultyLocked() { return false; }
    public void setSpawn(LevelData.RespawnData value) { throw new UnsupportedOperationException("spawn mutation"); }
  }
  static class EmptyClientLevel extends Level {
    final net.minecraft.world.level.border.WorldBorder border = new net.minecraft.world.level.border.WorldBorder();
    EmptyClientLevel(RegistryAccess registry, Holder<DimensionType> dimension) {
      super(new Data(), Level.OVERWORLD, registry, dimension, true, false, 0L, 1000000);
    }
    public BlockState getBlockState(BlockPos p) { return Blocks.AIR.defaultBlockState(); }
    public FluidState getFluidState(BlockPos p) { return Fluids.EMPTY.defaultFluidState(); }
    public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p) { return null; }
    public BlockGetter getChunkForCollisions(int x, int z) { return this; }
    public boolean hasChunk(int x, int z) { return true; }
    public int getSeaLevel() { return 63; }
    public Holder<Biome> getUncachedNoiseBiome(int x,int y,int z) { return registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS); }
    public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() { return net.minecraft.world.flag.FeatureFlags.VANILLA_SET; }
    public net.minecraft.world.level.border.WorldBorder getWorldBorder() { return border; }
    public List<? extends Player> players() { return List.of(); }
    public List<Entity> getEntities(Entity e,AABB box,Predicate<? super Entity> p) { return List.of(); }
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity,T> t,AABB box,Predicate<? super T> p) { return List.of(); }
    protected LevelEntityGetter<Entity> getEntities() { throw new UnsupportedOperationException("entity lookup"); }
    public Entity getEntity(int id) { return null; }
    public Collection<net.minecraft.world.entity.boss.enderdragon.EnderDragonPart> dragonParts() { return List.of(); }
    public net.minecraft.world.TickRateManager tickRateManager() { throw new UnsupportedOperationException("tick rate"); }
    public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(net.minecraft.world.level.saveddata.maps.MapId id) { throw new UnsupportedOperationException("map"); }
    public net.minecraft.world.scores.Scoreboard getScoreboard() { throw new UnsupportedOperationException("scoreboard"); }
    public net.minecraft.world.item.crafting.RecipeAccess recipeAccess() { throw new UnsupportedOperationException("recipes"); }
    public net.minecraft.world.clock.ClockManager clockManager() { throw new UnsupportedOperationException("clocks"); }
    public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes() { throw new UnsupportedOperationException("attributes"); }
    public ChunkSource getChunkSource() { throw new UnsupportedOperationException("chunks"); }
    public void sendBlockUpdated(BlockPos p,BlockState a,BlockState b,int f) { throw new UnsupportedOperationException("block update"); }
    public void playSeededSound(Entity e,double x,double y,double z,Holder<net.minecraft.sounds.SoundEvent> s,net.minecraft.sounds.SoundSource source,float v,float pitch,long seed) { throw new UnsupportedOperationException("sound"); }
    public void playSeededSound(Entity e,Entity f,Holder<net.minecraft.sounds.SoundEvent> s,net.minecraft.sounds.SoundSource source,float v,float pitch,long seed) { throw new UnsupportedOperationException("sound"); }
    public void explode(Entity e,net.minecraft.world.damagesource.DamageSource d,ExplosionDamageCalculator c,double x,double y,double z,float power,boolean fire,Level.ExplosionInteraction interaction,net.minecraft.core.particles.ParticleOptions a,net.minecraft.core.particles.ParticleOptions b,net.minecraft.util.random.WeightedList<net.minecraft.core.particles.ExplosionParticleInfo> particles,Holder<net.minecraft.sounds.SoundEvent> sound) { throw new UnsupportedOperationException("explosion"); }
    public String gatherChunkSourceStats() { return "controlled-empty-client-world"; }
    public void setRespawnData(LevelData.RespawnData d) { throw new UnsupportedOperationException("respawn mutation"); }
    public LevelData.RespawnData getRespawnData() { return LevelData.RespawnData.DEFAULT; }
    public void destroyBlockProgress(int id,BlockPos p,int v) { throw new UnsupportedOperationException("block progress"); }
    public net.minecraft.world.ticks.LevelTickAccess<Block> getBlockTicks() { throw new UnsupportedOperationException("block ticks"); }
    public net.minecraft.world.ticks.LevelTickAccess<Fluid> getFluidTicks() { throw new UnsupportedOperationException("fluid ticks"); }
    public void addParticle(net.minecraft.core.particles.ParticleOptions p,double x,double y,double z,double dx,double dy,double dz) { throw new UnsupportedOperationException("particle"); }
    public void levelEvent(Entity e,int id,BlockPos p,int data) { throw new UnsupportedOperationException("level event"); }
    public void gameEvent(Holder<net.minecraft.world.level.gameevent.GameEvent> e,Vec3 v,net.minecraft.world.level.gameevent.GameEvent.Context c) { throw new UnsupportedOperationException("game event"); }
    public void close() { }
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  static Registry<?> copyRegistry(HolderLookup.RegistryLookup lookup) {
    MappedRegistry registry=new MappedRegistry(lookup.key(), Lifecycle.stable());
    lookup.listElements().forEach(value -> { Holder.Reference h=(Holder.Reference)value; registry.register(h.key(),h.value(),RegistrationInfo.BUILT_IN); });
    registry.bindAllTagsToEmpty();
    return registry.freeze();
  }
  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    var lookup=VanillaRegistries.createWorldLookup();
    List<Registry<?>> registries=new ArrayList<>();
    BuiltInRegistries.REGISTRY.forEach(registries::add);
    registries.add(copyRegistry(lookup.lookupOrThrow(Registries.DAMAGE_TYPE)));
    registries.add(copyRegistry(lookup.lookupOrThrow(Registries.DIMENSION_TYPE)));
    registries.add(copyRegistry(lookup.lookupOrThrow(Registries.BIOME)));
    RegistryAccess registry=new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    Holder<DimensionType> dimension=registry.lookupOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();
    EmptyClientLevel level=new EmptyClientLevel(registry, dimension);
    int stateId=Integer.parseInt(args[0]); boolean noGravity=Boolean.parseBoolean(args[1]); int ticks=Integer.parseInt(args[2]);
    FallingBlockEntity seat=new FallingBlockEntity(EntityTypes.FALLING_BLOCK, level);
    seat.recreateFromPacket(new ClientboundAddEntityPacket(42,UUID.fromString("00000000-0000-0000-0000-000000000042"),8,65,8,0,0,EntityTypes.FALLING_BLOCK,stateId,Vec3.ZERO,0));
    seat.setNoGravity(noGravity);
    double y=seat.getY(); int ran=0;
    for (; ran<ticks && !seat.isRemoved();ran++) seat.tick();
    System.out.println("CLIENT_SEAT_RESULT {\"stateId\":"+stateId+",\"block\":\""+BuiltInRegistries.BLOCK.getKey(seat.getBlockState().getBlock())+"\",\"noGravity\":"+seat.isNoGravity()+",\"clientSide\":"+level.isClientSide()+",\"ticks\":"+ran+",\"removed\":"+seat.isRemoved()+",\"initialY\":"+y+",\"finalY\":"+seat.getY()+",\"renderShape\":\""+seat.getBlockState().getRenderShape()+"\"}");
  }
}
