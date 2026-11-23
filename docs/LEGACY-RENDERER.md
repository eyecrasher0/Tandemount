# Legacy rider renderer audit

The original vanilla clients discard an AIR seat on their first tick. The
released legacy Forge builds use separate following seats and have exact
server, received-packet and original-client checks for those seats. Human
visual confirmation is **unverified**.
[Legacy Forge 1.0.0 verification](https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/verification-legacy-forge.json)

## Falling-block candidates

The exact vanilla living renderers test whether a rider's vehicle is an
`EntityLivingBase`. That path derives the rider's body yaw from the vehicle,
wraps the head/body difference and clamps it. A final nonliving
`EntityFallingBlock` avoids that branch. Its vanilla dimensions must remain the
same on server and client; empty server collision bounds are applied separately.

AIR is not a client-safe falling-block state. All three original client
`onUpdate` methods test AIR, call `setDead` and return before checking whether
the world is remote. A server transformer cannot change this unmodified client.
The original-client fixture explicitly reproduces first-tick AIR removal.

The candidate states were vanilla moving piston (`piston_extension`) on 1.7.10
and vanilla barrier on 1.8.9 / 1.12.2. The moving-piston and 1.8.9 barrier block
render types are -1; 1.12.2 barrier is `INVISIBLE`, while its falling-block
renderer requires `MODEL`. They are non-air and have no falling-block model.
No entity registry entry, resource pack or client mod is added.

1.12.2 supports synchronized native `NoGravity`. The older two clients subtract
0.04 gravity unconditionally. Their tracking-only repair sends native teleport
and velocity packets each server tick. The original client's actual spawn,
metadata, teleport and velocity packet readers are executed before its unchanged
falling-block ticks. The report measures drift both with the observed refresh
frames and for one additional unrefreshed tick. This distinguishes bounded
transport evidence from a guarantee under packet delay or loss.

`tools/legacy-client-test.py` downloads metadata-hashed official clients and
libraries into the task cache. `ClientChecks.java` uses normal Bootstrap,
Minecraft/WorldSettings/WorldClient constructors and native packet handlers.
It does not replace a game class or tick, subclass WorldClient, use Unsafe,
load Tandemount into the client, or claim graphical/human review. It records
the untouched falling-block class digest, full official client/library hashes,
probe source digest and consumed packet-sequence digest.

Native reflection is bound by the complete mapped JVM descriptor, including the
return type. In the obfuscated 1.8.9 client, a void mount method and an AABB
collision method share the same short name and parameter types. Selecting by
those two properties alone can call the collision query without mounting.
The corrected probe retains each exact SRG descriptor and verifies the native
vehicle relationship after the call. Earlier graph observations made with
ambiguous method selection are superseded by descriptor-bound execution.

The 1.12.2 play-state registration in the original client identifies entity
metadata as packet `0x3c`; the probe consumes the actual captured metadata
before checking `hasNoGravity`. Original-client horse controls are also tested
at the correct ownership boundary: the older two releases integrate travel on
the server, while 1.12.2 integrates a local client's vehicle travel. The server
suite checks that the first controlling passenger remains the driver; the
1.12.2 original-client fixture additionally requires actual horizontal travel.

## Native graph capability evidence

`--graph` runs the original native mount and passenger-update methods on a
normally constructed official client world. It tests the direct second child
and horse→driver→falling-block→player adapters and 1200 original mounted ticks.
The direct slot on 1.7.10 / 1.8.9 cannot remain the first driver and a second
seat simultaneously. The simple driver chain can share the horse root, but
its native passenger update replaces the requested rear location with its
parent's center. A forced second child in 1.12.2 has the same centered-offset
problem while preserving the first passenger list element.

These are measurements of the named simple adapters. In particular, old local
and remote player representations use different stance coordinates; a larger
graph routed through the driver needs both views checked before acceptance.

The complete exact MCP override inventory was also inspected. Horizontal
passenger placement is overridden by Chicken, Horse and Boat on 1.7.10 / 1.8.9;
1.12.2 additionally provides Llama and uses AbstractHorse. Chicken uses a
0.1 body-yaw offset; Horse's additional offset depends on rearing; old Boat
uses 0.4 but its native renderer draws the boat model despite the invisibility
flag. Llama uses its native 0.3 offset. Generic Entity sets passenger X/Z to
its own center. Native height/getYOffset overrides and synchronized slime
sizes are additional helper-graph inputs, not arbitrary native transforms.
An exhaustive impossibility result for all combinations of these helpers has
**not** been established. A more complex adapter needs exact client yaw,
geometry, transport, visibility and lifecycle proof before use.

## Exact client and mapping evidence

