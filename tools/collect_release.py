#!/usr/bin/env python3
"""Collect current native reports into an audited, hash-bound release manifest."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from uuid import uuid4

from matrix_verify import sources
from attachment_evidence import passed_probe
from legacy_evidence import (LEGACY_CARRIERS, LEGACY_REPORT, OBSERVER_OFFSETS, horse_moved,
                             legacy_fixture_digest, lifetime_passed, observer_snapshot_passed)
from release_audit import DEFAULT_MANIFEST, ROOT, REQUIRED_CHECKS, PLUGIN_PLATFORMS, audit, digest, portable_report, unavailable_check
from project_metadata import metadata
from source_archive import export


FINAL_SETTINGS = (
    "command-chat-has-color-and-style", "command-chat-has-clickable-adjustments",
    "command-click-adjustment-applies", "missing-settings-use-owner-defaults",
    "saved-settings-remain-preserved", "fresh-config-uses-owner-defaults",
    "reset-restores-owner-defaults",
)
MOD_ATTACHMENT = {
    "mount": ("server-native-attachment-shares-horse-root",
              "server-native-horse-keeps-first-driver-control",
              "vanilla-client-seat-survives-native-ticks"),
    "late-observer": ("existing-observer-received-native-attachment-shares-horse-root",
                      "late-observer-received-native-attachment-shares-horse-root",
                      "attachment-has-no-periodic-position-follow-packets"),
}


MODERN = {
    "mount": ("native-interaction-opens-second-seat", "second-rider-uses-native-vehicle"),
    "driver-control": ("driver-control-vehicle-preserved", "server-accepts-driver-vehicle-movement-during-shared-ride"),
    "third-rider-rejected": ("third-rider-rejected", "real-client-third-rider-rejected"),
    "dismount": ("rider-dismount-cleans-seat", "driver-dismount-cleans-seat"),
    "cancelled-interaction": ("consumed-interaction-not-overridden",),
    "cancelled-dismount": ("loader-native-dismount-veto-observed", "loader-cancelled-dismount-retains-ride"),
    "permissions": ("ordinary-player-admin-denied", "permission-denial-preserves-settings"),
    "settings": ("height-adjustment-independent", "rear-adjustment-independent", "nonfinite-config-rejected", "admin-settings-saved-immediately", "admin-reload-restores-saved-settings") + FINAL_SETTINGS,
    "late-observer": ("late-observer-native-pairing-includes-state", "late-observer-native-seat-passenger-edge", "late-observer-native-horse-driver-edge"),
    "multiple-rides": ("multiple-rides-own-independent-seats",),
    "disconnect": ("native-rider-disconnect-cleans-seat", "same-identity-reconnect-uses-new-session"),
    "death": ("rider-death-cleans-only-own-ride", "driver-death-cleans-only-own-ride"),
    "world-change": ("native-player-world-change-cleans-own-ride",),
    "teleport": ("native-player-teleport-cleans-own-ride",),
    "chunk-unload": ("unloaded-chunk-cleans-without-force-loading", "unloaded-seat-destination-cleans-without-force-loading", "unloaded-seat-destination-rejects-mount-without-force-loading"),
    "restart-cleanup": ("server-stop-cleans-all-active-seats", "restart-has-no-persisted-seat-artifact", "restart-has-no-stale-ride-state"),
}
PLUGIN = {
    "mount": ("ordinary-non-op-passenger-mounted", "native-driver-mounted",
              "real-client-right-click-mount-persists", "real-client-repeated-click-keeps-ride",
              "real-client-mounted-movement-keeps-ride", "vanilla-client-seat-survives-native-ticks"),
    "driver-control": ("driver-still-controls-original-horse", "server-accepts-driver-vehicle-movement-during-shared-ride"),
    "third-rider-rejected": ("third-rider-rejected",),
    "dismount": ("passenger-dismount-cleans-seat", "driver-dismount-cleans-seat"),
    "cancelled-interaction": ("cancelled-final-interaction-does-not-mount",),
    "cancelled-dismount": ("cancelled-dismount-keeps-ride", "cancelled-driver-dismount-keeps-ride"),
    "permissions": ("non-op-admin-denied",),
    "settings": ("vertical-independent-step", "rearward-independent-step", "nonfinite-setting-rejected", "active-rider-height", "active-rider-rearward",
                 ) + FINAL_SETTINGS,
    "late-observer": ("late-observer-requires-no-remount", "tmthird-passenger-seat-edge-received", "tmlate-passenger-seat-edge-received", "tmlate-horse-has-only-driver-on-client", "tmlate-seat-position-updates-received"),
    "multiple-rides": ("two-simultaneous-rides-independent", "secondary-removal-preserves-primary-ride"),
    "disconnect": ("real-disconnect-cleans-seat", "reconnected-player-can-retry"),
    "death": ("passenger-death-cleans-seat", "driver-death-cleans-seat", "horse-death-cleans-seat"),
    "world-change": ("native-passenger-world-change-cleans-seat", "retry-after-native-world-change"),
    "teleport": ("passenger-teleport-cleans-seat", "retry-after-teleport"),
    "chunk-unload": ("native-chunk-unload-after-evacuation", "retry-after-native-chunk-unload"),
    "restart-cleanup": ("plugin-disable-cleans-seat", "native-restart-does-not-restore-helper", "active-seat-refuses-native-serialization", "native-saved-world-contains-no-helper-uuid"),
}
PLUGIN_ATTACHMENT = {
    "mount": ("server-native-attachment-shares-horse-root",
              "server-native-horse-keeps-first-driver-control",
              "tmthird-received-native-attachment-shares-horse-root"),
    "late-observer": ("late-observer-requires-no-remount",
                      "tmthird-passenger-seat-edge-received", "tmlate-passenger-seat-edge-received",
                      "tmthird-received-native-attachment-shares-horse-root",
                      "tmlate-received-native-attachment-shares-horse-root",
                      "tmthird-attachment-has-no-periodic-position-follow-packets",
                      "tmlate-attachment-has-no-periodic-position-follow-packets"),
}
LEGACY = {
    "mount": ("ordinary-native-driver-mounted", "uncancelled-native-interaction-joins",
              "vanilla-client-seat-survives-native-ticks"),
    "driver-control": ("native-driver-input-turns-and-moves-shared-ride",),
    "third-rider-rejected": ("third-rider-rejected-without-disruption",),
    "dismount": ("normal-native-dismount-cleans-seat", "driver-dismount-cleans-passenger"),
    "cancelled-interaction": ("cancelled-interaction-no-join",),
    "cancelled-dismount": ("cancelled-dismount-stays-mounted", "cancelled-driver-dismount-keeps-ride"),
    "permissions": ("ordinary-player-settings-permission-denied",),
    "settings": ("vertical-independent-and-live", "rear-independent-and-live", "nonfinite-config-rejected") + FINAL_SETTINGS,
    "late-observer": ("protocol-observers-passed", "movement-and-turn-pose-with-driver-preserved"),
    "multiple-rides": ("multiple-rides-isolated",),
    "disconnect": ("native-disconnect-cleans-seat", "real-vanilla-disconnect-cleans-seat", "same-identity-reconnect-can-ride"),
    "death": ("passenger-death-cleans-seat", "driver-death-cleans-seat", "horse-death-cleans-passenger"),
    "world-change": ("native-passenger-dimension-transfer-completes", "native-passenger-world-change-cleans-seat", "native-driver-dimension-transfer-completes", "native-driver-world-change-cleans-seat"),
    "teleport": ("native-passenger-teleport-cleans-seat", "native-driver-teleport-cleans-seat"),
    "chunk-unload": ("native-chunk-unload-cleans-without-reloading",),
    "restart-cleanup": ("native-server-stop-cleans-seat", "restart-seat-absent-from-native-world"),
}


def with_attachment(groups, attachment, replace=()):
    result = dict(groups)
    for name, required in attachment.items():
        result[name] = required if name in replace else result[name] + required
    return result


def index_assertions(items, name_key="name", pass_key="passed", success=True):
    result = {}
    for item in items:
        name = item[name_key]
        passed = item.get(pass_key) == success
        # A later duplicate PASS must not hide an earlier failure.
        result[name] = result.get(name, True) and passed
    return result


def legacy_lifetime(probe, minecraft):
    return (probe.get("minecraft") == minecraft and probe.get("status") == "passed"
            and probe.get("probe_exit_code") == 0 and probe.get("ticks", 0) >= 64
            and probe.get("removed") is False and probe.get("unobstructed_picking") is True
            and all(re.fullmatch(r"[0-9a-f]{64}", probe.get(key) or "")
                    for key in ("official_client_sha256", "packet_sequence_sha256", "probe_report_sha256")))


def normalize(raw, family):
    """Require named behavioral outcomes; an overall PASS alone is insufficient."""
    server = raw.get("server", {})
    minecraft, platform = raw["minecraft"], raw["platform"]
    version = tuple(map(int, minecraft.split(".")))
    attached = version >= (1, 19, 4)
    artifact_sha256 = raw.get("artifact_sha256", raw.get("sha256"))
    if family == "modern":
        observed = {name: True for name in raw.get("checks", [])}
        observed.update({name: True for name in raw.get("restart_fixture", {}).get("checks", [])})
        network = raw.get("network_probe", {})
        if network.get("result") == "pass":
            observed.update({name: True for name in network.get("checks", [])})
        good = (raw.get("native_fixture") is True and raw.get("bootstrap") == "passed"
                and raw.get("connected_clients") is True and raw.get("restart_passed") is True
                and raw.get("exit_code") == 0 and not raw.get("error"))
        groups = with_attachment(MODERN, MOD_ATTACHMENT if attached else {
            "mount": ("server-native-horse-keeps-first-driver-control", "vanilla-client-seat-survives-native-ticks"),
            "late-observer": ("existing-observer-received-complete-early-follower-graph",
                              "late-observer-received-complete-early-follower-graph",
                              "early-follower-moves-only-interpolated-native-root"),
        })
        if version >= (1, 21, 2):
            groups["world-change"] += ("native-helper-transfer-horse-reaches-destination",
                "native-helper-transfer-rider-uses-own-native-transfer-and-detaches",
                "native-helper-transfer-player-coordinate-matches-native-destination",
                "native-helper-transfer-closes-source-ride")
    elif family == "plugin":
        items = [item for key in ("assertions", "packet_assertions", "menu_assertions", "active_save_assertions", "save_assertions")
                 for item in raw.get(key, [])]
        observed = index_assertions(items, "assertion", "status", "passed")
        good = (raw.get("status") == "passed" and raw.get("kind") == "native-runtime"
                and raw.get("restart") == "passed" and raw.get("exit_code") == 0
                and bool(items) and all(observed.values()))
        groups = with_attachment(PLUGIN, PLUGIN_ATTACHMENT, replace=("late-observer",)) if attached else dict(PLUGIN)
        if not attached:
            groups["late-observer"] += ("tmthird-horse-has-only-driver-on-client", "tmthird-seat-position-updates-received")
    elif family == "legacy":
        items = (raw.get("native_checks", []) + raw.get("synthetic_behavior", {}).get("checks", [])
                 + raw.get("restart", {}).get("checks", []) + raw.get("final_checks", []))
        observed = index_assertions(items)
        observers = raw.get("protocol_observers")
        if not isinstance(observers, dict):
            observers = {}
        attachment = observers.get("native_attachment", {})
        minecraft = raw["minecraft"]
        observed["protocol-observers-passed"] = (
            observed.get("protocol-observers-passed", True) and observers.get("status") == "passed"
            and observers.get("real_client_interaction_mounted") is True
            and attachment.get("mode") == "early-follower" and attachment.get("shared_horse_root") is False
            and all(observer_snapshot_passed(observers.get(phase, {}), phase) for phase in OBSERVER_OFFSETS)
            and horse_moved(observers["initial"]["native"], observers["movement"]["native"])
        )
        observed["vanilla-client-seat-survives-native-ticks"] = (
            observed.get("vanilla-client-seat-survives-native-ticks") is True
            and lifetime_passed(raw.get("client_lifetime", {}), minecraft)
        )
        login = raw.get("vanilla_socket_login", {})
        good = (minecraft in LEGACY_CARRIERS and raw["platform"] == "forge"
                and server.get("minecraft") == minecraft and server.get("platform") == "forge"
                and raw.get("status") == "passed" and raw.get("exit_code") == 0
                and raw.get("restart", {}).get("status") == "passed"
                and login.get("status") == "passed" and login.get("ordered_play_login") is True
                and raw.get("synthetic_behavior", {}).get("status") == "passed"
                and raw.get("network_policy", {}).get("status") == ("not-applicable" if minecraft == "1.12.2" else "passed")
                and all(observed.values()))
        groups = dict(LEGACY)
        if minecraft == "1.8.9":
            groups["cancelled-dismount"] += ("cancelled-outer-passenger-dismount-keeps-ride",
                                            "cancelled-outer-driver-dismount-keeps-ride")
            groups["dismount"] += ("allowed-passenger-dismount-fires-both-events-once",
                                   "allowed-driver-dismount-fires-both-events-once")
        if minecraft == "1.12.2":
            groups["driver-control"] = ("native-first-rider-can-steer-shared-ride", "shared-ride-driver-vehicle-move-accepted")
            observed["shared-ride-driver-vehicle-move-accepted"] = (
                observed.get("shared-ride-driver-vehicle-move-accepted") is True
                and observers.get("driver_vehicle_move_accepted") is True
            )
    else:
        raise ValueError("Unknown native report family: " + family)
    if family in {"modern", "plugin"}:
        proof = raw.get("official_client_attachment", {})
        probes = proof.get("probes", {})
        good = (good and proof.get("status") == "passed"
                and proof.get("artifact_sha256") == artifact_sha256
                and proof.get("minecraft") == minecraft and set(probes) == {"existing", "late"})
        if minecraft in {"1.7.10", "1.12.2"}:
            carrier = "signed-size-slime" if minecraft == "1.7.10" else "marker-armor-stand"
            good = good and all(legacy_lifetime(probe, minecraft) and probe.get("carrier") == carrier for probe in probes.values())
        else:
            mode = "native-horse-root" if attached else "early-follower"
            good = good and all(passed_probe(probe, artifact_sha256) and probe.get("attachmentMode") == mode for probe in probes.values())
    checks = {name: all(observed.get(item) is True for item in required)
              for name, required in groups.items()}
    report = {"schema": 1, "kind": "native", "minecraft": raw["minecraft"], "platform": raw["platform"],
              "artifact_sha256": raw.get("artifact_sha256", raw.get("sha256")),
              "checks": checks, "check_evidence": {name: list(required) for name, required in groups.items()},
              "not_applicable": {}, "visual": "not-verified",
              "attachment_mode": "native-horse-root" if attached else "early-follower"}
    if family == "modern" and raw["platform"] == "fabric":
        capability = raw.get("loader_cancellation", {})
        if capability.get("status") == "not-applicable" and capability.get("reason"):
            checks["cancelled-dismount"] = "not-applicable"
            report["not_applicable"]["cancelled-dismount"] = capability["reason"]
    if family == "legacy":
        report["attachment_mode"] = attachment.get("mode")
        report["driver_control_scope"] = ("Native steering eligibility and accepted driver vehicle movement over loopback"
                                          if minecraft == "1.12.2" else "Native server travel from synthetic first-rider steering input")
        if raw["minecraft"] == "1.7.10":
            checks["cancelled-dismount"] = "not-applicable"
            report["not_applicable"]["cancelled-dismount"] = "Forge 1.7.10 does not expose a cancellable EntityMountEvent; ordinary native dismount was tested."
    field = {"spigot": "revision", "fabric": "loader", "forge": "sdk", "neoforge": "sdk"}.get(platform, "build")
    identity = server.get(field)
    files = server.get("launch_files", {})
    exact = ((server.get("minecraft"), server.get("platform")) == (minecraft, platform)
             and isinstance(identity, str) and bool(identity) and server.get("launcher") in files
             and all(re.fullmatch(r"[0-9a-f]{64}", value) for value in files.values()))
    report["server_version"] = f"{platform} {identity} / Minecraft {minecraft}" if exact else ""
    report["server"] = server
    for key in ("world_change_fixture", "unloaded_chunk_fixture", "renderer_analysis", "official_client_attachment", "client_lifetime"):
        if key in raw:
            report[key] = raw[key]
    incomplete = [key for key in REQUIRED_CHECKS if checks.get(key) is not True
                  and not (checks.get(key) == "not-applicable" and unavailable_check(report, key))]
    report["result"] = "pass" if good and not incomplete and exact else "fail"
    report["incomplete_checks"] = sorted(incomplete)
    return report


def load_matrix_record(directory, key, mode):
    record = json.loads((directory / (key + ".json")).read_text())
    if record.get("result") != "pass" or record.get("source_fingerprint") != sources(mode):
        raise ValueError(key + ": native matrix result is failed or from different source")
    native_path = ROOT / record["native_report"]
    if digest(native_path) != record["native_report_sha256"]:
        raise ValueError(key + ": raw native report changed after matrix verification")
    raw = json.loads(native_path.read_text())
    if raw.get("server") != record.get("server"):
        raise ValueError(key + ": executed server identity differs from the matrix record")
    artifact = ROOT / record["artifact"]
    if digest(artifact) != record["artifact_sha256"]:
        raise ValueError(key + ": matrix artifact changed")
    return raw, native_path, artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--modern", type=Path, default=ROOT / ".agents/matrix-modern")
    parser.add_argument("--plugins", type=Path, default=ROOT / ".agents/matrix-plugins")
    parser.add_argument("--legacy", type=Path, default=ROOT / LEGACY_REPORT)
    parser.add_argument("--output", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True):
        raise SystemExit("Commit release source before collecting evidence")
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    candidates = args.output.parent.resolve() / "candidates"
    candidates.relative_to(ROOT)
    candidates.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="collect-", dir=candidates) as directory:
        return collect(args, Path(directory), candidates, source_commit)


def collect(args, staging, candidates, source_commit):
    matrix_path = ROOT / "platforms.json"
    matrix = json.loads(matrix_path.read_text())
    project = metadata()
    legacy = None
    evidence = staging / "native"
    (evidence / "raw").mkdir(parents=True)
    artifacts, reports, errors = {}, [], []
    binaries = staging / "binaries"
    binaries.mkdir()
    for target in matrix["targets"]:
        mc = target["minecraft"]
        for platform, state in target["platforms"].items():
            if not state["available"]:
                continue
            key = platform + "-" + mc
            try:
                if platform in PLUGIN_PLATFORMS:
                    raw, native_path, artifact = load_matrix_record(args.plugins, key, "plugins")
                    family, identifier = "plugin", "plugin-" + mc
                elif platform == "forge" and mc in LEGACY_CARRIERS:
                    if legacy is None:
                        test_sha = legacy_fixture_digest(ROOT)
                        legacy = {r["minecraft"]: r for r in json.loads(args.legacy.read_text())["targets"]}
                    raw, native_path = legacy[mc], args.legacy
                    artifact = Path(raw["artifact"])
                    if raw.get("test_sources_sha256") != test_sha:
                        raise ValueError("Legacy fixture source changed since native execution")
                    build = json.loads(artifact.with_suffix(".json").read_text())
                    java_sources = sorted((ROOT / "forge/legacy/src/main/java").rglob("*.java")) + sorted((ROOT / f"forge/{mc}/src/main/java").rglob("*.java"))
                    current = hashlib.sha256((ROOT / "project.json").read_bytes()
                                             + b"".join(p.read_bytes() for p in java_sources)).hexdigest()
                    if current != build["source_sha256"] or digest(artifact) != build["sha256"]:
                        raise ValueError("Legacy build source or artifact changed")
                    family, identifier = "legacy", key
                else:
                    raw, native_path, artifact = load_matrix_record(args.modern, key, "modern")
                    family, identifier = "modern", key
                report = normalize(raw, family)
                if (report["minecraft"], report["platform"]) != (mc, platform):
                    raise ValueError("Native report names a different target")
                if digest(artifact) != report["artifact_sha256"]:
                    raise ValueError("Native artifact hash differs from current artifact")
                report["raw_report_sha256"] = digest(native_path)
                if report["result"] != "pass":
                    raise ValueError("Native checks incomplete: " + repr(report["incomplete_checks"]))
                destination = binaries / artifact.name
                if destination.exists() and digest(destination) != report["artifact_sha256"]:
                    raise ValueError("Conflicting staged artifact: " + destination.name)
                if not destination.exists():
                    shutil.copy2(artifact, destination)
                entry = artifacts.setdefault(identifier, {"id": identifier, "file": str(destination.relative_to(ROOT)),
                    "sha256": report["artifact_sha256"], "java_min": 8 if family in {"plugin", "legacy"} else target["java"],
                    "targets": [], "native_results": []})
                if entry["sha256"] != report["artifact_sha256"]:
                    raise ValueError("One release artifact has different hashes on its target platforms")
                proof_path = evidence / (key + ".json")
                proof_path.write_text(json.dumps(report, indent=2) + "\n")
                raw_copy = evidence / "raw" / (report["raw_report_sha256"] + ".json")
                if not raw_copy.exists():
                    shutil.copy2(native_path, raw_copy)
                reports.append(report)
                entry["targets"].append({"minecraft": mc, "platform": platform})
                entry["native_results"].append({"file": proof_path.name, "sha256": digest(proof_path),
                                               "raw_sha256": report["raw_report_sha256"]})
            except (OSError, ValueError, KeyError, TypeError) as error:
                errors.append(key + ": " + str(error))
    manifest = {"schema": 1, "name": "Tandemount", "license": "GPL-3.0-only", "source_commit": source_commit,
                "release_version": project["version"], "matrix_sha256": digest(matrix_path),
                "artifacts": sorted(artifacts.values(), key=lambda a: a["id"])}
    result = audit(ROOT, matrix, manifest, evidence)
    if errors:
        result["collection_errors"] = errors
        result["result"] = "fail"
    if result["result"] != "pass":
        print(json.dumps(result, indent=2))
        return 1
    archive = staging / ("Tandemount-" + project["version"] + "-source.zip")
    source = export(ROOT, source_commit, archive, project["version"])
    manifest["source"] = {**source, "file": archive.name}
    for artifact in manifest["artifacts"]:
        artifact["file"] = Path(artifact["file"]).name
    candidate = candidates / hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()
    for artifact in manifest["artifacts"]:
        artifact["file"] = str((candidate / "binaries" / artifact["file"]).relative_to(ROOT))
    manifest_path = staging / "artifacts.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    (staging / "audit.json").write_text(json.dumps(result, indent=2) + "\n")
    verification = portable_report(manifest_path, manifest, reports, result)
    (staging / "verification.json").write_text(json.dumps(verification, indent=2) + "\n")
    if candidate.exists():
        for expected in staging.rglob("*"):
            if expected.is_file() and digest(expected) != digest(candidate / expected.relative_to(staging)):
                raise ValueError("Existing candidate differs from its collected contents")
    else:
        staging.rename(candidate)
    published = candidate / "artifacts.json"
    if not args.output.exists() or args.output.is_symlink():
        pointer = args.output.with_name("." + args.output.name + "-" + uuid4().hex)
        pointer.symlink_to(published.relative_to(candidates.parent))
        pointer.replace(args.output)
    print(json.dumps({**result, "manifest": str(published)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
