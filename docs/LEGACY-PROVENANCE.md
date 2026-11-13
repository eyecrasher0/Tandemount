# Legacy Forge provenance

The legacy implementation was written independently for Tandemount by
EyeCrasher, under GPL-3.0-only. Its initial project inputs were `AGENTS.md`,
`docs/BEHAVIOR.md`, and `platforms.json`. No predecessor horse-sharing source,
archive, patch, binary, test, project memory, or chat history was consulted.

The implementation does not register a custom entity type. Its final seat is a
vanilla `EntityFallingBlock` with the air block state on all three legacy
versions. It is nonliving, so the vanilla player renderer does not use the
living-vehicle body-yaw path; its client renderer also draws no model for the
air state. `SeatTransformer` blocks its ordinary falling/update path, while
`LegacyRuntime` controls its server-side position and rider relationship. The
existing player-to-horse relationship remains intact. An identity set limits
save, damage, interaction, collision, and server tick guards to managed seats.
The narrow class hooks and reflection helper are original code. See
`LEGACY-RENDERER.md` for the exact client evidence and the remaining native and
visual validation limits.

The following upstream material was consulted or used as build/runtime input:

| Upstream | Exact input and use |
| --- | --- |
| [Forge Maven](https://maven.minecraftforge.net/) | Forge `1.7.10-10.13.4.1614-1.7.10` userdev, installer and universal artifacts; the userdev's `binaries.jar` supplies the actual compile API. Its FML `Mod`, `FMLCorePlugin`, and `CoreModManager` source documents metadata and transformer ordering. |
| [Forge Maven](https://maven.minecraftforge.net/) | Forge `1.8.9-11.15.1.2318-1.8.9` userdev, MDK, installer and universal artifacts; the userdev's `classes.jar` supplies the actual compile API. |
| [Forge Maven](https://maven.minecraftforge.net/) | Forge `1.12.2-14.23.5.2864` universal, userdev3, MDK and installer artifacts; universal supplies the actual compile API. The MDK and userdev configuration were inspected to assess development tooling. |
| [Forge source, 1.7.10](https://github.com/MinecraftForge/MinecraftForge/tree/1.7.10) | Official `EntityInteractEvent` and the vanilla `Entity.java.patch`, to check cancellation and riding hooks. |
| [Forge source, 1.8.9](https://github.com/MinecraftForge/MinecraftForge/tree/1.8.9) | Official `EntityInteractEvent`, `EntityMountEvent`, and vanilla `Entity.java.patch`. |
| [Forge EntityLivingBase patch, 1.8.9](https://github.com/MinecraftForge/MinecraftForge/blob/1.8.9/patches/minecraft/net/minecraft/entity/EntityLivingBase.java.patch) | Checked exact cancellation APIs. The outer `mountEntity(null)` uses `isMounting=true`, calls the cancellable inner `dismountEntity`, then clears relationships regardless of that inner cancellation. An original hook limited to managed seats performs one dismount permission event before the outer clear. |
| [Forge source, 1.12.x](https://github.com/MinecraftForge/MinecraftForge/tree/1.12.x) | Official `EntityMountEvent` and vanilla `Entity.java.patch`. |
| [Official FML source, 1.7.10](https://github.com/MinecraftForge/FML/tree/1.7.10) | Exact Forge 1614 userdev FML sources: `FMLNetworkHandler`, `NetworkDispatcher`, `NetworkRegistry`, `NetworkModHolder`, `FMLHandshakeServerState`, and `ChannelRegistrationHandler`. Checked the loader's vanilla policy and handshake lifecycle. The original bridge calls native three-argument player connection initialization only when FML confirms every network mod accepts vanilla clients. |
| [Official FML player connection patch, 1.7.10](https://github.com/MinecraftForge/FML/blob/1.7.10/patches/minecraft/net/minecraft/server/management/ServerConfigurationManager.java.patch) | Checked `initializeConnectionToPlayer`'s Forge-added third `NetHandlerPlayServer` argument and native login events. |
| [MCP distributions on Forge Maven](https://maven.minecraftforge.net/de/oceanlabs/mcp/) | `mcp_stable:12-1.7.10`, `22-1.8.9`, and `39-1.12`, plus exact 1.7.10/1.8.9 SRG bundles and `mcp_config:1.12.2-20200226.224830`. Only the used MCP/SRG symbol subset is included in production jars. Native tests use a separate test symbol table. |
| [Mojang libraries](https://libraries.minecraft.net/) | `net.minecraft:launchwrapper:1.12`; Forge's own dependency. |
| [ASM on Forge Maven](https://maven.minecraftforge.net/org/ow2/asm/asm-debug-all/5.0.3/) | `org.ow2.asm:asm-debug-all:5.0.3`, for the original narrow bytecode hooks. It is a compile dependency; the loader supplies ASM at runtime. |
| [Mojang version metadata](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) | Exact official Minecraft dedicated servers, downloaded and verified by their official Forge installers, for disposable local runtime checks. |
| Exact official dedicated server binaries above | Inspected vanilla `EntityLivingBase.mountEntity` and `dismountEntity` control flow, plus native spawn/attachment/teleport/player-position packet serializers via `javap` and the exact MCP symbols. Used only as platform API/protocol evidence. The original test-only socket fixture implements those vanilla wire formats; no native bytecode or decompiled implementation is bundled. |

Download caches, native server jars, test worlds, and operational logs stay out
of Git. `tools/legacy-build.py` records the exact compile inputs, artifact SHA256,
and source digest beside each artifact. `tools/legacy-native-test.py` binds native
results to that artifact and launcher SHA256. An official SDK being available,
a successful compilation, a successful bootstrap, native object checks,
protocol observations, and human client visual checks are distinct evidence.

The Forge event source was used as API documentation; its implementation was
not copied into Tandemount. Forge, Minecraft, LaunchWrapper, ASM, and MCP binaries
are not bundled in the mod.
