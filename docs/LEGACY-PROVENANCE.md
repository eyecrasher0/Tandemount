# Legacy Forge provenance

The legacy implementation was written independently for Tandemount by
EyeCrasher, under GPL-3.0-only. Its initial project inputs were `AGENTS.md`,
`docs/BEHAVIOR.md`, and `platforms.json`. No predecessor horse-sharing source,
archive, patch, binary, test, project memory, or chat history was consulted.

The released 1.0.0 implementation uses unmodified client entity types for the
separately following seats. Its runtime changes and regression fixtures come
from Tandemount source commit `bbb095c92ed2e40a6d863de70b64ef2a0e3e94f8`. The
release report binds that archive to the exact Forge builds and native and
client checks. [Corresponding legacy Forge source](https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/Tandemount-1.0.0-legacy-forge-source.zip) ·
[Legacy Forge 1.0.0 verification](https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/verification-legacy-forge.json)

The narrow class hooks, runtime and verification fixtures are original code.
An identity set limits save, damage, interaction, collision and server tick
guards to managed seats. Original-client probes use unchanged whole official
client jars, normal native world constructors and actual packet/tick methods;
no copied native implementation or transformed client bytecode is distributed.

The following upstream material was consulted or used as build/runtime input:

| Upstream | Exact input and use |
| --- | --- |
| [Forge Maven](https://maven.minecraftforge.net/) | Forge `1.7.10-10.13.4.1614-1.7.10` userdev, installer and universal artifacts; the userdev's `binaries.jar` supplies the actual compile API. Its FML `Mod`, `FMLCorePlugin`, and `CoreModManager` source documents metadata and transformer ordering. |
| [Forge Maven](https://maven.minecraftforge.net/) | Forge `1.8.9-11.15.1.2318-1.8.9` userdev, MDK, installer and universal artifacts; the userdev's `classes.jar` supplies the actual compile API. |
| [Forge Maven](https://maven.minecraftforge.net/) | Forge `1.12.2-14.23.5.2864` universal, userdev3, MDK and installer artifacts; universal supplies the actual compile API. The MDK and userdev configuration were inspected to assess development tooling. |
| [Forge source, 1.7.10](https://github.com/MinecraftForge/MinecraftForge/tree/1.7.10) | Official `EntityInteractEvent` and the vanilla `Entity.java.patch`, to check cancellation and riding hooks. |
| [Forge source, 1.8.9](https://github.com/MinecraftForge/MinecraftForge/tree/1.8.9) | Official `EntityInteractEvent`, `EntityMountEvent`, and vanilla `Entity.java.patch`. |
| [Forge EntityLivingBase patch, 1.8.9](https://github.com/MinecraftForge/MinecraftForge/blob/1.8.9/patches/minecraft/net/minecraft/entity/EntityLivingBase.java.patch) | Checked both cancellation points. A managed driver or passenger goes through the native null-target permission and then the vehicle's dismount permission. An allowed attempt emits each event once before vanilla clears the relationship. |
| [Forge source, 1.12.x](https://github.com/MinecraftForge/MinecraftForge/tree/1.12.x) | Official `EntityMountEvent` and vanilla `Entity.java.patch`. |
| [Official FML source, 1.7.10](https://github.com/MinecraftForge/FML/tree/1.7.10) | Exact Forge 1614 userdev FML sources: `FMLNetworkHandler`, `NetworkDispatcher`, `NetworkRegistry`, `NetworkModHolder`, `FMLHandshakeServerState`, and `ChannelRegistrationHandler`. Checked the original timeout and `NetworkRegistry.isVanillaAccepted(CLIENT)` gate for the short delay and `NetworkDispatcher.completeServerSideConnection(VANILLA)`. |
| [Official FML player connection patch, 1.7.10](https://github.com/MinecraftForge/FML/blob/1.7.10/patches/minecraft/net/minecraft/server/management/ServerConfigurationManager.java.patch) | Checked `initializeConnectionToPlayer`'s Forge-added third `NetHandlerPlayServer` argument and native login events. |
| [MCP distributions on Forge Maven](https://maven.minecraftforge.net/de/oceanlabs/mcp/) | `mcp_stable:12-1.7.10`, `22-1.8.9`, and `39-1.12`, plus exact 1.7.10/1.8.9 SRG bundles and `mcp_config:1.12.2-20200226.224830`. Only the used MCP/SRG symbol subset is included in production jars. Native tests use a separate test symbol table. |
| [Mojang libraries](https://libraries.minecraft.net/) | `net.minecraft:launchwrapper:1.12`; Forge's own dependency. |
| [ASM on Forge Maven](https://maven.minecraftforge.net/org/ow2/asm/asm-debug-all/5.0.3/) | `org.ow2.asm:asm-debug-all:5.0.3`, for the original narrow bytecode hooks. It is a compile dependency; the loader supplies ASM at runtime. |
| [Mojang version metadata](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) | Exact official Minecraft dedicated servers, downloaded and verified by their official Forge installers, for disposable local runtime checks. |
| Exact official dedicated server binaries above | Inspected vanilla `EntityLivingBase.mountEntity` and `dismountEntity` control flow, plus native spawn/attachment/teleport/player-position packet serializers via `javap` and the exact MCP symbols. Used only as platform API/protocol evidence. The original test-only socket fixture implements those vanilla wire formats; no native bytecode or decompiled implementation is bundled. |
| Exact Forge userdev patches and official server binaries above | Inspected `EntityPlayerMP.travelToDimension` (1.7.10/1.8.9), `EntityPlayerMP.changeDimension` (1.12.2), `ServerConfigurationManager`/`PlayerList` transfer APIs, `World` dangerous-removal methods, `DimensionManager.getWorld`/`initDimension` APIs, and the vanilla End `Teleporter.placeInPortal` branch. The player dimension APIs do not clear the ordinary horse relationship themselves. The test-only fixture explicitly initializes the standard End if Forge has unloaded it, records whether setup initialized or reused it, then calls the real player dimension API for both ride participants. It verifies world/dimension/entity membership, managed-seat cleanup and source-horse preservation, then clears the ordinary driver link during teardown. It does not simulate a world change by editing a field. Dimension and local landing-platform preparation are confined to the disposable fixture, not production runtime behavior. |

Download caches, native server jars, test worlds, and operational logs stay out
of Git. `tools/legacy-build.py` records the exact compile inputs, artifact SHA256,
and source digest beside each artifact. `tools/legacy-native-test.py` binds native
results to that artifact and launcher SHA256. An official SDK being available,
a successful compilation, a successful bootstrap, native object checks,
protocol observations, and human client visual checks are distinct evidence.

The Forge event source was used as API documentation; its implementation was
not copied into Tandemount. Forge, Minecraft, LaunchWrapper, ASM, and MCP binaries
are not bundled in the mod.

Additional upstream use for the client-lifetime repair and capability audit:

- Exact official 1.7.10 / 1.8.9 / 1.12.2 client jars and Mojang metadata-hashed
  libraries: native Bootstrap, WorldSettings, WorldClient, Minecraft constructor
  thread identity, FallingBlock state/physics, packet readers/handlers, native
  mount/updateRidden/passenger placement, and nonliving renderer control flow.
- The exact Forge-hosted MCP stable/SRG inputs above: complete inventory of
  passenger-position/getMountedYOffset/getYOffset overrides, including Chicken,
  Horse/AbstractHorse, Boat, Llama, and native slime-size synchronization.
- Exact 1.7.10 vanilla NetHandlerPlayServer.setPlayerLocation and original client
  NetHandlerPlayClient.handlePlayerPosLook/C06 packet serializer: S08 carries
  stance Y, and its acknowledgement carries bounding-box minY followed by posY.
  The isolated wire fixture uses those units and ordinary movement acknowledgements.
- Exact original client method descriptors from the Forge-hosted SRG inputs:
  overloaded obfuscated methods are selected by their complete JVM signature,
  including return type. The fixture does not infer a mount from a short name
  and matching parameters alone.
- Exact original 1.12.2 `EnumConnectionState` play-state registration and
  `SPacketEntityMetadata` reader: metadata packet `0x3c` carries the native
  NoGravity value consumed by the unchanged client. The original horse travel
  bytecode and controlling-passenger APIs establish server travel on 1.7.10 /
  1.8.9 and local-client travel on 1.12.2.
- Exact Forge 1.8.9 build 2318 userdev `sources.zip`:
  `NetworkDispatcher.insertIntoChannel`, `handlerAdded`,
  `serverToClientHandshake`, `serverInitiateHandshake`, and
  `completeServerSideConnection`, together with `FMLNetworkHandler` and
  `NetworkRegistry.isVanillaAccepted`. The asynchronous channel handler can
  start the handshake while immediate unmarked vanilla completion is already
  running. The independent adapter uses existing native login initialization
  only after FML's vanilla-policy approval and, on 1.8.9, a false/absent FML marker; it
  preserves the official connected-event ordering for each old loader.
- Exact official old `EntityTrackerEntry.updatePlayerEntity` and native
  `S1BPacketEntityAttach` serializers: the accepted player relationships are
  sent only after all four native tracking sets already include the observer,
  following interaction/mount cancellation checks. Native `IntHashMap.lookup`
  and `trackedEntityHashTable` identify those real tracker entries. No entity
  is force-spawned or made visible.
- Exact original `C15PacketClientSettings` / `CPacketClientSettings` serializers:
  the wire fixture sends ordinary client settings after JoinGame and waits for
  the first native server position before beginning periodic movement frames.
- The official 1.12.2 `EnumConnectionState` Play registration and
  `CPacketVehicleMove` format identify the driver movement packet used by the
  loopback test. [Official 1.12.2 version metadata](https://piston-meta.mojang.com/v1/packages/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json)

The native report's source digest binds both test runners, both Java fixtures,
the build, metadata, runtime storage and evidence helpers, and `project.json`.
Original-client probes additionally record
client class, metadata, libraries, packet sequence and probe-source digests.
A native test, received packet, original client method execution and human
visual review remain distinct evidence. The legacy follower report records its
independent vehicle root separately from the observed riding checks.
