#!/usr/bin/env python3
"""Probe exact untouched official client metadata components, without game ticks."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import zipfile
from cache_servers import java_for

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("modern_build", ROOT / "tools/modern-build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)
CACHE = Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents/cache"))
BASE = CACHE / "modern-final-client-components"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    args = parser.parse_args()
    version = args.minecraft
    target = next(t for t in json.loads((ROOT / "platforms.json").read_text())["targets"] if t["minecraft"] == version)
    folder = BASE / version
    folder.mkdir(parents=True, exist_ok=True)
    metadata = json.loads(build.fetch(target["minecraft_metadata"], folder / "version.json").read_text())
    artifacts = {}
    for kind in ("client", "client_mappings"):
        if kind not in metadata["downloads"]:
            continue
        download = metadata["downloads"][kind]
        options = [CACHE / "plugin-renderer-audit" / (version + ("-client.jar" if kind == "client" else "-client_mappings.txt")),
                   CACHE / "client-attachment-api" / version / ("client.jar" if kind == "client" else "client_mappings.txt")]
        path = next((p for p in options if p.exists()), folder / (kind + (".jar" if kind == "client" else ".txt")))
        artifacts[kind] = build.fetch(download["url"], path, download["sha1"])
    if "client_mappings" in artifacts:
        classes, methods, fields = build.mojang(artifacts["client_mappings"].read_text())
    else:
        with zipfile.ZipFile(artifacts["client"]) as archive:
            declared = {name[:-6]: build.classfile(archive.read(name)) for name in archive.namelist() if name.endswith(".class")}
        classes, methods, fields = build.identity_mappings(declared)
    aliases = {"Entity":"net.minecraft.world.entity.Entity", "EntityType":"net.minecraft.world.entity.EntityType",
               "Interaction":"net.minecraft.world.entity.Interaction", "Level":"net.minecraft.world.level.Level",
               "ClientLevel":"net.minecraft.client.multiplayer.ClientLevel", "Pose":"net.minecraft.world.entity.Pose",
               "Data":"net.minecraft.network.syncher.SynchedEntityData", "Value":"net.minecraft.network.syncher.SynchedEntityData$DataValue",
               "Bootstrap":"net.minecraft.server.Bootstrap", "Shared":"net.minecraft.SharedConstants"}
    symbols = {"c." + key: classes[name] for key, name in aliases.items()}
    wanted = {"entity.bbHeight":("Entity","getBbHeight",""), "entity.pose":("Entity","setPose",aliases["Pose"]),
              "entity.data":("Entity","getEntityData",""), "interaction.width":("Interaction","setWidth","float"),
              "interaction.height":("Interaction","setHeight","float"), "data.nonDefault":("Data","getNonDefaultValues",""),
              "data.assign":("Data","assignValues","java.util.List"), "value.create":("Value","create","net.minecraft.network.syncher.EntityDataAccessor,java.lang.Object"),
              "shared.detect":("Shared","tryDetectVersion",""), "bootstrap":("Bootstrap","bootStrap",""),
              "entity.passengerPoint":("Entity","getPassengerRidingPosition",aliases["Entity"]), "entity.passengerHeight":("Entity","getPassengersRidingOffset","")}
    for key, (owner, member, params) in wanted.items():
        value = methods.get((aliases[owner], member, params))
        if value: symbols["m." + key] = "#".join(value)
    for owner, members in {"EntityType":["INTERACTION"],"Pose":["SITTING"],"Level":["isClientSide"],"Entity":["DATA_POSE"]}.items():
        for member in members:
            value = fields.get((aliases[owner], member))
            if value is None and owner == "EntityType": value = fields[("net.minecraft.world.entity.EntityTypes",member)]
            symbols["f." + owner + "." + member] = "#".join(value)
    symbol_file = folder / "symbols.properties"
    symbol_file.write_text("\n".join(key+"="+value for key,value in sorted(symbols.items()))+"\n")
    libraries = []
    for lib in metadata["libraries"]:
        if not lib.get("downloads",{}).get("artifact"):
            continue
        item = lib["downloads"]["artifact"]
        candidates = [CACHE / part / item["path"] for part in ("modern/libraries","client-attachment-api/libraries","client-tick-26.3/libraries","modern-final-client-components/libraries")]
        path = next((p for p in candidates if p.exists()), candidates[-1])
        libraries.append(build.fetch(item["url"],path,item["sha1"]))
    inputs = {"minecraft":version,"official_client_sha256":hashlib.sha256(artifacts["client"].read_bytes()).hexdigest(),
              "symbols_sha256":hashlib.sha256(symbol_file.read_bytes()).hexdigest(),
              "libraries_sha256":{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in libraries}}
    source = ROOT / "mods/modern/src/test/client/InteractionPoseProbe.java"
    inputs["source_sha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
    (folder/"inputs.json").write_text(json.dumps(inputs,indent=2)+"\n")
    classes_out = folder / "classes";classes_out.mkdir(exist_ok=True)
    java = Path(os.environ.get("TANDEMOUNT_JAVA") or java_for(max(21,target["java"])))
    subprocess.run([str(java.with_name("javac")),"-d",str(classes_out),str(source)],check=True)
    command = [str(java),"-cp",os.pathsep.join(map(str,[classes_out,artifacts["client"],*libraries])),"dev.eyecrasher.tandemount.clienttest.InteractionPoseProbe",str(symbol_file),str(folder/"result.json")]
    process = subprocess.run(command,cwd=folder,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (folder/"console.log").write_text(process.stdout)
    print(process.stdout,end="")
    return process.returncode


if __name__ == "__main__":
    raise SystemExit(main())
