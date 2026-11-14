# Installation and commands

Use the file for your **exact Minecraft release and server platform**. The
[platform matrix](COMPATIBILITY.md) lists which upstream platforms exist; release
verification is recorded separately. Development builds are still under test.

| Server | File pattern | Install directory |
| --- | --- | --- |
| Paper, Spigot, Purpur, Folia | `tandemount-1.0.0-MINECRAFT-plugin.jar` | `plugins/` |
| Fabric, modern Forge, NeoForge | `tandemount-1.0.0-LOADER-MINECRAFT.jar` | `mods/` |
| Forge 1.7.10, 1.8.9, 1.12.2 | `tandemount-forge-MINECRAFT-1.0.0.jar` | `mods/` |

Install one Tandemount file on the server, then start the server normally. Clients
need no Tandemount installation, resource pack, or custom entity registry. Do not
install the native-test fixture JARs. A modded server may still require client
mods because of its other installed mods.

The server must use the Java version required by its Minecraft release, recorded
in the matrix. Plugin and mod files are separate installation options.

## Riding

Use an adult, tamed, saddled ordinary horse. The first player mounts and controls
it normally. A second player right-clicks the occupied horse to join behind the
driver, then uses the normal dismount action to leave. A third player cannot
join that ride. Ordinary riding requires no operator privileges.

Server protection can refuse an interaction or dismount. Supported cancellable
platform operations retain their final decision. Fabric Loader alone has no
gameplay event bus; compatibility with additional protection mixins is not
established by the Loader-only tests. Forge 1.7.10 has no `EntityMountEvent`.

## Administration

`/tandemount` shows help and clickable adjustment controls where supported. Text
commands work from the console. Changes save immediately and apply to active
rides; `+` and `-` move an offset by 1/16 block.

Plugin defaults are height `0.8125` blocks and rearward `0.5` blocks. New
configuration, missing numeric values and `/tandemount reset` use those values.
Existing saved values are preserved until an administrator changes or resets them.

| Action | Plugins | Forge 1.7.10–1.12.2 | Modern mods |
| --- | --- | --- | --- |
| Vertical offset | `/tandemount vertical <+\|-\|number>` | `/tandemount vertical <+\|-\|number>` | `/tandemount height <+\|-\|number>` |
| Rear offset | `/tandemount rearward <+\|-\|number>` | `/tandemount rear <+\|-\|number>` | `/tandemount rear <+\|-\|number>` |
| Debug logging | `/tandemount debug on\|off` | `/tandemount debug on\|off` | `/tandemount debug true\|false` |
| Enable or disable | `/tandemount enable` or `/tandemount disable` | Same | Same |
| Reload configuration | `/tandemount reload` | Same | Same |
| Restore defaults | `/tandemount reset` | Same | Same |

Plugins use `tandemount.admin`, default operator. Forge 1.12.2 registers that
permission with Forge's permission API. Other mod targets use vanilla operator
permission level 2. An external permissions provider is not required.

| Installation | Configuration | Vertical bounds | Rear bounds |
| --- | --- | --- | --- |
| Plugin | `plugins/Tandemount/config.yml` | 0.25–3 blocks | 0–2 blocks |
| Legacy Forge | `config/tandemount.properties` | −1–2 blocks | 0.25–2 blocks |
| Modern mod | `config/tandemount.properties` | 0–3 blocks | 0–2 blocks |

The legacy vertical setting adjusts the platform's native riding height;
plugin and modern settings specify player height above the horse's feet. Use
each installation's defaults before adjusting. All variants support ordinary
horses only. Invalid numbers and unsupported horse types are rejected.

Disable ends active shared rides. Death, disconnect, horse removal, teleport,
world change, unload, shutdown, and restart must leave no extra seat in the
world; exact native verification remains a release requirement.
