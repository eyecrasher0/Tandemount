# Tandemount

[GitHub Releases](https://github.com/eyecrasher0/Tandemount/releases) ·
[Modrinth](https://modrinth.com/project/tandemount) ·
[CurseForge Mods](https://www.curseforge.com/projects/1802613) ·
[CurseForge Plugins](https://www.curseforge.com/projects/1802617)

<img src="branding/icon.png" width="128" height="128" alt="Tandemount">

Share a tamed, saddled horse with a friend. The first player steers and jumps with
the usual Minecraft controls while a second player rides behind. Give someone
a lift back to base or travel between villages together on one horse.

Mount a tamed, saddled horse as usual. A friend can right-click the occupied
horse with an empty hand to take the rear seat. They can look around during the
ride and press the usual dismount key (Shift by default) to get off.

## Choose your server build

Download the JAR for your server's exact Minecraft version and platform, put it
in the folder shown below, and restart the server. Players can connect with a
standard Minecraft client.

| Server | JAR | Install in |
| --- | --- | --- |
| Paper, Spigot, Purpur, Folia | `tandemount-bukkit-<release>+<minecraft>.jar` | `plugins/` |
| Fabric | `tandemount-fabric-<release>+<minecraft>.jar` | `mods/` |
| Forge | `tandemount-forge-<release>+<minecraft>.jar` | `mods/` |
| NeoForge | `tandemount-neoforge-<release>+<minecraft>.jar` | `mods/` |

The build matrix covers 38 exact Minecraft releases from 1.7.10 through 26.3.
Available platforms vary by patch. See [Minecraft versions and Java requirements](BACKPORTS.md)
and [published downloads](https://github.com/eyecrasher0/Tandemount/releases).

## Administrator settings

- `/tandemount` opens the seat controls and lists commands.
- `/tandemount status` shows the active settings.
- `/tandemount reload` applies changes from the configuration file.
- `/tandemount reset` restores the default seat position, 0.8125 blocks above
  the horse's feet and 0.5 blocks behind its center.

Plugins use `plugins/Tandemount/config.yml` with the `tandemount.admin`
permission. Mods use `config/tandemount.properties` with operator permissions.
The configuration can exclude specific worlds or dimensions.
[Installation and commands](docs/INSTALL.md).

## Build

| Sources | Build |
| --- | --- |
| [Bukkit](bukkit) | `make plugin` |
| [Fabric](fabric) | `make fabric` |
| [Forge](forge) | `make forge` for modern targets or `make legacy` for 1.7.10, 1.8.9 and 1.12.2 |
| [NeoForge](neoforge) | `make neoforge` |

Modern mod loaders share the riding code in [mod-common](mod-common).
Run `make test` for the build and release tooling tests. For exact builds and
disposable server tests, see the [modern mod guide](docs/MODERN_BUILD.md),
[legacy Forge guide](docs/LEGACY.md), and [plugin test guide](docs/PLUGIN_PORTAL_TEST.md).

## License

Created by **EyeCrasher** under the GNU General Public License, version 3 only
([GPL-3.0-only](LICENSE)). Published releases include corresponding source code
and checksums. [Implementation provenance](docs/PROVENANCE.md).
