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

The build fetches the exact official Forge APIs and MCP mappings. It does not
compile against stubs or depend on another mod. Each jar accepts unmodified
clients and installs only on the server. Do not install the native-test jar on
a real server. The compatibility matrix and artifact-bound evidence determine
which builds are verified; compilation alone does not establish support.

Run native checks only in the disposable runner. The explicit switch accepts
the Minecraft EULA for that local fixture:

```sh
python3 tools/legacy-native-test.py --java /path/to/jdk8 --cache /large/task/cache/legacy --artifacts /path/to/artifacts --accept-eula
```

The runner binds to `127.0.0.1`, uses offline fixture identities, and limits each
sequential native server to a 1 GiB Java heap. It does not connect to or modify
an existing server. Native test sources are in `mods/legacy/native-test/` and
are compiled into a separate local test mod. They are never in a distributable.
It records artifact, launcher, test-mod, source, and log SHA256 digests, then
restarts the native server to inspect the saved fixture world. A failed required
check produces a nonzero exit status.

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
vertical=0.0625
rear=0.625
types=horse
```

Both offsets are blocks in 1/16-block increments. Vertical placement is bounded
to `[-1, 2]` and rearward placement to `[0.25, 2]`. Invalid, out-of-range,
nonfinite, or fractional-step values are rejected. Only ordinary horses are
enabled. On 1.7.10 and 1.8.9, donkeys, mules, skeleton horses and zombie horses
use horse variant data and are explicitly excluded.

`/tandemount` shows help and clickable controls. The equivalent text commands
are `vertical <value|+|->`, `rear <value|+|->`, `enable`, `disable`, `reload`,
`reset`, `types horse`, and `debug <on|off>`. `status` is available to ordinary
users. Every accepted change saves immediately, preserves unknown properties,
and updates active rides. Reload rejects a broken file while keeping the
active configuration. Disabling removes active seats.

Forge 1.12.2 registers and checks the native Forge permission node
`tandemount.admin`, default `OP`. Forge 1.7.10 and 1.8.9 have no equivalent
standard node API: admin commands use vanilla command permission level 2 with
the command name `tandemount`. Ordinary riding and help require no operator
status. Console can use all text commands.

Forge 1.7.10 predates the later loader's vanilla login completion path. Its
server hook uses normal native player connection initialization only when FML's
`NetworkRegistry.isVanillaAccepted(CLIENT)` confirms that all loaded network
mods accept an unmodified client. Another mod requiring a client installation
retains its normal FML handshake requirements. Tandemount does not override that
decision. A separate native test covers this rejection policy.

## Native lifecycle and evidence limits

Joining occurs synchronously inside ordinary horse interaction after Forge's
whole cancellable interaction event has returned. A later listener of the same
event priority therefore cannot be bypassed by a queued join. The native mount
method is also respected. Forge 1.8.9 and 1.12.2 have a cancellable
`EntityMountEvent`; Forge 1.7.10 does not expose that event. Ordinary sneak
dismount uses the native riding path. Cancellation on supported APIs leaves
the ride intact. Destroying a vanished/dead ride removes dangling references;
this is lifecycle cleanup, not a queued retry of a cancelled user operation.
The 1.8.9 seat hook emits one dismount permission event before the native outer
`mountEntity(null)` clears references; this preserves cancellation that the
upstream outer method otherwise ignores. Explicit rider teleports and world
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

Minecraft 1.7.10 and 1.8.9 have only one direct rider per entity. A second seat
cannot be attached directly to the occupied horse without replacing the
driver. Chaining it to the driver fixes the vanilla client's horizontal
offset and cannot provide independently adjustable rearward placement. The
implementation therefore moves a separate native seat after horse entity
updates. Minecraft 1.12.2 also uses a separate seat so both placement controls
work with vanilla clients.

The final seat uses vanilla `EntityFallingBlock` with the air block state on all
three versions. It is nonliving, and the exact client renderer has no model to
draw for air; the legacy renderer audit records the bytecode evidence. The
transformer also blocks falling-block updates. Native lifecycle checks still
need to confirm the entity never places a block, remains attached during a
ride, and is absent after shutdown/restart. Actual vanilla-client testing is
also needed for interpolation, late-observer pose, and natural seated
appearance. Until those checks are complete, these requirements remain
unverified and these targets must not be described as fully
behavior-compatible.
