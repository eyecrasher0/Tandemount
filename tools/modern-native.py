#!/usr/bin/env python3
"""Run an exact modern artifact in a fresh, local, disposable loader server.

EULA acceptance must be explicit. This is an in-process native fixture with
synthetic ServerPlayers, not a connected-client or human visual confirmation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid
from cache_servers import java_for
from runtime_storage import RuntimeWorkspace

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents" / "cache")) / "modern"


def run(version, platform, accept_eula=False, timeout=240, network_test=False, restart_test=False):
    if not accept_eula:
        raise SystemExit("Disposable Minecraft execution requires explicit --accept-eula (https://aka.ms/MinecraftEULA)")
    target = next(t for t in json.loads((ROOT / "platforms.json").read_text())["targets"] if t["minecraft"] == version)
    distribution = CACHE / "servers" / (platform + "-" + version)
    if not (distribution / "distribution.json").exists():
        raise FileNotFoundError("Prepare official loader distribution first: " + str(distribution))
    metadata = json.loads((distribution / "distribution.json").read_text())
    artifact = ROOT / "mods" / "modern" / "build" / f"tandemount-1.0.0-{platform}-{version}.jar"
    artifact_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()
    # Every run gets its own directory; an earlier world is never silently reused.
    workspace = RuntimeWorkspace(CACHE / "native", platform + "-" + version + "-"
                                 + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6], distribution)
    run_dir = workspace.directory
    (run_dir / "mods").mkdir()
    shutil.copy2(artifact, run_dir / "mods" / artifact.name)
    (run_dir / "eula.txt").write_text("eula=true\n")
    port = 0
    if network_test:
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
    (run_dir / "server.properties").write_text("\n".join(("online-mode=false", "white-list=false", "enforce-whitelist=false", "server-ip=127.0.0.1", "server-port=" + str(port), "enable-query=false", "enable-rcon=false", "view-distance=2", "simulation-distance=2", "max-players=8", "spawn-protection=0", "structures={}", "generate-structures=false", "level-type=flat", "level-seed=0", 'generator-settings={"biome":"minecraft:plains","structures":{},"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]}', "level-name=disposable-fixture", "allow-flight=true", "sync-chunk-writes=false", "pause-when-empty-seconds=-1", "")))
    launcher = ["-jar", "fabric-server-launch.jar"]
    if platform != "fabric":
        args_files = metadata.get("args_files", [])
        if args_files:
            launcher = ["@" + args_files[0]]
        else:
            jars = metadata.get("launcher_jars", [])
            if not jars: raise ValueError("No official FML launcher found")
            launcher = ["-jar", jars[0]]
    command = [os.environ.get("TANDEMOUNT_JAVA") or java_for(target["java"]), "-Xms256M", "-Xmx1G", "-XX:ActiveProcessorCount=2", "-Dtandemount.selftest=true"]
    if network_test:
        command += ["-Dtandemount.networktest=true", "-Dtandemount.test.port=" + str(port)]
    command += [*launcher, "nogui"]
    started = time.monotonic()
    network = {}
    with (run_dir / "console.log").open("w") as log:
        try:
            if network_test:
                process = subprocess.Popen(command, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE, text=True)
                while process.poll() is None and time.monotonic() - started < timeout:
                    if (run_dir / "tandemount-network-ready.json").exists():
                        output = run_dir / "connected-client.json"
                        probe = subprocess.run([os.environ.get("PYTHON", "python3"), str(ROOT / "tools" / "modern-client.py"), "--minecraft", version, "--ids", str(run_dir / "tandemount-protocol.properties"), "--port", str(port), "--output", str(output)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=45)
                        (run_dir / "client.log").write_text(probe.stdout)
                        if output.exists(): network = json.loads(output.read_text())
                        process.communicate("stop\n", timeout=45)
                        break
                    if (run_dir / "tandemount-network-error.txt").exists():
                        process.communicate("stop\n", timeout=45)
                        break
                    time.sleep(0.25)
                if process.poll() is None:
                    process.kill()
                    process.wait()
                    code = "timeout"
                else: code = process.returncode
            else:
                process = subprocess.run(command, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
                code = process.returncode
        except subprocess.TimeoutExpired:
            if network_test:
                process.kill()
                process.wait()
            code = "timeout"
    fixture = run_dir / "tandemount-native-result.json"
    result = {"minecraft": version, "platform": platform, "artifact": str(artifact.relative_to(ROOT)), "sha256": artifact_hash, "run_directory": str(run_dir), "java": target["java"], "seconds": round(time.monotonic()-started, 2), "exit_code": code, "bootstrap": "unverified", "native_fixture": False, "connected_clients": False, "visual": "not-run",
              "server": metadata, "distribution_sha256": hashlib.sha256((distribution / "distribution.json").read_bytes()).hexdigest()}
    if fixture.exists():
        result.update(json.loads(fixture.read_text()))
        result["bootstrap"] = "passed"
    else:
        text = (run_dir / "console.log").read_text(errors="replace")
        if "Done (" in text:
            result["bootstrap"] = "passed"
        result["error"] = "Native fixture result was not produced"
    if network_test:
        result["network_probe"] = network
        result["connected_clients"] = network.get("result") == "pass" and network.get("joined_play", False) and network.get("connection_held", False)
    if restart_test and result.get("native_fixture") and code == 0:
        restart_command = command[:command.index(launcher[0])] + ["-Dtandemount.networktest=false", "-Dtandemount.selftest.restart=true", "-Dtandemount.selftest.result=tandemount-restart-result.json", *launcher, "nogui"]
        with (run_dir / "restart-console.log").open("w") as log:
            try:
                restart_process = subprocess.run(restart_command, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
                result["restart_exit_code"] = restart_process.returncode
            except subprocess.TimeoutExpired:
                result["restart_exit_code"] = "timeout"
        restart_fixture = run_dir / "tandemount-restart-result.json"
        result["restart_fixture"] = json.loads(restart_fixture.read_text()) if restart_fixture.exists() else {"native_fixture": False, "error": "Restart fixture result was not produced"}
        result["restart_passed"] = result["restart_fixture"].get("native_fixture", False) and result["restart_exit_code"] == 0
    evidence = ROOT / ".agents" / "modern-native"
    evidence.mkdir(parents=True, exist_ok=True)
    result = workspace.finish(result)
    (evidence / (platform + "-" + version + ".json")).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", default="1.21.1")
    parser.add_argument("--platform", choices=("fabric", "forge", "neoforge"), default="fabric")
    parser.add_argument("--accept-eula", action="store_true")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--network-test", action="store_true", help="Also connect a real local vanilla protocol client")
    parser.add_argument("--restart-test", action="store_true", help="Reopen the saved disposable world and check for seat artifacts")
    args = parser.parse_args()
    result = run(args.minecraft, args.platform, args.accept_eula, args.timeout, args.network_test, args.restart_test)
    passed = (result.get("native_fixture") is True and result.get("exit_code") == 0
              and result.get("bootstrap") == "passed" and not result.get("error")
              and (not args.network_test or result.get("connected_clients") is True)
              and (not args.restart_test or result.get("restart_passed") is True))
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
