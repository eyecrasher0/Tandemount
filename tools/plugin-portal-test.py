#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Reproduce and verify Paper 26.3 native horse portal transfers in isolation.

Requires a prepared exact Paper server cache and its native protocol-ID export.
No existing server, player, plugin configuration or world is contacted.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import zipfile

from cache_servers import java_for
from runtime_storage import RuntimeWorkspace
from vanilla_protocol import VanillaClient, load_ids

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents/cache"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def mounted_graph(snapshot, tree):
    """Check the single horse and the complete helper chain in the fixture."""
    driver, rider = snapshot["driver"], snapshot["rider"]
    by_id = {entity["id"]: entity for entity in tree}
    horse = by_id.get(driver["vehicle"])
    if horse is None or horse["type"] != "HORSE" or horse["vehicle"] != -1 or not horse["valid"]:
        return False

    helpers = []
    seen = {driver["id"], rider["id"], horse["id"]}
    vehicle = rider["vehicle"]
    while vehicle not in seen and vehicle in by_id:
        entity = by_id[vehicle]
        if not entity["valid"] or not entity["noPhysics"] or entity["world"] != horse["world"]:
            return False
        helpers.append(entity["type"])
        seen.add(vehicle)
        vehicle = entity["vehicle"]

    # The two supported graph shapes differ only by a neutral separator.
    expected = ["BLOCK_DISPLAY", "INTERACTION", "LLAMA", "LLAMA", "INTERACTION"]
    separated = [*expected[:3], "INTERACTION", *expected[3:]]
    return (vehicle == horse["id"] and helpers in (expected, separated)
            and snapshot["nativeRideEntries"] == len(helpers)
            and seen == by_id.keys() and len(seen) == len(tree))


def transferred(result, passenger_cancelled=False):
    before = result["beforePortal"]
    start = result["treeBeforePortal"][0]
    after = result["after10Ticks"]
    arrival = result["afterSynchronous"]
    horse = result["treeAfterSynchronous"][0]
    target = result["nativeExit"]
    target_world = result["nativeExitWorld"]
    def at(actual, expected):
        return all(abs(a - b) < .002 for a, b in zip(actual, expected, strict=True))
    def placed(player):
        expected = [target[i] + before[player]["xyz"][i] - start["xyz"][i] for i in range(3)]
        return arrival[player]["world"] == target_world and at(arrival[player]["xyz"], expected)
    checks = {"native-helper-graph-present-before-portal": mounted_graph(before, result["treeBeforePortal"]),
              "native-portal-exit-recorded": target_world != start["world"],
              "horse-arrives-at-native-exit": horse["world"] == target_world and at(horse["xyz"], target),
              "driver-arrives-at-native-exit": placed("driver") and after["driver"]["vehicle"] == horse["id"],
              "helpers-cleaned-before-target-cloning": arrival["nativeRideEntries"] == after["nativeRideEntries"] == 0
              and len(result["treeAfterSynchronous"]) == len(result["treeAfter10Ticks"]) == 2}
    if passenger_cancelled:
        checks["native-passenger-teleport-cancelled"] = any(
            event.get("kind") == "teleport-monitor" and event.get("cause") == "NETHER_PORTAL"
            and event.get("playerId") == before["rider"]["id"] and event.get("cancelled") is True
            for event in result["events"])
        checks["cancelled-passenger-teleport-not-forced"] = after["rider"]["world"] == before["rider"]["world"] and after["rider"]["vehicle"] == -1
        checks["cancelled-passenger-has-no-arrival-cooldown"] = arrival["rider"]["portalCooldown"] == before["rider"]["portalCooldown"]
    else:
        checks.update({"second-rider-arrives-with-pretransfer-pose": placed("rider"),
                       "second-seat-ends-on-world-transfer": after["rider"]["world"] == target_world and after["rider"]["vehicle"] == -1,
                       "second-rider-retains-independent-look": abs((arrival["rider"]["yaw"] - before["rider"]["yaw"] + 180) % 360 - 180) < .02
                       and abs(arrival["rider"]["pitch"] - before["rider"]["pitch"]) < .02,
                       "native-arrival-cooldown-applied": arrival["rider"]["portalCooldown"] == arrival["rider"]["nativeArrivalCooldown"] > 0})
    return checks


