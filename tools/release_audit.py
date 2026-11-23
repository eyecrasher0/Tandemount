#!/usr/bin/env python3
"""Audit packaged artifacts and hash-bound native evidence before publication."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import tomllib
import zipfile

from project_metadata import artifact_name, metadata

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "build/release/latest-artifacts.json"
REQUIRED_CHECKS = frozenset({
    "mount", "driver-control", "third-rider-rejected", "dismount",
    "cancelled-interaction", "cancelled-dismount", "permissions", "settings",
    "late-observer", "multiple-rides", "disconnect", "death", "world-change",
    "teleport", "chunk-unload", "restart-cleanup",
})
PLUGIN_PLATFORMS = frozenset({"paper", "spigot", "purpur", "folia"})
MOD_PLATFORMS = frozenset({"fabric", "forge", "neoforge"})


def unavailable_check(report, name):
    """Allow only documented absence of a loader's cancellable dismount API.

    Fabric Loader without Fabric API has no gameplay event bus. Forge 1.7.10
    predates EntityMountEvent. Neither condition is a successful cancellation
    test, so retain a distinct value and reason in the publication evidence.
    """
    pair = report.get("minecraft"), report.get("platform")
    supported_exception = (name == "cancelled-dismount"
                           and (pair == ("1.7.10", "forge") or pair[1] == "fabric"))
    reason = report.get("not_applicable", {}).get(name)
    return supported_exception and isinstance(reason, str) and bool(reason.strip())


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


def portable_report(manifest_path, manifest, reports, result):
    return {"schema": 1, "name": "Tandemount", "source_commit": manifest["source_commit"],
            "manifest_sha256": digest(manifest_path), "matrix_sha256": manifest["matrix_sha256"],
            "source_archive_sha256": manifest["source"]["sha256"], "result": result["result"],
            "visual_verification": "not-performed",
            "artifacts": [{key: value for key, value in artifact.items() if key in {"id", "sha256", "java_min", "targets"}}
                          for artifact in manifest["artifacts"]],
            "native_reports": sorted(reports, key=lambda report: (report["minecraft"], report["platform"])),
            "audit": result}


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


def descriptor_errors(path, family, target, version):
    mc = target["minecraft"]
    with zipfile.ZipFile(path) as jar:
        fields = dict(line.split(": ", 1) for line in jar.read("META-INF/MANIFEST.MF").decode().splitlines() if ": " in line)
        target_field = "Tandemount-Minecraft-Target" if family == "plugin" else "Tandemount-Minecraft"
        loader = "bukkit" if family == "plugin" else family
        if (fields.get(target_field), fields.get("Implementation-Version"), fields.get("Tandemount-Loader")) != (mc, version, loader):
            return ["JAR manifest names a different game, release version, or loader"]
        if family == "plugin":
            descriptor = {}
            for line in jar.read("plugin.yml").decode().splitlines():
                if not line or line[0].isspace() or line.startswith("#"):
                    continue
                key, separator, value = line.partition(":")
                if not separator or key in descriptor:
                    raise ValueError("Invalid or duplicate top-level plugin descriptor key")
                descriptor[key] = value.strip().strip("\"'")
            valid = (descriptor.get("name"), descriptor.get("version"), descriptor.get("main")) == (
                "Tandemount", version, "dev.eyecrasher.tandemount.TandemountPlugin")
            if target["platforms"].get("folia", {}).get("available"):
                valid = valid and descriptor.get("folia-supported") == "true"
        elif family == "fabric":
            descriptor = json.loads(jar.read("fabric.mod.json"))
            depends = descriptor.get("depends", {})
            valid = (descriptor.get("id"), descriptor.get("version"), descriptor.get("environment"),
                     descriptor.get("license"), depends.get("minecraft"), depends.get("fabricloader"), depends.get("java")) == (
                "tandemount", version, "server", "GPL-3.0-only", "=" + mc,
                ">=" + target["platforms"]["fabric"]["loader"], ">=" + str(target["java"]))
        elif family == "forge" and mc in {"1.7.10", "1.8.9", "1.12.2"}:
            descriptor = json.loads(jar.read("mcmod.info"))
            valid = (isinstance(descriptor, list) and len(descriptor) == 1
                     and (descriptor[0].get("modid"), descriptor[0].get("version"), descriptor[0].get("mcversion"))
                     == ("tandemount", version, mc)
                     and fields.get("FMLCorePlugin") == "dev.eyecrasher.tandemount.legacy.core.CorePlugin")
        else:
            filename = ("META-INF/neoforge.mods.toml" if family == "neoforge" and tuple(map(int, mc.split("."))) >= (1, 20, 5)
                        else "META-INF/mods.toml")
            descriptor = tomllib.loads(jar.read(filename).decode())
            mods = descriptor.get("mods", [])
            requirements = descriptor.get("dependencies", {}).get("tandemount", [])
            games = [requirement for requirement in requirements if requirement.get("modId") == "minecraft"]
            valid = (descriptor.get("modLoader") == "javafml" and descriptor.get("license") == "GPL-3.0-only"
                     and len(mods) == 1 and (mods[0].get("modId"), mods[0].get("version")) == ("tandemount", version)
                     and len(games) == 1 and games[0].get("versionRange") == f"[{mc}]" and games[0].get("side") == "SERVER")
        return [] if valid else ["Loader descriptor does not match Tandemount's exact release target"]


def audit(root, matrix, manifest, evidence_root):
    errors = []
    not_applicable = []
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
    by_version = {target["minecraft"]: target for target in matrix["targets"]}
    version = metadata(root)["version"]
    if manifest.get("release_version") != version:
        errors.append("Manifest release version differs from project metadata")
    matrix_path = root / "platforms.json"
    if manifest.get("matrix_sha256") != digest(matrix_path) or matrix != json.loads(matrix_path.read_text()):
        errors.append("Manifest does not bind the exact platform matrix")
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("source_commit") or ""):
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
            if type(artifact["java_min"]) is not int or artifact["java_min"] < 8:
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
            versions = {mc for mc, _ in pairs}
            family = "plugin" if platforms <= PLUGIN_PLATFORMS else next(iter(platforms)) if len(platforms) == 1 else None
            if len(versions) != 1 or family not in {*MOD_PLATFORMS, "plugin"}:
                errors.append(prefix + "Expected one game and one distribution family per JAR")
            else:
                mc = next(iter(versions))
                target = by_version[mc]
                loader = "bukkit" if family == "plugin" else family
                if path.name != artifact_name(loader, mc, version):
                    errors.append(prefix + "Filename differs from the exact artifact contract")
                if artifact["java_min"] != (8 if family == "plugin" else target["java"]):
                    errors.append(prefix + "Minimum Java version differs from the exact target")
                errors.extend(prefix + error for error in descriptor_errors(path, family, target, version))
            overlap = declared & pairs
            if overlap:
                errors.append(prefix + "Targets declared by several artifacts: " + repr(sorted(overlap)))
            declared.update(pairs)
            native = set()
            for proof in artifact.get("native_results", []):
                report_path = child(evidence_root, proof["file"])
                raw_hash = proof["raw_sha256"]
                if (not re.fullmatch(r"[0-9a-f]{64}", raw_hash)
                        or digest(report_path) != proof["sha256"]
                        or digest(evidence_root / "raw" / (raw_hash + ".json")) != raw_hash):
                    raise ValueError("Native report or raw evidence hash mismatch")
                report = json.loads(report_path.read_text())
                if report.get("raw_report_sha256") != raw_hash:
                    raise ValueError("Native report references different raw evidence")
                pair = report["minecraft"], report["platform"]
                if pair not in pairs:
                    errors.append(prefix + "Native evidence names a different artifact target")
                    continue
                if pair in native:
                    raise ValueError("Duplicate native report for the same exact target")
                if report.get("artifact_sha256") != artifact["sha256"]:
                    errors.append(prefix + "Native evidence artifact hash mismatch")
                    continue
                checks = report.get("checks", {})
                absent = REQUIRED_CHECKS - checks.keys()
                failed = [key for key, value in checks.items() if value is not True
                          and not (value == "not-applicable" and unavailable_check(report, key))]
                if report.get("kind") != "native" or report.get("result") != "pass" or not report.get("server_version"):
                    errors.append(prefix + "Native server execution evidence incomplete: " + repr(pair))
                    continue
                if absent or failed:
                    errors.append(prefix + f"Native checks incomplete for {pair}: missing={sorted(absent)}, failed={failed}")
                    continue
                not_applicable.extend({"minecraft": pair[0], "platform": pair[1],
                                       "check": key, "reason": report["not_applicable"][key]}
                                      for key, value in checks.items() if value == "not-applicable")
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
        "not_applicable": not_applicable,
        "errors": errors,
        "visual_evidence": "Separately recorded; native tests do not establish rendered appearance.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        manifest = args.manifest.resolve()
        result = audit(ROOT, json.loads((ROOT / "platforms.json").read_text()),
                       json.loads(manifest.read_text()), args.evidence or manifest.parent / "native")
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