Client jars came from the official [Mojang version manifest](https://launchermeta.mojang.com/mc/game/version_manifest_v2.json) and the version metadata listed below. SHA-1 is the client hash published in that metadata; SHA-256 was computed locally. The client classes themselves remain the original obfuscated Mojang bytecode. Forge-hosted MCP stable and SRG archives were used only to identify class/member names before inspecting them with `javap -p -c` (OpenJDK `javap` 17.0.20.1).

| Version | Version metadata | Client jar SHA-1 / SHA-256 | Mapped renderer / seat / block classes |
|---|---|---|---|
| 1.7.10 | [Mojang metadata](https://piston-meta.mojang.com/v1/packages/ed5d8789ed29872ea2ef1c348302b0c55e3f3468/1.7.10.json), SHA-256 `265c7e47aad4c74f6085a49667d3a828f48d63f484def2ae70b5e426e5b7cba6` | `e80d9b3bf5085002218d4be59e668bac718abbc6` / `a4fc2284657544e0f4bcc964f927c2fda3e3a205178ed1d5d58883aaf9780cce` ([client jar](https://launcher.mojang.com/v1/objects/e80d9b3bf5085002218d4be59e668bac718abbc6/client.jar)) | `RendererLivingEntity` / `EntityFallingBlock` / `BlockAir` (`boh` / `xj` / `aja`); seat superclass `Entity` (`sa`); renderer `RenderFallingBlock` (`bnq`) |
| 1.8.9 | [Mojang metadata](https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json), SHA-256 `341af5f47a6b5bc137bfe681f4f3533e081041290aabbf9d7ebeae9881de768f` | `3870888a6c3d349d3771a3e9d16c9bf5e076b908` / `14f0d96d1a56fb4f5c3b2233d00699525893fe5ce3dcf181e7de59120595d298` ([client jar](https://launcher.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar)) | `RendererLivingEntity` / `EntityFallingBlock` / `BlockAir` (`bjl` / `uy` / `aey`); seat superclass `Entity` (`pk`); renderer `RenderFallingBlock` (`bix`) |
| 1.12.2 | [Mojang metadata](https://piston-meta.mojang.com/v1/packages/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json), SHA-256 `56dac2e1caa872d7e37a7b5523056cd812dca94f912ad2bd652817bee60edca4` | `0f275bc1547d01fa5f56ba34bdc87d981ee12daf` / `8ada07da5ee77dad3527bd7278fbd05ee1fc8a597813b216a871a2d7d64cc64f` ([client jar](https://piston-data.mojang.com/v1/objects/0f275bc1547d01fa5f56ba34bdc87d981ee12daf/client.jar)) | `RenderLivingBase` / `EntityFallingBlock` / `BlockAir` (`caa` / `ack` / `aom`); seat superclass `Entity` (`vg`); renderer `RenderFallingBlock` (`bzk`) |

Forge-hosted mappings used for those symbols:

| Version | MCP stable archive (SHA-256) | SRG archive (SHA-256) |
|---|---|---|
| 1.7.10 | [`mcp_stable-12-1.7.10.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/12-1.7.10/mcp_stable-12-1.7.10.zip) `83d303a80071582cd07d41b35aa77c178d609a27c69c8472b794636eb50905b0` | [`mcp-1.7.10-srg.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.7.10/mcp-1.7.10-srg.zip) `d49df5c445dcf00ada532ab3b0e4283bf4b05ac13f210647658dfd3cec8b34c2` |
| 1.8.9 | [`mcp_stable-22-1.8.9.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/22-1.8.9/mcp_stable-22-1.8.9.zip) `aeed0aaba9d159b7ce60a21e2dcc36adb249fade65ce2f76c730dd0ec7270763` | [`mcp-1.8.9-srg.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.8.9/mcp-1.8.9-srg.zip) `ea6fdd37e68569a378fd149b0ab7cfede1de7fa3e794f4ed81f9018366fbdc51` |
| 1.12.2 | [`mcp_stable-39-1.12.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/39-1.12/mcp_stable-39-1.12.zip) `13a31f28c11f8f395ffe7e8563ade459f5a0ee46493abbbde3ce6e9493ac4152` | [`mcp-1.12.2-srg.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.12.2/mcp-1.12.2-srg.zip) `e2ddd3a7bb65618ad0de1d8fc0334527e5c346180300d94bfc7d3727b1976b42` |

Forge describes [MCPConfig](https://github.com/MinecraftForge/MCPConfig) as its public SRG-mapping project. These are Forge-hosted MCP mappings, not Mojang-published named mappings.

## Build and test status

The three modules compile against the exact Forge API artifacts listed in
`LEGACY-PROVENANCE.md`. The native runner records fresh artifact-bound checks
for default feet geometry, explicit configuration-unit migration, independent
adjustments, permission/cancellation, rider/horse state, cleanup, real dimension
transfer, chunk unloading, disconnect/reconnect and save/restart. Its wire
fixture receives styled clickable chat and sends the exact encoded adjustment
commands, then observes the resulting live position change.

Run the native and original-client checks together:

```sh
python3 tools/legacy-native-test.py --java /path/to/jdk8 --cache /large/task/cache/legacy --artifacts /path/to/artifacts --port 25844 --client-ticks 240 --accept-eula
```

Run an exact original-client capability probe separately:

```sh
python3 tools/legacy-client-test.py --minecraft 1.7.10 --graph --java /path/to/jdk8 --sdk-cache /large/task/cache/legacy --cache /large/task/cache/client --output /path/to/graph.json
```

Schema 2 native reports use `status` for the early-follower checks and record
the actual horse-root relationship in `protocol_observers.native_attachment`.
Existing and late observer packets drive separate original-client lifetime and
picking probes. Named `final_checks` use `{name, passed}`. The full-matrix
release collector has its own attachment qualification.
The source binding concatenates, in order: `tools/legacy-native-test.py`,
`forge/legacy/native-test/NativeChecks.java`, `tools/legacy-client-test.py`, and
`forge/legacy/client-test/ClientChecks.java`. Operational logs and binaries remain
outside Git. Forge 1.7.10 has no cancellable `EntityMountEvent`; only that event
capability is unavailable. All lifecycle checks remain required.
