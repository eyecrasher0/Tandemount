# Release evidence

`project.json` defines the project identity. `artifacts.json` will be produced
after the implementation has real compiled artifacts and native test evidence.
Its absence means there is no complete release candidate yet.

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
python3 tools/release_audit.py --manifest release/artifacts.json \
  --evidence .agents/native --output .agents/release/audit.json
```

The audit verifies the artifact and metadata contracts, evidence hashes and
coverage. The native runners supply behavioral evidence; the audit does not
replace those runners or a review of their tests.
