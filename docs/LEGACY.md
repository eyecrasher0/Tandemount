# Legacy Forge modules

Tandemount includes independent server-only source targets for these exact
Forge versions:

| Minecraft | Forge |
| --- | --- |
| 1.7.10 | 10.13.4.1614 |
| 1.8.9 | 11.15.1.2318 |
| 1.12.2 | 14.23.5.2864 |

Build with Java 8 and Python 3:

```sh
python3 tools/legacy-build.py --java /path/to/jdk8 --cache /large/task/cache/legacy --output /path/to/artifacts
```

`--java` names a Java 8 JDK, not a JRE. Without it, the tools check
`TANDEMOUNT_JAVA_8`, `JAVA8_HOME`, the ignored `.agents/toolchains.json` key `8`,
`JAVA_HOME`, and `javac` on `PATH`, accepting only Java 8. Install Python 3,
`curl`, and a Java 8 JDK before building. No owner-specific directory is needed.
The default cache is `$TANDEMOUNT_CACHE/legacy`, or `.agents/cache/legacy` when
that environment variable is unset. `--cache` specifies the complete legacy
cache directory. Artifacts default to its `artifacts/` subdirectory.

The build reads the release version from `project.json`, expands the annotation templates, and writes `tandemount-forge-VERSION+MINECRAFT.jar` with matching Forge and manifest metadata. It fetches the exact official Forge APIs and MCP name mappings. The client probe prepares its pinned SRG archive in the same SDK cache. The build does not
compile against stubs or depend on another mod. Each jar accepts unmodified
clients and installs only on the server. Do not install the native-test jar on
a real server. The compatibility matrix and artifact-bound evidence determine
which builds are verified; compilation alone does not establish support.

Run native checks only in the disposable runner. The explicit switch accepts
the Minecraft EULA for that local fixture:

```sh
python3 tools/legacy-native-test.py --java /path/to/jdk8 --cache /large/task/cache/legacy --artifacts /path/to/artifacts --accept-eula
```

The runner binds to `127.0.0.1` (`--port`, default 25844), uses offline fixture identities, and limits each
sequential native server to a 1 GiB Java heap. It does not connect to or modify
an existing server. Native test sources are in `forge/legacy/native-test/` and
are compiled into a separate local test mod. They are never in a distributable.
It records SHA-256 digests for the installed mod, exact Forge launcher and
vanilla server, test mod, test inputs, and logs. It then restarts the native
server to inspect the saved fixture world. A failed required check produces a
nonzero exit status.

The vanilla protocol fixture sends no FML hostname marker, registration payload,
or client mod channel. It logs in through `JoinGame`, sends real `UseEntity`
interactions, and decodes ordinary entity spawn, attachment, and movement
packets for existing and late observers. Those observations establish wire
behavior only. They do not establish client rendering or human visual approval.

## Configuration and permissions

`config/tandemount.properties` contains:

```properties
enabled=true
debug=false
vertical=0.8125
vertical-origin=horse-feet
rear=0.5
types=horse
excluded-dimensions=
```

New, missing and reset settings place the passenger's feet 0.8125 blocks above
the horse's feet and 0.5 blocks rearward. Both offsets are blocks in 1/16-block increments. Vertical placement is bounded
to `[-1, 2]` and rearward placement to `[0.25, 2]`. Invalid, out-of-range,
nonfinite, or fractional-step values are rejected. Only ordinary horses are
enabled. On 1.7.10 and 1.8.9, donkeys, mules, skeleton horses and zombie horses
use horse variant data and are explicitly excluded.

An older file with an explicit `vertical` and no `vertical-origin` is saved with
`vertical-origin=legacy-mounted-offset`. Its numeric values and original units
remain in effect: vertical is relative to the horse's native mounted offset,
before the player's native feet offset. Adjustment commands retain those units;
help and status display them. `/tandemount reset` explicitly switches to the new
owner defaults and `horse-feet` units. A missing vertical value always uses the
new feet default. Unknown and duplicate properties are rejected so a typo cannot silently change behavior. This format change does not
silently reinterpret an existing saved vertical number.

`/tandemount` shows gold help and aqua clickable adjustment, reload and reset
controls to administrators. The equivalent text commands
are `vertical <value|+|->`, `rear <value|+|->`, `enable`, `disable`, `reload`,
`reset`, `types horse`, and `debug <on|off>`. `status` requires administrative permission and shows enabled/debug, real active offsets and their units, horse types, excluded dimensions, and the current ride count. Every accepted change saves immediately and updates active rides. Invalid syntax, duplicate/unknown keys, invalid booleans, invalid offsets, or invalid exclusions fail reload while retaining the last valid active settings and leaving the invalid file byte-for-byte unchanged. Disabling removes active seats.

`excluded-dimensions` is empty by default. To exclude dimensions, set it to comma-separated signed Forge integer dimension IDs, for example `excluded-dimensions=-1,1`, then use `/tandemount reload`. Namespaced names belong to later game versions and are rejected on these releases. IDs must fit a signed 32-bit integer; duplicate IDs, empty entries and malformed numbers are rejected. An unloaded or not-yet-registered custom dimension ID may be listed without loading or initializing that dimension. New rides cannot start in an excluded dimension. Reload ends extra seats in newly excluded dimensions while retaining the drivers' ordinary horse relationships.

Forge 1.12.2 registers and checks the native Forge permission node
`tandemount.admin`, default `OP`. Forge 1.7.10 and 1.8.9 have no equivalent
standard node API: admin commands use vanilla command permission level 2 with
the command name `tandemount`. Ordinary riding and help require no operator
status. Console can use all text commands.

