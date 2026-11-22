#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Replay a Paper 26.3 motion capture from each rider's connection.

Executes original client entity components in a controlled empty Level. Driver
root positions/yaw are supplied from recorded outgoing controls. This does not
construct LocalPlayer, render graphics, or establish a human visual PASS.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import zipfile

from cache_servers import java_for
from vanilla_protocol import load_ids

ROOT = Path(__file__).resolve().parents[1]
CLIENT_SHA256 = "4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d"
PACKETS = frozenset({"add_entity", "entity_position_sync", "teleport_entity",
    "move_entity_pos", "move_entity_pos_rot", "move_entity_rot", "set_entity_data",
    "set_entity_motion", "rotate_head", "set_passengers", "update_attributes"})


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-report", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--client-classpath", required=True,
        help="Official exact 26.3 client JAR and libraries, separated by the OS path separator")
    parser.add_argument("--transport-report", type=Path,
        help="Optional retained loopback-proxy timing report")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    native = json.loads(args.native_report.read_text())
    artifact_hash = digest(args.artifact)
    if native.get("artifact_sha256") != artifact_hash:
        parser.error("Native capture names a different runtime artifact")
    if native.get("server", {}).get("minecraft") != "26.3":
        parser.error("This probe requires an exact Minecraft 26.3 capture")
    trajectory = native["trajectory"]
    controls = trajectory["predicted_controls"]
    if len(controls) < 60 or any(b["at_ns"] <= a["at_ns"] for a, b in zip(controls, controls[1:])):
        parser.error("Capture needs at least 60 strictly ordered driver controls")
    classpath = [Path(p).expanduser().resolve() for p in args.client_classpath.split(os.pathsep)]
    if not classpath or any(not p.is_file() for p in classpath):
        parser.error("Every official client classpath entry must be a regular JAR")
    if digest(classpath[0]) != CLIENT_SHA256:
        parser.error("This regression is pinned to the recorded official Minecraft 26.3 client bytes")
    with zipfile.ZipFile(classpath[0]) as jar:
        if "net/minecraft/client/player/LocalPlayer.class" not in jar.namelist():
            parser.error("Classpath must start with the official unobfuscated 26.3 client")
    java = Path(java_for(25))
    folder = ROOT / ".agents/perspective-probes" / uuid.uuid4().hex
    classes = folder / "classes"
    classes.mkdir(parents=True)
    sources = [ROOT / "plugin/src/test/client" / (name + ".java") for name in
        ("OfficialClientSeatProbe", "ClientSeatMotionProbe", "PoseAttachmentProbe", "ClientDriverLatencyReplay")]
    source_hashes = {str(p.relative_to(ROOT)): digest(p) for p in sources}
    with (folder / "compile.log").open("w") as log:
        subprocess.run([str(java.with_name("javac")), "-proc:none", "--release", "25",
            "-cp", args.client_classpath, "-d", str(classes), *map(str, sources)],
            stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    control_file = folder / "controls.tsv"
    control_file.write_text("".join(str(c["at_ns"]) + "\t" + "\t".join(map(str,
        [*c["position"], c["yaw"]])) + "\n" for c in controls))
    run = Path(native["run"])
    protocol_file = run / "plugins/TandemountNativeFixture/protocol.properties"
    identifiers = {value: key.split("minecraft:")[-1] for key, value in load_ids(protocol_file).items()
        if key.startswith("game.clientbound.minecraft:") and key.split("minecraft:")[-1] in PACKETS}
    probes, inputs = [], {}
    for role, name in (("driver", "TmDriver"), ("passenger", "TmRider"), ("observer", "TmThird")):
        packet_file = run / (name + "-packets.json")
        packets = json.loads(packet_file.read_text())
        if len(packets["packets"]) != len(packets["received_monotonic_ns"]):
            raise ValueError("Packet timestamp count differs: " + role)
        frames = folder / (role + "-frames.tsv")
        frames.write_text("".join(f"{at}\t{identifiers[identifier]}\t{body}\n"
            for (stage, identifier, body), at in zip(packets["packets"], packets["received_monotonic_ns"])
            if stage == "game" and identifier in identifiers and at <= trajectory["end_monotonic_ns"]))
        inputs[role] = {"packets_sha256": digest(packet_file), "frames_sha256": digest(frames)}
        for phase in (0, 25_000_000):
            directory = folder / (role + "-" + str(phase))
            directory.mkdir()
            command = [str(java), "-Xmx1G", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                "-cp", str(classes) + os.pathsep + os.pathsep.join(map(str, classpath)), "ClientDriverLatencyReplay",
                str(frames), str(trajectory["horse_id"]), str(trajectory["seat_id"]), str(trajectory["rider_id"]),
                str(trajectory["driver_id"]), str(trajectory["start_monotonic_ns"]), str(trajectory["end_monotonic_ns"]),
                str(phase), str(trajectory["expected_rider_vertical"]), str(trajectory["expected_rearward"]), role, str(control_file)]
            result = subprocess.run(command, cwd=directory, capture_output=True, text=True, timeout=120)
            (directory / "run.log").write_text(result.stdout + result.stderr)
            row = next((line.split("CLIENT_DRIVER_LATENCY_REPLAY ", 1)[1] for line in result.stdout.splitlines()
                if "CLIENT_DRIVER_LATENCY_REPLAY " in line), None)
            if result.returncode or row is None:
                raise RuntimeError("Original client probe failed: " + str(directory / "run.log"))
            observation = json.loads(row)
            raw = directory / "result.json"
            raw.write_text(json.dumps(observation, indent=2) + "\n")
            checks = {"native-horse-root-retained": observation["rootFailures"] == 0,
                "motion-window-executed": observation["ticks"] >= 60,
                "relative-position-error-within-one-sixteenth": observation["maxError"] <= 1 / 16,
                "no-helper-interaction-ray-hits": observation["helperRayHits"] == 0}
            probes.append({"role": role, "phase_ns": phase, "checks": checks,
                "maximum_relative_error": observation["maxError"], "mean_relative_error": observation["meanError"],
                "raw_report": str(raw), "raw_report_sha256": digest(raw)})
    if digest(args.artifact) != artifact_hash or source_hashes != {str(p.relative_to(ROOT)): digest(p) for p in sources}:
        raise ValueError("Runtime artifact or probe sources changed during execution")
    report = {"kind": "original-client-perspective-components", "minecraft": "26.3",
        "artifact_sha256": artifact_hash, "native_report_sha256": digest(args.native_report),
        "native_protocol_export_sha256": digest(protocol_file), "controls_sha256": digest(control_file),
        "sources": source_hashes,
        "client_classpath": [{"file": str(p), "sha256": digest(p)} for p in classpath],
        "inputs": inputs, "probes": probes, "local_player_constructed": False, "rendered_client_verified": False,
        "limits": ["Driver root motion is injected from outgoing control observations; no original LocalPlayer prediction executed.",
            "Player geometry uses original Mannequin entities; no real camera, controller, player renderer or human visual verification.",
            "Measured transport delay is a separate input when supplied; this replay does not simulate transport."],
        "status": "passed" if all(all(p["checks"].values()) for p in probes) else "failed"}
    if args.transport_report:
        report["transport_report_sha256"] = digest(args.transport_report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "artifact_sha256": artifact_hash,
        "probes": [{k: p[k] for k in ("role", "phase_ns", "maximum_relative_error", "checks")} for p in probes]}))
    return int(report["status"] != "passed")


if __name__ == "__main__":
    sys.exit(main())
