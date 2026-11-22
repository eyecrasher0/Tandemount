#!/usr/bin/env python3
"""Build and verify exact platform targets with resumable, hash-bound results."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading

from cache_servers import ROOT
from project_metadata import artifact_name


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def plugin_runner_helpers():
    names = ("plugin-owner-regression.py", "plugin_protocol.py", "client_lifetime.py")
    return ([ROOT / "tools" / name for name in names if (ROOT / "tools" / name).is_file()]
            + [path for path in (ROOT / "plugin/src/test/client").rglob("*") if path.is_file()])


def modern_runner_helpers():
    names = ("modern-wire.py", "modern-client-components.py")
    return ([ROOT / "tools" / name for name in names if (ROOT / "tools" / name).is_file()]
            + [path for path in (ROOT / "mods/modern/src/test/client").rglob("*") if path.is_file()])


def attachment_runner_helpers():
    names = ("client_attachment.py", "attachment_evidence.py", "modern-build.py", "cache_servers.py")
    return ([ROOT / "tools" / name for name in names if (ROOT / "tools" / name).is_file()]
            + [path for path in (ROOT / "tests/client").rglob("*") if path.is_file()])


def sources(mode):
    folder = ROOT / ("mods/modern/src" if mode == "modern" else "plugin/src")
    files = [p for p in folder.rglob("*") if p.is_file()]
    names = (["modern-build.py", "modern-native.py", "modern-client.py", "vanilla_protocol.py", "runtime_storage.py"]
             if mode == "modern" else ["plugin-build.py", "plugin-runtime.py", "vanilla_protocol.py", "runtime_storage.py"])
    files += [ROOT / "tools" / name for name in names]
    if mode == "plugins":
        files += plugin_runner_helpers()
    else:
        files += modern_runner_helpers()
    files += attachment_runner_helpers()
    files += [ROOT / "platforms.json", ROOT / "project.json", ROOT / "LICENSE",
              ROOT / "tools/project_metadata.py"]
    hasher = hashlib.sha256()
    for path in sorted(set(files)):
        hasher.update(path.relative_to(ROOT).as_posix().encode() + b"\0")
        hasher.update(path.read_bytes())
        hasher.update(b"\0")
    return hasher.hexdigest()


def runner_sources(mode):
    names = (["modern-native.py", "modern-client.py", "vanilla_protocol.py", "runtime_storage.py", "cache_servers.py"]
             if mode == "modern" else ["plugin-runtime.py", "vanilla_protocol.py", "runtime_storage.py"])
    files = [ROOT / "tools" / name for name in names] + [
        ROOT / "platforms.json", ROOT / "project.json", ROOT / "tools/project_metadata.py"]
    if mode == "plugins":
        files += plugin_runner_helpers()
    else:
        files += modern_runner_helpers()
    files += attachment_runner_helpers()
    hasher = hashlib.sha256()
    for path in sorted(set(files)):
        hasher.update(path.relative_to(ROOT).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    return hasher.hexdigest()


def matching_native(previous, artifact, native_path, fixture=None):
    if (previous.get("result") != "pass" or not artifact.is_file() or not native_path.is_file()
            or previous.get("artifact_sha256") != sha(artifact)
            or previous.get("native_report_sha256") != sha(native_path)):
        return False
    native = json.loads(native_path.read_text())
    if native.get("artifact_sha256", native.get("sha256")) != previous["artifact_sha256"]:
        return False
    if fixture is not None and (not fixture.is_file() or native.get("fixture_sha256") != sha(fixture)):
        return False
    return True


def rebuilt_cache(previous, artifact, native_path, fingerprint, runner_fingerprint, fixture=None):
    """Reuse execution only after a rebuild produced the same exact bytes.

    A build-only compatibility fix for another target can change source inputs
    while leaving this target's JAR unchanged. The real native report, runner
    inputs, and any separate fixture must all still match independently.
    """
    if previous.get("runner_fingerprint") != runner_fingerprint or not matching_native(previous, artifact, native_path, fixture):
        return None
    return {**previous, "source_fingerprint": fingerprint,
            "previous_source_fingerprint": previous["source_fingerprint"],
            "cached": True, "native_reused_after_identical_rebuild": True}


def same_source_cache(previous, artifact, native_path, fingerprint, runner_fingerprint, fixture=None):
    if (previous.get("source_fingerprint") == fingerprint
            and previous.get("runner_fingerprint") == runner_fingerprint
            and matching_native(previous, artifact, native_path, fixture)):
        return {**previous, "cached": True}
    return None


def run_process(command, log, env, timeout):
    with log.open("wb") as output:
        try:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=timeout)
            return result.returncode
        except subprocess.TimeoutExpired:
            return "timeout"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["modern", "plugins"], required=True)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--platforms", nargs="+")
    parser.add_argument("--exclude", action="append", default=[], help="Skip platform@minecraft reserved by another worker")
    parser.add_argument("--accept-eula", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("--accept-eula is required for disposable local Minecraft tests")
    if not 1 <= args.jobs <= 4:
        parser.error("jobs must be between 1 and 4")
    output = args.output or ROOT / ".agents" / ("matrix-" + args.mode)
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    if args.cache:
        env["TANDEMOUNT_CACHE"] = str(args.cache.resolve())
    matrix = json.loads((ROOT / "platforms.json").read_text())
    platforms = ["fabric", "forge", "neoforge"] if args.mode == "modern" else ["paper", "spigot", "purpur", "folia"]
    if args.platforms:
        if set(args.platforms) - set(platforms):
            parser.error("Platforms do not belong to the selected mode")
        platforms = args.platforms
    targets = [(t["minecraft"], p) for t in matrix["targets"]
               if (not args.versions or t["minecraft"] in args.versions)
               and (args.mode != "modern" or t["minecraft"] not in {"1.7.10", "1.8.9", "1.12.2"})
               for p in platforms if t["platforms"][p]["available"]
               and p + "@" + t["minecraft"] not in args.exclude]
    fingerprint = sources(args.mode)
    if args.mode == "plugins":
        command = [sys.executable, "tools/plugin-build.py", "--all", "--fixture"]
        code = run_process(command, output / "build-all.log", env, args.timeout)
        if code != 0:
            raise SystemExit("Plugin build failed; see " + str(output / "build-all.log"))
    lock = threading.Lock()
    records = []

    def one(item, slot):
        target_fingerprint = sources(args.mode)
        runner_fingerprint = runner_sources(args.mode)
        version, platform = item
        key = platform + "-" + version
        record_path = output / (key + ".json")
        if args.mode == "modern":
            artifact = ROOT / "mods/modern/build" / artifact_name(platform, version)
            native_path = ROOT / ".agents/modern-native" / (key + ".json")
            build_record = ROOT / ".agents/modern-build" / key / "artifact.json"
            previous_fixture = json.loads(build_record.read_text()).get("fixture") if build_record.is_file() else None
            fixture = ROOT / previous_fixture if previous_fixture else None
        else:
            artifact = ROOT / ".agents/plugin-build" / artifact_name("bukkit", version)
            native_path = ROOT / ".agents/plugin-evidence" / (key + ".json")
            fixture = ROOT / ".agents/plugin-build/tandemount-1.0.0-fixture.jar"
        previous = None
        if args.resume and record_path.is_file() and artifact.is_file() and fixture is not None:
            previous = json.loads(record_path.read_text())
            cached = same_source_cache(previous, artifact, native_path, target_fingerprint, runner_fingerprint, fixture)
            if cached is not None:
                record_path.write_text(json.dumps(cached, indent=2) + "\n")
                return cached
        result = {"minecraft": version, "platform": platform, "source_fingerprint": target_fingerprint,
                  "runner_fingerprint": runner_fingerprint,
                  "artifact": str(artifact.relative_to(ROOT)), "result": "fail"}
        if args.mode == "modern":
            code = run_process([sys.executable, "tools/modern-build.py", "--minecraft", version, "--platform", platform],
                               output / (key + "-build.log"), env, args.timeout)
            if code != 0:
                result["build_exit_code"] = code
                record_path.write_text(json.dumps(result, indent=2) + "\n")
                return result
            fixture = ROOT / json.loads(build_record.read_text())["fixture"]
        if previous and sources(args.mode) == target_fingerprint:
            cached = rebuilt_cache(previous, artifact, native_path, target_fingerprint, runner_fingerprint, fixture)
            if cached is not None:
                record_path.write_text(json.dumps(cached, indent=2) + "\n")
                return cached
        result["artifact_sha256"] = sha(artifact)
        if args.mode == "modern":
            command = [sys.executable, "tools/modern-native.py", "--minecraft", version, "--platform", platform,
                       "--accept-eula", "--network-test", "--restart-test", "--timeout", str(args.timeout)]
        else:
            command = [sys.executable, "tools/plugin-runtime.py", "--minecraft", version, "--platform", platform,
                       "--accept-eula", "--port", str(27700 + slot), "--timeout", str(args.timeout)]
        code = run_process(command, output / (key + "-native.log"), env, args.timeout * 2 + 45)
        result["native_exit_code"] = code
        if native_path.is_file():
            native = json.loads(native_path.read_text())
            result["native_report"] = str(native_path.relative_to(ROOT))
            result["native_report_sha256"] = sha(native_path)
            reported_hash = native.get("artifact_sha256", native.get("sha256"))
            result["artifact_hash_matches"] = reported_hash == result["artifact_sha256"]
            if args.mode == "modern":
                passed = (native.get("native_fixture") is True and native.get("connected_clients") is True
                          and native.get("restart_passed") is True and native.get("bootstrap") == "passed")
            else:
                passed = native.get("status") == "passed" and bool(native.get("assertions"))
            result["result"] = "pass" if passed and code == 0 and result["artifact_hash_matches"] else "fail"
        if sources(args.mode) != target_fingerprint:
            result["result"] = "stale"
            result["error"] = "Implementation or runner source changed after the matrix started"
        record_path.write_text(json.dumps(result, indent=2) + "\n")
        return result

    # Different loader builds for one game version share generated mapping
    # caches. Keep each game's loader targets sequential in the same worker.
    grouped = {}
    for target in targets:
        grouped.setdefault(target[0], []).append(target)
    task_queue = list(grouped.values())
    counter = iter(range(len(task_queue)))

    def worker(slot):
        while True:
            with lock:
                index = next(counter, None)
            if index is None:
                return
            for target in task_queue[index]:
                try:
                    record = one(target, slot)
                except Exception as error:
                    record = {"minecraft": target[0], "platform": target[1], "result": "fail", "error": str(error)}
                with lock:
                    records.append(record)
                    (output / "summary.json").write_text(json.dumps({
                        "mode": args.mode, "source_fingerprint": fingerprint, "requested": len(targets),
                        "completed": len(records), "passed": sum(r["result"] == "pass" for r in records),
                        "records": records}, indent=2) + "\n")
                    print(json.dumps(record), flush=True)

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for future in as_completed([pool.submit(worker, slot) for slot in range(args.jobs)]):
            future.result()
    failed = [r for r in records if r["result"] != "pass"]
    print(json.dumps({"requested": len(targets), "passed": len(records)-len(failed), "failed": failed}), flush=True)
    return int(bool(failed))


if __name__ == "__main__":
    sys.exit(main())
