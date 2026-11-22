#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run unchanged official-client attachment components in a normal test Level.

The generated world supplies controlled empty terrain and real registries.
Original entity classes, metadata consumers, packet codecs and native ticks
remain unchanged. Geometry subjects for players are ordinary ArmorStands;
this tool does not render a client or establish LocalPlayer/camera behavior.
"""

import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import zipfile

from cache_servers import java_for
from vanilla_protocol import load_ids

ROOT=Path(__file__).resolve().parents[1]
CACHE=Path(os.environ.get("TANDEMOUNT_CACHE", ROOT/".agents/cache"))
spec=importlib.util.spec_from_file_location("attachment_client_build", ROOT/"tools/modern-build.py")
b=importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def normalize_capture(raw):
    state=raw.get("mounted_state",raw)
    if "horsePosition" not in state:
        raise ValueError("Capture must record the actual horse position")
    result=dict(state)
    result["height"]=state.get("height",state.get("vertical"))
    result["rear"]=state.get("rear",state.get("rearward"))
    result["helpers"]=[{**helper,"type":helper["type"].lower(),
                        "vehicle":helper.get("vehicle",helper.get("parent")),
                        "position":helper.get("position",[helper.get(key) for key in ("x","y","z")])}
                       for helper in state["helpers"]]
    required={"horse","driver","rider","riderVehicle","height","rear","helpers"}
    if required-result.keys() or result["height"] is None or result["rear"] is None:
        raise ValueError("Incomplete native helper-state capture")
    return result


def generate_world(mc, jar, mp, out):
    """Implement only Level's environment interface from exact SDK declarations."""
    z=zipfile.ZipFile(jar)
    if mp is not None:
     c,m,f=b.mojang(mp.read_text())
    else:
     declarations={name[:-6]:b.classfile(z.read(name)) for name in z.namelist() if name.endswith('.class')}
     c,m,f=b.identity_mappings(declarations)
    # Keep the probe's canonical API spelling across the EntityTypes rename.
    if 'net.minecraft.world.entity.EntityType' not in c:
     old='net.minecraft.world.entity.EntityTypes';new='net.minecraft.world.entity.EntityType';c[new]=c[old]
     m.update({(new,name,params):value for (owner,name,params),value in list(m.items()) if owner==old})
     f.update({(new,name):value for (owner,name),value in list(f.items()) if owner==old})
    decs={}
    def dec(name):
     if name not in decs:decs[name]=b.classfile(z.read(name+'.class'))
     return decs[name]
    def cls(name):return c.get(name,name).replace('$','.')
    def fld(owner,name):return cls(owner)+'.'+f[(owner,name)][1]
    def meth(owner,name,params=''):return m[(owner,name,params)][1]
    rev={(v[0],v[1],v[2]):(owner,name,params) for (owner,name,params),v in m.items()}
    def methods(internal):
     result={}
     def visit(name):
      if name.startswith('java/'):return
      _,parents,declared,_=dec(name)
      for parent in reversed(parents):visit(parent)
      for name,desc,access in declared:
       if name.startswith('<') or access&0xa:continue
       result[(name,desc.split(')')[0]+')')]=(name,desc,access,internal)
     visit(internal)
     return result

    def split_type(desc,index=0):
     dims=0
     while desc[index]=='[':dims+=1;index+=1
     if desc[index]=='L':
      end=desc.index(';',index);name=desc[index+1:end].replace('/','.').replace('$','.');index=end+1
     else:
      name={'V':'void','Z':'boolean','B':'byte','C':'char','S':'short','I':'int','J':'long','F':'float','D':'double'}[desc[index]];index+=1
     return name+'[]'*dims,index

    def signature(desc):
     args=[];i=1
     while desc[i]!=')':name,i=split_type(desc,i);args.append(name)
     result,_=split_type(desc,i+1);return result,args

    def semantic(name,desc):
     for owner in hierarchy:
      if (owner,name,desc) in rev:return rev[(owner,name,desc)]
     return None,'',''
    level='net.minecraft.world.level.Level';writable='net.minecraft.world.level.storage.WritableLevelData'
    hierarchy=[]
    def collect(i):
     hierarchy.append(i)
     for par in dec(i)[1]:
      if not par.startswith('java/') and par not in hierarchy:collect(par)
    collect(c[level])
    abstract=methods(c[level])
    statics='''final java.util.Map<Integer,%s> entities=new java.util.LinkedHashMap<>();
    final java.util.Map<Long,%s> chunks=new java.util.HashMap<>();
    final %s border=new %s();
    final %s scoreboard=new %s();
    final %s biome;
    '''%(cls('net.minecraft.world.entity.Entity'),cls('net.minecraft.world.level.chunk.LevelChunk'),cls('net.minecraft.world.level.border.WorldBorder'),cls('net.minecraft.world.level.border.WorldBorder'),cls('net.minecraft.world.scores.Scoreboard'),cls('net.minecraft.world.scores.Scoreboard'),cls('net.minecraft.core.Holder'))
    if 'net.minecraft.world.TickRateManager' in c:statics+='final %s tickrate=new %s();\n'%(cls('net.minecraft.world.TickRateManager'),cls('net.minecraft.world.TickRateManager'))
    body=[]
    # Abstract declarations plus concrete environment queries used by untouched entity ticks.
    override_names={'getBlockState','getFluidState','getBlockEntity','getChunkForCollisions','hasChunk','getSeaLevel','getUncachedNoiseBiome','enabledFeatures','getWorldBorder','players','getEntities','getEntity','getScoreboard','getChunk','tickRateManager','gameEvent','playSeededSound','addParticle','levelEvent','getBlockTicks','getFluidTicks','getBrightness','getRawBrightness','getSkyDarken'}
    existing=[]
    for key,(name,desc,access,_) in abstract.items():
     _,semantic_name,params=semantic(name,desc)
     if not(access&0x400) and semantic_name not in override_names:continue
     if access&0x10:continue
     result,args=signature(desc);argdecl=','.join(t+' p'+str(i) for i,t in enumerate(args))
     stmt='throw new UnsupportedOperationException("controlled Level: '+semantic_name+'");'
     if semantic_name=='getBlockState':stmt='return '+fld('net.minecraft.world.level.block.Blocks','AIR')+'.'+meth('net.minecraft.world.level.block.Block','defaultBlockState')+'();'
     elif semantic_name=='getFluidState':stmt='return '+fld('net.minecraft.world.level.material.Fluids','EMPTY')+'.'+meth('net.minecraft.world.level.material.Fluid','defaultFluidState')+'();'
     elif semantic_name=='getBlockEntity':stmt='return null;'
     elif semantic_name=='getChunkForCollisions':stmt='return this;'
     elif semantic_name=='hasChunk':stmt='return true;'
     elif semantic_name=='getSeaLevel':stmt='return 63;'
     elif semantic_name in {'getBrightness','getRawBrightness','getSkyDarken'}:stmt='return 0;'
     elif semantic_name=='getUncachedNoiseBiome':stmt='return biome;'
     elif semantic_name=='enabledFeatures':stmt='return '+fld('net.minecraft.world.flag.FeatureFlags','VANILLA_SET')+';'
     elif semantic_name=='getWorldBorder':stmt='return border;'
     elif semantic_name=='getEntities' and result=='java.util.List' and len(args)==3 and args[0]==cls('net.minecraft.world.entity.Entity'):
      stmt='return entities.values().stream().filter(e->e!=p0&&e.'+meth('net.minecraft.world.entity.Entity','getBoundingBox')+'().'+meth('net.minecraft.world.phys.AABB','intersects','net.minecraft.world.phys.AABB')+'(p1)&&p2.test(e)).toList();'
     elif semantic_name in {'players','getEntities'} and result=='java.util.List':stmt='return java.util.List.of();' 
     elif semantic_name=='getEntity':stmt='return entities.get(p0);'
     elif semantic_name=='getScoreboard':stmt='return scoreboard;'
     elif semantic_name=='tickRateManager':stmt='return tickrate;'
     elif semantic_name=='getChunk' and len(args)==4:stmt='long key=((long)p0&0xffffffffL)|((long)p1<<32);return chunks.computeIfAbsent(key,k->new '+cls('net.minecraft.world.level.chunk.EmptyLevelChunk')+'(this,new '+cls('net.minecraft.world.level.ChunkPos')+'(p0,p1),biome));'
     elif semantic_name in {'gameEvent','playSeededSound','addParticle','levelEvent','sendBlockUpdated','destroyBlockProgress','disconnect','setMapData'}:stmt=''
     elif semantic_name in {'gatherChunkSourceStats'}:stmt='return "controlled-empty-client-world";'
     elif semantic_name=='getMapData':stmt='return null;'
     elif semantic_name=='getBlockTicks' or semantic_name=='getFluidTicks':stmt='return null;'
     body.append('public '+result+' '+name+'('+argdecl+'){'+stmt+'}')
     existing.append([semantic_name,desc])
    ctor='World(Object data,Object registry,Object dimension,Object plains){super(('+cls(writable)+')data,'+fld(level,'OVERWORLD')+',('+cls('net.minecraft.core.RegistryAccess')+')registry,('+cls('net.minecraft.core.Holder')+')dimension,()->'+fld('net.minecraft.util.profiling.InactiveProfiler','INSTANCE')+',true,false,0L,1000000);biome=('+cls('net.minecraft.core.Holder')+')plains;}'
    world='public class World extends '+cls(level)+'{\n'+statics+ctor+'\n'+'\n'.join(body)+'\n}'
    world_path=out/'World.java'
    world_path.write_text(world)
    properties={}
    for canonical,obf in c.items():properties['class.'+canonical]=obf
    for (owner,name,params),(obf,newname,desc) in m.items():properties['method.'+owner+'#'+name+'#'+params]=obf+'#'+newname+'#'+desc
    for (owner,name),(obf,newname,desc) in f.items():properties['field.'+owner+'#'+name]=obf+'#'+newname
    properties['minecraft']=mc
    symbols_path=out/'all-symbols.properties'
    symbols_path.write_text('\n'.join(k+'='+v for k,v in properties.items())+'\n')
    (out/'world-methods.json').write_text(json.dumps(existing,indent=2))

    z.close()
    return world_path,symbols_path


