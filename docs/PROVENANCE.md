# Implementation provenance

Status: behavioral specification and upstream availability research prepared;
independent implementation has not started. No release has been published.

The specification author read the predecessor's user documentation and handoff,
which included internal design descriptions. That material is excluded from
the implementers' input. No predecessor Java sources, patches, tests, or binary
code have been read or incorporated into this repository.

The intended clean-room separation is a behavioral specification author and
fresh implementation conversations receiving `BEHAVIOR.md`, this repository's
instructions, and official platform specifications. Explicit authorization to
delegate the implementation is pending.

The platform resolver consults official Mojang, Fabric, MinecraftForge,
NeoForged, PaperMC, PurpurMC, and SpigotMC metadata. Spigot `BuildData/info.json`
is read at pinned official Git revisions to identify the actual game version.
This metadata is used for build selection and is not horse-sharing code.

GPL-3.0-only text: <https://www.gnu.org/licenses/gpl-3.0.txt>.

Implementation attestations and the final source inventory will be recorded
here before release. Runtime behavior and real-client visuals require separate
verification; availability metadata establishes neither.
