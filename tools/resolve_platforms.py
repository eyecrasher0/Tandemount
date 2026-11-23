#!/usr/bin/env python3
"""Resolve exact upstream availability; never infer tested compatibility."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = (
    "1.7.10 1.8.9 1.12.2 1.16.5 1.17 1.17.1 1.18 1.18.1 1.18.2 "
    "1.19 1.19.1 1.19.2 1.19.3 1.19.4 1.20 1.20.1 1.20.2 1.20.3 "
    "1.20.4 1.20.5 1.20.6 1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 "
    "1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.1.1 "
    "26.1.2 26.2 26.3"
).split()
PLATFORMS = ("paper", "spigot", "purpur", "folia", "fabric", "forge", "neoforge")
SOURCES = {
    "minecraft": "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
    "fabric": "https://meta.fabricmc.net/v2/versions/game",
    "fabric_loader": "https://meta.fabricmc.net/v2/versions/loader",
    "forge": "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml",
    "neoforge": "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
    "neoforge_legacy": "https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml",
    "paper": "https://fill.papermc.io/v3/projects/paper",
    "folia": "https://fill.papermc.io/v3/projects/folia",
    "purpur": "https://api.purpurmc.org/v2/purpur",
}
USER_AGENT = "Tandemount/1.0 (https://github.com/eyecrasher0)"


def numeric(value):
    return tuple(int(n) for n in re.findall(r"\d+", value))


def java_for(version):
    v = numeric(version)
    if v[0] >= 26:
        return 25
    if v >= (1, 20, 5):
        return 21
    if v >= (1, 18):
        return 17
    if v >= (1, 17):
        return 16
    return 8


class Research:
    def __init__(self, output, builddata):
        self.output = output
        self.builddata = builddata
        output.mkdir(parents=True, exist_ok=True)
        if not builddata.exists():
            subprocess.run([
                "git", "clone", "--bare",
                "https://hub.spigotmc.org/stash/scm/spigot/builddata.git", str(builddata),
            ], check=True)

    def fetch(self, url, kind="json", absent=False):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            if absent and error.code == 404:
                return None
            raise
        name = hashlib.sha256(url.encode()).hexdigest()
        (self.output / (name + ".body")).write_bytes(raw)
        (self.output / (name + ".json")).write_text(json.dumps({
            "url": url, "sha256": hashlib.sha256(raw).hexdigest(),
            "retrieved_at": datetime.now(timezone.utc).isoformat(),
        }, indent=2) + "\n")
        if kind == "xml":
            return [n.text for n in ET.fromstring(raw).findall("./versioning/versions/version")]
        return json.loads(raw)

    def spigot(self, version):
        candidates = [version]
        # Named endpoints are updated to newer patch releases. Historical
        # official revisions still require an exact BuildData version check.
        historical = json.loads((ROOT / "tools/spigot-revisions.json").read_text())
        if version in historical["revisions"]:
            candidates.append(historical["revisions"][version])
        aliases = []
        for candidate in candidates:
            url = f"https://hub.spigotmc.org/versions/{candidate}.json"
            metadata = self.fetch(url, absent=True)
            if metadata is None:
                continue
            ref = metadata["refs"]["BuildData"]
            # The primary host's raw HTTP view can return 403 while its official
            # Git service works. Read the pinned source object, not a mirror.
            raw = subprocess.check_output([
                "git", "--git-dir=" + str(self.builddata), "show", ref + ":info.json",
            ])
            info_url = "https://hub.spigotmc.org/stash/scm/spigot/builddata.git#" + ref + ":info.json"
            (self.output / (ref + "-builddata-info.json")).write_bytes(raw)
            info = json.loads(raw)
            actual = info["minecraftVersion"]
            if actual != version:
                aliases.append({"endpoint": url, "actual_minecraft": actual})
                continue
            return {
                "available": True, "status": "unverified", "revision": metadata["name"],
                "minecraft": actual, "refs": metadata["refs"],
                "source": url, "builddata": info_url,
                "java_class_versions": metadata.get("javaVersions"),
            }
        return {"available": False, "reason": "No official exact-version BuildTools target found.",
                "aliases_rejected": aliases}


def unavailable(reason="No published upstream for this exact Minecraft release."):
    return {"available": False, "reason": reason}


def select_sdk(versions, prefix):
    matches = [v for v in versions if v.startswith(prefix) and "snapshot" not in v]
    stable = [v for v in matches if not re.search(r"(?:alpha|beta|rc|pre)", v)]
    return max(stable or matches, key=numeric, default=None)


def neoforge_prefix(version):
    parts = version.split(".")
    if parts[0] == "26":
        return version + ("." if len(parts) == 3 else ".0.")
    if numeric(version) >= (1, 20, 2):
        return parts[1] + "." + (parts[2] if len(parts) > 2 else "0") + "."
    return None


def render(matrix):
    rows = [
        "# Exact platform availability", "",
        "Upstream availability checked on " + matrix["checked_at"][:10] + ". "
        "Available does not mean implemented or tested. Release evidence is tracked separately.", "",
        "| Minecraft | Java | " + " | ".join(p.title() for p in PLATFORMS) + " |",
        "|---|---|" + "---|" * len(PLATFORMS),
    ]
    for target in matrix["targets"]:
        values = ["available" if target["platforms"][p]["available"] else "—" for p in PLATFORMS]
        rows.append("| " + " | ".join([target["minecraft"], str(target["java"]), *values]) + " |")
    rows += ["", "Official sources:", ""]
    rows += [f"- [{key}]({url})" for key, url in SOURCES.items()]
    rows += ["- [Spigot BuildTools version metadata](https://hub.spigotmc.org/versions/)", ""]
    return "\n".join(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=ROOT / ".agents/research/upstream")
    parser.add_argument("--builddata", type=Path, default=ROOT / ".agents/research/BuildData.git")
    args = parser.parse_args()
    research = Research(args.evidence, args.builddata)
    names = list(SOURCES)
    with ThreadPoolExecutor(max_workers=8) as pool:
        values = list(pool.map(lambda key: research.fetch(
            SOURCES[key], "xml" if key in {"forge", "neoforge", "neoforge_legacy"} else "json"), names))
    metadata = dict(zip(names, values))
    releases = {v["id"]: v for v in metadata["minecraft"]["versions"] if v["type"] == "release"}
    missing = set(VERSIONS) - set(releases)
    if missing:
        raise RuntimeError("Requested releases absent from Mojang manifest: " + str(sorted(missing)))
    with ThreadPoolExecutor(max_workers=8) as pool:
        spigot = dict(zip(VERSIONS, pool.map(research.spigot, VERSIONS)))
    with ThreadPoolExecutor(max_workers=8) as pool:
        game_info = dict(zip(VERSIONS, pool.map(lambda v: research.fetch(releases[v]["url"]), VERSIONS)))
    indexed = {}
    for name in ("paper", "folia"):
        indexed[name] = {v for group in metadata[name]["versions"].values() for v in group}
    indexed["purpur"] = set(metadata["purpur"]["versions"])
    indexed["fabric"] = {v["version"] for v in metadata["fabric"] if v["stable"]}
    loader = next(v["version"] for v in metadata["fabric_loader"] if v["stable"])
    targets = []
    for version in VERSIONS:
        info = game_info[version]
        target = {"minecraft": version, "java": java_for(version), "platforms": {},
                  "minecraft_metadata": releases[version]["url"],
                  "downloads": {k: v for k, v in info["downloads"].items() if k.startswith("server")}}
        for name in ("paper", "folia", "purpur", "fabric"):
            state = {"available": True, "status": "unverified"} if version in indexed[name] else unavailable()
            if state["available"] and name == "fabric":
                state["loader"] = loader
            target["platforms"][name] = state
        target["platforms"]["spigot"] = spigot[version]
        forge_sdk = select_sdk(metadata["forge"], version + "-")
        target["platforms"]["forge"] = ({"available": True, "status": "unverified", "sdk": forge_sdk}
                                         if forge_sdk else unavailable())
        prefix = neoforge_prefix(version)
        neo_sdk = select_sdk(metadata["neoforge"], prefix) if prefix else None
        neo_artifact = "net.neoforged:neoforge"
        if version == "1.20.1":
            neo_sdk = select_sdk(metadata["neoforge_legacy"], version + "-")
            neo_artifact = "net.neoforged:forge"
        target["platforms"]["neoforge"] = ({"available": True, "status": "unverified",
                                                "sdk": neo_sdk, "artifact": neo_artifact}
                                               if neo_sdk else unavailable())
        targets.append(target)
    matrix = {"schema": 1, "checked_at": datetime.now(timezone.utc).isoformat(),
              "scope": "Exact upstream availability; runtime support requires separate evidence.",
              "sources": SOURCES, "targets": targets}
    output = ROOT / "platforms.json"
    output.write_text(json.dumps(matrix, indent=2) + "\n")
    (ROOT / "BACKPORTS.md").write_text(render(matrix))
    print(json.dumps({"versions": len(targets), "upstreams": {
        name: sum(t["platforms"][name]["available"] for t in targets) for name in PLATFORMS},
        "output": str(output)}))


if __name__ == "__main__":
    main()
