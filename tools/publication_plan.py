#!/usr/bin/env python3
"""Create a reviewable, hash-bound release plan after complete native verification."""

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

from release_audit import DEFAULT_MANIFEST, ROOT, PLUGIN_PLATFORMS, artifact_targets, audit, child, digest
from source_archive import export


def entries_for(manifest):
    entries = []
    used = set()
    for artifact in manifest["artifacts"]:
        targets = artifact_targets(artifact)
        by_version = defaultdict(set)
        for minecraft, platform in targets:
            by_version[minecraft].add(platform)
        # Platform tags describe exactly the chosen game version. A single
        # flattened list would imply Folia on old releases where it never ran.
        if len(by_version) != 1:
            raise ValueError("Publish exact-game artifacts to preserve platform metadata: " + artifact["id"])
        minecraft, platforms = next(iter(by_version.items()))
        family = "plugin" if platforms <= PLUGIN_PLATFORMS else next(iter(platforms)) if len(platforms) == 1 else None
        if family is None:
            raise ValueError("An artifact mixes unrelated mod loader families")
        key = family + "-" + minecraft
        if key in used:
            raise ValueError("Duplicate publication entry: " + key)
        used.add(key)
        entries.append({"id": key, "artifact_id": artifact["id"], "minecraft": [minecraft],
                        "platforms": sorted(platforms), "family": family,
                        "file": artifact["file"], "sha256": artifact["sha256"], "java_min": artifact["java_min"]})
    return sorted(entries, key=lambda e: e["id"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / ".agents/release/plan.json")
    parser.add_argument("--dist", type=Path, default=ROOT / "dist/1.0.0")
    parser.add_argument("--curseforge-mods", type=int)
    parser.add_argument("--curseforge-plugins", type=int)
    args = parser.parse_args()
    project = json.loads((ROOT / "release/project.json").read_text())
    matrix = json.loads((ROOT / "platforms.json").read_text())
    manifest_file = args.manifest.resolve()
    manifest = json.loads(manifest_file.read_text())
    result = audit(ROOT, matrix, manifest, args.evidence or manifest_file.parent / "native")
    if result["result"] != "pass":
        raise SystemExit(json.dumps(result, indent=2))
    status = subprocess.check_output(["git", "-C", str(ROOT), "status", "--porcelain"], text=True)
    if status:
        raise SystemExit("Commit all release source changes before creating a publication plan")
    entries = entries_for(manifest)
    args.dist.mkdir(parents=True, exist_ok=True)
    binaries = args.dist / "binaries"
    binaries.mkdir(exist_ok=True)
    for entry in entries:
        source = child(ROOT, entry["file"])
        destination = binaries / source.name
        if destination.exists() and digest(destination) != entry["sha256"]:
            raise ValueError("Conflicting staged artifact: " + destination.name)
        shutil.copy2(source, destination)
        entry["file"] = str(destination.resolve())
        entry["sha1"] = digest(destination, "sha1")
        entry["sha512"] = digest(destination, "sha512")
        entry["version_number"] = project["version"] + "+" + entry["family"] + "-mc" + entry["minecraft"][0]
    tag = "tandemount-" + project["version"]
    verification_bytes = manifest_file.with_name("verification.json").read_bytes()
    verification = json.loads(verification_bytes)
    verification_sha256 = hashlib.sha256(verification_bytes).hexdigest()
    archive = f"Tandemount-{project['version']}-source.zip"
    source = export(ROOT, "HEAD", args.dist.resolve() / archive, project["version"])
    if verification.get("result") != "pass" or verification.get("source_commit") != source["source_commit"]:
        raise ValueError("Portable verification evidence must pass for the exact source commit")
    published_verification = args.dist / "verification.json"
    published_verification.write_bytes(verification_bytes)
    sums = [f"{entry['sha256']}  binaries/{Path(entry['file']).name}" for entry in entries]
    sums.append(source["sha256"] + "  " + Path(source["file"]).name)
    sums.append(verification_sha256 + "  " + published_verification.name)
    sums_path = args.dist / "SHA256SUMS"
    sums_path.write_text("\n".join(sums) + "\n")
    plan = {"schema": 1, "project": project, "source": source, "entries": entries,
            "manifest_sha256": digest(manifest_file), "matrix_sha256": digest(ROOT / "platforms.json"),
            "audit": result, "tag": tag,
            "checksums": str(sums_path.resolve()), "checksums_sha256": digest(sums_path),
            "verification": str(published_verification.resolve()), "verification_sha256": verification_sha256,
            "curseforge": {"mods": args.curseforge_mods, "plugins": args.curseforge_plugins}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(plan, indent=2) + "\n")
    print(json.dumps({"plan": str(args.output), "artifacts": len(entries), "targets": result["expected_targets"],
                      "source_commit": source["source_commit"], "plan_sha256": digest(args.output)}, indent=2))


if __name__ == "__main__":
    main()
