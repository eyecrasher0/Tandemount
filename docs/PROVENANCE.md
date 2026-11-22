# Implementation provenance

Tandemount was implemented independently after the owner's instruction to
continue. Publication status is recorded in [PUBLICATION.md](PUBLICATION.md).

The specification author read the predecessor's user documentation and handoff,
which included internal design descriptions. That material is excluded from
the implementers' input. No predecessor Java sources, patches, tests, or binary
code have been read or incorporated into this repository.

The clean-room separation is a behavioral specification author and
fresh implementation conversations receiving `BEHAVIOR.md`, this repository's
instructions, and official platform specifications. On 13 November 2026 the
owner instructed the coordinator to continue after a limits interruption. Three
implementers were started with no inherited conversation: plugin platforms,
modern mod loaders, and legacy Forge. Their input explicitly excludes
predecessor material. They share new implementation findings with each other.

The platform resolver consults official Mojang, Fabric, MinecraftForge,
NeoForged, PaperMC, PurpurMC, and SpigotMC metadata. Spigot `BuildData/info.json`
is read at pinned official Git revisions to identify the actual game version.
This metadata is used for build selection and is not horse-sharing code.
The Forge-specific sources, mappings, renderer findings, and compile provenance
for 1.7.10, 1.8.9, and 1.12.2 are recorded in `LEGACY-PROVENANCE.md` and
`LEGACY-RENDERER.md`.

## Plugin seat renderer audit

The client-side renderer audit used the official Mojang version manifest at
`https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` (SHA-256
`f27326fe134922d1c983d35e22d6508c8d0e93c790b60b9528863b085cef1ba6`) to locate
these exact releases and official client artifacts:

| Version | Official version metadata SHA-256 | Official client JAR SHA-256 | Official client mappings SHA-256 |
| --- | --- | --- | --- |
| 1.20.6 | `18592898f8768632f982894d094cfff045f20d14928a11c018a8e74fb5b955ad` | `02dfd345ac1ad55692d5dbc8486ac7e4fea72cd54ac494a79cd48963048e56b2` | `27f4ef3a9362e9874e2c33edac981b6f485ad97f92c4e35b497525b646aae745` |
| 1.21.11 | `4a59771fce3439436515cc52b3cc685f18c197bb8e73fadd3b29072267a54e3a` | `1473c9489ac50fda3c435049a76a70d61a10b8610db27f5ba9d8756b686cd3bd` | `517799a8485e107e932dc1bd27c002b2d0b9207eb2396b685bcfe6c3321a9fbd` |
| 26.3 | `a1831b3e5ffa479c02d88a441794ff0a69813215e5d0e945edf03a99c7608c2e` | `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d` | Not published in the official version metadata |

The client JARs came from the `downloads.client.url` fields of the exact
version metadata; both published client mapping files came from
`downloads.client_mappings.url`. Each downloaded file's SHA-1 matched the hash
published in Mojang's metadata. The 26.3 client JAR contains named
`LivingEntityRenderer`, `AvatarRenderer`, and `FallingBlockEntity` classes, so
its bytecode was inspected directly. No third-party code or horse-sharing
implementation was consulted.

The renderer/model findings and the separate visual-validation status are in
`PLUGIN_RENDERER.md`. Raw downloaded client artifacts and `javap` output are
kept in the ignored task cache/evidence directories, not in Git.

