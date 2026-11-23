#!/usr/bin/env python3
"""Install official Fabric launchers for exact game versions, without starting worlds."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

from cache_servers import ROOT, UA, checksum, fetch_json, java_for
from runtime_storage import sdk_hashes, server_identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents/cache")))
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 4:
        parser.error("jobs must be between 1 and 4")
    metadata = fetch_json("https://meta.fabricmc.net/v2/versions/installer")
    selected = next(item for item in metadata if item["stable"])
    cache = args.cache / "modern"
    cache.mkdir(parents=True, exist_ok=True)
    installer = cache / ("fabric-installer-" + selected["version"] + ".jar")
    if not installer.is_file():
        request = urllib.request.Request(selected["url"], headers={"User-Agent": UA})
        with urllib.request.urlopen(request, timeout=90) as response:
            installer.write_bytes(response.read())
    # Fabric Maven publishes the digest beside the installer.
    with urllib.request.urlopen(urllib.request.Request(selected["url"] + ".sha1", headers={"User-Agent": UA}), timeout=30) as response:
        expected = response.read().decode().strip().split()[0]
    if checksum(installer, "sha1") != expected:
        raise ValueError("Official Fabric installer SHA1 mismatch")
    matrix = json.loads((ROOT / "platforms.json").read_text())
    targets = [target for target in matrix["targets"] if target["platforms"]["fabric"]["available"]
               and (not args.versions or target["minecraft"] in args.versions)]

    def install(target):
        version = target["minecraft"]
        loader = target["platforms"]["fabric"]["loader"]
        directory = cache / "servers" / ("fabric-" + version)
        directory.mkdir(parents=True, exist_ok=True)
        record_path = directory / "distribution.json"
        server = directory / "server.jar"
        if server.is_file() and record_path.is_file():
            record = json.loads(record_path.read_text())
            try:
                server_identity(target, "fabric", record, directory)
            except (KeyError, OSError, ValueError):
                pass
            else:
                return {"minecraft": version, "result": "cached", "directory": str(directory)}
        command = [java_for(17), "-Xmx512M", "-jar", str(installer), "server", "-mcversion", version,
                   "-loader", loader, "-dir", str(directory), "-downloadMinecraft"]
        log = directory / "installation.log"
        with log.open("wb") as output:
            process = subprocess.run(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT, timeout=360)
        if process.returncode or not server.is_file() or not (directory / "fabric-server-launch.jar").is_file():
            raise RuntimeError(f"Fabric {version} installation failed; see {log}")
        expected_server = target["downloads"]["server"]["sha1"]
        if checksum(server, "sha1") != expected_server:
            raise ValueError(f"Fabric {version} downloaded an unexpected vanilla server")
        record = {"minecraft": version, "platform": "fabric", "loader": loader,
                  "installer": selected["version"], "installer_sha256": checksum(installer),
                  "vanilla_sha1": expected_server, "vanilla_sha256": checksum(server),
                  "installation": "pass", "java": target["java"], "launch_files": sdk_hashes(directory)}
        record_path.write_text(json.dumps(record, indent=2) + "\n")
        return {"minecraft": version, "result": "installed", "directory": str(directory)}

    failed = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(install, target): target["minecraft"] for target in targets}
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
