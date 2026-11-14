# Building modern loader artifacts

Use Python 3 and a Java 21 compiler, plus Java 25 for the 26.x loader SDKs.
Set `JAVA21_HOME`, `JAVA_HOME`, or
`TANDEMOUNT_JAVA_21` to an installed JDK; Java on `PATH` is also detected.
`TANDEMOUNT_JAVA` can explicitly select the compiler executable. Native tests
need the exact Java major recorded for the target in `platforms.json`; use
`JAVA8_HOME`, `JAVA16_HOME`, `JAVA17_HOME`, `JAVA21_HOME` or `JAVA25_HOME`, or
the corresponding `TANDEMOUNT_JAVA_<major>` executable variables.

Downloaded SDKs and disposable worlds live under `TANDEMOUNT_CACHE`, defaulting
to the ignored `.agents/cache` directory. They are never included in artifacts.

```sh
python3 tools/modern-build.py --minecraft 1.21.1 --platform fabric
python3 tools/modern-build.py --all --platform forge
python3 tools/modern-build.py --all --platform neoforge
```

Each jar declares exactly one Minecraft release and one loader. The build reads
the exact official mappings and loader SDK, then generates full native method
descriptors. Narrow native hooks fail when an expected signature is absent.
Artifacts are written to `mods/modern/build/`.

Prepare an official disposable server distribution using the project's cache
tools before native execution. Local execution requires explicit EULA
acceptance. The fixture binds only to loopback, uses an isolated world and an
offline test identity, and never connects to an existing server.

```sh
python3 tools/modern-native.py --minecraft 1.21.1 --platform fabric \
  --accept-eula --network-test --restart-test
```

Native entity checks, a real vanilla protocol login, and reopening the saved
world produce separate evidence in `.agents/modern-native/`. These tests do
not establish a human visual check of animation or seat placement.

Settings are saved in `config/tandemount.properties`. `height` is bounded to
0 through 3 blocks and `rear` to 0 through 2 blocks; `+` and `-` change either
by 1/16 block. Only `horse-types=horse` is accepted. Additional species require
separate implementation and compatibility evidence. `enabled` and `debug`
accept `true` or `false`; unknown configuration keys survive saved updates.

`/tandemount` is available to ordinary players. Administration on these loaders
uses vanilla operator permission level 2 as the native default for
`tandemount.admin`; the mod does not install an external permissions provider.
Cancelled native operations are not rescheduled. Seats use native tracking and
do not send direct visibility overrides or force-load chunks.

The second rider's final vehicle is a vanilla `BlockDisplay` carrying the
invisible-render-shape `BARRIER` state. It is a nonliving entity, so the native
player renderer can turn the rider's head and body independently. The horse
retains the first player as its first passenger and controlling passenger. All
seat helpers belong to the horse's native passenger graph on the server and
are synchronized through the server's ordinary visibility-aware tracker.

The native graph has two attachment families:

- Releases with the `SCALE` attribute use an Interaction anchor, two opposed
  llama carriers, an Interaction lift, and the final BlockDisplay.
- Earlier releases with Interaction and BlockDisplay use eight adult llama
  carriers with symmetric yaw angles. Zero-height Interaction links between
  them prevent native living-vehicle body-yaw inheritance from changing the
  attachment geometry. Their continuous rearward range covers the documented
  0 through 2 blocks.

Interaction metadata alone does not refresh cached attachment dimensions in
several official clients. A normal synchronized pose update refreshes those
dimensions. Each native pairing collector receives a pose-only metadata tail
following its complete snapshot; a changed height receives the same tail
following the ordinary dirty metadata update. These use the existing native
tracker and native pairing collector, including the exact NeoForge collector
adapter where required. There are no independent seat-position broadcasts and
no periodic pose refresh. The final helper metadata is prepared and flushed by
the ordinary native tracker before mounting the second player. Height calibration
uses native attachment methods without temporarily moving the real player.
All helpers have empty server collision boxes,
gravity and physics disabled, invulnerability, damage and interaction guards,
and native save guards. Living helper geometry stays far below the riders;
the final native vehicle stays near the rider for ordinary dismounting.

`height` means the second player's feet above the horse's feet. New settings,
missing values, and `/tandemount reset` use `height=0.8125` and `rear=0.5`.
Existing saved numeric values keep their existing absolute units and values.
The colored chat menu provides height/rear adjustment, save, reload, reset,
and enable/disable buttons; text commands have the same behavior. Adjustments
save immediately, and `save` explicitly writes the current settings again.

Versions before Interaction and BlockDisplay require a different native graph.
That capability is still under investigation. A missing native height primitive
is an explicit verification failure; an independent following seat is not
substituted, and earlier follower-based reports do not establish compatibility
with the final attachment contract. Do not publish a target until its final
artifact has passed that contract.

The native fixture distinguishes synthetic entities and tracker collections
from actual loopback clients. `--network-test` now connects ordinary protocol
players, right-clicks the horse, inspects received existing/late observer
passenger graphs, executes the received adjustment command, and records
absence of periodic helper position packets. Original-client component probes
are separate:

```sh
python3 tools/modern-client-components.py --minecraft 1.19.4
python3 tools/modern-client-components.py --minecraft 1.20.6
```

These component probes load untouched exact official client classes and apply
normal synchronized metadata. They establish metadata ordering and native
attachment points, with no direct dimensions refresh. Their uninitialized
client-level shell does not establish client ticking, rendered motion, or
human visual review. Full original-client lifetime/motion evidence and human
visual review remain distinct release checks.
