#!/usr/bin/env python3
"""Cache exact official plugin server distributions without starting a world."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from runtime_storage import sdk_hashes

ROOT = Path(__file__).resolve().parents[1]
UA = "Tandemount/1.0 (https://github.com/eyecrasher0/Tandemount)"


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.load(response)


def checksum(path, algorithm="sha256"):
    h = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def distribution(platform, version):
    if platform in {"paper", "folia"}:
        url = f"https://fill.papermc.io/v3/projects/{platform}/versions/{version}/builds"
        builds = fetch_json(url)
        valid = [b for b in builds if "server:default" in b.get("downloads", {})]
        preferred = [b for b in valid if b.get("channel") in {"STABLE", "RECOMMENDED"}]
        chosen = max(preferred or valid, key=lambda b: b["id"])
        item = chosen["downloads"]["server:default"]
        return {"platform": platform, "minecraft": version, "build": str(chosen["id"]),
                "channel": chosen["channel"], "name": item["name"], "url": item["url"],
                "checksums": item["checksums"], "metadata_url": url}
    if platform == "purpur":
        url = f"https://api.purpurmc.org/v2/purpur/{version}"
        build = str(fetch_json(url)["builds"]["latest"])
        metadata_url = url + "/" + build
        metadata = fetch_json(metadata_url)
        return {"platform": platform, "minecraft": version, "build": build,
                "channel": "upstream", "name": f"purpur-{version}-{build}.jar",
                "url": metadata_url + "/download", "metadata_url": metadata_url,
                "checksums": {"md5": metadata["md5"]}}
    raise ValueError("Unsupported distribution: " + platform)


def download(item, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    def matches():
        return destination.is_file() and all(
            checksum(destination, algorithm) == value for algorithm, value in item["checksums"].items())
    if not matches():
        temporary = destination.with_suffix(".download")
        request = urllib.request.Request(item["url"], headers={"User-Agent": UA})
        with urllib.request.urlopen(request, timeout=180) as response, temporary.open("wb") as target:
            while chunk := response.read(1024 * 1024):
                target.write(chunk)
        for algorithm, expected in item["checksums"].items():
            if checksum(temporary, algorithm) != expected:
                raise ValueError("Official download checksum mismatch: " + str(destination))
        temporary.replace(destination)
    return checksum(destination)


def java_for(major):
    configured = (os.environ.get(f"TANDEMOUNT_JAVA_{major}") or os.environ.get(f"JAVA{major}_HOME")
                  or os.environ.get(f"JAVA_HOME_{major}_X64") or os.environ.get(f"JAVA_HOME_{major}_ARM64"))
    local = ROOT / ".agents/toolchains.json"
    if not configured and local.is_file():
        configured = json.loads(local.read_text()).get(str(major))
    if configured:
        base = Path(configured)
        executable = base / ("bin/java.exe" if os.name == "nt" else "bin/java") if base.is_dir() else base
        if not executable.is_file():
            raise ValueError(f"Configured Java {major} executable does not exist")
        return str(executable)
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(str(Path(os.environ["JAVA_HOME"]) / ("bin/java.exe" if os.name == "nt" else "bin/java")))
    if shutil.which("java"):
        candidates.append(shutil.which("java"))
    for candidate in candidates:
        result = subprocess.run([candidate, "-version"], capture_output=True, text=True, timeout=15)
        match = re.search(r'(?:version\s+)?"?(\d+)(?:\.(\d+))?', result.stderr or result.stdout)
        if result.returncode == 0 and match:
            found = int(match[2]) if match[1] == "1" else int(match[1])
            if found == major:
                return candidate
    raise ValueError(f"Set TANDEMOUNT_JAVA_{major} or JAVA{major}_HOME to the required JDK")


def obtain(cache, platform, target, bootstrap):
    version = target["minecraft"]
    directory = cache / "servers" / platform / version
    directory.mkdir(parents=True, exist_ok=True)
    record = directory / "distribution.json"
    item = json.loads(record.read_text()) if record.is_file() else distribution(platform, version)
    server = directory / item["name"]
    item["sha256"] = download(item, server)
    if "launch_files" in item and item["launch_files"] != sdk_hashes(directory):
        raise ValueError("Cached Bukkit SDK files differ from their bootstrap record")
    item["file"] = str(server)
    item["java"] = target["java"]
    if bootstrap:
        command = [java_for(target["java"]), "-Xmx1G", "-Dpaperclip.patchonly=true", "-jar", str(server)]
        log = directory / "bootstrap.log"
        with log.open("wb") as output:
            result = subprocess.run(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT,
                                    timeout=360)
        if result.returncode:
            item["bootstrap"] = "failed"
            record.write_text(json.dumps(item, indent=2) + "\n")
            raise RuntimeError(f"Bootstrap failed: {platform}/{version}; see {log}")
        item["bootstrap"] = "pass"
    item["launch_files"] = sdk_hashes(directory)
    record.write_text(json.dumps(item, indent=2) + "\n")
    return {"platform": platform, "minecraft": version, "sha256": item["sha256"],
            "file": str(server), "bootstrap": item.get("bootstrap", "not-run")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents/cache")))
    parser.add_argument("--platforms", nargs="+", choices=["paper", "purpur", "folia"], default=["paper", "purpur", "folia"])
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--jobs", type=int, default=3)
    parser.add_argument("--bootstrap", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.jobs <= 8:
        parser.error("jobs must be between 1 and 8")
    targets = json.loads((ROOT / "platforms.json").read_text())["targets"]
    selected = [(p, t) for t in targets if not args.versions or t["minecraft"] in args.versions
                for p in args.platforms if t["platforms"][p]["available"]]
    failed = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(obtain, args.cache, p, t, args.bootstrap): (p, t["minecraft"])
                   for p, t in selected}
        for future in as_completed(futures):
            try:
                print(json.dumps(future.result()), flush=True)
            except Exception as error:
                pair = futures[future]
                failed.append({"platform": pair[0], "minecraft": pair[1], "error": str(error)})
                print(json.dumps(failed[-1]), file=sys.stderr, flush=True)
    print(json.dumps({"requested": len(selected), "failed": failed}), flush=True)
    return int(bool(failed))


if __name__ == "__main__":
    sys.exit(main())