def prepare(version, folder):
    target=next(t for t in json.loads((ROOT/"platforms.json").read_text())["targets"] if t["minecraft"]==version)
    metadata_path=b.fetch(target["minecraft_metadata"],folder/"version.json")
    metadata=json.loads(metadata_path.read_text())
    artifacts={}
    for kind in ("client","client_mappings"):
        if kind not in metadata["downloads"]:
            continue
        item=metadata["downloads"][kind]
        suffix="-client.jar" if kind=="client" else "-client_mappings.txt"
        candidates=[CACHE/"plugin-renderer-audit"/(version+suffix),
                    CACHE/"client-attachment-api"/version/("client.jar" if kind=="client" else "client_mappings.txt")]
        path=next((p for p in candidates if p.is_file()),folder/(kind+(".jar" if kind=="client" else ".txt")))
        artifacts[kind]=b.fetch(item["url"],path,item["sha1"])
    libraries=[]
    for library in metadata["libraries"]:
        item=library.get("downloads",{}).get("artifact")
        if not item:
            continue
        candidates=[CACHE/part/item["path"] for part in ("modern/libraries","client-attachment-api/libraries",
                    "client-tick-26.3/libraries","modern-final-client-components/libraries","client-attachment/libraries")]
        path=next((p for p in candidates if p.is_file()),candidates[-1])
        libraries.append(b.fetch(item["url"],path,item["sha1"]))
    return target,artifacts,libraries,metadata_path


