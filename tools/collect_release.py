#!/usr/bin/env python3
"""Collect current native reports into an audited, hash-bound release manifest."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

from matrix_verify import sources
from attachment_evidence import passed_probe
from release_audit import ROOT, REQUIRED_CHECKS, PLUGIN_PLATFORMS, audit, digest, unavailable_check


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
    "driver-control": ("driver-control-vehicle-preserved", "real-client-first-driver-controls-native-horse"),
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
    "driver-control": ("driver-still-controls-original-horse", "driver-input-moves-horse"),
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
    "driver-control": ("movement-and-turn-pose-with-driver-preserved",),
    "third-rider-rejected": ("third-rider-rejected-without-disruption",),
    "dismount": ("normal-native-dismount-cleans-seat", "driver-dismount-cleans-passenger"),
    "cancelled-interaction": ("cancelled-interaction-no-join",),
    "cancelled-dismount": ("cancelled-dismount-stays-mounted",),
    "permissions": ("ordinary-player-settings-permission-denied",),
    "settings": ("vertical-independent-and-live", "rear-independent-and-live", "nonfinite-config-rejected") + FINAL_SETTINGS,
    "late-observer": ("protocol-observers-passed",),
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


def normalize(raw, family):
    """Require named behavioral outcomes; an overall PASS alone is insufficient."""
    if family == "modern":
        observed = {name: True for name in raw.get("checks", [])}
        observed.update({name: True for name in raw.get("restart_fixture", {}).get("checks", [])})
        network = raw.get("network_probe", {})
        if network.get("result") == "pass":
            observed.update({name: True for name in network.get("checks", [])})
        good = (raw.get("native_fixture") is True and raw.get("bootstrap") == "passed"
                and raw.get("connected_clients") is True and raw.get("restart_passed") is True
                and raw.get("exit_code") == 0 and not raw.get("error"))
        groups = with_attachment(MODERN, MOD_ATTACHMENT)
    elif family == "plugin":
        items = [item for key in ("assertions", "packet_assertions", "active_save_assertions", "save_assertions")
                 for item in raw.get(key, [])]
        observed = index_assertions(items, "assertion", "status", "passed")
        good = (raw.get("status") == "passed" and raw.get("kind") == "native-runtime"
                and raw.get("restart") == "passed" and raw.get("exit_code") == 0
                and bool(items) and all(observed.values()))
        # Every requested port must prove the native attachment, including
        # received client graphs. A world-position follower is insufficient.
        groups = with_attachment(PLUGIN, PLUGIN_ATTACHMENT, replace=("late-observer",))
    elif family == "legacy":
        items = (raw.get("native_checks", []) + raw.get("synthetic_behavior", {}).get("checks", [])
                 + raw.get("restart", {}).get("checks", []) + raw.get("final_checks", []))
        observed = index_assertions(items)
        observers = raw.get("protocol_observers", {})
        observed["protocol-observers-passed"] = isinstance(observers, dict) and observers.get("status") == "passed"
        good = (raw.get("status") == "passed" and raw.get("exit_code") == 0
                and raw.get("restart", {}).get("status") == "passed"
                and raw.get("vanilla_socket_login", {}).get("status") == "passed"
                and raw.get("synthetic_behavior", {}).get("status") == "passed"
                and bool(items) and all(observed.values()))
        groups = with_attachment(LEGACY, MOD_ATTACHMENT)
    else:
        raise ValueError("Unknown native report family: " + family)
    if family in {"modern", "plugin"}:
        proof = raw.get("official_client_attachment", {})
        probes = proof.get("probes", {})
        artifact_sha256 = raw.get("artifact_sha256", raw.get("sha256"))
        good = (good and proof.get("status") == "passed"
                and proof.get("artifact_sha256") == artifact_sha256
                and proof.get("minecraft") == raw["minecraft"]
                and set(probes) == {"existing", "late"}
                and all(passed_probe(probe, artifact_sha256) for probe in probes.values()))
    checks = {name: all(observed.get(item) is True for item in required)
              for name, required in groups.items()}
    report = {"schema": 1, "kind": "native", "minecraft": raw["minecraft"], "platform": raw["platform"],
              "artifact_sha256": raw.get("artifact_sha256", raw.get("sha256")),
              "checks": checks, "check_evidence": {name: list(required) for name, required in groups.items()},
              "not_applicable": {}, "visual": "not-verified"}
    if family == "modern" and raw["platform"] == "fabric":
        capability = raw.get("loader_cancellation", {})
        if capability.get("status") == "not-applicable" and capability.get("reason"):
            checks["cancelled-dismount"] = "not-applicable"
            report["not_applicable"]["cancelled-dismount"] = capability["reason"]
    if family == "legacy" and raw["minecraft"] == "1.7.10":
        checks["cancelled-dismount"] = "not-applicable"
        report["not_applicable"]["cancelled-dismount"] = "Forge 1.7.10 does not expose a cancellable EntityMountEvent; ordinary native dismount was tested."
    server = raw.get("server", {})
    identity = server.get("build", server.get("sdk", server.get("loader", raw.get("sdk"))))
    report["server_version"] = f"{raw['platform']} {identity} / Minecraft {raw['minecraft']}" if identity else ""
    for key in ("world_change_fixture", "unloaded_chunk_fixture", "renderer_analysis", "official_client_attachment"):
        if key in raw:
            report[key] = raw[key]
    incomplete = [key for key in REQUIRED_CHECKS if checks.get(key) is not True
                  and not (checks.get(key) == "not-applicable" and unavailable_check(report, key))]
    report["result"] = "pass" if good and not incomplete and identity else "fail"
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
    artifact = ROOT / record["artifact"]
    if digest(artifact) != record["artifact_sha256"]:
        raise ValueError(key + ": matrix artifact changed")
    return raw, native_path, artifact


def legacy_fixture_digest(root):
    paths = ("tools/legacy-native-test.py", "mods/legacy/native-test/NativeChecks.java",
             "tools/legacy-client-test.py", "mods/legacy/client-test/ClientChecks.java")
    return hashlib.sha256(b"".join((root / path).read_bytes() for path in paths)).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--modern", type=Path, default=ROOT / ".agents/matrix-modern")
    parser.add_argument("--plugins", type=Path, default=ROOT / ".agents/matrix-plugins")
    parser.add_argument("--legacy", type=Path, default=ROOT / ".agents/legacy-final-native.json")
    parser.add_argument("--evidence", type=Path, default=ROOT / ".agents/native")
    parser.add_argument("--output", type=Path, default=ROOT / "build/release/artifacts.json")
    args = parser.parse_args()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True):
        raise SystemExit("Commit release source before collecting evidence")
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    matrix = json.loads((ROOT / "platforms.json").read_text())
    legacy = {r["minecraft"]: r for r in json.loads(args.legacy.read_text())["targets"]}
    test_sha = legacy_fixture_digest(ROOT)
    args.evidence.mkdir(parents=True, exist_ok=True)
    artifacts, reports, errors = {}, [], []
    output_root = ROOT / "build/release/binaries"
    output_root.mkdir(parents=True, exist_ok=True)
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
                elif mc in {"1.7.10", "1.8.9", "1.12.2"}:
                    raw, native_path = legacy[mc], args.legacy
                    artifact = Path(raw["artifact"])
                    if raw.get("test_sources_sha256") != test_sha:
                        raise ValueError("Legacy fixture source changed since native execution")
                    build = json.loads(artifact.with_suffix(".json").read_text())
                    java_sources = sorted((ROOT / "mods/legacy/src/main/java").rglob("*.java")) + sorted((ROOT / f"mods/legacy/forge-{mc}/src/main/java").rglob("*.java"))
                    current = hashlib.sha256(b"".join(p.read_bytes() for p in java_sources)).hexdigest()
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
                report["raw_report"] = str(native_path.resolve())
                (args.evidence / (key + ".json")).write_text(json.dumps(report, indent=2) + "\n")
                if report["result"] != "pass":
                    raise ValueError("Native checks incomplete: " + repr(report["incomplete_checks"]))
                # Publish portable evidence without workstation paths, test
                # identities, worlds, or raw game/loader binaries.
                reports.append({key: value for key, value in report.items() if key != "raw_report"})
                destination = output_root / artifact.name
                if destination.exists() and digest(destination) != report["artifact_sha256"]:
                    raise ValueError("Conflicting staged artifact: " + destination.name)
                if not destination.exists():
                    shutil.copy2(artifact, destination)
                entry = artifacts.setdefault(identifier, {"id": identifier, "file": str(destination.relative_to(ROOT)),
                    "sha256": report["artifact_sha256"], "java_min": 8 if family in {"plugin", "legacy"} else target["java"],
                    "targets": [], "native_results": []})
                if entry["sha256"] != report["artifact_sha256"]:
                    raise ValueError("One release artifact has different hashes on its target platforms")
                entry["targets"].append({"minecraft": mc, "platform": platform})
                entry["native_results"].append(key + ".json")
            except (OSError, ValueError, KeyError, TypeError) as error:
                errors.append(key + ": " + str(error))
    manifest = {"schema": 1, "name": "Tandemount", "license": "GPL-3.0-only", "source_commit": source_commit,
                "artifacts": sorted(artifacts.values(), key=lambda a: a["id"])}
    result = audit(ROOT, matrix, manifest, args.evidence)
    result["collection_errors"] = errors
    if errors:
        result["result"] = "fail"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n")
    args.output.with_name("audit.json").write_text(json.dumps(result, indent=2) + "\n")
    verification = {"schema": 1, "name": "Tandemount", "source_commit": source_commit,
                    "result": result["result"], "visual_verification": "not-performed",
                    "artifacts": [{key: value for key, value in artifact.items()
                                   if key in {"id", "sha256", "java_min", "targets"}}
                                  for artifact in manifest["artifacts"]],
                    "native_reports": sorted(reports, key=lambda r: (r["minecraft"], r["platform"])),
                    "audit": result}
    args.output.with_name("verification.json").write_text(json.dumps(verification, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return int(result["result"] != "pass")


if __name__ == "__main__":
    raise SystemExit(main())
