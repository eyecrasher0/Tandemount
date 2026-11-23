# Publication status

GitHub hosts 33 Tandemount 1.0.0 JARs for the 45 exact Minecraft and platform
pairs in the download table. Each published batch links to its corresponding
source archive, verification report, and SHA-256 checksums.
Additional backports still require qualification. [Verified downloads](../README.md#choose-your-server-build) ·
[Build targets](../BACKPORTS.md)

The Modrinth project remains a draft. CurseForge listing visibility depends on moderation.
The repository and storefronts use the owner's supplied artwork.
[Project icon](../branding/icon.png)

Modrinth's current [AI policy](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai)
does not permit a project produced primarily by generative AI to appear publicly
in search. Its help page describes unlisted publication as an option in many
cases. Tandemount will request unlisted visibility and disclose its generated
code and text. Moderation approval must be verified; an upload alone does not
establish approval or public availability.

## CurseForge

CurseForge mods and Bukkit plugins require separate project categories. The
owner supplied two existing Minecraft projects: **Tandemount**, Mods ID
**1802613**, and **Tandemount (Plugin)**, Bukkit Plugins ID **1802617**. Both
list EyeCrasher as author and GNU GPLv3 as license. Upload into these projects;
do not create duplicates. The publisher uploads files to the existing projects
and leaves their artwork in place.
The publisher accepts the two numeric IDs only after the release audit passes. It
uses CurseForge's documented multipart upload route and author token, keeps a
local hash ledger to avoid duplicate retries, and stops on an ambiguous upload
outcome. CurseForge's documented upload response returns a file ID rather than
the uploaded file hash; remote byte verification must be completed from an
available public download or the Authors Console after review.

```sh
python3 tools/publish_curseforge.py
python3 tools/publish_curseforge.py --mods-project-id 1802613 \
  --plugins-project-id 1802617 --apply
```

The second command reads `CURSEFORGE_TOKEN` from the environment. An upload
accepted by the API may still be waiting for CurseForge moderation; it is not
reported as public until that review completes. The official [CurseForge Upload
API](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-upload-api)
documents the API token, game-version tags, upload request, and file ID
response.
The uploader uses the documented `gameVersionNames` field after checking the
official version list. Historical Minecraft releases have different numeric
version groups for Mods and Bukkit; equal names in those groups must not be
resolved by choosing an arbitrary numeric ID.

## Modrinth

The Modrinth publisher consumes the hash-bound `.agents/release/plan.json` and
uploads every exact game/platform version as a draft first. It records the
`ai_content` disclosure for generated code and text, requests unlisted status,
and verifies the SHA-512 reported for every uploaded JAR. A new project uses
`branding/icon.png`. The official API describes [project creation](https://docs.modrinth.com/api/operations/createproject/),
[version creation](https://docs.modrinth.com/api/operations/createversion/),
[version updates](https://docs.modrinth.com/api/operations/modifyversion/),
and [project updates](https://docs.modrinth.com/api/operations/modifyproject/).

After the complete native audit and publication plan exist, inspect the upload
plan without changing Modrinth:

```sh
python3 tools/publish_modrinth.py
```

With `MODRINTH_TOKEN` already present in the environment, `--apply` creates or
resumes the project and uploads the audited files. The script refuses an
incomplete audit, a dirty source tree, a changed source commit, a mismatched
artifact, or a conflicting remote version. Its local resume record stays in
ignored `.agents/release/`.

An existing project is checked through its actual [team membership](https://docs.modrinth.com/api/operations/getteammembers/),
matching the authenticated user's immutable ID to an accepted owner. The
project response does not contain an author field. An empty matching draft
with an unset source and license can receive the audited release metadata;
a conflicting source, another license, or existing files block that initialization.
The publisher keeps the installed icon and verifies the resulting source URL
and license after a refresh. Project description links point to `main`.
Both publishers link to the source archive and verification report for the
JAR's SHA-256 and exact platforms. New releases use the checked archive and
report named by the audited publication plan. A plan for an existing release
must match its published artifact hashes and verified platforms.
