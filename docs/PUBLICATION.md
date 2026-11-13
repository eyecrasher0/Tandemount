# Publication status

Development and verification are in progress. No playable release has been
published. The GitHub repository currently contains only its initial base.

The requested destinations are GitHub, Modrinth, and CurseForge. Release files
will be bound to exact source and artifact hashes and verified after upload.
No icon or placeholder artwork is included, following the owner's instruction.

Modrinth's current [AI policy](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai)
does not permit a project produced primarily by generative AI to appear publicly
in search. Its help page describes unlisted publication as an option in many
cases. Tandemount will request unlisted visibility and disclose its generated
code and text. Moderation approval must be verified; an upload alone does not
establish approval or public availability.

GitHub is intended to host the complete source and public release artifacts.
CurseForge mods and Bukkit plugins require separate project categories. The
documented CurseForge upload API can upload to existing projects; project
creation in its Authors Console still needs an available browser connection.
Create two projects in the **Minecraft** game: one in **Mods** and one in
**Bukkit Plugins**. Use the title **Tandemount**, author **EyeCrasher**, license
**GPL-3.0-only**, source `https://github.com/eyecrasher0/Tandemount`, and the
same server-only feature description as this repository. Leave the icon blank
until the owner supplies one. CurseForge's [project-submission guide](https://support.curseforge.com/support/solutions/articles/9000197241-creating-and-submitting-a-project)
states that a logo is required for texture packs or Sims projects, not mods.
The publisher accepts the two numeric IDs only after the release audit passes. It
uses CurseForge's documented multipart upload route and author token, keeps a
local hash ledger to avoid duplicate retries, and stops on an ambiguous upload
outcome. CurseForge's documented upload response returns a file ID rather than
the uploaded file hash; remote byte verification must be completed from an
available public download or the Authors Console after review.

```sh
python3 tools/publish_curseforge.py
python3 tools/publish_curseforge.py --mods-project-id MODS_ID \
  --plugins-project-id PLUGINS_ID --apply
```

The second command reads `CURSEFORGE_TOKEN` from the environment. An upload
accepted by the API may still be waiting for CurseForge moderation; it is not
reported as public until that review completes. The official [CurseForge Upload
API](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-upload-api)
documents the API token, game-version tags, upload request, and file ID
response.

## Modrinth

The Modrinth publisher consumes the hash-bound `.agents/release/plan.json` and
uploads every exact game/platform version as a draft first. It records the
`ai_content` disclosure for generated code and text, requests unlisted status,
and verifies the SHA-512 reported for every uploaded JAR. It does not upload an
icon. The official API describes [project creation](https://docs.modrinth.com/api/operations/createproject/),
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