PACKET_CLASSES={
    "add_entity":"ClientboundAddEntityPacket", "add_player":"ClientboundAddPlayerPacket",
    "set_entity_data":"ClientboundSetEntityDataPacket", "update_attributes":"ClientboundUpdateAttributesPacket",
    "set_passengers":"ClientboundSetPassengersPacket", "rotate_head":"ClientboundRotateHeadPacket",
}
PLUGIN_PACKET_CLASSES={
    "PacketPlayOutSpawnEntity":"ClientboundAddEntityPacket",
    "PacketPlayOutNamedEntitySpawn":"ClientboundAddPlayerPacket",
    "PacketPlayOutEntityMetadata":"ClientboundSetEntityDataPacket",
    "PacketPlayOutUpdateAttributes":"ClientboundUpdateAttributesPacket",
    "PacketPlayOutMount":"ClientboundSetPassengersPacket",
    "PacketPlayOutEntityHeadRotation":"ClientboundRotateHeadPacket",
}


def received_state(trace, protocol, output, end_ns=None):
    """Normalize captured native packet bytes, retaining only state components.

    Movement is deliberately not replayed by this component interface. The
    original native graph ticks start with decoded received helper state.
    """
    ids={}
    for key,identifier in load_ids(protocol).items():
        if not key.startswith(("game.clientbound.","play.clientbound.")):
            continue
        name=key.split(".clientbound.",1)[1].removeprefix("minecraft:").removeprefix("class.")
        if name in PACKET_CLASSES:
            ids[identifier]=PACKET_CLASSES[name]
        elif name in PACKET_CLASSES.values():
            ids[identifier]=name
        elif name in PLUGIN_PACKET_CLASSES:
            ids[identifier]=PLUGIN_PACKET_CLASSES[name]
    raw=json.loads(trace.read_text())
    if raw.get("errors"):
        raise ValueError("Captured observer has protocol errors")
    rows=[]
    for index,packet in enumerate(raw["packets"]):
        if isinstance(packet,dict):
            when,identifier=packet["at_ns"],packet["id"]
            payload=base64.b64decode(packet["base64"],validate=True).hex()
        else:
            when=raw["received_monotonic_ns"][index]
            stage,identifier,payload=packet
            if stage not in {"game","play"}:
                continue
        if end_ns is not None and when>end_ns:
            continue
        if identifier in ids:
            # Decode here too so a malformed source cannot become valid text.
            bytes.fromhex(payload)
            rows.append(ids[identifier]+"\t"+payload)
    if not rows:
        raise ValueError("No native attachment state packets in the selected observer trace")
    output.write_text("\n".join(rows)+"\n")
    return len(rows)


