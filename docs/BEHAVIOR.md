# Tandemount behavioral specification

Owner request, 13 November 2026: create an independent implementation from
scratch using a clean-room process, choose a new name, use GPL-3.0, and release
on GitHub, Modrinth, and CurseForge. Author: EyeCrasher. Working name Tandemount,
identifier `tandemount`, initial version 1.0.0, SPDX `GPL-3.0-only`.

This is an input/output and lifecycle contract, not a description of a previous
implementation. Implementers must not open predecessor sources or archives.

## Player behavior

- Two players can share one adult, tamed, saddled horse. The first player drives
  using the ordinary horse controls. The second player right-clicks the occupied
  horse to join and uses the normal dismount action to leave.
- Exactly two riders; reject a third rider without disrupting the ride.
- Ordinary riding is available without operator status or administrative
  privileges. A cancelled interaction or dismount stays cancelled. Respect
  server protection and visibility decisions, and do not bypass them with a
  deferred operation.
- The second player appears seated behind the driver at a natural height.
  Movement, turning, running, jumping, and looking around must not visibly
  oscillate the rider. A newly arriving observer sees the same pose and location
  as an existing observer without requiring either rider to remount.
- Ports from Minecraft 1.19.4 onward must attach the second rider's seat to the horse's native vehicle
  graph, with that horse as the shared root vehicle.
  The attachment must exist on the server and in the vanilla client's passenger
  graph. An independently positioned seat following the horse does not satisfy
  this request. The first rider must retain ordinary horse controls.
- Install on the server only. Unmodified clients can connect; no resource pack,
  client mod, custom entity registry entry, or external plugin is required.
- The extra seat cannot take damage, be attacked or interacted with as loot,
  become a saved world artifact, interfere with driver control, or remain after
  either rider's death, departure, disconnect, world change, horse death/removal,
  chunk unloading, server stop/restart, or plugin disable.
- Multiple simultaneous rides do not share or corrupt state. Rider reconnect,
  driver dismount, cancellation, and retries must be race safe. Never force-load
  worlds/chunks or force a vanished/hidden entity visible to another player.
- Preserve player inventory, health, experience, permissions, and horse owner,
  inventory, equipment, health, and identity. Do not alter unrelated gameplay.
- Ordinary horses are required. Additional horse-like species may be offered
  only with explicit configuration and equivalent compatibility evidence.

## Administration

- `/tandemount` opens clear help and clickable seat adjustment controls when
  the client supports clickable chat. Text commands provide equivalent behavior.
- Admin permission `tandemount.admin`, default operator. Ordinary users cannot
  change server settings; console can use textual commands.
- Adjust vertical and rearward placement independently, in 1/16-block steps.
  Settings save immediately and apply to active rides. Reload configuration and
  reset defaults without a server restart. Reject invalid or nonfinite numbers
  without breaking existing rides. Configuration is bounded and documented.
- The default rider-feet position is `0.8125` blocks above the horse's feet and
  `0.5` blocks rearward, matching the owner's chosen position. Each platform's
  new configuration, missing values, and reset must produce this position.
  Preserve existing saved settings and their documented units until changed or
  reset by an administrator; handle any required format migration explicitly.
- Provide enable/disable, supported horse types, and optional debug logging.
  Preserve existing config and avoid logging personal or authentication data.
- Public messages and docs are English. Preserve cancellation semantics and
  loader-specific permission behavior, documenting any differences.

## Exact requested Minecraft releases

1.7.10, 1.8.9, 1.12.2, 1.16.5,
1.17, 1.17.1, 1.18, 1.18.1, 1.18.2,
1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4,
1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6,
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11,
26.1, 26.1.1, 26.1.2, 26.2, 26.3.

Mods: Forge, Fabric, NeoForge wherever the loader supports that exact release.
The three oldest releases use Forge for mods. Plugins: Paper, Spigot, Purpur,
Folia wherever an actual server for that exact release exists. In particular,
Folia support requires correct entity/region ownership and native-runtime tests,
not only a metadata declaration.

## Release evidence

- Real loader APIs and exact game versions; do not publish stub-only builds or
  infer compatibility solely from successful compilation or metadata tags.
- Exercise successful/cancelled mounting, third rider rejection, driver control,
  independent settings, late observers, several rides, lifecycle cleanup,
  unload/restart, teleport/world changes, and permission denial.
- Track compilation, native behavior tests, and actual client visual checks
  separately. Do not present synthetic observers as human visual confirmation.
- Use official SDKs and primary API/mapping documentation. Record provenance.
- Apply the final seating, client-lifetime, attachment, movement, settings-menu,
  defaults, and lifecycle fixes throughout the 38-release port. A native API
  capability difference must be demonstrated on that exact game and recorded;
  do not silently omit the requested behavior or claim an independent follower
  is a native attachment. For the 13 requested releases before 1.19.4, the
  owner conditionally approved a separately following seat on 15 November:
  it must retain the same usable riding quality, correct position, client
  lifetime, late-observer behavior, unobstructed interaction and cleanup.
  This exception does not waive those requirements. Existing per-target reports predate these fixes and
  require fresh artifact-bound verification.
- Publish matching complete sources, build instructions, GPL text, compatibility
  matrix, changelog, and checksums. Verify downloaded artifacts after publication.
- The scope includes publication, not modification of an existing game server.

## Publication preparation standard — 17 November 2026

The owner's current instruction is preparation for publication, using their
EyeCrasher mod/plugin standard. Do not push source, create a PR/release, upload
to a platform, or change an existing server during this preparation stage.
The earlier personal-test publication gate remains in force.

- Keep `/tandemount` as the only project command root. Provide `reload` and
  `status`; show real active settings and apply administrative permissions.
- Expose enabled/debug and world/dimension exclusions. Plugins use exact
  case-sensitive world names; mods use their platform's dimension identifiers.
  No matching ride may start in an excluded world. Reloading settings must
  clean up rides that become excluded without disturbing unrelated players.
- Invalid syntax, types, nonfinite/out-of-range numbers and invalid exclusions
  must fail reload without replacing the last valid active configuration or
  rewriting the invalid file. Existing valid saved seat values retain their
  documented units unless the administrator changes or resets them.
- A shared horse's portal transfer must never derive player coordinates from
  a dismounted helper or transfer helpers as ordinary cloned entities. The
  second seat ends on a world change; any accepted second-player teleport
  uses the native destination and respects cancellation and native cooldown.
- Runtime file names are `tandemount-bukkit-VERSION+MINECRAFT.jar` and
  `tandemount-LOADER-VERSION+MINECRAFT.jar`. Version and loader metadata inside
  each JAR must match the artifact. Test fixtures remain separate.
- Preserve the existing source history and Git identity. English public docs,
  author EyeCrasher, GitHub eyecrasher0, GPL-3.0-only, no icon until supplied.
- Source exports must build without private paths or local steering files.
  Publishable compatibility means exact artifact-bound native verification,
  not upstream availability or a neighboring release's passing test.
