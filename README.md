# Tandemount

[GitHub Releases](https://github.com/eyecrasher0/Tandemount/releases) ·
[CurseForge Mods](https://www.curseforge.com/projects/1802613) ·
[CurseForge Plugins](https://www.curseforge.com/projects/1802617)

<img src="branding/icon.png" width="128" height="128" alt="Tandemount">

Tandemount adds a rear seat to tamed, saddled horses on Minecraft servers.
The driver steers and jumps with the usual controls while a friend rides
behind. Share one horse on a trip between villages or give someone a lift
back to base.

To join a ride, right-click an occupied horse with an empty hand. The
passenger can look around and use Sneak (Shift by default) to get off while
the driver stays mounted.

## Choose your server build

Select a file for your exact Minecraft version and server platform. Use the Files tab on CurseForge or the assets and compatibility notes in the matching [GitHub release](https://github.com/eyecrasher0/Tandemount/releases).

- **Fabric, Forge or NeoForge.** Put the server mod in `mods/`.
- **Paper, Spigot, Purpur or Folia.** Put the matching Bukkit plugin in `plugins/`.

Restart the server after installing. Players can use a standard Minecraft client.

Each release includes matching source code, runtime verification reports, and SHA-256 checksums. The [build targets](BACKPORTS.md) document the Java requirements. The [original release catalog](https://github.com/eyecrasher0/Tandemount/blob/f2d2f617317edf1763c5470ac7792985dfbb7bab/README.md#choose-your-server-build) preserves the direct links for Tandemount 1.0.0.

## Administrator settings

- `/tandemount` opens the seat controls and lists commands.
- `/tandemount status` shows the active settings.
- `/tandemount reload` applies changes from the configuration file.
- `/tandemount reset` restores the default seat position, 0.8125 blocks above
  the horse's feet and 0.5 blocks behind its center. On Bukkit it preserves
  the enabled setting, debug logging, and world exclusions. On Fabric, Forge,
  and NeoForge it also enables shared riding, turns debug logging off, and
  clears excluded dimensions.

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

Before running `make plugin`, set `JAVA8_HOME` to a Java 8 JDK and prepare the
Paper 1.12.2 SDK in the default cache or under `TANDEMOUNT_CACHE`.

```sh
python3 tools/cache_servers.py --platforms paper --versions 1.12.2 --bootstrap
make plugin
```

Compilation uses a JDK 9 or newer from `JAVA_HOME` or `javac` on `PATH`.
For a different Bukkit API, pass `--api` to `tools/plugin-build.py` along with
`--all` and a `--classpath` argument for each additional dependency.

Modern mod loaders share the riding code in [mod-common](mod-common).
Run `make test` for the build and release tooling tests. For exact builds and
disposable server tests, see the [modern mod guide](docs/MODERN_BUILD.md),
[legacy Forge guide](docs/LEGACY.md), and [plugin test guide](docs/PLUGIN_PORTAL_TEST.md).

## License

Created by **EyeCrasher** under the GNU General Public License, version 3 only
([GPL-3.0-only](LICENSE)). Published releases include corresponding source code
and checksums. [Implementation provenance](docs/PROVENANCE.md).
