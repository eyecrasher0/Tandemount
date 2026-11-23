#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Compile each legacy server mod against its real, exact upstream Forge API.

No game binary, stub, or another mod is part of the distributable. Native calls
are resolved from an exact-version MCP symbol subset at runtime.
"""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile
from project_metadata import metadata, artifact_name

ROOT = Path(__file__).resolve().parents[1]
SDK = {"1.7.10": "1.7.10-10.13.4.1614-1.7.10", "1.8.9": "1.8.9-11.15.1.2318-1.8.9", "1.12.2": "1.12.2-14.23.5.2864"}
MAPPINGS = {"1.7.10": "12-1.7.10", "1.8.9": "22-1.8.9", "1.12.2": "39-1.12"}
MAPPING_HASHES = {
    "1.7.10": ("83d303a80071582cd07d41b35aa77c178d609a27c69c8472b794636eb50905b0",
               "d49df5c445dcf00ada532ab3b0e4283bf4b05ac13f210647658dfd3cec8b34c2"),
    "1.8.9": ("aeed0aaba9d159b7ce60a21e2dcc36adb249fade65ce2f76c730dd0ec7270763",
              "ea6fdd37e68569a378fd149b0ab7cfede1de7fa3e794f4ed81f9018366fbdc51"),
    "1.12.2": ("13a31f28c11f8f395ffe7e8563ade459f5a0ee46493abbbde3ce6e9493ac4152",
               "e2ddd3a7bb65618ad0de1d8fc0334527e5c346180300d94bfc7d3727b1976b42"),
}
MAVEN = "https://maven.minecraftforge.net/"
DEFAULT_CACHE = Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents/cache")) / "legacy"

def java8_home(explicit=None):
    """Use an explicit/environment JDK, an ignored local override, or PATH."""
    candidates = [explicit, os.environ.get("TANDEMOUNT_JAVA_8"), os.environ.get("JAVA8_HOME")]
    local = ROOT / ".agents/toolchains.json"
    if local.exists():
        candidates.append(json.loads(local.read_text()).get("8"))
    candidates.append(os.environ.get("JAVA_HOME"))
    javac = shutil.which("javac")
    if javac:
        candidates.append(Path(javac).resolve().parents[1])
    for candidate in candidates:
        if not candidate:
            continue
        home = Path(candidate).expanduser()
        if (home / "bin/java").is_file() and (home / "bin/javac").is_file():
            version = subprocess.run([str(home / "bin/java"), "-version"], capture_output=True, text=True)
            if version.returncode == 0 and re.search(r'version "1\.8\.', version.stderr + version.stdout):
                return home.resolve()
        if explicit:
            raise SystemExit("--java must name a Java 8 JDK containing bin/java and bin/javac")
    raise SystemExit("Java 8 JDK not found. Set TANDEMOUNT_JAVA_8/JAVA8_HOME or pass --java /path/to/jdk8.")

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def fetch(cache, name, url, expected=None):
    path = cache / name
    if not path.exists():
        temp = path.with_suffix(path.suffix + ".download")
        subprocess.run(["curl", "-fsSL", "--retry", "3", url, "-o", str(temp)], check=True)
        if expected and sha(temp) != expected:
            raise ValueError("Official archive checksum mismatch: " + name)
        temp.replace(path)
    if expected and sha(path) != expected:
        raise ValueError("Cached official archive checksum mismatch: " + name)
    return path

def mappings(mc, cache):
    cache.mkdir(parents=True, exist_ok=True)
    names, srg = MAPPING_HASHES[mc]
    return (fetch(cache, f"mcp-{mc}-names.zip", MAVEN+f"de/oceanlabs/mcp/mcp_stable/{MAPPINGS[mc]}/mcp_stable-{MAPPINGS[mc]}.zip", names),
            fetch(cache, f"mcp-{mc}-srg.zip", MAVEN+f"de/oceanlabs/mcp/mcp/{mc}/mcp-{mc}-srg.zip", srg))

def build(mc, args):
    cache = args.cache; cache.mkdir(parents=True, exist_ok=True)
    project = metadata(); version = project["version"]
    sdk = SDK[mc]
    if mc == "1.12.2":
        api = fetch(cache, "forge-1.12.2-api.jar", MAVEN+f"net/minecraftforge/forge/{sdk}/forge-{sdk}-universal.jar")
    else:
        userdev = fetch(cache, f"forge-{sdk}-userdev.jar", MAVEN+f"net/minecraftforge/forge/{sdk}/forge-{sdk}-userdev.jar")
        api = cache/f"forge-{mc}-api.jar"
        if not api.exists():
            with zipfile.ZipFile(userdev) as z:
                api.write_bytes(z.read("binaries.jar" if mc == "1.7.10" else "classes.jar"))
    asm = fetch(cache, "asm-5.0.3.jar", MAVEN+"org/ow2/asm/asm-debug-all/5.0.3/asm-debug-all-5.0.3.jar")
    launch = fetch(cache, "launchwrapper-1.12.jar", "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")
    names, srg = mappings(mc, cache)
    sources = sorted((ROOT/"forge/legacy/src/main/java").rglob("*.java")) + sorted((ROOT/f"forge/{mc}/src/main/java").rglob("*.java"))
    literals = set()
    for source in sources:
        for literal in re.findall(r'"([A-Za-z_][A-Za-z_0-9|]*)"', source.read_text()):
            literals.update(literal.split("|"))
    symbols = {"minecraft": mc}
    with zipfile.ZipFile(names) as z:
        for name in ("methods.csv", "fields.csv"):
            for row in csv.DictReader(io.StringIO(z.read(name).decode())):
                if row["name"] in literals:
                    symbols[row["searge"]] = row["name"]
    with tempfile.TemporaryDirectory(prefix=f"build-{mc}-", dir=cache) as temporary:
        classes=Path(temporary)/"classes"; classes.mkdir()
        generated = []
        for source in sources:
            target = Path(temporary)/"sources"/source.relative_to(ROOT/"forge")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source.read_text().replace("@VERSION@", version))
            generated.append(target)
        subprocess.run([str(args.java/"bin/javac"), "-source", "8", "-target", "8", "-encoding", "UTF-8", "-Xlint:all,-path", "-cp", os.pathsep.join(map(str,[api,asm,launch])), "-d", str(classes), *map(str,generated)],check=True)
        (classes/"tandemount-names.properties").write_text("\n".join(k+"="+v for k,v in sorted(symbols.items()))+"\n")
        info=[{"modid":project["id"],"name":project["name"],"version":version,"mcversion":mc,"description":"Independent server-side horse sharing.","authorList":[project["author"]],"credits":"","url":"https://github.com/"+project["github"],"updateUrl":"","logoFile":"","dependencies":[]}]
        (classes/"mcmod.info").write_text(json.dumps(info,indent=2)+"\n")
        output=args.output/artifact_name("forge",mc); output.parent.mkdir(parents=True,exist_ok=True)
        manifest="Manifest-Version: 1.0\nFMLCorePlugin: dev.eyecrasher.tandemount.legacy.core.CorePlugin\nFMLCorePluginContainsFMLMod: true\nImplementation-Title: "+project["name"]+"\nImplementation-Version: "+version+"\nImplementation-Vendor: "+project["author"]+"\nTandemount-Minecraft: "+mc+"\nTandemount-License: "+project["license"]+"\n\n"
        with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED,compresslevel=9) as z:
            def add(name,data):
                entry=zipfile.ZipInfo(name,(2026,11,13,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;entry.external_attr=0o644<<16;z.writestr(entry,data)
            add("META-INF/MANIFEST.MF",manifest.encode())
            for path in sorted(classes.rglob("*")):
                if path.is_file():add(path.relative_to(classes).as_posix(),path.read_bytes())
            license_path=ROOT/"LICENSE"
            if license_path.exists():add("META-INF/LICENSE",license_path.read_bytes())
        record={"minecraft":mc,"version":version,"platform":"forge","sdk":sdk,"artifact":str(output.resolve()),"sha256":sha(output),"compilation":"passed-real-forge-api","native_behavior":"unverified","human_visual":"unverified","visual":"unverified","dependencies":[{"file":p.name,"sha256":sha(p)} for p in [api,asm,launch,names,srg]],"source_sha256":hashlib.sha256((ROOT/"project.json").read_bytes()+b"".join(p.read_bytes() for p in sources)).hexdigest()}
        output.with_suffix(".json").write_text(json.dumps(record,indent=2)+"\n")
        print(json.dumps(record))
        return record

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft",choices=SDK)
    parser.add_argument("--cache",type=Path,default=DEFAULT_CACHE)
    parser.add_argument("--java",type=Path)
    parser.add_argument("--output",type=Path)
    args=parser.parse_args()
    args.java=java8_home(args.java)
    args.output=args.output or args.cache/"artifacts"
    records=[build(mc,args) for mc in ([args.minecraft] if args.minecraft else SDK)]
    (args.output/"legacy-artifacts.json").write_text(json.dumps({"schema":1,"artifacts":records},indent=2)+"\n")

if __name__=="__main__":main()
