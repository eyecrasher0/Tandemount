# Modern loader implementation provenance

The modern implementation is original Java and Python written against the
behavioral contract in `docs/BEHAVIOR.md`. It covers Fabric, Forge and NeoForge
targets from Minecraft 1.16.5 onward where an exact upstream is available.
This document records implementation inputs, not a compatibility assertion.

The implementation agent read the new project's `AGENTS.md`, behavioral
specification, `platforms.json`, and the new `mods/modern/` implementation and
`tools/modern-*.py` tooling. It did not read predecessor horse-sharing mod
sources, patches, tests, archives or binaries, retrieve predecessor project
memory, or inspect the running KopajStvaraj server. Work was confined to the
new project and disposable local official loader servers. No artwork was used.

Upstream inputs consulted:

- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json),
  exact version metadata, published server mappings and official vanilla server
  artifacts. Build tooling reads JVM declarations, descriptors and inheritance;
  it does not distribute game classes. Minecraft 26.x declaration names are
  already unobfuscated. Mapping artifacts retain their upstream checksums.
- [Fabric metadata API](https://meta.fabricmc.net/),
  [Fabric intermediary mappings](https://maven.fabricmc.net/net/fabricmc/intermediary/),
  and the exact Fabric Loader profile and SDK libraries selected in the manifest.
- [Forge Maven](https://maven.minecraftforge.net/) and
  [NeoForge Maven](https://maven.neoforged.net/releases/), exact userdev SDK
  configurations, MCP mappings where the production runtime uses SRG names,
  and installed dedicated-server distributions.
- [Fabric Loader documentation](https://docs.fabricmc.net/develop/loader/) and
  [Fabric events documentation](https://docs.fabricmc.net/develop/events/), for
  the distinction between loader initialization/class transformation and the
  separate Fabric API gameplay-event library. The tested Fabric distributions
  install Fabric Loader, not Fabric API or third-party protection mixins.
- The loader-provided Sponge Mixin and ASM APIs for narrow transformations of
  exact, published vanilla signatures. The mod compiles against those real APIs;
  substitute game or loader API stubs are not used or packaged.
- Mojang Brigadier from the exact game dependency manifest, for vanilla textual
  administration commands. Dependencies are supplied by the game and loader.
- The newly written project socket client in `tools/modern-client.py` and
  `tools/vanilla_protocol.py`, authored independently within this project. The
  Java fixture exports packet IDs from the live native protocol registries.

## Vanilla client renderer audit

The renderer analysis inspected official Mojang client JARs and the matching
official client mappings for 1.16.5, 1.19.4, 1.21.1, and 1.21.11. The 26.3
client is named in the JAR and has no separate client-mappings artifact. The
exact version metadata, client JAR, client mappings, and inspected class-file
hashes are recorded in the ignored `.agents/modern-renderer-audit.json`; the
official metadata and client artifact links are also listed in
`docs/PROVENANCE.md`.

In 1.16.5, 1.19.4, and 1.21.1, `LivingEntityRenderer.render` checks whether the
player's vehicle is a `LivingEntity`; that branch wraps the head/body yaw delta,
clamps it to ±85°, and applies a 0.2 adjustment when its magnitude exceeds 50°.
In 1.21.11 and 26.3, `LivingEntityRenderer.solveBodyRot` contains the same
living-vehicle branch and values. The modern seat is a vanilla
`AreaEffectCloud`, whose official client class extends `Entity` directly, so
unmodified clients take the renderer's nonliving-vehicle path.

The cloud has zero radius and is not waiting. Hash-bound assertions against the
official client class files are recorded in `.agents/modern-renderer-audit.json`.
In 1.16.5, the cloud renderer declares no geometry-render override, and the
client particle loop has a zero upper bound at radius zero. In 26.3,
`EntityRenderers` binds the cloud to `NoopRenderer`, and
`AreaEffectCloud.clientTick` computes a zero particle count at radius zero.
The cloud starts with no potion effects; 26.3's server tick skips effect
application for empty potion contents and skips natural expiry for
`INFINITE_DURATION`. The seat sets its radius/duration adjustments to zero and
disables gravity/physics. On 1.16.5, which has no infinite-duration sentinel,
the fallback is `Integer.MAX_VALUE`; vanilla can expire it only after that many
loaded ticks (about 3.4 years at 20 TPS). The native fixture contains runtime
assertions for these configured fields. The owner has confirmed EULA acceptance;
native results are recorded separately for each exact artifact. This verifies
the model and renderer code paths, not the appearance confirmed by a human
using a client.

Native test fixtures use official entities, tracking objects, save hooks and
loader runtimes. Synthetic `ServerPlayer` fixtures, actual socket logins,
restart checks, and human visual checks are separate evidence categories.
No synthetic fixture result is described as a connected-client or human visual
confirmation. Public compatibility evidence must identify the exact artifact
hash and distinguish these categories.

On 2026-11-13, the two previously absent compile-only targets were checked with
the exact NeoForge SDKs published in `platforms.json` (1.20.2: `20.2.93`;
1.20.3: `20.3.8-beta`). Both commands passed; each emitted only javac's
source/target 8 obsolete-option warnings. Their artifact hashes and
compile-only status are recorded in `.agents/modern-build/neoforge-1.20.2/`
and `.agents/modern-build/neoforge-1.20.3/`.

```sh
TANDEMOUNT_CACHE=/run/media/eyecrasher/files/Development/.tandemount-cache \
  python3 tools/modern-build.py --minecraft 1.20.2 --platform neoforge
TANDEMOUNT_CACHE=/run/media/eyecrasher/files/Development/.tandemount-cache \
  python3 tools/modern-build.py --minecraft 1.20.3 --platform neoforge
```

With these additions, `.agents/modern-build/` contains 90 artifact records for
90 available modern loader/version candidates. These compile-only checks did not
launch a server or client and do not establish native compatibility. No human
client visual check has been performed.

## Early-modern portability and lifecycle fixtures

The targeted Forge 1.16.5 failure was investigated using the official
[Forge 1.16.5-36.2.42 userdev SDK](https://maven.minecraftforge.net/net/minecraftforge/forge/1.16.5-36.2.42/forge-1.16.5-36.2.42-userdev.jar),
specifically `patches/net/minecraft/entity/Entity.java.patch`, plus the matching
Mojang and MCP mappings already used by the build. Forge patches the native raw
position setter to load the destination chunk for a live entity added to a
server world. The former fixture moved an added horse through that setter, so
the fixture itself loaded the chunk that its next assertion expected to be
absent. The retained failure log establishes the failed assertion; the SDK
patch explains its loader-specific setup error. SDK and patch hashes are in
the ignored `.agents/modern-portability/forge-1.16.5-inputs.json`.

The corrected unavailable-horse-chunk fixture deliberately writes only exact
mapped position caches on a disposable native entity immediately before the
lifecycle guard runs. It asserts the destination is absent before setup, after
setup, and after cleanup. This is a native-object guard probe; it is not a
claim that the engine completed a real chunk unload. The world-change guard
similarly uses the exact mapped level setter or field on a disposable fixture
player, without claiming a completed player dimension transition.

Seat creation, movement, and settings updates now check the offset seat's
destination with the native non-loading `hasChunkAt` query before native
position setters, rider positioning, or world insertion. The fixture locates
an already loaded chunk edge through non-loading queries, then checks that
moving an active ride to an absent seat destination cleans the seat and that
a fresh mount there is rejected. Both assertions also require that the absent
neighbor remains unloaded. A separate player-teleport fixture calls the exact
native `ServerPlayer.teleportTo` signature with an already loaded destination,
confirms the native teleport occurred, and checks cleanup of that player's ride.
These additions require fresh native runs; compilation alone cannot pass them.

The strengthened late-observer fixture collects native `ServerEntity` pairing
packets separately for the seat and horse. It requires a typed seat-add packet
whose entity ID and coordinates match the current seat, a metadata packet for
that seat with nonempty data, and exact passenger packets containing only the
seat's second rider and the horse's driver, respectively. Passenger-packet
getters listed in 1.16.5 mappings are absent from its actual dedicated-server
class; the fixture reads the exact mapped `vehicle` and `passengers` fields
instead. The official server declarations inspected across 1.16.5, 1.17.1,
1.19.4, 1.21.1, 1.21.11, 26.1 and 26.3 retain those fields. This verifies the
native pairing payload sent to a synthetic observer; it is not a connected
client rendering or human visual confirmation.

The actual world-transfer fixture is separate from the mapped-level guard.
It selects the Nether world already created by the disposable server and
prepares its chosen destination chunk as fixture setup. It registers a fresh
synthetic player using native `ServerLevel.addNewPlayer`, opens that player's
own ride, and invokes the exact native `ServerPlayer.teleportTo` overload.
Assertions require the prepared destination, applied native teleport, target
world and Nether dimension, detached rider, removed seat, and survival of an
unrelated ride. The fixture returns that player through the native teleport
API before disposal. Exact `Level.dimension` and `ResourceKey.location`
signatures come from official mappings; the latter is named `identifier` in
1.21.11 and 26.x. Preparing a disposable test chunk does not grant the
production runtime a chunk-loading path. These strengthened fixtures require
fresh artifact-bound native runs.

Fabric Loader supplies initialization and class-transformation facilities;
its official documentation assigns game-specific hooks to the separate Fabric
API. This project's loader-only Fabric distribution supplies no native gameplay
cancellation event bus to which the fixture can attach an interaction, mount,
or dismount veto. Its result therefore records `loader_cancellation.status` as
`not-applicable`, with that exact scope. The actual
`consumed-interaction-not-overridden` assertion remains independent. Forge and
NeoForge record true cancellation booleans only after their real event-bus
listeners observed the veto and the matching state assertion passed. Fabric
API and third-party protection mixins are not installed or tested; the Fabric
exception does not establish compatibility with them.

## Forge cancellation API generations

The Forge 26.3 fixture failure was a missing legacy event class, not evidence
that cancellation was unavailable. The official
[Forge 26.3-66.1.8 sources](https://maven.minecraftforge.net/net/minecraftforge/forge/26.3-66.1.8/forge-26.3-66.1.8-sources.jar)
and userdev `Player.java.patch` show that the three-argument native interaction
posts `PlayerInteractEvent.EntityInteractSpecific` before invoking the target
entity. `EntityMountEvent` still vetoes native mounting and dismounting. Both
events have their own `CancellableEventBus`; the official
[EventBus 7.0.6 sources](https://maven.minecraftforge.net/net/minecraftforge/eventbus/7.0.6/eventbus-7.0.6-sources.jar)
define predicate listeners that cancel by returning true and listener handles
removed through `EventBus.removeListener`. Cancellation state is held by the
dispatcher, so the older `setCanceled` event mutation does not apply.

Exact cached userdev configurations place the transition from EventBus 6 to 7
between Forge 1.21.5 and 1.21.6. Dedicated event and EventBus declarations were
checked for 1.21.5 through 1.21.11 and all requested 26.x releases. Forge
1.21.6 through 1.21.11 retain `EntityInteract` with an event-specific bus;
26.1, 26.1.1, 26.1.2, 26.2 and 26.3 use `EntityInteractSpecific` instead. All
inspected EventBus 7 revisions expose the same predicate-registration and
listener-handle removal signatures. Input hashes and exact declarations are
recorded in the ignored `.agents/modern-portability/forge-eventbus-api-inputs.json`.

The fixture detects a declared event-specific `BUS`, registers a predicate
which vetoes only the chosen player and mount direction, and removes the
returned handle on close. Legacy Forge and NeoForge retain their global-bus
listener path. A missing Forge `EntityInteract` selects the actual native
`EntityInteractSpecific` replacement; missing cancellation APIs still fail the
fixture. This repair changes only the opt-in test listener and does not add a
runtime hook or a cancellation exemption. New builds require fresh native
interaction, mount and dismount cancellation checks before compatibility can
be asserted.

## Native fixture player constructors

The 1.19 fixture-construction failure was investigated against exact official
`ServerPlayer` declarations for all 35 requested modern game releases. Their
public constructor signatures form four cases: 1.16.5 takes
`ServerPlayerGameMode`; 1.17 through 1.18.2 and 1.19.3 through 1.20.1 take the
three server/level/profile arguments alone; 1.19, 1.19.1 and 1.19.2 additionally
take `ProfilePublicKey`; 1.20.2 through 26.3 take `ClientInformation` instead.
Class-file parameter annotations mark the profile-key argument as
`javax.annotation.Nullable` in each of the three relevant versions. The
fixture passes null for that known optional key on its offline synthetic
player. It does not create signing keys or alter authentication settings.

Build tooling now generates a complete constructor descriptor only after
matching a known exact signature against public declarations in that game's
official server JAR. The fixture requests precisely that constructor and
selects the fourth argument by its exact mapped type. It no longer infers the
argument from constructor arity or attempts a game-mode fallback when another
fourth argument is present. Unknown or ambiguous signatures fail the build.
Game JAR, class hashes, constructor descriptors and profile-key parameter
annotations are retained in the ignored
`.agents/modern-portability/player-constructor-inputs.json`. This correction
is confined to the opt-in native fixture and exact build mappings; successful
compilation does not establish that the remaining native checks pass.
