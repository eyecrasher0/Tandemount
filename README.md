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

Build and installation instructions will be added alongside verified artifacts.
Please do not install development fixtures on a live server.