def assertions(result, scenario):
    """Evaluate actual server positions, events and ownership."""
    before = result["beforePortal"]
    if scenario == "cancel-portal":
        after = result.get("afterCancelled", result.get("afterSynchronous", {}))
        return {
            "native-helper-graph-present-before-portal": mounted_graph(before, result["treeBeforePortal"]),
            "native-horse-portal-cancelled": any(event.get("kind") == "entity-portal" and event.get("cancelled") is True
                and event["entity"]["id"] == before["driver"]["vehicle"] and event["entity"]["world"] == before["driver"]["world"]
                and event.get("to") not in {None, "null"} for event in result["events"]),
            "cancelled-portal-keeps-source-world": all(after[p]["world"] == before[p]["world"] for p in ("driver", "rider")),
            "cancelled-portal-preserves-ride": after["nativeRideEntries"] == before["nativeRideEntries"]
                and all(after[p]["vehicle"] == before[p]["vehicle"] for p in ("driver", "rider")),
        }
    checks = transferred(result, scenario == "cancel-passenger")
    if scenario == "return":
        retry, back = result["remountState"], result["returnState"]
        checks["second-rider-can-remount-after-portal"] = (retry["driver"]["vehicle"] != -1
            and retry["rider"]["vehicle"] not in (-1, retry["driver"]["vehicle"])
            and retry["nativeRideEntries"] == before["nativeRideEntries"])
        checks.update(("return-" + key, verified) for key, verified in transferred(back).items())
        checks["return-keeps-original-world"] = back["nativeExitWorld"] == before["driver"]["world"]
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--config", type=Path, help="Tandemount YAML to copy into this isolated run")
    parser.add_argument("--protocol-export", type=Path, required=True,
                        help="Exact loaded native protocol.properties from the standard plugin fixture")
    parser.add_argument("--with-portallinked", type=Path)
    parser.add_argument("--scenario", choices=("normal", "return", "cancel-portal", "cancel-passenger"), default="return")
    parser.add_argument("--port", type=int, default=25847)
    parser.add_argument("--accept-eula", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Explicit EULA acceptance is required for disposable Minecraft execution")
    sdk = CACHE / "servers/paper/26.3"
    metadata = json.loads((sdk / "distribution.json").read_text())
    if digest(sdk / metadata["name"]) != metadata["sha256"]:
        raise ValueError("Prepared Paper distribution differs from its recorded checksum")
    java = Path(java_for(25))
    source = ROOT / "bukkit/src/test/portal/PortalTransferFixture.java"
    build = ROOT / ".agents/portal-test-build" / uuid.uuid4().hex
    classes = build / "classes"
    classes.mkdir(parents=True)
    classpath = os.pathsep.join(map(str, [sdk / "versions/26.3/paper-26.3.jar", *sorted((sdk / "libraries").rglob("*.jar"))]))
    with (build / "compile.log").open("w") as log:
        subprocess.run([str(java.with_name("javac")), "-proc:none", "--release", "25", "-cp", classpath,
                        "-d", str(classes), str(source)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    fixture = build / "PortalTransferFixture.jar"
    with zipfile.ZipFile(fixture, "w") as archive:
        archive.writestr("plugin.yml", 'name: PortalDiagnosis\nversion: 1\nmain: dev.eyecrasher.tandemount.diagnosis.PortalTransferFixture\napi-version: "26.3"\nsoftdepend: [Tandemount, PortalLinked]\ncommands:\n  portaldiag: {}\n')
        for path in classes.rglob("*.class"):
            archive.write(path, path.relative_to(classes))
    workspace = RuntimeWorkspace(CACHE / "paper263-portal", args.scenario + "-" + uuid.uuid4().hex[:10], sdk)
    directory = workspace.directory
    (directory / "plugins").mkdir()
    shutil.copy2(args.plugin, directory / "plugins/Tandemount.jar")
    if args.config is not None:
        config = directory / "plugins/Tandemount/config.yml"
        config.parent.mkdir()
        shutil.copy2(args.config, config)
    shutil.copy2(fixture, directory / "plugins/PortalTransferFixture.jar")
    if args.with_portallinked:
        shutil.copy2(args.with_portallinked, directory / "plugins/PortalLinked.jar")
    (directory / "eula.txt").write_text("eula=true\n")
    generator = json.dumps({"layers": [{"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:grass_block", "height": 1}], "biome": "minecraft:plains"})
    (directory / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nwhite-list=false\nenforce-secure-profile=false\nlevel-type=minecraft:flat\ngenerator-settings={generator}\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nallow-flight=true\nnetwork-compression-threshold=-1\npause-when-empty-seconds=-1\ngenerate-structures=false\n")
    (directory / "bukkit.yml").write_text("settings:\n  connection-throttle: 0\n  allow-end: false\nspawn-limits:\n  monsters: 0\n  animals: 0\n")
    log_path = directory / "console.log"
    log = log_path.open("w")
    process = subprocess.Popen([str(java), "-Xms256M", "-Xmx1G", "-XX:ActiveProcessorCount=2", "-jar", metadata["name"], "nogui"],
                               cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
    clients, result = [], {"kind": "native-portal-regression", "status": "failed", "minecraft": "26.3", "platform": "paper",
                          "server": metadata, "native_sdk_sha256": digest(sdk / "versions/26.3/paper-26.3.jar"),
                          "portallinked_sha256": digest(args.with_portallinked) if args.with_portallinked else None,
                          "artifact_sha256": digest(args.plugin),
                          "config_sha256": digest(config) if args.config is not None else None,
                          "fixture_sha256": digest(fixture), "fixture_source_sha256": digest(source),
                          "protocol_export_sha256": digest(args.protocol_export), "scenario": args.scenario, "run_directory": str(directory)}
    state_path = directory / "plugins/PortalDiagnosis/state.json"

    def state():
        try:
            return json.loads(state_path.read_text())
        except (OSError, ValueError):
            return {}

    def wait(phase):
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            current = state()
            if current.get("phase") == "failed" or process.poll() is not None:
                raise RuntimeError("Native fixture failed: " + json.dumps(current))
            if current.get("phase") == phase:
                return current
            time.sleep(.1)
        raise TimeoutError("Native fixture did not reach " + phase)

    def command(value):
        process.stdin.write("portaldiag " + value + "\n")
        process.stdin.flush()

    try:
        wait("ready")
        deadline = time.monotonic() + 150
        while "Done (" not in log_path.read_text(errors="replace"):
            if time.monotonic() > deadline or process.poll() is not None:
                raise TimeoutError("Native startup")
            time.sleep(.1)
        ids = load_ids(args.protocol_export)
        for name in ("PortalDriver", "PortalRider"):
            client = VanillaClient(args.port, "26.3", ids, name=name, timeout=60)
            clients.append(client)
            outcome = client.verify(20, remain=.2)
            if not outcome["joined_play"] or outcome["errors"]:
                raise RuntimeError(str(outcome))
            loaded = client.packet("game", "serverbound", "player_loaded", "ServerboundPlayerLoadedPacket")
            if loaded is not None:
                client.send(loaded)
        time.sleep(.5)
        command("setup")
        wait("mounted")
        if args.scenario.startswith("cancel-"):
            command(args.scenario)
            wait("cancel-set")
        command("look")
        wait("look-set")
        command("travel")
        raw = wait("complete")
        if args.scenario == "return":
            command("retry")
            retry = wait("remounted")
            command("return")
            back = wait("complete")
            raw["remountState"] = retry["retry"]
            raw["returnState"] = back
        result["observations"] = raw
        result["checks"] = assertions(raw, args.scenario)
        if all(result["checks"].values()):
            result["status"] = "passed"
    except Exception as error:
        result["error"] = str(error)
    finally:
        for client in clients:
            client.close()
        if process.poll() is None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=35)
            except Exception:
                process.terminate()
                process.wait(timeout=10)
        log.close()
        result["exit_code"] = process.returncode
        if process.returncode:
            result["status"] = "failed"
        result = workspace.finish(result)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: result.get(key) for key in ("status", "artifact_sha256", "checks", "error")}))
    return int(result["status"] != "passed")


if __name__ == "__main__":
    raise SystemExit(main())
