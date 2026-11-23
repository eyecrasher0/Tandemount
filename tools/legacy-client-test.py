#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Execute exact official vanilla client packet consumers and native entity ticks.

This is a headless class/packet execution probe, separately from human visual QA.
Mojang-signed client class bytes are never transformed, stripped or redistributed.
"""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("legacy_build",Path(__file__).with_name("legacy-build.py"))
build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)
SRG_ARCHIVES={
    "1.7.10": ("mcp/1.7.10/mcp-1.7.10-srg.zip", "d49df5c445dcf00ada532ab3b0e4283bf4b05ac13f210647658dfd3cec8b34c2"),
    "1.8.9": ("mcp/1.8.9/mcp-1.8.9-srg.zip", "ea6fdd37e68569a378fd149b0ab7cfede1de7fa3e794f4ed81f9018366fbdc51"),
    "1.12.2": ("mcp_config/1.12.2-20200226.224830/mcp_config-1.12.2-20200226.224830.zip",
               "e2ddd3a7bb65618ad0de1d8fc0334527e5c346180300d94bfc7d3727b1976b42"),
}
METADATA={
    "1.7.10":"https://piston-meta.mojang.com/v1/packages/ed5d8789ed29872ea2ef1c348302b0c55e3f3468/1.7.10.json",
    "1.8.9":"https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json",
    "1.12.2":"https://piston-meta.mojang.com/v1/packages/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json",
}
ALIASES={
    "Bootstrap":"init/Bootstrap","WorldSettings":"world/WorldSettings","GameType":"world/WorldSettings$GameType",
    "WorldType":"world/WorldType","Difficulty":"world/EnumDifficulty","Handler":"client/network/NetHandlerPlayClient",
    "WorldClient":"client/multiplayer/WorldClient","Profiler":"profiler/Profiler","Blocks":"init/Blocks",
    "FallingBlock":"entity/item/EntityFallingBlock","Entity":"entity/Entity","LivingBase":"entity/EntityLivingBase",
    "Block":"block/Block","World":"world/World","Minecraft":"client/Minecraft","GameConfiguration":"client/main/GameConfiguration",
    "PacketBuffer":"network/PacketBuffer",
    "SpawnObject":"network/play/server/S0EPacketSpawnObject","EntityVelocity":"network/play/server/S12PacketEntityVelocity",
    "EntityTeleport":"network/play/server/S18PacketEntityTeleport","EntityMetadata":"network/play/server/S1CPacketEntityMetadata",
    "SpawnMob":"network/play/server/S0FPacketSpawnMob","EntityHeadLook":"network/play/server/S19PacketEntityHeadLook",
    "Horse":"entity/passive/EntityHorse","AbstractHorse":"entity/passive/AbstractHorse","OtherPlayer":"client/entity/EntityOtherPlayerMP",
    "Chicken":"entity/passive/EntityChicken","Slime":"entity/monster/EntitySlime",
    "ArmorStand":"entity/item/EntityArmorStand","Cloud":"entity/EntityAreaEffectCloud",
    "AABB":"util/AxisAlignedBB","Vec3":"util/Vec3",
    "Attributes":"entity/SharedMonsterAttributes","AttributeInstance":"entity/ai/attributes/IAttributeInstance",
    "Session":"util/Session","LocalPlayer":"client/entity/EntityPlayerSP","ClientPlayer":"client/entity/EntityClientPlayerMP",
    "StatFileWriter":"stats/StatFileWriter","StatisticsManager":"stats/StatisticsManager","RecipeBook":"stats/RecipeBook",
}

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def fetch(path,url,sha1=None):
    if not path.exists():
        path.parent.mkdir(parents=True,exist_ok=True)
        temporary=path.with_suffix(path.suffix+".download")
        urllib.request.urlretrieve(url,temporary);temporary.replace(path)
    if sha1 and hashlib.sha1(path.read_bytes()).hexdigest()!=sha1:raise ValueError("Official artifact hash mismatch: "+str(path))
    return path

def mapping(mc,sdk):
    names_archive=build.mcp_names(mc,sdk)
    srg_path,srg_hash=SRG_ARCHIVES[mc]
    srg_archive=build.fetch(sdk,f"mcp-{mc}-srg.zip",build.MAVEN+"de/oceanlabs/mcp/"+srg_path,srg_hash)
    with zipfile.ZipFile(names_archive) as z:
        names={r["searge"]:r["name"] for f in ("methods.csv","fields.csv") for r in csv.DictReader(io.StringIO(z.read(f).decode()))}
    with zipfile.ZipFile(srg_archive) as z:
        lines=z.read(next(n for n in z.namelist() if n.endswith(("joined.srg","joined.tsrg")))).decode().splitlines()
    classes={};members={};owner=None
    for line in lines:
        s=line.split()
        if line.startswith("CL: "):classes[s[2]]=s[1]
        elif line.startswith(("FD: ","MD: ")):
            canonical=s[2] if s[0]=="FD:" else s[3];scope,_,member=canonical.rpartition("/")
            members[scope+"."+names.get(member,member)]=s[1].rpartition("/")[2]
            if s[0]=="MD:":members[scope+"."+names.get(member,member)+"@"+s[2]]=s[1].rpartition("/")[2]
        elif not line.startswith(("\t"," ")):
            if len(s)==2:owner=s[1];classes[owner]=s[0]
        elif s:
            members[owner+"."+names.get(s[-1],s[-1])]=s[0]
            if len(s)==3 and s[1].startswith("("):members[owner+"."+names.get(s[-1],s[-1])+"@"+s[1]]=s[0]
    aliases=dict(ALIASES)
    if mc=="1.12.2":
        aliases["GameType"]="world/GameType"
        aliases["AABB"]="util/math/AxisAlignedBB";aliases["Vec3"]="util/math/Vec3d"
        for packet in ("SpawnObject","SpawnMob","EntityVelocity","EntityTeleport","EntityMetadata","EntityHeadLook"):aliases[packet]="network/play/server/SPacket"+packet
    result={"minecraft":mc}
    for alias,native in aliases.items():
        native="net/minecraft/"+native
        if native not in classes:continue
        result[alias]=classes[native]
        for key,value in members.items():
            if key.startswith(native+"."):result[alias+key[len(native):]]=value
    return result

def probe(mc,java,sdk,cache,trace,seat_id,state_id,ticks=1200,graph=False,carriers=False,work=None):
    downloads=cache/mc
    folder=work or downloads;folder.mkdir(parents=True,exist_ok=True)
    metadata_path=fetch(downloads/"version.json",METADATA[mc]);metadata=json.loads(metadata_path.read_text())
    client=fetch(downloads/"client.jar",metadata["downloads"]["client"]["url"],metadata["downloads"]["client"]["sha1"])
    dependencies=[];natives=folder/"natives";natives.mkdir(exist_ok=True)
    for library in metadata["libraries"]:
        downloads=library.get("downloads",{});artifact=downloads.get("artifact")
        if artifact:
            dependencies.append(fetch(cache/"libraries"/artifact["path"],artifact["url"],artifact["sha1"]))
        native_name=library.get("natives",{}).get("linux","").replace("${arch}","64")
        native=downloads.get("classifiers",{}).get(native_name)
        if native:
            jar=fetch(cache/"libraries"/native["path"],native["url"],native["sha1"])
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if name.endswith(".so"):(natives/Path(name).name).write_bytes(z.read(name))
    symbols=mapping(mc,sdk);symbols.update(seat_id=str(seat_id),ticks=str(ticks))
    properties=folder/"symbols.properties";properties.write_text("\n".join(k+"="+v for k,v in symbols.items())+"\n")
    classes=folder/"classes";classes.mkdir(exist_ok=True)
    source=ROOT/"forge/legacy/client-test/ClientChecks.java"
    subprocess.run([str(java/"bin/javac"),"-source","8","-target","8","-Xlint:all","-d",str(classes),str(source)],check=True)
    mode="carriers" if carriers else "graph" if graph else "report"
    raw_report=folder/("native-client-"+mode+".json");log=folder/("native-client-"+mode+".log")
    cp=os.pathsep.join(map(str,[classes,client,*dependencies]))
    command=[str(java/"bin/java"),"-Djava.awt.headless=true","-Djava.library.path="+str(natives),"-cp",cp,"dev.eyecrasher.tandemount.clienttest.ClientChecks",str(properties),"--carriers" if carriers else "--graph" if graph else str(trace),str(folder),str(raw_report)]
    with log.open("w") as out:result=subprocess.run(command,cwd=folder,stdout=out,stderr=subprocess.STDOUT,timeout=120)
    if result.returncode!=0:raise RuntimeError("Original client probe failed; see "+str(log))
    record=json.loads(raw_report.read_text())
    with zipfile.ZipFile(client) as z:entity_hash=hashlib.sha256(z.read(symbols["FallingBlock"]+".class")).hexdigest()
    record.update(minecraft=mc,official_client_sha256=sha(client),official_entity_class_sha256=entity_hash,
                  probe_source_sha256=hashlib.sha256(Path(__file__).read_bytes()+source.read_bytes()).hexdigest(),
                  probe_report_sha256=sha(raw_report),received_spawn_state_id=state_id,packet_sequence_sha256=sha(trace) if trace else None,
                  official_metadata_sha256=sha(metadata_path),official_library_sha256=[sha(p) for p in dependencies],
                  probe_log_sha256=sha(log),probe_exit_code=result.returncode)
    return record

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--minecraft",required=True,choices=METADATA)
    p.add_argument("--java",type=Path);p.add_argument("--sdk-cache",type=Path,required=True);p.add_argument("--cache",type=Path,required=True)
    p.add_argument("--trace",type=Path);p.add_argument("--seat-id",type=int,default=1);p.add_argument("--state-id",type=int,default=0);p.add_argument("--graph",action="store_true")
    p.add_argument("--carriers",action="store_true")
    p.add_argument("--ticks",type=int,default=1200);p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    if not (a.graph or a.carriers or a.trace):p.error("--trace is required unless --graph or --carriers is used")
    record=probe(a.minecraft,build.java8_home(a.java),a.sdk_cache.resolve(),a.cache.resolve(),a.trace.resolve() if a.trace else None,a.seat_id,a.state_id,a.ticks,a.graph,a.carriers)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(record,indent=2)+"\n");print(json.dumps(record))

if __name__=="__main__":main()
