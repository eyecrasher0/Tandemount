# Plugin seat and passenger rendering

The helper seat is a native vanilla nonliving entity. The adapter for 1.19.4 and
later uses a BARRIER
block display in the same native vehicle graph on the server and vanilla client,
with the ordinary horse as its shared root:

```text
horse ─ first player (ordinary driver)
      └ zero-width Interaction anchor
        └ invisible NoAI llama for rear offset
          └ invisible NoAI opposing llama for the zero-offset baseline
            └ zero-width Interaction lift
              └ BARRIER block display ─ second player
```

The scale-capable family, beginning with 1.20.5, has five helpers. The
1.19.4–1.20.4 family uses eight ordinary adult llamas with alternating native
rotations, separated by seven zero-height Interactions, for eighteen helpers.
Their horizontal vectors cancel sideways and sum to the configured rearward
distance. The separators keep each carrier's direct vehicle nonliving, so
vanilla horse passenger body rotation cannot erase the alternating directions.
Original native mount methods position every
edge. This path does not hide an edge or broadcast recurring absolute world
positions for a follower. The first player remains the horse's controlling
passenger. The second player's direct vehicle remains nonliving.

Vanilla horse attachments alone place the player's feet about 0.84375 blocks
above the horse with no rear offset. Real synchronized llama scale attributes
provide rear distance, with two opposing carriers supporting the entire 0–2
range. The lift's signed synchronized height supplies the independent vertical
setting. Both scales remain inside the native attribute bounds.

An invisible living carrier still has a client pick box. The anchor places
the carriers 64 blocks below the horse, and the lift restores the display
beside the player. This keeps ordinary interaction rays away from their boxes.
The final display is separate from the lift because vanilla dismount searches
near the direct vehicle's origin. Neither Interaction spacer has width. All
helpers have no gravity, empty server bounds and refused native save entry
points. The carrier server ticks run only the ride validator; AI, collision,
sound and persistence are disabled. No entity type is created or registered.
Pre-1.19.4 adapters remain a separate porting boundary; successful compilation
of their falling-block path does not establish native graph compatibility.

Older Interaction metadata updates its bounding box without refreshing the
cached dimensions used by passenger attachment points. The adapter measures
the native height ratio, uses ordinary pose callbacks on the server, and
appends a native pose-only metadata value after each existing helper metadata
snapshot. The original vanilla data assignment invokes the dimensions callback
even when that pose value equals the current value. This ordering also applies
to an upstream tracker's initial snapshot for a late observer. It introduces
no periodic pose or helper world-position updates and does not create an entity
or select observers independently of the native tracker.

The display seat carries the vanilla BARRIER block state. AIR is not a
safe invisible state: the unchanged vanilla client removes an AIR falling block
at the beginning of its first entity tick, before checking whether the world is
client-side. The server's generated subclass replaces only the server tick, so
server ride maps and mount packets alone cannot establish client survival.
BARRIER remains non-AIR and its render shape is `INVISIBLE`. Its official
block-display model contains only a particle texture, with no elements or
parent geometry. The existing no-gravity metadata is retained.

The earlier 26.3 follower repair used three-tick display interpolation to avoid
the falling block's no-op interpolation and the local-passenger packet snap
branch. The current graph instead advances attached passengers through native
`commonTick` and `rideTick` calls from the horse root. It sets no independent
display interpolation delay. Client entities and their movement methods are
unchanged. The display has no controlling passenger, so the second player does
not gain horse control.

Attachment selection requires the real upstream BlockDisplay and Interaction
types. Exact aliases for attachment points, dimensions callbacks, body rotation
and metadata access are recorded in `attachment-mappings.properties`, derived
from each exact official server mapping. Each target still requires its own
artifact-bound native and original-client evidence before a release claim.

This seat type avoids the living-vehicle branch in the vanilla player renderer.
Official client bytecode for 1.20.6, 1.21.11, and 26.3 shows that
`LivingEntityRenderer` gets the rendered living entity's vehicle and only uses
the vehicle's body yaw when that vehicle is itself a `LivingEntity`. That branch
wraps and clamps the head-to-body yaw difference to ±85 degrees, then applies
the 0.2 adjustment when the absolute clamped difference exceeds 50 degrees.
In 1.20.6 the bytecode compares the squared difference to 2500, which is the
same 50-degree threshold; later audited clients compare the absolute value.
Otherwise the renderer uses the passenger's own body yaw. At each audited
version, the vanilla falling block and block display both extend `Entity`, not
`LivingEntity`; the player renderer uses `LivingEntityRenderer` as its base.

