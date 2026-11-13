# Implementation provenance

Status: independent implementation started after the owner's instruction to
continue. No release has been published.

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
source-level renderer evidence, not a human visual check. Native runtime checks
were not run during this targeted compile-only repair; the owner has since
confirmed EULA acceptance, and the coordinator will resume them after the
mapping fixes.

GPL-3.0-only text: <https://www.gnu.org/licenses/gpl-3.0.txt>.

The plugin runner shares the modern vanilla protocol helper for teleport
acknowledgements. The official 26.3 `ServerboundAcceptTeleportationPacket`
record requires the resulting position and rotation in addition to the
teleport ID; earlier releases acknowledge the ID alone. The runner's wire
regression tests exercise both layouts. This is protocol-fixture behavior,
not rendered-client verification.

Implementation attestations and the final source inventory will be recorded
here before release. Runtime behavior and real-client visuals require separate
verification; availability metadata establishes neither.

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
