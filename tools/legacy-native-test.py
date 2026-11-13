#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run a real, loopback-only Forge server in a disposable task cache.

The Minecraft EULA (https://aka.ms/MinecraftEULA) must be accepted explicitly.
No existing world or running server is used. Synthetic native-player checks and
protocol-client checks are reported separately from human visual validation.
"""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import queue
import socket
import struct
import shutil
import subprocess
import threading
import tempfile
import time
import zipfile
import zlib
from datetime import datetime, timezone

spec=importlib.util.spec_from_file_location("legacy_build",Path(__file__).with_name("legacy-build.py"))
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def vanilla_login(mc,port):
    """Vanilla protocol handshake: deliberately no FML marker or mod channels."""
    protocol={"1.7.10":5,"1.8.9":47,"1.12.2":340}[mc]
    join_id=0x23 if mc=="1.12.2" else 1
    def vi(n):
        data=bytearray()
        while True:
            v=n&127;n>>=7;data.append(v|(128 if n else 0))
            if not n:return bytes(data)
    def string(s):
        d=s.encode();return vi(len(d))+d
    def unpack(data,offset=0):
        n=0
        for i in range(5):
            v=data[offset+i];n|=(v&127)<<(7*i)
            if not v&128:return n,offset+i+1
        raise ValueError("Oversized protocol VarInt")
    record={"status":"failed","protocol":protocol,"hostname_fml_marker":False,"client_mod_channels_sent":False,"login_success":False,"play_join_game":False}
    try:
        with socket.create_connection(("127.0.0.1",port),timeout=15) as connection:
            connection.settimeout(15)
            def exact(n):
                data=b""
                while len(data)<n:
                    chunk=connection.recv(n-len(data))
                    if not chunk:raise EOFError("Server closed vanilla fixture connection")
                    data+=chunk
                return data
            def packet(payload):connection.sendall(vi(len(payload))+payload)
            packet(vi(0)+vi(protocol)+string("127.0.0.1")+struct.pack(">H",port)+vi(2))
            packet(vi(0)+string("VanillaNative"))
            compression=None;deadline=time.monotonic()+25
            while time.monotonic()<deadline:
                length_data=bytearray()
                while True:
                    v=exact(1);length_data+=v
                    if not v[0]&128:break
                    if len(length_data)>5:raise ValueError("Invalid packet length")
                length,_=unpack(length_data)
                if length>8*1024*1024:raise ValueError("Unexpected fixture packet size")
                data=exact(length)
                if compression is not None:
                    original,offset=unpack(data);data=data[offset:]
                    if original:
                        data=zlib.decompress(data)
                        if len(data)!=original:raise ValueError("Protocol compression length mismatch")
                identifier,offset=unpack(data)
                if not record["login_success"]:
                    if identifier==3:
                        compression,_=unpack(data,offset)
                    elif identifier==2:record["login_success"]=True
                    elif identifier==0:raise ValueError("Vanilla login rejected: "+data[offset:].decode(errors="replace")[:300])
                    elif identifier==1:raise ValueError("Unexpected online authentication on offline fixture")
                elif identifier==join_id:
                    record["play_join_game"]=True;record["status"]="passed";break
    except Exception as ex:record["error"]=str(ex)
    return record

def varint(number):
    data=bytearray();number&=0xffffffff
    while True:
        byte=number&127;number>>=7;data.append(byte|(128 if number else 0))
        if not number:return bytes(data)

def read_varint(data,offset=0):
    number=0
    for index in range(5):
        byte=data[offset+index];number|=(byte&127)<<(index*7)
        if not byte&128:return number,offset+index+1
    raise ValueError("Oversized protocol VarInt")

def protocol_string(value):
    data=value.encode("utf-8");return varint(len(data))+data

class VanillaClient:
    """Small vanilla-wire fixture. It decodes native packets; it does not render a client."""
    def __init__(self,mc,port,name):
        self.mc=mc;self.modern=mc=="1.12.2";self.oldest=mc=="1.7.10"
        self.compression=None;self.play=False;self.closed=False;self.buffer=bytearray()
        self.entities={};self.players=set();self.mounts={};self.error=None;self.joined=threading.Event();self.lock=threading.Lock()
        self.packet_counts={};self.location=(0.0,0.0,0.0,0.0,0.0)
        self.record={"status":"failed","protocol":{"1.7.10":5,"1.8.9":47,"1.12.2":340}[mc],"hostname_fml_marker":False,"client_mod_channels_sent":False,"login_success":False,"play_join_game":False}
        self.connection=socket.create_connection(("127.0.0.1",port),timeout=15);self.connection.settimeout(.25)
        self.send(0,varint(self.record["protocol"])+protocol_string("127.0.0.1")+struct.pack(">H",port)+varint(2))
        self.send(0,protocol_string(name))
        self.thread=threading.Thread(target=self.receive,daemon=True);self.thread.start()
        if not self.joined.wait(20):
            self.close();raise RuntimeError(self.error or "Vanilla client did not receive JoinGame")

    def send(self,identifier,data=b""):
        payload=varint(identifier)+data
        with self.lock:
            if self.compression is not None:
                payload=varint(len(payload))+zlib.compress(payload) if len(payload)>=self.compression else b"\x00"+payload
            self.connection.sendall(varint(len(payload))+payload)

    def receive(self):
        try:
            while not self.closed:
                try:chunk=self.connection.recv(65536)
                except socket.timeout:continue
                if not chunk:raise EOFError("Vanilla connection closed")
                self.buffer.extend(chunk)
                while self.buffer:
                    try:length,offset=read_varint(self.buffer)
                    except IndexError:break
                    if length>8*1024*1024:raise ValueError("Oversized fixture packet")
                    if len(self.buffer)<offset+length:break
                    data=bytes(self.buffer[offset:offset+length]);del self.buffer[:offset+length]
                    if self.compression is not None:
                        original,offset=read_varint(data);data=data[offset:]
                        if original:
                            data=zlib.decompress(data)
                            if len(data)!=original:raise ValueError("Protocol compression mismatch")
                    identifier,offset=read_varint(data);self.packet(identifier,data[offset:])
        except Exception as ex:
            if not self.closed:self.error=str(ex)

    def packet(self,identifier,data):
        if not self.play:
            if identifier==3:self.compression,_=read_varint(data)
            elif identifier==2:self.record["login_success"]=True;self.play=True
            elif identifier==0:raise ValueError("Vanilla login was rejected")
            elif identifier==1:raise ValueError("Unexpected authentication on an offline fixture")
            return
        self.packet_counts[identifier]=self.packet_counts.get(identifier,0)+1
        if identifier==(0x23 if self.modern else 1):
            self.record.update(status="passed",play_join_game=True);self.joined.set()
        elif identifier==(0x1f if self.modern else 0):self.send(0x0b if self.modern else 0,data)
        elif identifier==(0x2f if self.modern else 8):
            values=list(struct.unpack_from(">dddff",data));flags=0 if self.oldest else data[32]
            values=[value+(self.location[i] if flags&(1<<i) else 0) for i,value in enumerate(values)];self.location=tuple(values)
            if self.modern:
                teleport,_=read_varint(data,33);self.send(0,varint(teleport))
            x,y,z,yaw,pitch=values
            if self.oldest:self.send(6,struct.pack(">ddddff?",x,y,y+1.62,z,yaw,pitch,False))
            else:self.send(0x0e if self.modern else 6,struct.pack(">dddff?",x,y,z,yaw,pitch,False))
        elif identifier==(0x03 if self.modern else 0x0f):
            entity,offset=read_varint(data)
            if self.modern:
                offset+=16;kind,offset=read_varint(data,offset);position=struct.unpack_from(">ddd",data,offset)
            else:
                kind=data[offset];offset+=1;position=tuple(v/32 for v in struct.unpack_from(">iii",data,offset))
            self.entities[entity]={"kind":"mob","type":kind,"position":position}
        elif identifier==(0 if self.modern else 0x0e):
            entity,offset=read_varint(data)
            if self.modern:offset+=16
            kind=data[offset];offset+=1
            position=struct.unpack_from(">ddd",data,offset) if self.modern else tuple(v/32 for v in struct.unpack_from(">iii",data,offset))
            self.entities[entity]={"kind":"object","type":kind,"position":position}
        elif identifier==(0x05 if self.modern else 0x0c):self.players.add(read_varint(data)[0])
        elif not self.modern and identifier==0x1b:
            rider,vehicle=struct.unpack_from(">ii",data)
            if data[8]==0:self.mounts[rider]=vehicle
        elif self.modern and identifier==0x43:
            vehicle,offset=read_varint(data);count,offset=read_varint(data,offset)
            for rider in [r for r,v in self.mounts.items() if v==vehicle]:del self.mounts[rider]
            for _ in range(count):
                rider,offset=read_varint(data,offset);self.mounts[rider]=vehicle
        elif identifier==(0x4c if self.modern else 0x18):
            if self.oldest:entity=struct.unpack_from(">i",data)[0];offset=4
            else:entity,offset=read_varint(data)
            position=struct.unpack_from(">ddd",data,offset) if self.modern else tuple(v/32 for v in struct.unpack_from(">iii",data,offset))
            if entity in self.entities:self.entities[entity]["position"]=position
        elif identifier in ((0x25,0x26) if self.modern else (0x15,0x17)):
            if self.oldest:entity=struct.unpack_from(">i",data)[0];offset=4
            else:entity,offset=read_varint(data)
            delta=struct.unpack_from(">hhh" if self.modern else ">bbb",data,offset);unit=4096 if self.modern else 32
            if entity in self.entities:
                before=self.entities[entity]["position"];self.entities[entity]["position"]=tuple(v+d/unit for v,d in zip(before,delta))

    def interact(self,entity):
        if self.oldest:self.send(2,struct.pack(">iB",entity,0))
        else:self.send(0x0a if self.modern else 2,varint(entity)+varint(0)+(varint(0) if self.modern else b""))

    def observe(self,state):
        seat=self.entities.get(state["seat"])
        expected_type=70  # Vanilla air falling block object; its renderer has no visible model.
        position=list(seat["position"]) if seat else None
        tolerance=.04 if not self.modern else .002
        return {"seat_spawned":bool(seat and seat["kind"]=="object" and seat["type"]==expected_type),"driver_spawned":state["driver"] in self.players,"rider_spawned":state["rider"] in self.players,
                "driver_on_horse":self.mounts.get(state["driver"])==state["horse"],"rider_on_seat":self.mounts.get(state["rider"])==state["seat"],
                "seat_position":position,"position_matches_native":bool(position and all(abs(a-b)<=tolerance for a,b in zip(position,state["position"]))),"reader_error":self.error,
                "received_packet_counts":{hex(k):v for k,v in sorted(self.packet_counts.items())}}

    def close(self):
        self.closed=True
        try:self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:pass
        self.connection.close()
        if hasattr(self,"thread"):self.thread.join(timeout=2)

def protocol_observers(mc,port,process,output):
    clients=[];record={"status":"failed","human_visual":"unverified","client_mod_channels_sent":False}
    def issue(command):process.stdin.write(command+"\n");process.stdin.flush()
    def state(phase,command):
        issue("tandemount_native_test "+command);deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            try:line=output.get(timeout=.5)
            except queue.Empty:continue
            if "[TandemountObserver] {" in line:
                value=json.loads(line[line.index('{'):])
                if value["phase"]==phase:return value
        raise TimeoutError("Native observer phase did not complete: "+phase)
    try:
        early=VanillaClient(mc,port,"VanillaEarly");clients.append(early)
        driver=VanillaClient(mc,port,"VanillaDriver");clients.append(driver)
        rider=VanillaClient(mc,port,"VanillaRider");clients.append(rider)
        prepared=state("prepared","observer-prepare");time.sleep(1)
        driver.interact(prepared["horse"]);time.sleep(.5);rider.interact(prepared["horse"]);time.sleep(1)
        initial=state("state","observer-state")
        if not initial["joined"]:raise AssertionError("Real vanilla UseEntity interactions did not create a shared ride")
        late=VanillaClient(mc,port,"VanillaLate");clients.append(late);issue("tp VanillaLate 12 8 8");time.sleep(2)
        settled=state("state","observer-state");time.sleep(.5)
        record["initial"]={"native":settled,"existing_observer":early.observe(settled),"late_observer":late.observe(settled)}
        state("moving","observer-move");time.sleep(1)
        moved=state("state","observer-state");time.sleep(.5)
        record["movement"]={"native":moved,"existing_observer":early.observe(moved),"late_observer":late.observe(moved)}
        issue("tandemount vertical +");issue("tandemount rear +");time.sleep(1)
        adjusted=state("state","observer-state");time.sleep(.5)
        record["live_adjustment"]={"native":adjusted,"existing_observer":early.observe(adjusted),"late_observer":late.observe(adjusted)}
        flags=["seat_spawned","driver_spawned","rider_spawned","driver_on_horse","rider_on_seat","position_matches_native"]
        record["status"]="passed" if all(value[observer][flag] for value in [record["initial"],record["movement"],record["live_adjustment"]] for observer in ["existing_observer","late_observer"] for flag in flags) else "failed"
        record["vanilla_login"]=dict(early.record);record["real_client_interaction_mounted"]=True
        rider.close();clients.remove(rider);time.sleep(1);issue("tandemount_native_test observer-disconnected");time.sleep(.5)
    except Exception as ex:record["error"]=str(ex)
    finally:
        for client in clients:client.close()
    return record

def native_test_mod(mc,args,artifact,mods):
    api=args.cache/f"forge-{mc}-api.jar"
    fml="cpw.mods.fml" if mc=="1.7.10" else "net.minecraftforge.fml"
    interaction="net.minecraftforge.event.entity.player.EntityInteractEvent" if mc!="1.12.2" else "net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract"
    mount="" if mc=="1.7.10" else f"@SubscribeEvent(priority=EventPriority.LOWEST) public void mount(net.minecraftforge.event.entity.EntityMountEvent e) {{ NativeChecks.mountEvent(e); }}"
    server_only="" if mc=="1.7.10" else ",serverSideOnly=true"
    network_policy='@cpw.mods.fml.common.network.NetworkCheckHandler public boolean networkPolicy(java.util.Map<String,String> remote, cpw.mods.fml.relauncher.Side from) { return !Boolean.getBoolean("tandemount.native.requireClient") || from != cpw.mods.fml.relauncher.Side.CLIENT || remote.containsKey("tandemount_native_tests"); }' if mc=="1.7.10" else ""
    shim=f'''package dev.eyecrasher.tandemount.nativetest;
import {fml}.common.Mod;
import {fml}.common.event.FMLServerStartingEvent;
import {fml}.common.event.FMLServerStoppedEvent;
import {fml}.common.eventhandler.SubscribeEvent;
import {fml}.common.eventhandler.EventPriority;
@Mod(modid="tandemount_native_tests",name="Tandemount native tests",version="1",dependencies="required-after:tandemount",acceptableRemoteVersions="*"{server_only})
public final class NativeTestMod {{
 @Mod.EventHandler public void start(FMLServerStartingEvent e) {{ NativeChecks.register(e); net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this); }}
 @Mod.EventHandler public void stopped(FMLServerStoppedEvent e) {{ NativeChecks.stopped(); }}
 @SubscribeEvent(priority=EventPriority.LOWEST) public void interaction({interaction} e) {{ NativeChecks.interaction(e); }}
 {mount}
 {network_policy}
}}
'''
    with tempfile.TemporaryDirectory(prefix=f"native-tests-{mc}-",dir=args.cache) as temp:
        temp=Path(temp);source=temp/"NativeTestMod.java";source.write_text(shim);classes=temp/"classes";classes.mkdir()
        subprocess.run([str(args.java/"bin/javac"),"-source","8","-target","8","-Xlint:-path","-encoding","UTF-8","-cp",os.pathsep.join(map(str,[api,artifact,args.cache/"asm-5.0.3.jar"])),"-d",str(classes),str(source),str(b.ROOT/"mods/legacy/native-test/NativeChecks.java")],check=True)
        table={"minecraft":mc}
        with zipfile.ZipFile(args.cache/f"mcp-{mc}-names.zip") as z:
            for name in ["methods.csv","fields.csv"]:
                for row in csv.DictReader(io.StringIO(z.read(name).decode())):table[row["searge"]]=row["name"]
        (classes/"test-names.properties").write_text("\n".join(k+"="+v for k,v in sorted(table.items()))+"\n")
        output=mods/"tandemount-native-tests.jar"
        with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as z:
            for p in classes.rglob("*"):
                if p.is_file():z.write(p,p.relative_to(classes))
    return output

def install(mc,args):
    sdk=b.SDK[mc];folder=args.cache/f"server-{mc}";folder.mkdir(parents=True,exist_ok=True)
    jar=b.fetch(args.cache,f"forge-{sdk}-installer.jar",b.MAVEN+f"net/minecraftforge/forge/{sdk}/forge-{sdk}-installer.jar")
    launch=list(folder.glob("forge-*-universal.jar")) or list(folder.glob("forge-*.jar"))
    if not launch:
        with (folder/"install.log").open("w") as log:
            subprocess.run([str(args.java/"bin/java"),"-Xmx512m","-jar",str(jar),"--installServer",str(folder)],cwd=folder,stdout=log,stderr=subprocess.STDOUT,check=True)
    return folder,next(iter(list(folder.glob("forge-*-universal.jar")) or list(folder.glob("forge-*.jar"))))

def launch_native(folder,launch,args,properties=()):
    output=queue.Queue();lines=[]
    command=[str(args.java/"bin/java"),"-Xms256m","-Xmx1g","-Dlog4j2.formatMsgNoLookups=true",*properties,"-jar",str(launch),"nogui"]
    process=subprocess.Popen(command,cwd=folder,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    def consume():
        for line in process.stdout:lines.append(line);output.put(line)
    reader=threading.Thread(target=consume,daemon=True);reader.start()
    ready=False;deadline=time.monotonic()+180
    while time.monotonic()<deadline and process.poll() is None:
        try:line=output.get(timeout=1)
        except queue.Empty:continue
        if '[Tandemount] Ready' in line:ready=True;break
    return process,output,lines,reader,ready

def stop_native(process,reader):
    if process.poll() is None:
        try:process.stdin.write("stop\n");process.stdin.flush()
        except BrokenPipeError:pass
        try:process.wait(timeout=45)
        except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=10)
    reader.join(timeout=2)

def parsed_checks(lines):
    return [json.loads(line[line.index('{'):]) for line in lines if "[TandemountNative] {" in line]

def run(mc,args):
    if not args.accept_eula:raise SystemExit("Specify --accept-eula after accepting https://aka.ms/MinecraftEULA for these disposable servers.")
    folder,launch=install(mc,args)
    artifact=args.artifacts/f"tandemount-forge-{mc}-1.0.0.jar"
    if not artifact.exists():raise SystemExit(f"Build the exact artifact first: {artifact}")
    with zipfile.ZipFile(artifact) as z:
        if f"Tandemount-Minecraft: {mc}\n".encode() not in z.read("META-INF/MANIFEST.MF"):raise SystemExit("Exact-version artifact mismatch")
    mods=folder/"mods";mods.mkdir(exist_ok=True);shutil.copy2(artifact,mods/artifact.name)
    test_mod=native_test_mod(mc,args,artifact,mods)
    test_source_digest=hashlib.sha256(Path(__file__).read_bytes()+(b.ROOT/"mods/legacy/native-test/NativeChecks.java").read_bytes()).hexdigest()
    test_mod_digest=sha(test_mod)
    (folder/"eula.txt").write_text("# Explicitly accepted by the --accept-eula caller for this disposable fixture.\neula=true\n")
    port={"1.7.10":29710,"1.8.9":29809,"1.12.2":29212}[mc]
    (folder/"server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=tandemount-native-fixture\nlevel-type=FLAT\nspawn-protection=0\nmax-players=8\nview-distance=3\ngenerate-structures=false\nspawn-monsters=true\nspawn-animals=true\nallow-flight=true\n")
    logfile=folder/"native-test.log"
    process,output,lines,reader,ready=launch_native(folder,launch,args)
    checks=[];vanilla={"status":"not-run"};observers={"status":"not-run"};restart={"status":"not-run"};network_policy={"status":"not-applicable"};stop_clients=[]
    if ready:
        for text in ["gamerule doMobSpawning false","tandemount status","tandemount help","tandemount vertical +","tandemount rear +","tandemount vertical NaN","tandemount reset"]:
            process.stdin.write(text+"\n");process.stdin.flush();time.sleep(.25)
        process.stdin.write("tandemount_native_test\n");process.stdin.flush()
        check_deadline=time.monotonic()+45
        while time.monotonic()<check_deadline and process.poll() is None:
            try:line=output.get(timeout=1)
            except queue.Empty:continue
            if '"name":"suite-complete"' in line or '"name":"suite-exception"' in line:break
        vanilla=vanilla_login(mc,port)
        observers=protocol_observers(mc,port,process,output)
        try:
            stop_clients=[VanillaClient(mc,port,"StopDriver"),VanillaClient(mc,port,"StopRider")]
            process.stdin.write("tandemount_native_test stop-fixture\n");process.stdin.flush();time.sleep(.5)
        except Exception as ex:checks.append({"name":"native-stop-fixture-started","passed":False,"error":str(ex)})
    stop_native(process,reader);logfile.write_text("".join(lines))
    for client in stop_clients:client.close()
    if ready:
        second,second_output,second_lines,second_reader,second_ready=launch_native(folder,launch,args)
        stop_native(second,second_reader)
        restart_log=folder/"native-restart.log";restart_log.write_text("".join(second_lines))
        restart_checks=parsed_checks(second_lines)
        restart_passed=second_ready and second.returncode==0 and any(c["name"]=="restart-seat-absent-from-native-world" and c["passed"] for c in restart_checks)
        restart={"status":"passed" if restart_passed else "failed","checks":restart_checks,"exit_code":second.returncode,"log":str(restart_log),"log_sha256":sha(restart_log)}
        if mc=="1.7.10":
            policy,policy_output,policy_lines,policy_reader,policy_ready=launch_native(folder,launch,args,["-Dtandemount.native.requireClient=true"])
            deadline=time.monotonic()+10
            while time.monotonic()<deadline and not any(c["name"]=="client-required-mod-prevents-vanilla-bridge" for c in parsed_checks(policy_lines)):
                if policy.poll() is not None:break
                time.sleep(.1)
            stop_native(policy,policy_reader)
            policy_log=folder/"native-network-policy.log";policy_log.write_text("".join(policy_lines));policy_checks=parsed_checks(policy_lines)
            policy_passed=policy_ready and policy.returncode==0 and any(c["name"]=="client-required-mod-prevents-vanilla-bridge" and c["passed"] for c in policy_checks)
            network_policy={"status":"passed" if policy_passed else "failed","checks":policy_checks,"exit_code":policy.returncode,"log":str(policy_log),"log_sha256":sha(policy_log)}
    body="".join(lines)
    checks.append({"name":"native-bootstrap","passed":ready and "[Tandemount] Ready" in body})
    checks.append({"name":"console-commands-and-configuration","passed":ready and "Tandemount settings saved." in body and "Settings unchanged:" in body})
    checks.append({"name":"native-stop-saves-world-cleanly","passed":"Saving chunks for level" in body and "Exception stopping the server" not in body})
    native=parsed_checks(lines)
    passed=bool(native) and all(c["passed"] for c in native) and any(c["name"]=="suite-complete" for c in native)
    result={"schema":1,"completed_at":datetime.now(timezone.utc).isoformat(),"minecraft":mc,"platform":"forge","sdk":b.SDK[mc],"artifact":str(artifact.resolve()),"artifact_sha256":sha(artifact),"launcher_sha256":sha(launch),"exit_code":process.returncode,"native_checks":checks,"synthetic_behavior":{"status":"passed" if passed else "failed-or-not-run","checks":native},"vanilla_socket_login":vanilla,"protocol_observers":observers,"restart":restart,"network_policy":network_policy,"renderer_analysis":{"status":"passed-static-official-client-bytecode-audit","evidence":"docs/LEGACY-RENDERER.md"},"human_visual":"unverified","log":str(logfile),"log_sha256":sha(logfile),"test_sources_sha256":test_source_digest,"test_mod_sha256":test_mod_digest}
    result["status"]="passed" if passed and all(c["passed"] for c in checks) and vanilla["status"]=="passed" and observers["status"]=="passed" and restart["status"]=="passed" and network_policy["status"] in ["passed","not-applicable"] and process.returncode==0 else "failed"
    (folder/"native-results.json").write_text(json.dumps(result,indent=2)+"\n");print(json.dumps({k:result[k] for k in ["minecraft","artifact_sha256","completed_at","status"]}));return result

def compile_only(mc,args):
    """Compile the exact native fixture without accepting an EULA or starting a server."""
    artifact=args.artifacts/f"tandemount-forge-{mc}-1.0.0.jar"
    if not artifact.exists():raise SystemExit(f"Build the exact artifact first: {artifact}")
    with zipfile.ZipFile(artifact) as z:
        if f"Tandemount-Minecraft: {mc}\n".encode() not in z.read("META-INF/MANIFEST.MF"):raise SystemExit("Exact-version artifact mismatch")
    mods=args.cache/"compile-only"/mc/"mods";mods.mkdir(parents=True,exist_ok=True)
    test_mod=native_test_mod(mc,args,artifact,mods)
    test_source_digest=hashlib.sha256(Path(__file__).read_bytes()+(b.ROOT/"mods/legacy/native-test/NativeChecks.java").read_bytes()).hexdigest()
    result={"schema":1,"minecraft":mc,"platform":"forge","sdk":b.SDK[mc],"status":"compile-only-passed","artifact":str(artifact.resolve()),"artifact_sha256":sha(artifact),"test_mod":str(test_mod.resolve()),"test_mod_sha256":sha(test_mod),"test_sources_sha256":test_source_digest,"native_behavior":"not-run","renderer_analysis":"passed-static-official-client-bytecode-audit; see docs/LEGACY-RENDERER.md","human_visual":"unverified"}
    print(json.dumps(result));return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--minecraft",choices=b.SDK);p.add_argument("--accept-eula",action="store_true");p.add_argument("--compile-only",action="store_true",help="compile native fixtures without starting a server or writing EULA acceptance")
    p.add_argument("--cache",type=Path,default=b.DEFAULT_CACHE);p.add_argument("--java",type=Path)
    p.add_argument("--artifacts",type=Path);p.add_argument("--output",type=Path)
    args=p.parse_args();args.java=b.java8_home(args.java);args.artifacts=args.artifacts or args.cache/"artifacts";args.cache.mkdir(parents=True,exist_ok=True)
    if args.compile_only:
        args.output=args.output or b.ROOT/".agents/legacy-native-compile-results.json"
        results=[compile_only(mc,args) for mc in ([args.minecraft] if args.minecraft else b.SDK)]
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps({"schema":1,"targets":results},indent=2)+"\n")
        return
    args.output=args.output or b.ROOT/".agents/legacy-native-results.json"
    results=[run(mc,args) for mc in ([args.minecraft] if args.minecraft else b.SDK)]
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps({"schema":1,"targets":results},indent=2)+"\n")
    if any(r["status"]!="passed" for r in results):raise SystemExit(1)

if __name__=="__main__":main()
