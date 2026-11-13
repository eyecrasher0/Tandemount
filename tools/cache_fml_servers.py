#!/usr/bin/env python3
"""Install exact official Forge and NeoForge server SDKs into the task cache."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile

from cache_servers import ROOT, UA, checksum, java_for


def get_bytes(url):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code != 403:
            raise
        # Both clients use the same official HTTPS endpoint and verification.
        return subprocess.check_output(["curl", "-fsSL", "--max-time", "180", "-A", UA, url])


def install(cache, platform, target):
    version = target["minecraft"]
    selected = target["platforms"][platform]
    sdk = selected["sdk"]
    if platform == "forge":
        repository, group, artifact = "https://maven.minecraftforge.net", "net/minecraftforge", "forge"
    elif selected["artifact"] == "net.neoforged:forge":
        repository, group, artifact = "https://maven.neoforged.net/releases", "net/neoforged", "forge"
    else:
        repository, group, artifact = "https://maven.neoforged.net/releases", "net/neoforged", "neoforge"
    url = f"{repository}/{group}/{artifact}/{sdk}/{artifact}-{sdk}-installer.jar"
    directory = cache / "modern/servers" / (platform + "-" + version)
    directory.mkdir(parents=True, exist_ok=True)
    record_path = directory / "distribution.json"
    if record_path.is_file():
        record = json.loads(record_path.read_text())
        if record.get("sdk") == sdk and record.get("installation") == "pass":
            return {"minecraft": version, "platform": platform, "result": "cached"}
    installer = directory / (artifact + "-" + sdk + "-installer.jar")
    if not installer.is_file():
        installer.write_bytes(get_bytes(url))
    expected = get_bytes(url + ".sha1").decode().strip().split()[0]
    if checksum(installer, "sha1") != expected:
        raise ValueError(f"Official {platform} {version} installer SHA1 mismatch")
    with zipfile.ZipFile(installer) as archive:
        profile = json.loads(archive.read("install_profile.json"))
        actual = profile.get("minecraft") or profile.get("install", {}).get("minecraft")
        if actual and actual != version:
            raise ValueError(f"Installer Minecraft {actual} differs from requested {version}")
    # This older installer pins processor ZIP hashes produced with JDK 8's
    # deflater. JDK 16 produces valid contents with different compressed bytes,
    # which its integrity check correctly rejects. Run only the installer on 8;
    # Minecraft 1.17.1 itself still runs on its required Java 16.
    installer_java = 8 if platform == "forge" and version == "1.17.1" else target["java"]
    command = [java_for(installer_java), "-Xmx1G", "-XX:ActiveProcessorCount=2", "-jar", str(installer), "--installServer", str(directory)]
    log = directory / "installation.log"
    with log.open("wb") as output:
        result = subprocess.run(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT, timeout=900)
    if result.returncode:
        raise RuntimeError(f"{platform}/{version} installation failed; see {log}")
    args_files = list((directory / "libraries" / group / artifact).glob("*/unix_args.txt"))
    launch_jars = [p for p in directory.glob("*.jar") if p != installer and not p.name.startswith("minecraft_server")]
    if not args_files and not launch_jars:
        raise RuntimeError(f"No usable server launcher installed by {platform}/{version}")
    record = {"platform": platform, "minecraft": version, "sdk": sdk, "installer_url": url,
              "installer_sha256": checksum(installer), "installer_sha1": expected, "java": target["java"],
              "installation": "pass", "installer_java": installer_java,
              "args_files": [str(p.relative_to(directory)) for p in args_files],
              "launcher_jars": [p.name for p in launch_jars]}
    record_path.write_text(json.dumps(record, indent=2) + "\n")
    return {"minecraft": version, "platform": platform, "result": "installed", "directory": str(directory)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents/cache")))
    parser.add_argument("--platforms", nargs="+", choices=["forge", "neoforge"], default=["forge", "neoforge"])
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--jobs", type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 2:
        parser.error("jobs must be 1 or 2")
    targets = [(p, t) for t in json.loads((ROOT / "platforms.json").read_text())["targets"]
               if t["java"] >= 8 and t["minecraft"] not in {"1.7.10", "1.8.9", "1.12.2"}
               and (not args.versions or t["minecraft"] in args.versions)
               for p in args.platforms if t["platforms"][p]["available"]]
    failed = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(install, args.cache, p, t): (p, t["minecraft"]) for p, t in targets}
        for future in as_completed(futures):
            try:
                print(json.dumps(future.result()), flush=True)
            except Exception as error:
                platform, version = futures[future]
                failed.append({"platform": platform, "minecraft": version, "error": str(error)})
                print(json.dumps(failed[-1]), file=sys.stderr, flush=True)
    print(json.dumps({"requested": len(targets), "failed": failed}), flush=True)
    return int(bool(failed))


if __name__ == "__main__":
    sys.exit(main())
