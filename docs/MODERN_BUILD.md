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

The second seat is a vanilla `AreaEffectCloud`, which extends `Entity` directly
and therefore does not activate the vanilla player's living-vehicle body-yaw
clamp. The seat is invisible, has zero radius and an empty potion, does not wait,
has zero radius or duration adjustments, disables gravity and physics, and uses
the vanilla infinite-duration sentinel where available (maximum integer on
older releases). The older fallback delays vanilla expiry until the cloud's
age reaches `Integer.MAX_VALUE` (about 3.4 years of continuous ticking at 20
TPS); it is not a mathematically infinite lifetime. Hash-bound client-bytecode
assertions for 1.16.5 and 26.3 are recorded in
`.agents/modern-renderer-audit.json`: the 1.16.5 cloud renderer has no
entity-geometry override and its nonwaiting zero-radius client particle loop has
zero iterations; 26.3 registers the cloud with `NoopRenderer` and its
nonwaiting zero-radius client particle loop also has zero iterations. For 26.3,
the empty-potion branch skips status-effect application and the infinite
duration sentinel skips natural expiry. `TandemRuntime` configures gravity and
physics off. Native fixture results record these settings for each tested
artifact. The client-bytecode analysis and native state checks do not establish
a human visual check. That check remains a separate release-evidence item.
