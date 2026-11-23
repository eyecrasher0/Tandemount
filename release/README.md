# Release evidence

`project.json` defines the project identity. `tools/collect_release.py` produces
the release manifest from current compiled artifacts and native matrix records.
After a complete audit, `build/release/latest-artifacts.json` points to the
manifest alongside its reports under `build/release/candidates/`.
Collection refuses changed source, changed raw reports, mismatched artifact
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

The collector reads `.agents/legacy-native-results.json` by default, the same
path written by the legacy Forge runner. Run the gate before creating a
publication plan:

```sh
python3 tools/collect_release.py
python3 tools/release_audit.py --output .agents/release/audit.json
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

The eight published 1.0.0 batches have corresponding source and verification
assets. The publishers match those batches by JAR hash and platform. For an
audited release plan, the publishers check the staged archive and report and
verify the report covers every artifact's exact native targets.
[Verified download table](../README.md#choose-your-server-build)

Plugin mount evidence must include a real vanilla-client right-click packet,
sustained mounting after repeated interactions, and mounted movement. Calling a
Bukkit interaction event directly does not cover the network handler's choice
of event. Settings evidence must also inspect the actual chat's color/style and
click actions and execute a reported adjustment command. Numeric configuration
assertions alone do not establish a usable settings menu.

Falling-block seat evidence must also execute the official vanilla client
entity's tick method using the received spawn state. Server-side survival and
an invisible renderer alone cannot prove client survival: vanilla removes an
AIR falling block before its client/server side check. The client-lifetime
probe remains distinct from human review of rendered appearance.

Forge 1.7.10, 1.8.9, and 1.12.2 use the approved early follower. Their report
checks the native passenger position relative to the moving horse, both
observers' mount relationships, and original-client lifetime and picking for
each observer.

The legacy `driver-control` scope records simulated driver steering on the
1.7.10 and 1.8.9 servers. On 1.12.2 it checks first-rider steering eligibility
and the server's acceptance of vehicle-move packets from the seated driver.
Jump charge and rendered client prediction require separate evidence.

The Paper 26.3 attachment revision requires both server and received-client
horse-root relationships, first-rider control, a late observer's complete
attachment graph, and absence of periodic position-follow packets for the
attached helpers. A smooth independent follower does not establish this contract.
Configuration checks must separately execute fresh defaults, missing-key
fallback, reset to the owner's values, and preservation of existing saved
settings.

An unavailable cancellable dismount API is disclosed as `not-applicable`, with
a reason, only for Forge 1.7.10 and Fabric Loader-only installations. Ordinary
dismount still requires native evidence. No lifecycle check can use this
exception. Actual rendered appearance remains a separate verification item.
