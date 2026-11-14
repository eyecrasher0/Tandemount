# Release evidence

`project.json` defines the project identity. `tools/collect_release.py` produces
the ignored `build/release/artifacts.json` from current compiled artifacts and
native matrix records. Its absence means there is no complete release candidate
yet. Collection refuses changed source, changed raw reports, mismatched artifact
hashes, and missing named behavioral checks; it does not convert an overall
fixture result into blanket compatibility.

Each artifact entry identifies an exact file, SHA256, required Java version,
Minecraft versions, platforms, and paths to native result records. A native
record is bound to the artifact SHA256 and one exact game/platform pair. Its
checks must execute the server's real behavior; mock-only or model-only tests
are insufficient. Actual rendered appearance is recorded separately.

For a universal JAR use an explicit `targets` list of `minecraft`/`platform`
objects. This describes only the available pairs and avoids implying that a
particular platform existed on every Minecraft release. A rectangular mod
artifact may instead use `minecraft` and `platforms` lists.

Run the gate before creating a publication plan:

```sh
python3 tools/collect_release.py
python3 tools/release_audit.py --manifest build/release/artifacts.json \
  --evidence .agents/native --output .agents/release/audit.json
```

The audit verifies the artifact and metadata contracts, evidence hashes and
coverage. The native runners supply behavioral evidence; the audit does not
replace those runners or a review of their tests.

If a target-specific build change leaves another target's rebuilt JAR byte for
byte identical, the matrix may retain that exact artifact's earlier native
execution. This requires an independently matching runner fingerprint, unchanged
raw-report hash, and identical separate fixture when present. The matrix records
the previous source fingerprint and the identical-rebuild reuse explicitly.
A changed JAR, runner, fixture, or report requires new native execution.

The collector also emits `verification.json`, a portable public report with
exact artifact hashes, native check names and outcomes, server identities,
capability exceptions, and raw-report hashes. It excludes workstation paths,
test player data, worlds, and downloaded game binaries. The publication plan
stages this report with the source archive and checksums so the large local SDK
cache can be removed after uploads have been verified.

An unavailable cancellable dismount API is disclosed as `not-applicable`, with
a reason, only for Forge 1.7.10 and Fabric Loader-only installations. Ordinary
dismount still requires native evidence. No lifecycle check can use this
exception. Actual rendered appearance remains a separate verification item.
