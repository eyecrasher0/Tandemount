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


def assertions(result, scenario):
    """Evaluate actual server positions and ownership, not a fixture PASS label."""
    before = result["beforePortal"]
    checks = {"second-rider-mounted-before-portal": before["rider"]["vehicle"] != -1,
              "native-helper-graph-present-before-portal": before["nativeRideEntries"] == 5,
              "driver-keeps-native-horse-before-portal": before["driver"]["vehicle"] != -1}
    if scenario == "cancel-portal":
        after = result["afterCancelled"]
        checks.update({"cancelled-portal-keeps-source-world": all(after[p]["world"] == before[p]["world"] for p in ("driver", "rider")),
                       "cancelled-portal-preserves-ride": after["nativeRideEntries"] == 5 and after["rider"]["vehicle"] != -1})
        return checks
    after = result["after10Ticks"]
    target = result["nativeExit"]
    target_world = result["nativeExitWorld"]
    checks.update({"native-portal-exit-recorded": target_world != before["driver"]["world"],
                   "driver-arrives-at-native-exit": after["driver"]["world"] == target_world and abs(after["driver"]["xyz"][1] - target[1]) < 2,
                   "driver-retains-native-horse": after["driver"]["vehicle"] != -1,
                   "helpers-cleaned-before-target-cloning": after["nativeRideEntries"] == 0 and len(result["treeAfter10Ticks"]) == 2})
    if scenario == "cancel-passenger":
        checks["cancelled-passenger-teleport-not-forced"] = after["rider"]["world"] == before["rider"]["world"] and after["rider"]["vehicle"] == -1
        checks["cancelled-passenger-has-no-arrival-cooldown"] = result["afterSynchronous"]["rider"]["portalCooldown"] == before["rider"]["portalCooldown"]
    else:
        checks.update({"second-rider-arrives-in-native-target-world": after["rider"]["world"] == target_world,
                       "second-rider-has-no-helper-height-error": abs(after["rider"]["xyz"][1] - target[1] - .8125) < .002,
                       "second-rider-not-at-source-coordinates": abs(after["rider"]["xyz"][0] - target[0]) < 3 and abs(after["rider"]["xyz"][2] - target[2]) < 3,
                       "second-seat-ends-on-world-transfer": after["rider"]["vehicle"] == -1,
                       "second-rider-retains-independent-look": abs((after["rider"]["yaw"] - 135 + 180) % 360 - 180) < .02 and abs(after["rider"]["pitch"] - 25) < .02,
                       "native-arrival-cooldown-applied": result["afterSynchronous"]["rider"]["portalCooldown"] == result["afterSynchronous"]["rider"]["nativeArrivalCooldown"] > 0})
    if scenario == "return":
        retry, back = result["remountState"], result["returnState"]
        checks["second-rider-can-remount-after-portal"] = retry["rider"]["vehicle"] != -1 and retry["nativeRideEntries"] == 5
        checks["return-keeps-correct-world-and-height"] = (back["after10Ticks"]["rider"]["world"] == before["rider"]["world"]
            and abs(back["after10Ticks"]["rider"]["xyz"][1] - back["nativeExit"][1] - .8125) < .002
            and back["after10Ticks"]["nativeRideEntries"] == 0)
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", type=Path, required=True)
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
    source = ROOT / "plugin/src/test/portal/PortalTransferFixture.java"
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
                          "artifact_sha256": digest(args.plugin), "fixture_sha256": digest(fixture), "fixture_source_sha256": digest(source),
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
            raw["returnState"] = {key: back.get(key) for key in ("nativeExit", "nativeExitWorld", "afterSynchronous", "after10Ticks")}
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
