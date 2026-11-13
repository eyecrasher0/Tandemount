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

The portability snapshot compiles all plugin target packages and its disposable
fixture against the real Paper 1.12.2 SDK with Java release 8. A separate main
source compile uses the published Paper 26.3 API and its official dependencies
listed above. Exact source hashes, compiler commands, and artifact hashes are
retained in the ignored `.agents/plugin-portability-*/compile.json` records.
Compilation and API-bytecode inspection do not establish native or visual
compatibility; final native runs remain the coordinator's responsibility.