def run(version, capture, output, trace=None, protocol=None, end_ns=None, artifact=None):
    sdk=CACHE/"client-attachment"/version
    sdk.mkdir(parents=True,exist_ok=True)
    folder=CACHE/"client-attachment-runs"/version/uuid.uuid4().hex
    folder.mkdir(parents=True,exist_ok=True)
    target,artifacts,libraries,metadata=prepare(version,sdk)
    world,symbols=generate_world(version,artifacts["client"],artifacts.get("client_mappings"),folder)
    java=Path(java_for(max(21,target["java"])))
    launcher=ROOT/"tests/client/AttachmentClientLauncher.java"
    probe=ROOT/"tests/client/AttachmentClientProbe.java"
    classes=folder/"classes";classes.mkdir(exist_ok=True)
    launcher_classes=folder/"launcher-classes";launcher_classes.mkdir(exist_ok=True)
    classpath=os.pathsep.join(map(str,[artifacts["client"],*libraries]))
    compile_log=folder/"compile.log"
    with compile_log.open("w") as log:
        subprocess.run([str(java.with_name("javac")),"-proc:none","-d",str(launcher_classes),str(launcher)],
                       stdout=log,stderr=subprocess.STDOUT,check=True,timeout=120)
        subprocess.run([str(java.with_name("javac")),"-proc:none","-cp",classpath,"-d",str(classes),str(world),str(probe)],
                       stdout=log,stderr=subprocess.STDOUT,check=True,timeout=120)
    raw_output=folder/"result.json"
    normalized_capture=folder/"capture.json"
    normalized_capture.write_text(json.dumps(normalize_capture(json.loads(capture.read_text())),indent=2)+"\n")
    command=[str(java),"-XX:ActiveProcessorCount=2","-Djava.awt.headless=true","-cp",
             str(launcher_classes)+os.pathsep+classpath,"dev.eyecrasher.tandemount.clienttest.AttachmentClientLauncher",
             str(classes),str(symbols),str(normalized_capture),str(raw_output)]
    inputs={"metadata_sha256":digest(metadata),"official_client_sha256":digest(artifacts["client"]),
            "official_libraries_sha256":[digest(p) for p in libraries],
            "probe_source_sha256":hashlib.sha256(Path(__file__).read_bytes()+launcher.read_bytes()+probe.read_bytes()).hexdigest(),
            "generated_world_sha256":digest(world),"symbols_sha256":digest(symbols),"capture_sha256":digest(capture),
            "normalized_capture_sha256":digest(normalized_capture),"native_artifact_sha256":digest(artifact) if artifact else None}
    if trace is not None:
        if protocol is None:
            raise ValueError("An observer trace requires its exact native protocol export")
        normalized=folder/"received-state.tsv"
        inputs["captured_state_packets"]=received_state(trace,protocol,normalized,end_ns)
        inputs.update(observer_trace_sha256=digest(trace),protocol_export_sha256=digest(protocol),
                      normalized_state_sha256=digest(normalized),end_ns=end_ns)
        command.append(str(normalized))
    log_path=folder/"console.log"
    with log_path.open("w") as log:
        completed=subprocess.run(command,cwd=folder,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    if completed.returncode:
        raise RuntimeError("Original client probe failed; see "+str(log_path))
    report=json.loads(raw_output.read_text())
    report.update(inputs=inputs,probe_exit_code=completed.returncode,raw_report_sha256=digest(raw_output),
                  console_sha256=digest(log_path),human_visual=False)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,indent=2)+"\n")
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft",required=True)
    parser.add_argument("--capture",type=Path,required=True,help="Native helper-state export or modern connected-client.json")
    parser.add_argument("--trace",type=Path,help="Existing or late observer's actual native packet capture")
    parser.add_argument("--protocol",type=Path,help="Protocol ID export from that same exact native runtime")
    parser.add_argument("--end-ns",type=int,help="Last captured monotonic timestamp included in received state")
    parser.add_argument("--artifact",type=Path,help="Exact tested Tandemount JAR for artifact-bound client evidence")
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    result=run(args.minecraft,args.capture,args.output,args.trace,args.protocol,args.end_ns,args.artifact)
    print(json.dumps({key:result[key] for key in ("minecraft","ticks","helperCount","rootErrors",
                     "removedObservations","maximumRelativeMotionError","receivedNativeState")}))


if __name__=="__main__":
    main()
