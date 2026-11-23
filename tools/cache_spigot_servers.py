#!/usr/bin/env python3
"""Build exact Spigot SDKs with official BuildTools and verify all pinned refs."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

from cache_servers import ROOT, UA, checksum, java_for
from runtime_storage import sdk_hashes

BUILDTOOLS_URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/201/artifact/target/BuildTools.jar"


def build(cache, tools, target):
    version = target["minecraft"]
    selected = target["platforms"]["spigot"]
    directory = cache / "servers" / "spigot" / version
    directory.mkdir(parents=True, exist_ok=True)
    record_path = directory / "distribution.json"
    server = directory / "spigot.jar"
    if record_path.is_file() and server.is_file():
        record = json.loads(record_path.read_text())
        if (record.get("minecraft") == version and record.get("revision") == selected["revision"]
                and record.get("refs") == selected["refs"] and checksum(server) == record.get("sha256")
                and record.get("launch_files") == sdk_hashes(directory)):
            return {"platform": "spigot", "minecraft": version, "result": "cached", "file": str(server)}
    builder = directory / "builder"
    builder.mkdir(exist_ok=True)
    java = Path(java_for(target["java"]))
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java.parent.parent)
    env["PATH"] = str(java.parent) + os.pathsep + env["PATH"]
    # Metadata `name` is a display build name (e.g. 1573k), not necessarily a
    # resolvable endpoint. The checked source URL identifies the exact revision.
    buildtools_revision = selected["source"].rsplit("/", 1)[1].removesuffix(".json")
    command = [str(java), "-Xmx1G", "-XX:ActiveProcessorCount=2", "-jar", str(tools),
               "--rev", buildtools_revision, "--compile", "SPIGOT"]
    log = directory / "build.log"
    with log.open("wb") as output:
        result = subprocess.run(command, cwd=builder, env=env, stdout=output, stderr=subprocess.STDOUT,
                                timeout=2400)
    if result.returncode:
        raise RuntimeError(f"Spigot {version} BuildTools failed; see {log}")
    actual_refs = {}
    for repository, expected in selected["refs"].items():
        actual = subprocess.check_output(["git", "-C", str(builder / repository), "rev-parse", "HEAD"], text=True).strip()
        if actual != expected:
            raise ValueError(f"Spigot {version} {repository} commit differs from pinned official target")
        actual_refs[repository] = actual
    info = json.loads((builder / "BuildData/info.json").read_text())
    if info["minecraftVersion"] != version:
        raise ValueError(f"BuildTools silently selected {info['minecraftVersion']} instead of {version}")
    candidates = list(builder.glob("spigot-*.jar"))
    if len(candidates) != 1:
        raise RuntimeError(f"Spigot {version}: expected one built server JAR, got {len(candidates)}")
    shutil.copyfile(candidates[0], server)
    record = {"platform": "spigot", "minecraft": version, "revision": selected["revision"],
              "refs": actual_refs, "sha256": checksum(server), "java": target["java"],
              "buildtools_url": BUILDTOOLS_URL, "buildtools_sha256": checksum(tools),
              "file": str(server), "build": "pass", "launch_files": sdk_hashes(directory)}
    record_path.write_text(json.dumps(record, indent=2) + "\n")
    return {"platform": "spigot", "minecraft": version, "result": "built", "file": str(server), "sha256": record["sha256"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents/cache")))
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--jobs", type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 2:
        parser.error("jobs must be 1 or 2")
    args.cache.mkdir(parents=True, exist_ok=True)
    tools = args.cache / "BuildTools-201.jar"
    if not tools.is_file():
        request = urllib.request.Request(BUILDTOOLS_URL, headers={"User-Agent": UA})
        with urllib.request.urlopen(request, timeout=90) as response:
            tools.write_bytes(response.read())
    targets = [t for t in json.loads((ROOT / "platforms.json").read_text())["targets"]
               if t["platforms"]["spigot"]["available"] and (not args.versions or t["minecraft"] in args.versions)]
    failed = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(build, args.cache, tools, target): target["minecraft"] for target in targets}
        for future in as_completed(futures):
            try:
                print(json.dumps(future.result()), flush=True)
            except Exception as error:
                failed.append({"minecraft": futures[future], "error": str(error)})
                print(json.dumps(failed[-1]), file=sys.stderr, flush=True)
    print(json.dumps({"requested": len(targets), "failed": failed}), flush=True)
    return int(bool(failed))


if __name__ == "__main__":
    sys.exit(main())