The focused plugin builds use PaperMC's official Maven API artifacts, following
the [PaperMC project setup documentation](https://docs.papermc.io/paper/dev/project-setup/)
and repository `https://repo.papermc.io/repository/maven-public/`:

| Target | API coordinate | API JAR SHA-256 | Published POM SHA-256 |
| --- | --- | --- | --- |
| 1.21.11 | `io.papermc.paper:paper-api:1.21.11-R0.1-20260511.115010-91` | `c577b181c11a8674310e56c92a91e31c010b7f04c9bd10b91c3be18374401070` | `cc6f86c850e5199decac1364fc6110f32c4fe7df31a68700438eeaaa190b38ae` |
| 26.3 | `io.papermc.paper:paper-api:26.3.build.249-stable` | `d004db09ebb3ab27c960aed8daf0a9679abf834a586096fc8b3e13afc8cd7aba` | `645716dc6261baaaed7387181c6d87b87d54a9d3c7853851437bacb353f16fbb` |

The exact compile classpath and SHA-256 of each dependency JAR are recorded in
the ignored `.agents/plugin-build-*/compile.json` records. These builds establish
source compatibility with those APIs; they do not establish runtime behavior.

## Modern seat renderer audit

The modern loader audit used the official Mojang manifest
`https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` (SHA-256
`f27326fe134922d1c983d35e22d6508c8d0e93c790b60b9528863b085cef1ba6`) and the
exact client JARs and client mappings. Downloaded files passed the SHA-1 checks
published in their version metadata. Their SHA-256 hashes are:

| Version | Metadata SHA-256 | Client JAR SHA-256 | Client mappings SHA-256 |
| --- | --- | --- | --- |
| 1.16.5 | `3eeeab7b3165cc5263dc26ff8fe114fdb718b75a0244efead9d19635d090ba72` | `00b5ebbc33e95ea88c1ab80601599c9827e9aa861d93ccc2cfcbbfd996e86263` | `7931ed6d723eceb1d621d05a76e10ddf643bf468c6ecf1c4ecf377bd72cf8b8c` |
| 1.19.4 | `e919c8673058d6d1cd75c39606b85942101176d09dcbdaafb8f476a4bf98c48a` | `0e79cf7f07c107e9a1fe22ed703e472372b44149e64114944f0811abbd25f3ec` | `5ef270b938f89cfc77371e0deee9f9044c41d9a373fa11dcb1fd1923272c4606` |
| 1.21.1 | `1df876eef98429859b719d081ceca190d34a68a08806d3c1b96eb27e4917d2d7` | `499f6897d1837516680f3114072d8106e11c9adcd933fe5cf051b551089b0c99` | `140c47931cccc8fc9e4c22d7603e2d714d1a953a146f51ea7397d95c955536ec` |
| 1.21.11 | `4a59771fce3439436515cc52b3cc685f18c197bb8e73fadd3b29072267a54e3a` | `1473c9489ac50fda3c435049a76a70d61a10b8610db27f5ba9d8756b686cd3bd` | `517799a8485e107e932dc1bd27c002b2d0b9207eb2396b685bcfe6c3321a9fbd` |
| 26.3 | `a1831b3e5ffa479c02d88a441794ff0a69813215e5d0e945edf03a99c7608c2e` | `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d` | Not published; the client JAR uses named classes |

Bytecode inspection confirms `LivingEntityRenderer` applies the rider body-yaw
clamp only when the vehicle is a `LivingEntity` in 1.16.5, 1.19.4, 1.21.1,
1.21.11, and 26.3. The modern seat uses vanilla `AreaEffectCloud`, which extends
`Entity` directly. Hash-bound client-bytecode assertions confirm the 1.16.5
cloud renderer has no geometry override and its nonwaiting zero-radius particle
loop is empty; 26.3 registers the cloud with `NoopRenderer`, whose cloud
particle loop is also empty at zero radius. Its empty potion skips status-effect
application, and its infinite-duration sentinel skips natural expiry. The
implementation sets radius/duration adjustments to zero and disables gravity
and physics. Class-file hashes and the complete model analysis are in the ignored
`.agents/modern-renderer-audit.json` and `docs/MODERN_PROVENANCE.md`. This is
source-level renderer evidence, not a human visual check. The owner subsequently
accepted the EULA for isolated tests. All 90 modern loader/game targets have
completed artifact-bound native, vanilla-protocol login, and restart checks;
the release verification report records their exact hashes and evidence limits.

GPL-3.0-only text: <https://www.gnu.org/licenses/gpl-3.0.txt>.

## Exact 26.3 passenger movement repair

The fresh Spigot fixture implementer continued in a separate clean-room
worktree for the owner's 26.3 passenger movement report. Inputs remained this
independent implementation, its specification and native test evidence, and
official upstream SDKs; no predecessor or global project-memory material was
consulted. The inspected official 26.3 client JAR SHA-256 is
`4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`;
the exact Paper 26.3 build 249 patched SDK SHA-256 is
`dadd84d1697efa8a29bcad6c81e503efdbaaa2dac85dbd4662c8243c97f0b8a4`.
Their distribution and version-metadata provenance is recorded above.

Client `Entity.createInterpolationHandler()` returns the no-op handler, which
the original `FallingBlockEntity` inherits. The original client packet handler
calls `moveOrInterpolateTo`; this immediately moves the falling block while a
horse uses `SteppedInterpolationHandler`. The official client `Display`
instead creates a `LinearInterpolationHandler`, and its synchronized position
and rotation duration configures that handler. Exact Paper's
`CraftDisplay.setTeleportDuration(int)` writes that native data accessor.
The repair selects the existing native BARRIER block display only for 26.3
and its `26.3.build.*` Bukkit labels, keeping the helper nonliving, no-gravity,
empty-bounded, and refused by the native save entry points.

The official client block-display renderer resolves the block's model. Its
`assets/minecraft/blockstates/barrier.json` SHA-256 is
`63ea68aa8c357e5f17999d5104a8dce09057fa26d5724fef5e2d921212acf6e7`.
That file selects `minecraft:block/barrier`, whose model SHA-256 is
`194d740cb62d8d1ea36e7982fc7312571f9756df4079c28039cc4ebaeb8c5289`.
The model contains a particle texture only, with no parent or elements. No
geometry or particle emitter is added to the vanilla display entity.
Ignored client/server audit inventories retain original class hashes and
`javap -p -c` evidence. No downloaded game classes are packaged in the plugin.

The tested runtime JAR SHA-256 is
`55eecdc1a4240c3878c942e718f44b25712f284e62fd3398aeab14d8c81e8c63`;
the matching final fixture SHA-256 is
`8c324fa30e1162f0d21121305ae834aa35e148e7df6a231af89f165df9387bba`.
Exact Paper 26.3 build 249 passed 62 native assertions, ten observer-packet
assertions, direct active serialization refusal, saved-world scans, and native
restart. Ten real-click owner checks also passed, including actual decoded
block-display type, BARRIER metadata, no-gravity and interpolation duration,
plus the styled clickable command controls. Compilation passed against both
the real Java-8-targeted Paper 1.12.2 SDK and the exact 26.3 API/dependencies.

The public client probe sources preserve the independent test drivers. A
1,200-tick original block-display run remained stationary and alive. A
hash-bound replay of 467 real observer frames across four client tick phases
measured maximum relative error 0.02917 blocks, versus 0.11063 for the matched
original falling-block control. The fixture sends native-codec player-look
packets with its vehicle movement: omitting the driver's look creates an
inconsistent test where the ridden horse resets yaw between vehicle packets.
That earlier trace and its failing interpolation result remain retained.

The original local-passenger packet branch resets the local player's old
position only when the vehicle is not interpolating. The native component
probe confirms active display interpolation skips that branch and preserves
the original eye-position endpoints through native rider ticks. Its ordinary
ArmorStand passenger is explicitly a positional-component probe; it does not
claim a constructed LocalPlayer, rendered camera, or human visual review.

The plugin runner shares the modern vanilla protocol helper for teleport
acknowledgements. The official 26.3 `ServerboundAcceptTeleportationPacket`
record requires the resulting position and rotation in addition to the
teleport ID; earlier releases acknowledge the ID alone. The runner's wire
regression tests exercise both layouts. This is protocol-fixture behavior,
not rendered-client verification.

The implementation and repair attestations below identify the clean-room inputs
and official upstream evidence. The matching complete source archive preserves
the committed source inventory. Native behavior, renderer bytecode analysis,
and human visual review remain separate evidence categories; availability
metadata establishes none of them.

## Plugin native portability audit

A fresh implementation conversation inspected only this repository's
instructions, behavioral specification, independent sources, isolated native
test reports, and official upstream server SDKs. No predecessor source,
artifact, archive, design note, or global predecessor memory was consulted.
`javap -p -c` inspection of the following exact upstream artifacts established
the native wrapper, mutable bounds, mount-correction, cancellation, and fixture
transfer entry points:

| Upstream SDK | Distribution or pinned upstream revision | Inspected server JAR SHA-256 |
| --- | --- | --- |
| Paper 1.7.10 build 2025 | [PaperMC distribution](https://fill-data.papermc.io/v1/objects/33772078d92e9dbb027602da016524ef29af5b4c12eaddac1fe2465b01108185/paper-1.7.10-2025.jar), distribution SHA-256 `33772078d92e9dbb027602da016524ef29af5b4c12eaddac1fe2465b01108185` | Patched SDK `eb9b87e987c03492ae32709ab3bad4a4bb1aeadd2c906f848473c793487b9ba3` |
| Paper 1.12.2 build 1620 | [PaperMC distribution](https://fill-data.papermc.io/v1/objects/3a2041807f492dcdc34ebb324a287414946e3e05ec3df6fd03f5b5f7d9afc210/paper-1.12.2-1620.jar), distribution SHA-256 `3a2041807f492dcdc34ebb324a287414946e3e05ec3df6fd03f5b5f7d9afc210` | Patched SDK `30d3843b04680391cf86b548ff67bb5d324df0b2a5a50a18f647bca3f6acda75` |
| Spigot 1.12.2 | BuildTools revision `1573k`; CraftBukkit `f4830a1687f37a4ee7c0bd08a30d925fbe0f9349`, Spigot `79a30d7d26b9078dbf6071cbbfa060673bf117b2`, BuildData `be360cc298a06b5355ecd057f5b1feb894a73f0f` | `63fdb6df3327787f91fa469fd1cb323466e4b0ee3a3bdacb0ef2347f83cc9a08` |
| Folia 1.21.11 build 14 | [PaperMC distribution](https://fill-data.papermc.io/v1/objects/f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39/folia-1.21.11-14.jar), distribution SHA-256 `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39` | Patched SDK `6bfd94ffd8649cf416d247e849214667ac91759699a4c3536eef2307277c4e33` |

Paper 1.7.10 exposes `CraftFallingSand.getHandle(): EntityFallingBlock` and a
per-entity final `Entity.boundingBox`. Its `AxisAlignedBB.b(DDDDDD)` mutates
that instance in place. No final reference or static box is written.

Paper and Spigot 1.12.2 `EntityPlayer.a(Entity, boolean)` call the native player
connection's position-correction method after mounting. That method emits an
`UNKNOWN` `PlayerTeleportEvent` even when source and destination match. The
adapter checks the final event destination instead of ending a ride for this
no-op correction.

Spigot 1.12.2's generic `EntityDismountEvent` does not implement `Cancellable`.
Its `Entity.stopRiding()` clears the native vehicle pointer before firing
`VehicleExitEvent`, while cancellation leaves the vehicle's original passenger
list intact. `EntityLiving.stopRiding()` can then emit another `UNKNOWN`
same-world position correction for the falling-block dismount path. The
implementation repairs only that final-cancelled, half-detached pointer when
the original vehicle still lists the passenger and no replacement vehicle has
been chosen. It sends the ordinary native mount packet without dispatching a
new mount. The associated correction is ignored only while the final exit
remains cancelled and its final destination remains in the same world.

Folia 1.21.11's native
`Entity.teleportAsync(ServerLevel, Vec3, Float, Float, Vec3, TeleportCause, long, Consumer)`
requires the owning entity tick thread. Its published constants are
`TELEPORT_FLAG_LOAD_CHUNK=1`, `TELEPORT_FLAG_TELEPORT_PASSENGERS=2`, and
`TELEPORT_FLAG_UNMOUNT=4`. The disposable fixture uses the unmount flag alone
for an actual mounted-player world transfer into an already-prepared chunk.
Setup uses the official async chunk API and region scheduler. The unload
sequence and its limits are described in `PLUGIN_RENDERER.md`.
The setup retains each controlled landing chunk inside the async API's callback,
before its temporary upstream load ticket retires, and waits for full loaded
status on the owning region before editing the landing platform. These tickets
belong to the disposable fixture; the Tandemount runtime never installs them.

The portability snapshot compiles all plugin target packages and its disposable
fixture against the real Paper 1.12.2 SDK with Java release 8. A separate main
source compile uses the published Paper 26.3 API and its official dependencies
listed above. Exact source hashes, compiler commands, and artifact hashes are
retained in the ignored `.agents/plugin-portability-*/compile.json` records.
Compilation and API-bytecode inspection do not establish native or visual
compatibility; final native runs remain the coordinator's responsibility.

### Older Folia fixture API boundary audit

A read-only class-file audit covered all 13 available exact Folia releases in
`platforms.json`, using each official distribution's patched native server JAR
and bundled Folia API. Every native `teleportAsync` overload retains the same
eight-argument shape and flag values: load chunk `1`, teleport passengers `2`,
and unmount `4`. The 1.19.4, 1.20.1, 1.20.2, and 1.20.4 native mappings name
the vector `Vec3D` and the world `WorldServer`; 1.20.6 and later use `Vec3`
and `ServerLevel`. The fixture derives its vector constructor from the matched
native method signature instead of loading one mapping family by name.

The APIs from 1.19.4 through 1.20.6 have no five-argument
`getChunkAtAsync(int, int, boolean, boolean, Consumer)` callback. They expose
the four-argument `CompletableFuture` overload instead. The fixture installs a
persistent setup ticket before requesting that older future. Newer callback
APIs install the same ticket inside the callback before the upstream temporary
load ticket retires. Both paths wait for native full loaded status using delayed
owner-region tasks before editing the disposable landing. This loading and
retention behavior belongs solely to the fixture.

| Official Folia distribution | Patched native SDK SHA-256 | Bundled API SHA-256 |
| --- | --- | --- |
| [1.19.4 build 39](https://fill-data.papermc.io/v1/objects/e6729be678110cf76b5feebaf4da09f447aacad907350f156bf163b561a9d979/folia-1.19.4-39.jar) | `50911aa47418079eca6ca511d8b009f0bb40b75838c2de691a1bb6c53eb7b9d1` | `5bcbf2a00f2ba99e883e2e1957f94026abfce6d35fe281e448405bba9c8876af` |
| [1.20.1 build 17](https://fill-data.papermc.io/v1/objects/c533d8886c60e1db17ebcf841b862731ab0a18d72377f37189930c3324eb7759/folia-1.20.1-17.jar) | `f3d199fad62394f13c3dd1515d60603c8e8179d7866968c6b0041970aecef1c0` | `85e5e348ee459f42f80e0afc310758fa121d65652d7345127851b4684cbd92f3` |
| [1.20.2 build 20](https://fill-data.papermc.io/v1/objects/d01746e0176b6ef1ae0bef65bc3fa44e2f2063eaa8ab78f9e941345268d4c9e5/folia-1.20.2-20.jar) | `eac63cb9ec83ad3f96404875292d94360474a60932902dd59ed22cb7ab0af326` | `139d3e13303a069af2b1d2217b70e5e0fe234860dae0def97a0387f2456bf0a6` |
| [1.20.4 build 31](https://fill-data.papermc.io/v1/objects/b0d55be3ba19cb6e040f0e7fba400e5224a271fbc7db73a9683aef7468425af9/folia-1.20.4-31.jar) | `d815899692043f4400239e53be1edd25011d0107bc7478560655963c5d119739` | `a46c445784ae97f82609311a040f4e6fb65ff2b14ea7ebf1804bdcda90393d82` |
| [1.20.6 build 6](https://fill-data.papermc.io/v1/objects/a30625d8824b03aae64898b001b46bdc4424b0e5caee1a370af7b444d8ec361a/folia-1.20.6-6.jar) | `9e5ba28c5dd8c67412fe818ca0be06d18b8632ac2b73cafccef24a07d4b69ca0` | `ca8805fb68a96c85e76be4e7af3e84798167bb701c61cbf06a6ac777a7679721` |
| [1.21.4 build 6](https://fill-data.papermc.io/v1/objects/dcf2333211c1468c8eddc482bc8549600818cc661a709124a79c752f8fa2ac3a/folia-1.21.4-6.jar) | `87948b9124763a55bd5a7cb883c0de848baa5e2c1bb3f1d6c56c4f82218dc871` | `db062c45a5a251c601c0fbe94e0178daced95ebbd061f1f1978ccc173ef98b76` |
| [1.21.5 build 12](https://fill-data.papermc.io/v1/objects/ebeb8f2f9e97fd972c89ebc276bc547c6194c6542d3a1884e3cfef7228f69cdb/folia-1.21.5-12.jar) | `be8629144890be072dd301a71af206280fb1a1e42f9601af95313212878d586c` | `c8affa78207322e4c3d1f68a1f38be963c0ef29f93d432d4cdf84550ab97da71` |
| [1.21.6 build 6](https://fill-data.papermc.io/v1/objects/917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234/folia-1.21.6-6.jar) | `74a595c77ba27d77826a3f8a1fd3ccc57e8576e699dee166ccb6559d9c80dbc5` | `bc4446bf329e6c86aef4f34274bfadfe7be51634ac74cdd95ab2318496b822c7` |
| [1.21.8 build 6](https://fill-data.papermc.io/v1/objects/233843cfd5001b6f658fcab549178d694cc37f0277d004ea295de0a94c57278f/folia-1.21.8-6.jar) | `484bc5c060bd4e8ab022bd5b6ece04df0c253892ec9aab21715ca75148c05b71` | `d740e00e8725ba3262ed7539e8ba77d6d497403d1f8e4e9860b25976e59afd88` |
| [1.21.11 build 14](https://fill-data.papermc.io/v1/objects/f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39/folia-1.21.11-14.jar) | `6bfd94ffd8649cf416d247e849214667ac91759699a4c3536eef2307277c4e33` | `b0f47989fc11e62a25d3cceb1f6c647e701e6224e17fbecfa10267ec4a9194a7` |
| [26.1.2 build 8](https://fill-data.papermc.io/v1/objects/607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b/folia-26.1.2-8.jar) | `1c6040315d5b608916ca6811f3a93c72ac950d0ca14cc2dd64aaa85fb27b124d` | `f10a91acaf5e3aa3f2aa27f8f23b932281ff71fce13ee6c9c1b53999ebe93e60` |
| [26.2 build 7](https://fill-data.papermc.io/v1/objects/128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3/folia-26.2-7.jar) | `a43ba2b1d4e829ab30bb9198660542d58fd054e592dc608f852130cfe1726875` | `b91778aa8a60aa04082e520431e0dff15ebebbe562b5eedaf386d2e980fbdd8b` |
| [26.3 build 6](https://fill-data.papermc.io/v1/objects/3d0a694905ddd9ec956af52259d555274ab8d3191871296d7094c290b686920d/folia-26.3-6.jar) | `4268a906fe28842ee8566a908c8486e656f6baf9d5947f512791c2a635cd3df6` | `03e1fef6cdd29a75880d58e0922a3272b95afd8cd9e35f681988310356504dfa` |

Focused main-and-fixture source compilation passed against the exact bundled
Folia 1.19.4 and 1.20.6 APIs and their bundled upstream dependencies, separately
from the normal Paper 1.12.2 build. The ignored API-audit record retains native
class-file descriptors, constants, distribution metadata, and exact artifact
hashes. The compilation records remain distinct from native execution reports;
this audit alone does not claim compatibility for any Folia release.

The Folia 1.19.4 boundary run then exposed an additional runtime mapping gap:
the native entity position setter is `Entity.e(double, double, double)`. Exact
server bytecode shows that this public, nonfinal void method delegates to
`setPosRaw(double, double, double, true)`. The same method is invoked by
`CraftEntity`'s location setter and the native `EntityFallingBlock`
constructor. The inspected 1.20.1 and 1.20.2 SDKs use the same `e` alias;
1.20.4 uses `a_`, and 1.20.6 uses `setPos`. The adapter accepts these verified
mapping names only with the exact three-double, void, nonstatic, nonfinal
signature required by its generated native subclass. The existing falling-block
tick and passenger movement entry points already match the 1.19.4 SDK.

A complete 1.19.4 initialization audit found that `Block.n()` returns a
`BlockStateList`, while `Block.o()` returns the actual `IBlockData`. The
adapter now derives the state type from the existing vanilla
`EntityFallingBlock(World, double, double, double, IBlockData)` constructor and
selects the zero-argument getter by that exact return type. The registry-and-world
constructor remains upstream-owned; the five-argument vanilla constructor uses
its existing `EntityTypes` falling-block entry. No custom registry type is
created. The old CraftWorld custom spawn method is
`addEntityToWorld(Entity, SpawnReason)`, which calls
`WorldServer.addFreshEntity`; the adapter recognizes that upstream entry point
and still verifies the final entity validity after cancellation handling.

The same audit confirmed `Entity.l()` as the falling-block tick,
`Entity.a(Entity, Entity.MoveFunction)` as passenger positioning, the mutable
instance bounds field `aG` separately from static bounds `k`, the six-double
`AxisAlignedBB` constructor, boolean save entry points `d/e(NBTTagCompound)`,
and boolean damage entry points with `DamageSource`. The generated seat also
rejects the upstream `serializeEntity(NBTTagCompound)` entry point. Existing
native teleport and mount packet constructors both accept the native `Entity`.

Folia 1.19.4 additionally requires spawn retention and spawn-location setup on
the global scheduler. The disposable fixture uses that scheduler for world
settings and the owning region scheduler for block work. Its old public
`CraftEntity.teleportAsync(Location, TeleportCause)` uses native flags `5`
(load and unmount); it can accept a mounted passenger without emitting a
cancellable teleport event. Later Folia's public API can reject a mounted
passenger instead. The fixture binds its assertions to the returned future and
whether an actual cancellation event fired, separately recording accepted
old-API cleanup and rejected-API ride preservation. It never labels a
nonexistent event as a successful cancellation test.

The repaired source was packaged in the immutable local `v11` snapshot and run
on the exact Folia 1.19.4 build 39 and 1.20.6 build 6 distributions listed
above. Each completed 58 native state assertions, 10 loopback packet assertions,
and the native stop/restart helper check with process exit code zero. The
fixture JAR SHA-256 was
`c1fd3806dd3aa3cbf210d5533db7b1868a23e99304fb299eb8bc0afcd2298490`.
The tested 1.19.4 plugin JAR SHA-256 was
`e2c64db9fedada993b819b8143767423756309e3f21f6501ad71bdc97c2a1386`;
the tested 1.20.6 plugin JAR SHA-256 was
`bcde95da1fd0378a63449b5a9e36a9c695b3368c3df2036c9015dad076a3dac2`.
These are native/protocol checks, without a real-client visual review.

The 1.20.6 server rejected the runner's `save-all flush` command. Its existing
active-phase file scan is therefore only a snapshot inspection, not proof that
the manual save command executed. The final shutdown save and restart check
did complete. This distinction must remain visible until the runner obtains
separate evidence of an accepted active save on that platform.

For the subsequent fixture-only `v12` save probe, the exact patched SDKs were
inspected directly for native serialization signatures and output factories:

| Official distribution | Patched native SDK SHA-256 |
| --- | --- |
| [Folia 1.20.6 build 6](https://fill-data.papermc.io/v1/objects/a30625d8824b03aae64898b001b46bdc4424b0e5caee1a370af7b444d8ec361a/folia-1.20.6-6.jar) | `9e5ba28c5dd8c67412fe818ca0be06d18b8632ac2b73cafccef24a07d4b69ca0` |
| [Folia 26.3 build 6](https://fill-data.papermc.io/v1/objects/3d0a694905ddd9ec956af52259d555274ab8d3191871296d7094c290b686920d/folia-26.3-6.jar) | `4268a906fe28842ee8566a908c8486e656f6baf9d5947f512791c2a635cd3df6` |
| [Paper 26.3 build 249](https://fill-data.papermc.io/v1/objects/4c734aeb4da98845934af1e5481f0df7c80ad3ca50d272319ffe2b0e8a187c8a/paper-26.3-249.jar) | `dadd84d1697efa8a29bcad6c81e503efdbaaa2dac85dbd4662c8243c97f0b8a4` |

Folia 1.20.6 exposes boolean `save(CompoundTag)`,
`serializeEntity(CompoundTag)`, and both one- and two-argument
`saveAsPassenger` entry points. The exact Paper and Folia 26.3 SDKs instead
expose boolean `save(ValueOutput)` and `saveAsPassenger(ValueOutput)`, plus a
passenger-save overload with three additional boolean arguments. Their
`TagValueOutput.createWithContext(ProblemReporter, HolderLookup.Provider)`
factory creates a real native compound using the supplied registry's
serialization context. `TagValueOutput.isEmpty()` and `buildResult()` inspect
that same backing compound. The fixture uses the native `ProblemReporter`
constant and the active helper's actual `registryAccess()`; no replacement
serialization interface, mock output, or textual proxy is used.

The probe runs on the seat's entity scheduler only in the active `save` phase.
It verifies the same live helper, mounted rider and driver, plugin ride maps,
and native-seat registry before and after the native calls. It passes true for
additional boolean save flags, requires every boolean entry point to refuse,
and checks every fresh output remains empty. Its canonical
`active-seat-refuses-native-serialization` assertion gates `save-probed` and
plugin disable. This supplies direct active serialization evidence without
treating the earlier rejected manual-save command or an old file snapshot as
an accepted flush. Final shutdown world-file and native restart checks remain
separate.

The fixture and all available exact plugin targets compile against the normal
real Paper 1.12.2 SDK. All 37 runtime plugin JAR hashes and all main-source
hashes remain identical to `v11`; only the fixture and its documentation changed.
The `v12` fixture JAR SHA-256 is
`e8ba2a9feed2bc8dff37958fb61e15a7d6107a6f891fe1c6271470db658a3e4b`.
Exact class descriptors, class hashes, and distribution metadata are retained
in the ignored save-probe audit. Native execution reports remain distinct from
this API inspection and compilation evidence.

### Combined plugin baseline repair audit

A fresh clean-room conversation first read only this repository's agent guide
and behavioral specification. It then audited the combined 113-target plugin
baseline before changing the independent implementation. The 49 failures
comprised 14 missing native tick aliases, 30 inaccessible Spigot coordinate
constructors, two 1.19.3 native geometry failures, and three loopback-client
protocol failures. The latter are test-client repairs and do not establish a
renderer change. No predecessor code, binaries, archives, design descriptions,
tests, or global predecessor memory were consulted.

The class-file audit inspected 44 exact official upstream SDKs covering all 37
available plugin game versions: every available Spigot release, Paper 1.7.10,
and Paper 1.18 through 1.19.3. The ignored
`.agents/plugin-baseline-fix/sdk/audit.json` retains distribution metadata,
native JAR hashes, extracted class hashes, and `javap -p -c` listings. Additional
native serialization and protocol-template class evidence is retained in the
adjacent `valueoutput` audit. These upstream class files remain operational
evidence and are never included in the plugin artifacts.

| SDK boundary | Official distribution or BuildTools revision | Inspected native JAR SHA-256 |
| --- | --- | --- |
| Paper 1.18 | [Build 66](https://fill-data.papermc.io/v1/objects/3c995f20dae4e4e21d5554fac957a0a8a5c85bd5bf34915fac4b4f16e0ef101b/paper-1.18-66.jar) | `5e8a29a5785f5f14352bdf311852acc3490b452f8e56d095fca439ea6754d125` |
| Paper 1.19.3 | [Build 448](https://fill-data.papermc.io/v1/objects/3007f2c638d5f04ed32b6adaa33053fe3634ccfa74345c83d3ea4982d38db5dc/paper-1.19.3-448.jar) | `6d79d568398cea6807305cf529d7868814a01910b5eb7c20c2104098fb3f6566` |
| Spigot 1.18.2 | `3498`, CraftBukkit `10ba1beb64286206f4edc8d4b44c6472639d044e` | `ec62d93e1cc081ddf8031cccffdf4c7c331195aed5960565716159781da391ed` |
| Spigot 1.20.5 | `4106`, CraftBukkit `8c1e7c84109cbbf4755c295a0ac88f7e302aeefe` | `542774f99570d4a964fa1b420265a743ab519a5d2f30ed05adc0f07d87ac0e8c` |
| Spigot 1.21.5 | `4504`, CraftBukkit `dacd062dd1c6d6a06f0774a837ea18ddf89edb6d` | `c0462e6e70fe3ab8a938e858e2894dcdca83cfed03266a1a1661c7c737495cd6` |
| Spigot 1.21.6 | `4518`, CraftBukkit `98f7fa28ed76204d2d290abb1c8b8083ed1787fb` | `c63213e8d9bce986daf2993866dab939f35a15810a9beb5b9762d0cc1a4ec326` |
| Spigot 26.2 | `4648` | `4ff2f1c5b327d1485d046b57e3f089a9a34de13fb7eae3b2f82d3a5918eaf81f` |
| Spigot 26.3 | `4664`, CraftBukkit `e89124d3e8e9585e75ceb7f9a4daba133788e5bd` | `46d7f9db84ed3a097553bc073e1366ebe3d61e259e866686d802a7ea9f0d50a3` |

These Spigot SDKs were produced with the official
[BuildTools build 201](https://hub.spigotmc.org/jenkins/job/BuildTools/201/artifact/target/BuildTools.jar),
SHA-256 `81ed06f5ce5fb705b558bf9b3cc2c5c53cdd9742406c5338c452faa13a1e1c24`.
The audit retains all pinned Bukkit, CraftBukkit, Spigot, and BuildData revisions
from the corresponding isolated server metadata.

The SDKs establish the following native boundaries. Spigot 1.18–1.19.2 declares
falling-block tick `k()`, while 1.21.5–1.21.11 declares `g()`. The adapter now
requires the selected tick to be declared by the falling-block class itself.
In 1.19.3, `Entity.f(DDD)` delegates to `setPosRaw(DDD, true)` and the falling-block
constructor calls that method. `Entity.e(DDD)` only resets `VecDeltaCodec`.
The 1.19.4 SDK reverses those short aliases. The implementation therefore binds
the 1.19.3 setter to that exact upstream version.

Spigot 1.18.2 through 26.3 makes the five-argument falling-block constructor
private while retaining the public native entity-type/world constructor. The
adapter obtains the existing `minecraft:falling_block` type through upstream
`EntityTypes.a(String)` or `EntityType.byString(String)`, or the named
`EntityTypes.FALLING_BLOCK` constant introduced in 26.2. It checks the native
registry key before calling the upstream constructor. It does not construct,
register, or replace an entity type, and it preserves the real AIR-state and
mutable-instance-bounds checks.

The audit also establishes boolean native save entry points `a_(NBTTagCompound)`
on Spigot 1.16.5, `f/g(NBTTagCompound)` on 1.21.5, and `b/c(ValueOutput)` on
1.21.6–1.21.11. Their bytecode performs native entity/passenger serialization;
they are not inferred from an arbitrary boolean method alone. The subclass
refuses these exact signatures and the disposable fixture probes them. Extra
save parameters must be boolean flags. Mapped `TagValueOutput.a(ProblemReporter,
HolderLookup.a)` uses the active helper's real native registry access. Its
`a()` emptiness result and `b()` built compound are checked separately.

For mapped protocol templates, the fixture uses the SDK's retained native
listener generic types to identify serverbound and clientbound templates. It
visits the real native packet registry or its bound codec, and reads the packet
type's actual resource identifier. Spigot `PacketType.a()` is the flow;
`PacketType.b()` is the identifier. The fixture never substitutes guessed IDs.
Native execution evidence and real-client visual review remain separate from
these SDK observations.

The exact Spigot 26.3 public API compile exposed removal of the old
`World.setGameRuleValue(String, String)` method. Its `GameRule.RESPAWN_RADIUS`
constant and `World.setGameRule(GameRule, T)` provide the current typed form.
The disposable fixture selects this real upstream form only when the old
method is absent. The setting remains zero in either form. Exact main-and-fixture
compilation against this API is kept separately from the normal Java-8-targeted
Paper 1.12.2 build. This fixture compatibility path does not alter runtime
world settings or add a gameplay dependency.

### Native boundary verification for the combined repair

The immutable local `v18` snapshot passed the following exact isolated native boundaries. Every target completed 59 native state assertions, 10 loopback packet assertions, direct live-seat serialization refusal with the real native output, the final saved-world helper UUID scan, and a native server restart. Each run exited zero. The tested fixture SHA-256 is `fd24ced4ad0d3bfed3598b649d665018b9c5c50583cad30125ef095dec51129e`.

| Native server | Plugin JAR SHA-256 |
| --- | --- |
| Paper 1.18 | `fbf39cea7dd502568443dc3732db0e9fa99e6bfa7b80ebbf368e9c14673be1fc` |
| Spigot 1.18.2 | `9cd16aaa7fa9324931c9d30f956c1312bdc97191058cb1ec9c4bd26cff39b81e` |
| Paper 1.19.3 | `96a59f33b8d4a28dc8488416261443aed4df35562fb8a9cca9eea04d1af63a23` |
| Spigot 26.3 | `eb21719c97f23d55879ac04500f1ca170081bfff762afcc29e41af0de8e5e608` |
| Paper 1.21.3 | `1aeb97fb4bee1e537a36057e623b9f90ec8c7aa4878371fbb8b063e8affb88b0` |
| Spigot 1.21.5 | `b77cc0744f70a4bb130312d73ca667226d69eb7d3bdc90310058f5f871facaa1` |
| Spigot 1.21.6 | `598f9ae4315d193c885bccf94ef670de4601ce4e0e931cc30f06f31e54a392ba` |

All 37 exact runtime packages compile against the real Paper 1.12.2 SDK with Java release 8. The same source and fixture also compile independently against each of the seven exact Paper and Spigot API boundaries above. Native reports bind server, runtime artifact, and fixture hashes; the ignored `.agents/plugin-baseline-fix/final-validation.json` retains that inventory and the complete verification record.

The fixture retains its initial home only through setup, removes that ticket on its owner before creating a ride, and then proves actual evacuation and chunk unloading. Earlier failed attempts remain retained, including the early Paper 1.18 platform-neighbor physics error, one nonreproduced Spigot 1.18.2 eligibility result, and Spigot 1.21.5 setup-home unloading. Every original failed baseline report is preserved byte-for-byte with its baseline hash. No failed guard is exempted.

This is targeted native and protocol evidence for the combined fix. The full 113-target plugin matrix remains a separate coordinator run, and no real-client visual confirmation is claimed.

## Exact 26.3 native attachment revision

The same fresh clean-room implementation conversation continued from the
independent 26.3 repair for the owner's explicit native-attachment request.
Inputs remained the independent Tandemount specification, current code and test
evidence, plus original official Minecraft client classes and the exact Paper
26.3 build 249 SDK. No predecessor source, archives, binaries, design material,
tests or global project memory were consulted. The official client and patched
server SHA-256 values remain those recorded in the 26.3 movement audit above.

Original native `Entity.positionRider`, passenger/vehicle attachment points,
horse control, llama dimensions and scale attributes, Interaction height/width,
client passenger ticks, ray picking and dismount behavior were inspected and
executed. A plain horse → block display → player graph has the correct root,
but native default feet height is about 0.84375 blocks and rear distance is zero.
Display rendering transformations do not supply native passenger offsets.

The bounded five-helper graph uses registered Interaction, llama and block
display types. Two opposite llama attachment points, with scales
`rearward / (double)0.3f + 1/16` and `1/16`, provide the full independent rear
range including zero. Both scales stay within the native 0.0625–16 bounds.
The original synchronized Interaction height supports signed finite values;
its CraftBukkit setter writes that native accessor without adding a positivity
constraint. Zero width avoids a spacer hit box. An anchor height of −64 keeps
both invisible, NoAI living carriers below ordinary client interaction rays;
the lift compensates their native heights and restores the final BARRIER
display beside the rider. That final vehicle's nearby origin preserves the
ordinary native dismount search. Every helper remains in the same horse-rooted
passenger graph on both sides, and no recurring follower position packet is
broadcast. Normal input packets and protection events are unchanged.

The earlier three-helper client geometry probe established offsets but exposed
large invisible client pick boxes. A four-helper lowered-carrier server
prototype then exposed distant dismount-origin behavior. Both failed attempts
remain retained; neither is labeled a passing release. The final five-helper
fixture separately checks native roots and first-driver control, exact helper
count, no-gravity, invisibility, NoAI, empty server bounds, native client graph
packets, independent settings, third-rider rejection, final cancellation,
cleanup, native serialization refusal for every helper, saved-world UUID scans
and restart. Original-client picking remains separate from server bounds.

Plugin defaults are now height 0.8125 and rearward 0.5. Resource configuration,
missing numeric values and reset agree. Existing saved values are read without
replacement. Native fixture checks cover each path, while real command packets
exercise reset and independent offset controls. Human visual review remains a
separate owner acceptance step.

### Paper 26.3 owner regression: client lifetime and command controls

The owner reported immediate rider ejection and unformatted settings chat with
the independently implemented delivered 26.3 JAR, SHA-256
`f814a04ab2d14a3bc02a41f7126896f4ecce0d6d966753c19a7016efb4cec6c2`.
The repair was developed in an isolated worktree without changing the delivered
file or the coordinator's full matrix. No predecessor material was consulted.

The exact official Mojang client JAR SHA-256 is
`4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`,
obtained from the official metadata described above. Its unchanged
`FallingBlockEntity.tick()` first tests `blockState.isAir()` and calls `discard()`
before any world-side branch. `BarrierBlock.getRenderShape()` returns
`RenderShape.INVISIBLE`. `FallingBlockRenderer.submit()` returns immediately
unless the render shape is `MODEL`. These observations establish why an AIR
helper can disappear on a vanilla client despite remaining registered on the
server, and why BARRIER is a non-AIR invisible state for exact 26.3.

The real-packet regression on the original delivered JAR retained its server
mount across ticks, repeated main/off-hand clicks, and movement. It reproduced
the unformatted chat component: the actual clientbound component contained no
color or text style, although its four run-command actions existed. That
server-only observation does not refute the client disappearance path and is
recorded separately from vanilla client-tick execution.

The official Paper 26.3 build 249 SDK, native JAR SHA-256
`dadd84d1697efa8a29bcad6c81e503efdbaaa2dac85dbd4662c8243c97f0b8a4`,
provides the actual `ServerboundInteractPacket` record and `STREAM_CODEC` used
to encode the test's main/off-hand interactions. In 26.3 its fields are entity
ID, interaction hand, hit location vector, and secondary-action boolean. The
network handler emits `PlayerInteractAtEntityEvent`; the platform's inherited
handler dispatch reaches the existing interaction listener. No speculative
second listener or cancellation bypass was added. The client sends the native
loaded notification before interacting.

The fixture decodes an actual observer socket spawn body using the upstream
`ClientboundAddEntityPacket.STREAM_CODEC`, requiring its entity and block-state
IDs to match the tracked helper and requiring the entire body to be consumed.
The candidate's observed state is `14381`, with native no-gravity true. Its
decoded command component contains aqua/green bold controls, four exact
`/tandemount` run-command actions, and a successful independently verified
1/16-block vertical change submitted through the actual command packet.

Official class bytes, hashes, `javap` listings, original/candidate wire captures,
decoded components, per-tick states, and event records are retained under the
isolated worktree's ignored `.agents/owner/` and the task-specific
`paper263-owner-runs` cache namespace. None of those official class files are
included in runtime artifacts. These protocol checks do not claim human
graphical confirmation.

The independent standalone client harness then loaded that exact untouched
official client JAR and 66 metadata-bound official dependencies. It constructs
the vanilla falling-block entity normally, applies the spawn through
`recreateFromPacket`, and calls its original `tick()` in a controlled empty
client-side `Level`. It does not subclass the entity, replace its tick, use
Unsafe, or load the plugin. AIR state ID 0 is removed on tick 1. The actual
candidate state ID 14381 with no-gravity true survives 200 ticks at unchanged
height and reports `INVISIBLE` render shape. The independent harness source is
`plugin/src/test/client/OfficialClientSeatProbe.java`. This is executed vanilla
entity-lifetime evidence, separate from human graphical review.

The candidate runtime JAR SHA-256 is
`f4fc6db3e72f13de9a7e60cfa1fdc1404586a1bb1106a2fde35acd88c5cd5c23`.
On the exact official Paper build above it passes 59 native state assertions,
10 observer packet assertions, direct live-seat serialization refusal, a final
scan of 2653 native saved chunks containing none of the 13 observed helper UUIDs,
and a native restart. Its real-client owner regression separately passes
cancelled interaction, persistent ordinary-rider mounting, repeated hand clicks,
movement, observed spawn state, formatted chat, four clickable controls, and
an actual clicked setting change. No running community server or owner world
was modified, and the original delivered JAR remains unchanged.

### Spigot 1.21–1.21.4 protocol-fixture audit

A separate fresh clean-room conversation inspected only the independent
Tandemount sources, specification, failed native reports, and these exact
official Spigot BuildTools SDKs and Mojang server mappings. No predecessor or
global project-memory material was consulted. This repair changes only the
disposable protocol exporter; the runtime plugin sources and JAR bytes remain
unchanged.

| Spigot target / BuildTools revision | Native SDK SHA-256 | Official Mojang server mappings |
| --- | --- | --- |
| 1.21 / `4219` | `57146c6dcbb74fe9d33860c00feea2345d1ba22e7be93e718d476eddc3d72df1` | [1.21 mappings](https://piston-data.mojang.com/v1/objects/31c77994d96f05ba25a870ada70f47f315330437/server.txt) |
| 1.21.1 / `4344` | `05423d6841f8ce4de271dcd7a2ec930dfbebdf162651b380eb1752837e26fa30` | [1.21.1 mappings](https://piston-data.mojang.com/v1/objects/03f8985492bda0afc0898465341eb0acef35f570/server.txt) |
| 1.21.2 / `4347` | `83043639e0ae6a64330c18ba0ede4895b14437157ea36bdf3303bc4edaf393c9` | [1.21.2 mappings](https://piston-data.mojang.com/v1/objects/ce1d4ab050af87c41fc3d51050ef6862385da784/server.txt) |
| 1.21.3 / `4398` | `83e479f5693198c335368b77c34850079942f16d1c8064664e965f63966c2f5e` | [1.21.3 mappings](https://piston-data.mojang.com/v1/objects/c70e10f72ea65bb97e156143fd97c852dc958325/server.txt) |
| 1.21.4 / `4458` | `1af9f1e120421efad091f782f96f61bbadf5061e81274fa738996ca7b22ad6c4` | [1.21.4 mappings](https://piston-data.mojang.com/v1/objects/0b1e60cc509cfb0172573ae56b436c29febbc187/server.txt) |

The native SDK is the nested `META-INF/versions/spigot-<version>-R0.1-SNAPSHOT.jar`
from each hash-verified official BuildTools distribution. The ignored isolated
`.agents/spigot-protocol-fix/.agents/sdk/audit.json` retains distribution hashes,
all pinned upstream revisions, class hashes, `javap -p -c` listings, and mapping
hashes verified against the official URLs in `BuildData/info.json`.

In all five SDKs, `ProtocolInfo.Unbound.listPackets(PacketVisitor)` is exposed
as `ProtocolInfo$a.a(ProtocolInfo$a$a)`. Its anonymous
`ProtocolInfoBuilder$1` implementation visits the native codec-entry list and
passes each entry's actual `PacketType` and list index to `accept(PacketType,
int)`. The zero-argument `a()` returns the connection protocol ID; it does not
return separate packet details. The fixture now locates the native visitor on
the unbound template before trying the newer separate-details API. Packet
resource identifiers still come from the verified `PacketType.b()` accessor,
and no packet IDs or registry entries are synthesized.

All five exact isolated servers then passed 59 native assertions and 10 real
loopback packet assertions each, direct live-seat serialization refusal, the
final saved-world helper UUID scan, and native stop/restart, with exit code zero.
Their fixture JAR SHA-256 is
`d19a668f44197d3e525edd3b1e18a0b63f31edf3302f5be072886fbf698247f2`.
The ignored isolated `final-validation.json` binds the unchanged runtime JARs,
fixture source, SDKs, exported packet registries, and complete native reports
to exact hashes. These are native/protocol checks without human visual review.

### Portable plugin attachment API audit

The plugin attachment adapter was generalized in the same independent clean-room
conversation using the exact official server mappings below and hash-verified
BuildTools native SDK declarations. Only transformed member aliases are bundled;
no game classes or complete mappings are redistributed. The official original
clients for 1.16.5 through 1.19.3 were also inspected for every declared riding
offset and passenger positioning override, including shulker peek geometry,
horse rearing, boat rotation, cloud dimensions and slime size callbacks. This
audit does not by itself prove an older client graph or justify a compatibility
exemption.

| Exact Minecraft | Official server mapping / SHA-1 | Mapping SHA-256 |
| --- | --- | --- |
| 1.19.4 | [73c8bb982e420b33aad9632b482608c5c33e2d13](https://piston-data.mojang.com/v1/objects/73c8bb982e420b33aad9632b482608c5c33e2d13/server.txt) | `097dd353bd96a1a8c0c1c16505e202957730ca5f1095f089487e7ced4a20fad8` |
| 1.20 | [15e61168fd24c7950b22cd3b9e771a7ce4035b41](https://piston-data.mojang.com/v1/objects/15e61168fd24c7950b22cd3b9e771a7ce4035b41/server.txt) | `87960812690dcde8ef67401ed53e50fbfa2166b9ec31f1274d6bbdb5d358112e` |
| 1.20.1 | [0b4dba049482496c507b2387a73a913230ebbd76](https://piston-data.mojang.com/v1/objects/0b4dba049482496c507b2387a73a913230ebbd76/server.txt) | `dca153d20defb32cfac3f069c3bf77b3e13c30ae63847637479d3137e099bb72` |
| 1.20.2 | [dced504a9b5df367ddd3477adac084cea8024ba4](https://piston-data.mojang.com/v1/objects/dced504a9b5df367ddd3477adac084cea8024ba4/server.txt) | `d2e9d78ee5e4148555d1af15c85914968c8f0bbd5b4b18366c9e09937fbafa8c` |
| 1.20.3 | [c1cafe916dd8b58ed1fe0564fc8f786885224e62](https://piston-data.mojang.com/v1/objects/c1cafe916dd8b58ed1fe0564fc8f786885224e62/server.txt) | `d8a75ea91dd9c8b83cffc7c65a4e65cc3933ded6aecdca13fd4fa52fba9770b2` |
| 1.20.4 | [c1cafe916dd8b58ed1fe0564fc8f786885224e62](https://piston-data.mojang.com/v1/objects/c1cafe916dd8b58ed1fe0564fc8f786885224e62/server.txt) | `d8a75ea91dd9c8b83cffc7c65a4e65cc3933ded6aecdca13fd4fa52fba9770b2` |
| 1.20.5 | [14be97974a77b09eb8bca88ed9268445f0fde3e7](https://piston-data.mojang.com/v1/objects/14be97974a77b09eb8bca88ed9268445f0fde3e7/server.txt) | `4b8e6fedfb206a9cd755696c9a30228ba167dfb66b91af393d8d3dd1a515244d` |
| 1.20.6 | [9e96100f573a46ef44caab3e716d5eb974594bb7](https://piston-data.mojang.com/v1/objects/9e96100f573a46ef44caab3e716d5eb974594bb7/server.txt) | `4e5068e40a89c673b5541192ad7eab96289f6dcae704f577f8992bdf7c984aa3` |
| 1.21 | [31c77994d96f05ba25a870ada70f47f315330437](https://piston-data.mojang.com/v1/objects/31c77994d96f05ba25a870ada70f47f315330437/server.txt) | `c9d9fda3143c100fa0566099074fc5d912b8b7ef3cb5b84c87eab411bd5bfb51` |
| 1.21.1 | [03f8985492bda0afc0898465341eb0acef35f570](https://piston-data.mojang.com/v1/objects/03f8985492bda0afc0898465341eb0acef35f570/server.txt) | `9d0b04bead421c8229aff14b534432bbc927bea642e7c8593d1276b8df8ba53f` |
| 1.21.2 | [ce1d4ab050af87c41fc3d51050ef6862385da784](https://piston-data.mojang.com/v1/objects/ce1d4ab050af87c41fc3d51050ef6862385da784/server.txt) | `0d80dc2239ee9fe5fc6e6c122b1a73178a4243fffe991754faa2a9581364bdbf` |
| 1.21.3 | [c70e10f72ea65bb97e156143fd97c852dc958325](https://piston-data.mojang.com/v1/objects/c70e10f72ea65bb97e156143fd97c852dc958325/server.txt) | `59ae80f623920a897587d375767a0d722074b3ddc1a5563feb0079f99e2a903c` |
| 1.21.4 | [0b1e60cc509cfb0172573ae56b436c29febbc187](https://piston-data.mojang.com/v1/objects/0b1e60cc509cfb0172573ae56b436c29febbc187/server.txt) | `dae4a75ad2ca1d52d14e9c080e70e7fbb2888c3533c015ea2d970b2a15856639` |
| 1.21.5 | [f4812c1d66d0098a94616b19c21829e591d0af3a](https://piston-data.mojang.com/v1/objects/f4812c1d66d0098a94616b19c21829e591d0af3a/server.txt) | `ecd2f68afe3333a4db26e1fb1d1c31551c379fef2f3235007fa739f7be038a7c` |
| 1.21.6 | [94d453080a58875d3acc1a9a249809767c91ed40](https://piston-data.mojang.com/v1/objects/94d453080a58875d3acc1a9a249809767c91ed40/server.txt) | `c6fe95810b05dfec19fdbdfb8cbbb8f976923f7cb16fcc828a0b605a49d2c492` |
| 1.21.7 | [eb1e1eb47cb740012fc82eacc394859463684132](https://piston-data.mojang.com/v1/objects/eb1e1eb47cb740012fc82eacc394859463684132/server.txt) | `e373ba569839e6f3c7bd4880247f3e85577d92cde2c605adfa2024eb9cae503e` |
| 1.21.8 | [eb1e1eb47cb740012fc82eacc394859463684132](https://piston-data.mojang.com/v1/objects/eb1e1eb47cb740012fc82eacc394859463684132/server.txt) | `e373ba569839e6f3c7bd4880247f3e85577d92cde2c605adfa2024eb9cae503e` |
| 1.21.9 | [587e016fe8a876cbc1cc98d73f9d0d79bfb32b2c](https://piston-data.mojang.com/v1/objects/587e016fe8a876cbc1cc98d73f9d0d79bfb32b2c/server.txt) | `d65cf145581b63a6c84af66748436e390be962effdc4dfc8ab1b5460715b9ac5` |
| 1.21.10 | [c5440743411a6fd7490fa18a4b6c5d8edf36d88b](https://piston-data.mojang.com/v1/objects/c5440743411a6fd7490fa18a4b6c5d8edf36d88b/server.txt) | `6b90e3206eb288c1976e10e2369f3752e4e93ac7f0afe513754d390d07bb2ebb` |
| 1.21.11 | [5621e9253f05fd57872bbe7f8ddf5f9a7d525955](https://piston-data.mojang.com/v1/objects/5621e9253f05fd57872bbe7f8ddf5f9a7d525955/server.txt) | `7ffd98f77f403043748d56467aac287a58f8187a27d5d6b7ebcac70042b02b1c` |

The 1.19.4 and 1.20.6 original Entity metadata callbacks refresh dimensions on
pose data. Interaction height alone changes its box but leaves cached passenger
dimensions unchanged in these clients. Original SynchedEntityData.assignValues
invokes the callback for a received pose value even if its value is unchanged.
The plugin therefore preserves native tracker snapshots and follows each known
Interaction helper snapshot with an ordinary native pose value. Initial and
late pairing share this ordering. No independent recurring helper position
packets are added. Native server geometry must enter the one-argument virtual
passenger method to retain older llama positioning overrides. Living carrier
body/head rotation is synchronized explicitly, and angular carriers have
nonliving separators to preserve their independent directions.

The modern mod adapter uses `ServerEntity.sendPairingData` for the same native
pose refresh. The NeoForge 1.20.6 userdev patch replaces the vanilla collector
with `PacketAndPayloadAcceptor`. Its `accept(Packet)` overload returns the
collector in NeoForge 20.6.144. The generator reads the exact method descriptor
from the official universal JAR, SHA-256
`d92959aad10d9af0a83ced9cb1dc0f075ee0058ba8ca86ba64459da972d1bf0a`.

Private SDK audit records under the task-specific plugin-final-port cache
retain exact distribution refs, mapping URLs and hashes, native/client class
hashes and declaration listings. Compilation, real server/network assertions,
original client execution and human visual review remain separate evidence.

### Paper 26.3 Nether passenger transfer audit

A fresh clean-room implementer consulted the independent runtime and behavioral
specification, the isolated two-socket reproduction, and the official Paper
26.3 build 249 distribution. No predecessor project, archive, binary or project
memory was consulted. The [official distribution](https://fill-data.papermc.io/v1/objects/4c734aeb4da98845934af1e5481f0df7c80ad3ca50d272319ffe2b0e8a187c8a/paper-26.3-249.jar)
has SHA-256 `4c734aeb4da98845934af1e5481f0df7c80ad3ca50d272319ffe2b0e8a187c8a`;
its bootstrapped native server SDK has SHA-256
`dadd84d1697efa8a29bcad6c81e503efdbaaa2dac85dbd4662c8243c97f0b8a4`.

Original `javap -p -c` evidence for `Entity`, `LivingEntity`, `ServerPlayer`,
`NetherPortalBlock` and `TeleportTransition` shows the transfer order:
`Entity.teleportCrossDimension` ejects passengers before calculating their
transitions. Ejecting a living carrier calls `LivingEntity.dismountVehicle`,
then `Entity.teleportTo` and `teleportPassengers`, repositioning its descendants
through the attachment's signed vertical offsets. A real portal transfer with
the previously delivered plugin and no PortalLinked installed moved the second
rider from Y `64.8125` to Y `126.4435` while the first rider arrived at the
ordinary exit near Y `64.84375`.

The repair is limited to Paper 26.3. A generated Interaction override intercepts
the anchor's native `teleport(TeleportTransition)` after the horse's portal and
teleport event dispatch has accepted the transfer, before carrier ejection or
helper cloning. The adapter positions the existing descendants natively, calls
the upstream `Entity.calculatePassengerTransition` and
`PositionMoveRotation.calculateAbsolute`, and retires every helper before
invoking the player's own cancellable native teleport. Native relative
position, rotation, velocity, cause and portal callbacks are preserved. The
world change ends the second seat; the second rider arrives unmounted and the
horse and first rider complete their normal native transfer. A successful
second-player transfer receives the player's ordinary native portal cooldown,
preventing automatic reentry while the unmounted player touches the exit.
Cancelled horse portal events do not enter this override, and a cancelled second-player
teleport is not retried. This audit makes no new compatibility claim for other
versions or platforms. Private SDK listings and native reports remain ignored.