`NativeAccess.createSeat` rejects a helper unless its Bukkit entity has the
audited vanilla type for the exact game and is not a `LivingEntity`. The native fixture checks
the same invariant, and the loopback client capture ties that checked seat to
the entity ID in the received spawn and mount packets. This prevents a future
seat change from silently reintroducing the living-vehicle body-yaw path.

The 1.7.10 server names its Bukkit falling-block wrapper `CraftFallingSand`;
its native class is still `EntityFallingBlock`. The adapter accepts that exact
upstream wrapper name. Old platforms without a cancellable generic dismount
event receive a generated wrapper that also implements Bukkit `Vehicle`, so
their `VehicleExitEvent` can preserve a cancelled exit. This changes the Bukkit
interface only: the native class remains a nonliving falling block, with AIR
block state and the same vanilla client entity type.

On 1.7.10, the entity owns a final bounding-box reference whose six coordinates
are mutable. The adapter updates that box in place through its upstream setter.
Newer versions still require a nonstatic, nonfinal bounding-box field, avoiding
the shared empty-box constants in recent server classes.

The generated native subclass calls an accessible upstream constructor. When
Spigot makes the world/coordinates/state constructor private, the adapter calls
its public entity-type/world constructor with the already registered vanilla
`minecraft:falling_block` type and verifies that type's registry key. The display
adapter verifies `minecraft:block_display` instead. Spigot
26.2 and later keep this constant in the separate native `EntityTypes` holder.
No type is constructed or registered. Falling-block adapters select BARRIER
where the upstream provides it; 1.7.10 still needs a separately verified
invisible non-AIR state. Both adapters must expose the expected nonliving type
before joining a ride.

The subclass overrides the falling-block class's own declared tick, avoiding
unrelated inherited methods with the same short mapping names. The audited
Spigot families include `k` in 1.18–1.19.2 and `g` in 1.21.5–1.21.11. In exact
1.19.3 mappings, the position setter is `f(double, double, double)`; `e` only
updates `VecDeltaCodec`. The 1.19.4 mappings swap those two meanings, so this
selection is tied to the exact upstream game version rather than alias order.
The block display's original tick is declared by its native `Display` parent.
Carrier and spacer subclasses receive that exact mapped tick entry explicitly.
Passenger positioning enters the native one-argument virtual method first,
preserving older llama overrides instead of bypassing them through Entity's
two-argument movement callback.

## Native lifecycle fixture

The disposable fixture requests a real cross-world transfer while the second
player is sharing a ride. On Folia, its native async transfer runs on that
player's entity scheduler and uses the upstream `UNMOUNT` flag while omitting
`LOAD_CHUNK`. Native unmounting is part of that transfer. The fixture then
confirms the actual destination world, seat cleanup, and a successful retry.

Setup deliberately prepares a secondary-dimension landing chunk and a distant
return chunk in the disposable worlds. Folia prepares these asynchronously and
edits their blocks only on their region owners. Fixture chunk tickets, or a
legacy unload-cancellation listener, retain those two landing chunks. Old
world APIs also disable spawn retention in the disposable primary world. These
operations belong to the fixture; Tandemount's runtime does not load or retain
chunks.

During setup, a separate temporary fixture ticket keeps the initial home chunk
available until the three real players arrive. The fixture removes that ticket
on the chunk owner before creating a ride. Old Bukkit uses the same temporary
condition in its unload listener. This avoids a setup race after spawn retention
is disabled and leaves the later native evacuation/unload check unretained.

On older Folia, the public async teleport API can unmount a passenger as part of
an accepted transfer without emitting a cancellable teleport event. On later
Folia, that public request can be rejected while the ride remains active. The
fixture checks the returned future and observed events before asserting the
corresponding lifecycle result. World settings run on the global scheduler;
block preparation and player transfers use their region or entity owners.

For the unload check, all four real loopback players leave the ride's original
chunk through native world-transfer and dismount lifecycle. The fixture then
requests a real unload, observes `ChunkUnloadEvent` and the unloaded chunk
state, rejects live helper entities, and retries from the separate return
landing. This establishes cleanup across evacuation and actual chunk unloading;
it does not isolate unloading a chunk that still contains an occupied ride.
Dead native references awaiting removal are distinct from live helpers. The
world-save scan separately rejects every recorded helper UUID in saved data.

Before disabling the plugin, `tmfixture saveprobe` runs on the helper's entity
owner. It requires the same live helper and mounted riders to remain registered
in both the plugin ride maps and the native-seat bridge before and after the
probe. It invokes the exact native boolean save entry points, including
`serializeEntity` where present and the available passenger-save overloads.
Each invocation receives a new real native NBT compound, or a real
`TagValueOutput` created with the entity's server registry context on recent
versions. Boolean force-save arguments are true. Every call must return false
and leave its output empty; recent outputs also expose an empty built NBT
result. Only then does the fixture emit
`active-seat-refuses-native-serialization` and enter `save-probed`, allowing
the runner to disable the plugin.

