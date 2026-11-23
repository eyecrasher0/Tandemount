# Changelog

## 1.0.0

- Published 93 server mod builds across all 38 supported Minecraft releases.
  Bukkit downloads cover 37 Minecraft releases.
- Added a Paper 1.7.10 build with stable idle horses and restored passenger
  collision after dismount.
- Published verified builds for Forge 1.7.10, 1.8.9, and 1.12.2, and a shared
  Bukkit JAR for Paper and Spigot 1.12.2.
- Published verified Fabric and Forge builds for 1.16.5, 1.19.4, 1.20.6,
  1.21.1, and 26.3, plus NeoForge builds for 1.20.6, 1.21.1, and 26.3.
- Published verified Bukkit builds for Paper, Purpur, and Folia 1.19.4 and for
  Paper 26.3.
- Added Fabric and Forge builds for 1.17.1 and 1.18.2, Paper and Purpur
  for 1.18.2, Paper, Purpur, and Folia for 1.20.6, and all three mod
  loaders and four server cores for 1.21.11 and 26.2.
  [Release assets and evidence](https://github.com/eyecrasher0/Tandemount/releases/tag/tandemount-1.0.0)

- Independent server-side implementation by EyeCrasher under GPL-3.0-only.
- Two players share an ordinary adult, tamed, saddled horse; the first rider
  keeps the native horse controls.
- Separate vertical and rear adjustments in 1/16-block steps, immediate saving,
  reload, reset, enable/disable, and permission checks.
- Separate plugin and mod artifacts for exact Minecraft releases, with native
  loader/core verification and hash-bound release evidence.
- Paper 26.3: the second rider reaches the Nether portal exit at the correct
  height. World changes end the second seat and leave that rider unmounted;
  the driver and horse keep their ordinary native transfer.

Additional backports are tracked in the [build target matrix](BACKPORTS.md).
