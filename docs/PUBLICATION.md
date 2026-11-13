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