The mapped Spigot save entry points are included as well: `a_` on 1.16.5,
`f/g` on 1.21.5, and `b/c` on 1.21.6–1.21.11, with their exact native compound
or `ValueOutput` signatures. Additional save arguments must be boolean flags.
The Spigot `TagValueOutput` factory receives the helper's real registry access;
the probe checks both that output's emptiness and its built native compound.
The fixture obtains protocol IDs from the loaded upstream protocol templates
and their packet-type identifiers across both named and Spigot mappings.

Disposable platform and landing blocks use the upstream no-physics material
setter where it exists. This prevents early 1.18 Paper setup from triggering
neighbor physics into an unloaded chunk at the platform edge. The 1.7 Bukkit
fallback uses its ordinary material setter. These edits are fixture setup only.

This active serialization check, the final shutdown world-file scan, and the
native restart check are separate evidence. An old active-phase file scan
proves only the inspected snapshot when a platform rejects a manual save
command; Folia 1.20.6 exposed that earlier runner limitation. The current probe
does not depend on a manual save command being available.

## Evidence limits

The 26.3 owner regression fixture now sends real native-encoded interaction
packets through loopback clients, including repeated main/off-hand clicks. It
observes an ordinary non-operator passenger across ticks and movement, checks a
cancelled packet interaction, and decodes the observer's actual seat spawn with
the native packet codec. Its observed native block-display metadata must match
the helper's BARRIER state, no-gravity flag, and attached zero-delay metadata.
The command check decodes the actual clientbound chat component, requires color
and style plus all four adjustment click actions, then submits an observed click
command through the client's command packet and verifies independent settings.
These checks are separate from executing a vanilla client entity tick and from
human visual review.

For the earlier 26.3 follower repair, an actual curved ordinary-driver capture includes
native player-look, steering and vehicle-movement packets. Replaying 467
received observer frames through original vanilla packet codecs and native
entity movement, common-tick and tick methods measured at most 0.02917 blocks
of seat-relative error across four client-tick phases. The matched falling-block
control reached 0.11063 blocks. A separate original display completed 1,200
stationary client ticks without removal or displacement.

An executable positional-component probe uses an ordinary native ArmorStand
passenger and the original final eye-position methods. It confirms the
falling-block control immediately resets both position endpoints, while the
display keeps the previous endpoint and advances through three native rider
ticks. This is component evidence. It does not construct a LocalPlayer or
render a camera; the LocalPlayer and camera branches were audited separately
in the original client classes.

The official client bytecode establishes the renderer's type check and the
vanilla seat class hierarchy. The updated native fixture and loopback protocol
checks assert the runtime seat type and clientbound mount graph. Every release
target still needs native evidence bound to its final artifact hash. A protocol capture
does not execute the client renderer. No real client visual review has been
performed, so smoothness and appearance during a 179° to -179° turn remain open
for human confirmation.

The Paper 26.3 owner runner records each connection's received and sent packet
times, the driver's requested vehicle trajectory, and native driver/helper
poses. This keeps the driver's view, passenger's view and third observer
captures separate. A packet trace alone does not construct a `LocalPlayer` or
execute the client renderer.

An audit of the unchanged official 26.3 client found that
`ClientLevel.tickNonPassenger` and `tickPassenger` advance each vehicle before
its children, with `commonTick` followed by `rideTick` for each passenger.
`Entity.setOldPosAndRot` assigns both the renderer's old position and the
camera's old position. The llama passenger attachment uses the llama's own
rotation; the plugin currently updates that rotation from the server horse.
These declarations identify a possible difference between a driver's locally
controlled horse and remotely received helper rotations during a turn. They
do not establish the cause of an observed lag or verify a repair.

`ClientAttachmentLatencyProbe` isolates that rotation dependency using the
unchanged official 26.3 client entity classes. It advances the five-helper
vehicle graph with native passenger ticks, injects the requested horse path,
and delays helper rotations before submitting them to the native stepped
interpolator. For a gradual turn of two degrees per tick at the default
0.5-block rear offset, the maximum position errors were:

| Helper yaw delay | Maximum offset error |
| --- | --- |
| 0 ms | 0.035 blocks |
| 200 ms | 0.105 blocks |
| 500 ms | 0.208 blocks |
| 1,000 ms | 0.375 blocks |

Pure translation stayed within 0.000002 blocks for all four delays, retaining
the horse as the root. An abrupt 90-degree turn with delayed helper yaw reached
0.707 blocks of temporary error. These are component results with an injected
root path and player geometry stand-ins. The delays are not measured network
RTTs, and this probe does not establish either player's rendered view or a
fix for the owner's report.
