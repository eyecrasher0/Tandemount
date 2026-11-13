#!/usr/bin/env python3
"""Audit packaged artifacts and hash-bound native evidence before publication."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_CHECKS = frozenset({
    "mount", "driver-control", "third-rider-rejected", "dismount",
    "cancelled-interaction", "cancelled-dismount", "permissions", "settings",
    "late-observer", "multiple-rides", "disconnect", "death", "world-change",
    "teleport", "chunk-unload", "restart-cleanup",
})
PLUGIN_PLATFORMS = frozenset({"paper", "spigot", "purpur", "folia"})
MOD_PLATFORMS = frozenset({"fabric", "forge", "neoforge"})


def digest(path, algorithm="sha256"):
    result = hashlib.new(algorithm)
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def child(root, relative):
    if not isinstance(relative, str) or not relative:
        raise ValueError("Expected a nonempty relative path")
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError("Path must remain inside the declared root: " + relative)
    resolved = (root / path).resolve()
    resolved.relative_to(root.resolve())
    return resolved


def jar_contract(path, java_min, license_bytes):
    errors = []
    descriptors = set()
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            errors.append("Duplicate JAR entries")
        for entry in archive.infolist():
            name = entry.filename
            if name.startswith("/") or ".." in Path(name).parts or "\\" in name:
                errors.append("Unsafe JAR entry: " + name)
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                errors.append("JAR contains a symlink: " + name)
        classes = [name for name in names if name.endswith(".class")]
        if not classes:
            errors.append("JAR contains no executable classes")
        for name in classes:
            raw = archive.read(name)
            if len(raw) < 8 or raw[:4] != b"\xca\xfe\xba\xbe":
                errors.append("Invalid class file: " + name)
                continue
            major = struct.unpack(">H", raw[6:8])[0]
            if major > java_min + 44 and not name.startswith("META-INF/versions/"):
                errors.append(f"Class requires Java {major - 44}, declares {java_min}: {name}")
        for name in ("plugin.yml", "paper-plugin.yml", "fabric.mod.json", "mcmod.info",
                     "META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
            if name in names:
                descriptors.add(name)
        licenses = [name for name in names if Path(name).name.lower() in {"license", "license.txt"}]
        if not any(archive.read(name).strip() == license_bytes.strip() for name in licenses):
            errors.append("Packaged GPL-3.0 text is missing or differs from source LICENSE")
        bad_private = [name for name in names if any(
            part in {".agents", ".sober", ".env", "server.properties", "eula.txt"}
            for part in Path(name).parts)]
        if bad_private:
            errors.append("Private/runtime files in JAR: " + ", ".join(bad_private))
    return errors, descriptors


def artifact_targets(artifact):
    if "targets" in artifact:
        values = [(item["minecraft"], item["platform"]) for item in artifact["targets"]]
        if len(values) != len(set(values)):
            raise ValueError("Duplicate exact target in one artifact")
        return set(values)
    return {(mc, platform) for mc in artifact["minecraft"] for platform in artifact["platforms"]}


def audit(root, matrix, manifest, evidence_root):
    errors = []
    expected = {(t["minecraft"], p) for t in matrix["targets"]
                for p, state in t["platforms"].items() if state["available"]}
    if len(matrix["targets"]) != 38:
        errors.append("Expected exactly 38 requested Minecraft versions")
    if manifest.get("schema") != 1:
        errors.append("Unsupported release manifest schema")
    if manifest.get("license") != "GPL-3.0-only":
        errors.append("Release license must be GPL-3.0-only")
    if manifest.get("name") != "Tandemount":
        errors.append("Release name must be Tandemount")
    if not manifest.get("source_commit"):
        errors.append("Missing exact source commit")
    license_bytes = (root / "LICENSE").read_bytes()
    declared, verified = set(), set()
    ids, filenames = set(), set()
    artifacts = manifest.get("artifacts", [])
    for artifact in artifacts:
        identifier = artifact.get("id", "<missing>")
        prefix = f"{identifier}: "
        try:
            if identifier in ids:
                raise ValueError("Duplicate artifact id")
            ids.add(identifier)
            path = child(root, artifact["file"])
            if path in filenames:
                raise ValueError("Duplicate artifact file")
            filenames.add(path)
            if path.suffix != ".jar":
                raise ValueError("Artifact must be a JAR")
            if digest(path) != artifact["sha256"]:
                raise ValueError("Artifact SHA256 mismatch")
            if not isinstance(artifact["java_min"], int) or artifact["java_min"] < 8:
                raise ValueError("Invalid minimum Java version")
            jar_errors, descriptors = jar_contract(path, artifact["java_min"], license_bytes)
            errors.extend(prefix + error for error in jar_errors)
            pairs = artifact_targets(artifact)
            platforms = {platform for _, platform in pairs}
            if not pairs:
                raise ValueError("Empty compatibility declaration")
            if platforms & PLUGIN_PLATFORMS and not descriptors & {"plugin.yml", "paper-plugin.yml"}:
                errors.append(prefix + "Plugin descriptor missing")
            if "fabric" in platforms and "fabric.mod.json" not in descriptors:
                errors.append(prefix + "Fabric descriptor missing")
            if platforms & {"forge", "neoforge"} and not descriptors & {
                "mcmod.info", "META-INF/mods.toml", "META-INF/neoforge.mods.toml"}:
                errors.append(prefix + "Forge/NeoForge descriptor missing")
            invalid = pairs - expected
            if invalid:
                errors.append(prefix + "Unavailable upstream targets: " + repr(sorted(invalid)))
            overlap = declared & pairs
            if overlap:
                errors.append(prefix + "Targets declared by several artifacts: " + repr(sorted(overlap)))
            declared.update(pairs)
            native = set()
            for relative in artifact.get("native_results", []):
                report = json.loads(child(evidence_root, relative).read_text())
                pair = report["minecraft"], report["platform"]
                if pair not in pairs:
                    errors.append(prefix + "Native evidence names a different artifact target")
                    continue
                if report.get("artifact_sha256") != artifact["sha256"]:
                    errors.append(prefix + "Native evidence artifact hash mismatch")
                    continue
                checks = report.get("checks", {})
                absent = REQUIRED_CHECKS - checks.keys()
                failed = [key for key, value in checks.items() if value is not True]
                if report.get("kind") != "native" or report.get("result") != "pass" or not report.get("server_version"):
                    errors.append(prefix + "Native server execution evidence incomplete: " + repr(pair))
                    continue
                if absent or failed:
                    errors.append(prefix + f"Native checks incomplete for {pair}: missing={sorted(absent)}, failed={failed}")
                    continue
                native.add(pair)
            verified.update(native)
            if pairs - native:
                errors.append(prefix + "Native coverage missing: " + repr(sorted(pairs - native)))
        except (KeyError, ValueError, TypeError, OSError, zipfile.BadZipFile) as error:
            errors.append(prefix + str(error))
    missing = expected - declared
    if missing:
        errors.append("Release artifacts do not cover all requested available targets: " + repr(sorted(missing)))
    return {
        "result": "pass" if not errors else "fail",
        "artifacts": len(artifacts), "expected_targets": len(expected),
        "declared_targets": len(declared), "native_verified_targets": len(verified),
        "errors": errors,
        "visual_evidence": "Separately recorded; native tests do not establish rendered appearance.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "release/artifacts.json")
    parser.add_argument("--evidence", type=Path, default=ROOT / ".agents/native")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = audit(ROOT, json.loads((ROOT / "platforms.json").read_text()),
                       json.loads(args.manifest.read_text()), args.evidence)
    except (OSError, ValueError, KeyError) as error:
        result = {"result": "fail", "errors": [str(error)]}
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
    print(rendered, end="")
    return 0 if result["result"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
