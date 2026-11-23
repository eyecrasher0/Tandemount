# Installation and commands

Choose a download only if the release table lists your exact Minecraft release
and server platform. [Verified 1.0.0 downloads](../README.md#choose-your-server-build)

Put the Bukkit JAR in `plugins/` on Paper, Spigot, Purpur, or Folia. Put the
Fabric, Forge, or NeoForge JAR in `mods/`. Start the server normally. Clients
need no Tandemount installation, resource pack, or custom entity registry. Do
not install the native-test fixture JARs. Other mods may require client installs.

Use the Java version required by your Minecraft release. The build target matrix
includes Java requirements and upstream availability for all 38 releases.
[Build target matrix](../BACKPORTS.md)

## Riding

Use an adult, tamed, saddled ordinary horse. The first player mounts and controls
it normally. A second player right-clicks the occupied horse to join behind the
driver, then uses the normal dismount action to leave. A third player cannot
join that ride. Ordinary riding requires no operator privileges.

On sharp turns, the driver may briefly see the passenger lag behind the
horse. Higher connection latency can make this more noticeable.

Server protection can refuse an interaction or dismount. Supported cancellable
platform operations retain their final decision. Fabric Loader alone has no
gameplay event bus; compatibility with additional protection mixins is not
established by the Loader-only tests. Forge 1.7.10 has no `EntityMountEvent`.

## Administration

`/tandemount` shows help and clickable adjustment controls where supported. Text
commands work from the console. Changes save immediately and apply to active
rides; `+` and `-` move an offset by 1/16 block.

The defaults place the second player's feet `0.8125` blocks above the horse's
feet and `0.5` blocks behind the horse's centre. New configuration, missing
numeric values and `/tandemount reset` use those values.

| Action | Plugins | Forge 1.7.10–1.12.2 | Modern mods |
| --- | --- | --- | --- |
| Vertical offset | `/tandemount vertical <+\|-\|number>` | `/tandemount vertical <+\|-\|number>` | `/tandemount height <+\|-\|number>` |
| Rear offset | `/tandemount rearward <+\|-\|number>` | `/tandemount rear <+\|-\|number>` | `/tandemount rear <+\|-\|number>` |
| Debug logging | `/tandemount debug on\|off` | `/tandemount debug on\|off` | `/tandemount debug true\|false` |
| View active settings | `/tandemount status` | Same | Same |
| Enable or disable | `/tandemount enable` or `/tandemount disable` | Same | Same |
| Reload configuration | `/tandemount reload` | Same | Same |
| Reset settings | `/tandemount reset` | Same | Same |

Bukkit resets the seat offsets and preserves the enabled setting, debug
logging, and excluded worlds. Legacy Forge and modern mods reset the seat
offsets, enable shared riding, turn debug logging off, and clear excluded
dimensions.

Plugins use `tandemount.admin`, default operator. Forge 1.12.2 registers that
permission with Forge's permission API. Other mod targets use vanilla operator
permission level 2. An external permissions provider is not required.

| Installation | Configuration | Vertical bounds | Rear bounds |
| --- | --- | --- | --- |
| Plugin | `plugins/Tandemount/config.yml` | 0.25–3 blocks | 0–2 blocks |
| Legacy Forge | `config/tandemount.properties` | −1–2 blocks | 0.25–2 blocks |
| Modern mod | `config/tandemount.properties` | 0–3 blocks | 0–2 blocks |

On legacy Forge, an existing explicit vertical value saved before the
`vertical-origin` setting became available retains its original offset from the
native riding height. Reset switches to the feet-based defaults. All variants
support ordinary horses. Invalid numbers and unsupported horse types are rejected.

For plugins, put exact Bukkit world names in the `excluded-worlds` YAML list.
The default `[]` allows shared rides in every world. For example, use
`excluded-worlds: ['world_nether', 'Event World']`. Capitalization and spaces
must match the world name. Each entry must be a unique, nonblank string with
no control characters. You can list unloaded worlds.

Use `/tandemount reload` to apply file edits and `/tandemount status` to see the
active settings and exclusions. The plugin expects a single YAML mapping with
string keys, boolean `enabled` and `debug` values, and numeric offsets in the
ranges above. Missing entries use defaults. Syntax errors, duplicate keys, and
invalid settings reject the reload and preserve the active settings and file.
Reload ends shared rides in newly excluded worlds while drivers stay on their
horses. Players can still ride ordinary horses there.

Disable ends active shared rides. Death, disconnect, horse removal, teleport,
world change, unload, shutdown, and restart must leave no extra seat in the
world; exact native verification remains a release requirement.

On Paper 26.3, a horse's Nether portal transfer ends the second seat. The driver
stays on the horse and the second player arrives unmounted at the portal exit,
where they can join the horse again.
