import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/** Native positional components only: no LocalPlayer or rendered-camera claim. */
public class ClientPassengerInterpolationProbe {
    static Map<String,Object> run(ClientSeatMotionProbe.MotionLevel level, boolean display) throws Exception {
        Entity seat;
        if (display) {
            Display.BlockDisplay nativeDisplay = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
            var state = Display.BlockDisplay.class.getDeclaredMethod("setBlockState", net.minecraft.world.level.block.state.BlockState.class);
            state.setAccessible(true); state.invoke(nativeDisplay, Blocks.BARRIER.defaultBlockState());
            var duration = Display.class.getDeclaredMethod("setPosRotInterpolationDuration", int.class);
            duration.setAccessible(true); duration.invoke(nativeDisplay, 3);
            nativeDisplay.setId(44);
            seat = nativeDisplay;
        } else {
            FallingBlockEntity nativeFalling = new FallingBlockEntity(EntityTypes.FALLING_BLOCK, level);
            nativeFalling.recreateFromPacket(new ClientboundAddEntityPacket(42, UUID.randomUUID(), 8, 65, 8, 0, 0,
                    EntityTypes.FALLING_BLOCK, Block.getId(Blocks.BARRIER.defaultBlockState()), Vec3.ZERO, 0));
            seat = nativeFalling;
        }
        seat.setNoGravity(true); seat.snapTo(8, 65, 8, 0, 0);
        ArmorStand rider = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.LOAD);
        rider.setId(display ? 45 : 43);
        rider.setNoGravity(true);
        if (!rider.startRiding(seat, true, false)) throw new AssertionError("Native passenger mount failed");
        seat.positionRider(rider); rider.setOldPosAndRot();
        Vec3 before = rider.getEyePosition(.5F);
        seat.moveOrInterpolateTo(new Vec3(9, 65, 8), 0, 0);
        boolean interpolating = seat.isInterpolating();
        boolean hasPassenger = seat.hasIndirectPassenger(rider);
        boolean snapDecision = !interpolating && hasPassenger;
        // Exercise the native positional methods used by the audited packet
        // branch. The generic passenger is not presented as a LocalPlayer.
        if (snapDecision) { seat.positionRider(rider); rider.setOldPosAndRot(); }
        Vec3 afterPacket = rider.getEyePosition(.5F);
        List<Map<String,Object>> ticks = new ArrayList<>();
        for (int tick = 1; tick <= 3; tick++) {
            seat.commonTick(); seat.tick(); rider.commonTick(); rider.rideTick();
            ticks.add(Map.of("tick", tick, "eyeAtStart", rider.getEyePosition(0).x,
                    "eyeAtHalf", rider.getEyePosition(.5F).x, "eyeAtEnd", rider.getEyePosition(1).x));
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("seatClass", seat.getClass().getName()); result.put("riderClass", rider.getClass().getName());
        result.put("interpolationHandler", seat.getInterpolation().getClass().getName());
        result.put("hasIndirectPassenger", hasPassenger); result.put("controllingPassengerNull", seat.getControllingPassenger() == null);
        result.put("isLocalInstanceAuthoritative", seat.isLocalInstanceAuthoritative());
        result.put("isInterpolatingAfterPacket", interpolating); result.put("auditedImmediateSnapBranch", snapDecision);
        result.put("eyeXBeforePacket", before.x); result.put("eyeXAfterPacket", afterPacket.x);
        result.put("packetTimeEyeDisplacement", afterPacket.distanceTo(before)); result.put("ticks", ticks);
        result.put("finalRiderX", rider.getX()); result.put("removed", seat.isRemoved() || rider.isRemoved());
        if (!hasPassenger || seat.getControllingPassenger() != null || seat.isLocalInstanceAuthoritative() || seat.isRemoved() || rider.isRemoved())
            throw new AssertionError("Native noncontrolling passenger invariant");
        if (display ? !interpolating || snapDecision || afterPacket.distanceTo(before) != 0 : interpolating || !snapDecision || afterPacket.distanceTo(before) < .99)
            throw new AssertionError("Native interpolation/snap decision control");
        if (Math.abs(rider.getX() - 9) > 1E-9) throw new AssertionError("Native rideTick did not finish positioning passenger");
        return result;
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var lookup = VanillaRegistries.createWorldLookup(); List<Registry<?>> registries = new ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(registries::add);
        for (var key : List.of(Registries.DAMAGE_TYPE, Registries.DIMENSION_TYPE, Registries.BIOME))
            registries.add(OfficialClientSeatProbe.copyRegistry(lookup.lookupOrThrow(key)));
        RegistryAccess registry = new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
        Holder<DimensionType> dimension = registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                .get(net.minecraft.resources.Identifier.withDefaultNamespace("overworld")).orElseThrow();
        ClientSeatMotionProbe.MotionLevel level = new ClientSeatMotionProbe.MotionLevel(registry, dimension);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("kind", "native-passenger-interpolation-components");
        result.put("localPlayerConstructed", false); result.put("renderedCameraVerified", false);
        result.put("fallingControl", run(level, false)); result.put("display", run(level, true));
        result.put("status", "passed");
        System.out.println("CLIENT_PASSENGER_RESULT " + new com.google.gson.Gson().toJson(result));
    }
}