On Forge 1.7.10, the vanilla handshake timeout completes the connection
through the native dispatcher when FML's `isVanillaAccepted(CLIENT)` allows
vanilla clients. The fallback uses a five-second delay. Servers that require a
client mod keep Forge's original ten-hour handshake deadline and rejection
handler. The disposable fixture checks the scheduled deadline and delivers the
native timeout event to a pending vanilla connection to verify the rejection.
On Forge 1.8.9 the asynchronous dispatcher can race immediate
vanilla completion. The bridge initializes the native play connection only
when FML accepts vanilla clients and the FML marker is absent or false. Both
old loaders retain their client-required mod rejections. The native test also
checks rejection of an incompatible mod list on 1.7.10.

## Native lifecycle and evidence limits

Joining occurs synchronously inside ordinary horse interaction after Forge's
whole cancellable interaction event has returned. A later listener of the same
event priority therefore cannot be bypassed by a queued join. The native mount
method is also respected. Forge 1.8.9 and 1.12.2 have a cancellable
`EntityMountEvent`; Forge 1.7.10 does not expose that event. Ordinary sneak
dismount uses the native riding path. Cancellation on supported APIs leaves
the ride intact. Destroying a vanished/dead ride removes dangling references;
this is lifecycle cleanup, not a queued retry of a cancelled user operation.
The 1.8.9 hook checks Forge's outer null-target event and then its inner
dismount event for a managed passenger or driver. Either veto leaves both
riders in place. An allowed dismount emits each event once. Explicit rider teleports and world
changes end the shared ride. Expired seats detach their native relationships
without searching for terrain or reloading an unloading chunk.

The native object suite exercises actual player `travelToDimension` (1.7.10
and 1.8.9) or `changeDimension` (1.12.2) calls for the passenger and driver in
separate rides. Fixture setup looks up the disposable server's standard End
with `DimensionManager.getWorld(1)`. If Forge has unloaded that empty dimension,
the fixture calls `DimensionManager.initDimension(1)` before either transfer.
The assertion records whether the End was already loaded or initialized for
the fixture; failure to obtain a distinct `WorldServer` fails the suite without
skipping the transfers. It verifies destination dimension, world identity and entity membership,
source absence, preserved source horse, and removal of the managed seat and
its passenger relationship. The native player dimension APIs do not unlink
an ordinary horse themselves; the fixture clears that separate driver link
after verifying the extra seat. It never assigns a world field to simulate a transfer. Vanilla's
End teleporter may prepare its local landing platform and destination chunks
inside the disposable fixture. Dimension initialization and local preparation
are test setup only, separate from Tandemount's
runtime, which never loads a destination world or chunk. These are synthetic
native-object checks, not rendered-client evidence.

Seats use ordinary vanilla tracking. Tandemount never manually spawns a horse
for observers or sends packets forcing a hidden horse visible. Pose updates
check the already loaded destination chunk and never load one. Managed seats
cannot save, tick their native AI/physics, take damage, receive loot interaction,
or push other entities on the server.

Native tracking can send a mounted player
before its vehicle is known to an observer. The runtime checks the native
tracking sets for all four ride participants. Once the observer already tracks
every participant, it sends the accepted driver's horse link and passenger's
seat link once. Losing native tracking clears that observer's synchronization
record. This does not spawn an entity, add a tracker, retry a cancellation, or
send a hidden horse to an observer.

The legacy runtime uses the owner's explicit pre-1.19.4 exception: the second
player rides a **separate following seat**, with an independent vehicle root.
The released builds have artifact-bound server, wire, and original-client
checks for this seat. Human visual confirmation is unverified.
[Legacy Forge 1.0.0 verification](https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/verification-legacy-forge.json)

The helper is an existing vanilla entity, selected for actual client picking
and lifetime behavior:

| Minecraft | Helper and synchronized client behavior |
| --- | --- |
| 1.7.10 | Invisible slime, signed native size metadata `-1`. That exact client produces an inverted bounding box, including the generic picking border. Native ray intersection tests cannot select it; size zero still intercepts clicks. The helper's native size and mounted offset are preserved. |
| 1.8.9 | Invisible marker armor stand, with its native no-gravity flag. Marker metadata makes it noncollidable and gives it zero dimensions on the client. |
| 1.12.2 | Zero-radius area-effect cloud, with no gravity. It is nonliving and noncollidable. Zero radius produces no cloud particles. |

Only the managed helpers are guarded on the server against native ticks,
saving, damage, collision, and loot interactions. No custom entity registry
entry or client code is installed. The two living legacy helpers follow the
passenger's own accepted look yaw, including native head-yaw packets, because
the vanilla living-rider renderer otherwise takes body yaw from the vehicle.
The 1.12.2 nonliving helper avoids that renderer branch.

All three helpers receive ordinary native position packets through existing
tracking sets each server tick. The 1.7.10 client lacks a no-gravity flag; an
additional native zero-velocity packet prevents its original living tick from
integrating a downward velocity. The original client probe measures both the
refreshed frames and an extra frame without refresh. Network delivery gaps can
therefore affect this old follower. These packets do not establish a native
horse-root graph or prove rigid motion from a rendered driver's view.

The [client audit](LEGACY-RENDERER.md) separates artifact-bound native behavior,
received protocol packets, untouched original-client packet/tick/picking
execution, and human visual approval. No synthetic or headless test is described
as human visual confirmation. Old AIR/falling-block reports predate these
carriers and are superseded by fresh exact-artifact evidence.
