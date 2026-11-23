# Tandemount

[GitHub Releases](https://github.com/eyecrasher0/Tandemount/releases) ·
[Modrinth (pending)](https://modrinth.com/project/tandemount) ·
[CurseForge Mods](https://www.curseforge.com/projects/1802613) ·
[CurseForge Plugins](https://www.curseforge.com/projects/1802617)

CurseForge availability depends on moderation.

<img src="branding/icon.png" width="128" height="128" alt="Tandemount">

Share a tamed, saddled horse with a friend. The first player steers and jumps with
the usual Minecraft controls while a second player rides behind. Give someone
a lift back to base or travel between villages together on one horse.

Mount a tamed, saddled horse as usual. A friend can right-click the occupied
horse with an empty hand to take the rear seat. They can look around during the
ride and press the usual dismount key (Shift by default) to get off.

## Choose your server build

Tandemount 1.0.0 is verified and available for the exact pairs below. Choose
the row for your Minecraft version and server platform, put its JAR in the
listed folder, and restart the server. Players can use a standard Minecraft
client.

| Minecraft | Server | Download | Install in |
| --- | --- | --- | --- |
| 1.7.10 | Forge | [`tandemount-forge-1.0.0+1.7.10.jar`][forge-1.7.10] | `mods/` |
| 1.8.9 | Forge | [`tandemount-forge-1.0.0+1.8.9.jar`][forge-1.8.9] | `mods/` |
| 1.12.2 | Forge | [`tandemount-forge-1.0.0+1.12.2.jar`][forge-1.12.2] | `mods/` |
| 1.20.6 | NeoForge | [`tandemount-neoforge-1.0.0+1.20.6.jar`][neoforge-1.20.6] | `mods/` |
| 26.3 | Fabric | [`tandemount-fabric-1.0.0+26.3.jar`][fabric-26.3] | `mods/` |
| 26.3 | Forge | [`tandemount-forge-1.0.0+26.3.jar`][forge-26.3] | `mods/` |
| 26.3 | NeoForge | [`tandemount-neoforge-1.0.0+26.3.jar`][neoforge-26.3] | `mods/` |
| 26.3 | Paper | [`tandemount-bukkit-1.0.0+26.3.jar`][paper-26.3] | `plugins/` |

The release includes corresponding sources and SHA-256 checksums for each
batch. [GitHub release](https://github.com/eyecrasher0/Tandemount/releases/tag/tandemount-1.0.0) ·
[Modern mod and Paper verification](https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/verification.json) ·
[Legacy Forge verification](https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/verification-legacy-forge.json)

The build target matrix covers 38 exact Minecraft releases from 1.7.10 through
26.3 and records upstream platforms and Java requirements for each version.
[Build target matrix](BACKPORTS.md)

[forge-1.7.10]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-forge-1.0.0%2B1.7.10.jar
[forge-1.8.9]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-forge-1.0.0%2B1.8.9.jar
[forge-1.12.2]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-forge-1.0.0%2B1.12.2.jar
[neoforge-1.20.6]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-neoforge-1.0.0%2B1.20.6.jar
[fabric-26.3]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-fabric-1.0.0%2B26.3.jar
[forge-26.3]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-forge-1.0.0%2B26.3.jar
[neoforge-26.3]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-neoforge-1.0.0%2B26.3.jar
[paper-26.3]: https://github.com/eyecrasher0/Tandemount/releases/download/tandemount-1.0.0/tandemount-bukkit-1.0.0%2B26.3.jar

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
