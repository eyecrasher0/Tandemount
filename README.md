# Tandemount

Share a horse with a friend while the first rider stays in control.

Tandemount is a new server-side project by **EyeCrasher**, licensed under
**GPL-3.0-only**. It is being implemented independently from a behavioral
specification. See [implementation provenance](docs/PROVENANCE.md).

**Development is in progress. No Tandemount binary has been released yet.**
The requested scope is 38 exact Minecraft releases from 1.7.10 to 26.3, with
Forge, Fabric, NeoForge, Paper, Spigot, Purpur, and Folia wherever those upstreams
exist. [The matrix](docs/COMPATIBILITY.md) currently records upstream availability,
not tested Tandemount compatibility.

The intended experience is right-clicking an occupied horse to ride behind its
driver, using the normal dismount action to leave, and adjusting seat position
through `/tandemount` as an administrator. Vanilla clients should be sufficient.
The [behavioral specification](docs/BEHAVIOR.md) defines the acceptance criteria.

See [installation and commands](docs/INSTALL.md),
[modern mod builds](docs/MODERN_BUILD.md), and
[legacy Forge builds](docs/LEGACY.md). Plugin builds use
`python3 tools/plugin-build.py --all` against a real Bukkit/Paper API; use
`--api /path/to/api.jar` when the SDK cache is not prepared.
Please do not install development fixtures on a live server.
