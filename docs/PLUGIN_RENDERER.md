# Plugin seat and passenger rendering

The helper seat is a native vanilla falling-block entity. A passenger is
mounted on this seat while the driver remains mounted on the horse. The client
receives the native seat entity and its mount edge; no custom client entity is
created.

This seat type avoids the living-vehicle branch in the vanilla player renderer.
Official client bytecode for 1.20.6, 1.21.11, and 26.3 shows that
`LivingEntityRenderer` gets the rendered living entity's vehicle and only uses
the vehicle's body yaw when that vehicle is itself a `LivingEntity`. That branch
wraps and clamps the head-to-body yaw difference to ±85 degrees, then applies
the 0.2 adjustment when the absolute clamped difference exceeds 50 degrees.
In 1.20.6 the bytecode compares the squared difference to 2500, which is the
same 50-degree threshold; later audited clients compare the absolute value.
Otherwise the renderer uses the passenger's own body yaw. At each audited
version, the vanilla `FallingBlockEntity` class extends `Entity`, not
`LivingEntity`; the player renderer uses `LivingEntityRenderer` as its base.

`NativeAccess.createSeat` now rejects a helper unless its Bukkit entity is both
`EntityType.FALLING_BLOCK` and not a `LivingEntity`. The native fixture checks
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

This active serialization check, the final shutdown world-file scan, and the
native restart check are separate evidence. An old active-phase file scan
proves only the inspected snapshot when a platform rejects a manual save
command; Folia 1.20.6 exposed that earlier runner limitation. The current probe
does not depend on a manual save command being available.

## Evidence limits

The official client bytecode establishes the renderer's type check and the
vanilla seat class hierarchy. The updated native fixture and loopback protocol
checks assert the runtime seat type and clientbound mount graph. Every release
target still needs native evidence bound to its final artifact hash. A protocol capture
does not execute the client renderer. No real client visual review has been
performed, so smoothness and appearance during a 179° to -179° turn remain open
for human confirmation.
