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
import uuid
import zipfile
import zlib
from datetime import datetime, timezone
from legacy_evidence import (CLIENT_TICKS, LEGACY_REPORT, horse_moved, legacy_fixture_digest,
                             lifetime_passed, observer_snapshot_passed)
from runtime_storage import RuntimeWorkspace

spec=importlib.util.spec_from_file_location("legacy_build",Path(__file__).with_name("legacy-build.py"))
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
client_spec=importlib.util.spec_from_file_location("legacy_client_test",Path(__file__).with_name("legacy-client-test.py"))
client_probe=importlib.util.module_from_spec(client_spec);client_spec.loader.exec_module(client_probe)

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def before_join(mc, identifier, body):
    if mc == "1.7.10" and identifier == 0x00 and len(body) == 4:
        return b"KEEPALIVE", body
    if identifier == (0x1a if mc == "1.12.2" else 0x40):
        length, offset = read_varint(body)
        raise ValueError("Play disconnect before JoinGame: " + body[offset:offset+length].decode())
    if identifier == (0x18 if mc == "1.12.2" else 0x3f):
        length, offset = read_varint(body)
        channel = body[offset:offset+length]
        if channel in (b"REGISTER", b"FML|HS"):
            return channel, body[offset+length:]
    raise ValueError(f"Unexpected Play packet 0x{identifier:02x} before JoinGame")

def vanilla_login(mc,port,fixture_version=None,on_forge_hello=None):
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
    record={"status":"failed","protocol":protocol,"hostname_fml_marker":False,"client_mod_channels_sent":False,"login_success":False,"play_join_game":False,"ordered_play_login":False}
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
                    record.update(play_join_game=True, ordered_play_login=True, status="passed");break
                else:
                    channel, payload = before_join(mc, identifier, data[offset:])
                    if on_forge_hello is not None and channel == b"FML|HS" and len(payload) >= 4 and payload[2] == 0:
                        on_forge_hello(connection.getsockname()[1])
                        on_forge_hello = None
                    if fixture_version is not None and channel == b"FML|HS" and not record["client_mod_channels_sent"]:
                        if mc != "1.7.10" or len(payload) < 4 or payload[2] != 0:
                            raise ValueError("Expected Forge 1.7.10 ServerHello")
                        def custom(name, content):
                            packet(b"\x17" + string(name) + struct.pack(">H", len(content)) + content)
                        custom("REGISTER", b"FML|HS\0FML\0FORGE")
                        custom("FML|HS", b"\x01" + payload[3:4])
                        custom("FML|HS", b"\x02\x01" + string("tandemount_native_tests") + string(fixture_version))
                        record["client_mod_channels_sent"] = True
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
        self.compression=None;self.play=False;self.closed=False;self.buffer=bytearray();self.self_id=None
        self.entities={};self.players=set();self.mounts={};self.error=None;self.joined=threading.Event();self.lock=threading.Lock()
        self.traces=[];self.chats=[];self.movement_ready=threading.Event()
        self.packet_counts={};self.location=(0.0,0.0,0.0,0.0,0.0)
        self.record={"status":"failed","protocol":{"1.7.10":5,"1.8.9":47,"1.12.2":340}[mc],"hostname_fml_marker":False,"client_mod_channels_sent":False,"login_success":False,"play_join_game":False}
        self.connection=socket.create_connection(("127.0.0.1",port),timeout=15);self.connection.settimeout(.25)
        self.send(0,varint(self.record["protocol"])+protocol_string("127.0.0.1")+struct.pack(">H",port)+varint(2))
        self.send(0,protocol_string(name))
        self.thread=threading.Thread(target=self.receive,daemon=True);self.thread.start()
        if not self.joined.wait(20):
            self.close();raise RuntimeError(self.error or "Vanilla client did not receive JoinGame")
        self.movement_thread=threading.Thread(target=self.movement,daemon=True);self.movement_thread.start()

    def movement(self):
        while not self.closed:
            time.sleep(.05)
            if not self.movement_ready.is_set():continue
            try:
                x,y,z,yaw,pitch=self.location
                if self.mounts.get(self.self_id,-1)>=0:self.send(0x0c if self.modern else 3,struct.pack("?",False))
                elif self.oldest:self.send(6,struct.pack(">ddddff?",x,y,y+1.62,z,yaw,pitch,False))
                else:self.send(0x0e if self.modern else 6,struct.pack(">dddff?",x,y,z,yaw,pitch,False))
            except OSError:return

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
        join_game=0x23 if self.modern else 1
        if self.self_id is None and identifier != join_game:
            before_join(self.mc,identifier,data)
            return
        aliases={0 if self.modern else 0x0e:"SpawnObject",0x03 if self.modern else 0x0f:"SpawnMob",0x3e if self.modern else 0x12:"EntityVelocity",0x4c if self.modern else 0x18:"EntityTeleport",0x3c if self.modern else 0x1c:"EntityMetadata",0x36 if self.modern else 0x19:"EntityHeadLook"}
        if identifier in aliases:
            alias=aliases[identifier]
            entity=struct.unpack_from(">i",data)[0] if self.oldest and alias not in ("SpawnObject","SpawnMob") else read_varint(data)[0]
            self.traces.append((alias,entity,data.hex()))
        if identifier==(0x0f if self.modern else 2):
            length,offset=read_varint(data)
            self.chats.append(json.loads(data[offset:offset+length].decode("utf-8")))
        if identifier==join_game:
            self.self_id=struct.unpack_from(">i",data)[0]
            # The original JoinGame handler sends ordinary client settings before
            # its first movement frame. In particular these establish the view
            # distance used by native player/entity tracking.
            settings=protocol_string("en_us")+bytes([8,0,1])
            settings+=bytes([2,1]) if self.oldest else bytes([127])+(varint(1) if self.modern else b"")
            self.send(4 if self.modern else 0x15,settings)
            self.record.update(status="passed",play_join_game=True,ordered_play_login=True);self.joined.set()
        elif identifier==(0x1f if self.modern else 0):self.send(0x0b if self.modern else 0,data)
        elif identifier==(0x2f if self.modern else 8):
            values=list(struct.unpack_from(">dddff",data));flags=0 if self.oldest else data[32]
            values=[value+(self.location[i] if flags&(1<<i) else 0) for i,value in enumerate(values)]
            # 1.7's S08 Y is stance/eye position. The original client acknowledges
            # with bounding-box minY followed by posY, not Y followed by Y+eye.
            if self.oldest:values[1]-=1.6200000047683716
            self.location=tuple(values)
            if self.modern:
                teleport,_=read_varint(data,33);self.send(0,varint(teleport))
            x,y,z,yaw,pitch=values
            if self.oldest:self.send(6,struct.pack(">ddddff?",x,y,y+1.62,z,yaw,pitch,False))
            else:self.send(0x0e if self.modern else 6,struct.pack(">dddff?",x,y,z,yaw,pitch,False))
            self.movement_ready.set()
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
            state=struct.unpack_from(">i",data,offset+(24 if self.modern else 12)+2)[0]
            self.entities[entity]={"kind":"object","type":kind,"position":position,"spawn_data":state}
        elif identifier==(0x05 if self.modern else 0x0c):self.players.add(read_varint(data)[0])
        elif identifier==(0x32 if self.modern else 0x13):
            if self.oldest:
                removed=struct.unpack_from(">"+str(data[0])+"i",data,1)
            else:
                count,offset=read_varint(data)
                removed=[]
                for _ in range(count):
                    entity,offset=read_varint(data,offset)
                    removed.append(entity)
            for entity in removed:
                self.players.discard(entity)
                self.entities.pop(entity,None)
                self.mounts.pop(entity,None)
            self.mounts={rider:vehicle for rider,vehicle in self.mounts.items() if vehicle not in removed}
        elif not self.modern and identifier==0x1b:
            rider,vehicle=struct.unpack_from(">ii",data)
            self.traces.append(("EntityAttach",rider,data.hex()))
            if data[8]==0 and self.knows_entity(rider) and (vehicle<0 or self.knows_entity(vehicle)):
                self.mounts[rider]=vehicle
        elif self.modern and identifier==0x43:
            vehicle,offset=read_varint(data)
            if not self.knows_entity(vehicle):return
            count,offset=read_varint(data,offset)
            for rider in [r for r,v in self.mounts.items() if v==vehicle]:del self.mounts[rider]
            for _ in range(count):
                rider,offset=read_varint(data,offset)
                if self.knows_entity(rider):self.mounts[rider]=vehicle
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

    def knows_entity(self,entity):
        return entity==self.self_id or entity in self.players or entity in self.entities

    def interact(self,entity):
        if self.oldest:self.send(2,struct.pack(">iB",entity,0))
        else:self.send(0x0a if self.modern else 2,varint(entity)+varint(0)+(varint(0) if self.modern else b""))

    def command(self,text):self.send(2 if self.modern else 1,protocol_string(text))

    def root(self,entity):
        seen=set()
        while self.mounts.get(entity,-1)>=0 and entity not in seen:
            seen.add(entity);entity=self.mounts[entity]
        return entity

    def observe(self,state):
        seat=self.entities.get(state["seat"])
        expected_type={"1.7.10":55,"1.8.9":78,"1.12.2":3}[self.mc]
        position=list(seat["position"]) if seat else None
        tolerance=.04 if not self.modern else .002
        return {"seat_spawned":bool(seat and seat["kind"]==("mob" if self.oldest else "object") and seat["type"]==expected_type),"driver_spawned":state["driver"] in self.players,"rider_spawned":state["rider"] in self.players,
                "driver_on_horse":self.mounts.get(state["driver"])==state["horse"],"rider_on_seat":self.mounts.get(state["rider"])==state["seat"],
                "received_rider_vehicle":self.mounts.get(state["rider"]),
                "received_driver_vehicle":self.mounts.get(state["driver"]),
                "received_driver_mount_packets":[data for alias,entity,data in self.traces if alias=="EntityAttach" and entity==state["driver"]],
                "received_rider_mount_packets":[data for alias,entity,data in self.traces if alias=="EntityAttach" and entity==state["rider"]],
                "seat_has_horse_root":self.root(state["seat"])==state["horse"],"rider_has_horse_root":self.root(state["rider"])==state["horse"],
                "seat_position":position,"position_matches_native":bool(position and all(abs(a-b)<=tolerance for a,b in zip(position,state["position"]))),"reader_error":self.error,
                "received_packet_counts":{hex(k):v for k,v in sorted(self.packet_counts.items())}}

    def close(self):
        self.closed=True
        try:self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:pass
        self.connection.close()
        if hasattr(self,"thread"):self.thread.join(timeout=2)
        if hasattr(self,"movement_thread"):self.movement_thread.join(timeout=2)

def protocol_observers(mc,port,process,output,args,folder):
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
    def components(value):
        if isinstance(value,dict):
            yield value
            for item in value.values():yield from components(item)
        elif isinstance(value,list):
            for item in value:yield from components(item)
    try:
        early=VanillaClient(mc,port,"VanillaEarly");clients.append(early)
        early.movement_ready.wait(10);time.sleep(.1)
        driver=VanillaClient(mc,port,"VanillaDriver");clients.append(driver);driver.movement_ready.wait(10);time.sleep(.1)
        rider=VanillaClient(mc,port,"VanillaRider");clients.append(rider);rider.movement_ready.wait(10);time.sleep(.1)
        state("admin-ready","observer-op");index=len(early.chats);early.command("/tandemount")
        expected_clicks={"/tandemount vertical +","/tandemount vertical -","/tandemount rear +","/tandemount rear -","/tandemount reload","/tandemount reset"}
        deadline=time.monotonic()+15;clicks=set();flat=[]
        while time.monotonic()<deadline:
            flat=list(components(early.chats[index:]));clicks={part["clickEvent"]["value"] for part in flat if part.get("clickEvent",{}).get("action")=="run_command"}
            if expected_clicks<=clicks:break
            time.sleep(.1)
        ordinary_index=len(rider.chats);rider.command("/tandemount");rider.command("/tandemount rear +")
        deadline=time.monotonic()+15
        while time.monotonic()<deadline and not any("Permission denied" in part.get("text","") for part in components(rider.chats[ordinary_index:])):time.sleep(.1)
        ordinary_chat=rider.chats[ordinary_index:];ordinary_flat=list(components(ordinary_chat))
        record["settings_menu"]={"status":"passed" if expected_clicks<=clicks and {"gold","aqua"}<={part.get("color") for part in flat}
            and not any(part.get("clickEvent") for part in ordinary_flat) and any("Permission denied" in part.get("text","") for part in ordinary_flat) else "failed",
            "admin_run_commands":sorted(clicks),"colors":sorted({part["color"] for part in flat if "color" in part}),"ordinary_denied":any("Permission denied" in part.get("text","") for part in ordinary_flat),"observed_native_chat":True}
        prepared=state("prepared","observer-prepare")
        horse,driver_id,rider_id=(prepared[key] for key in ("horse","driver","rider"))
        deadline=time.monotonic()+10
        while horse not in early.entities or not {driver_id,rider_id}<=early.players:
            if early.error or time.monotonic()>deadline:
                raise RuntimeError(early.error or "Existing client did not track the horse and both players before either mounted")
            time.sleep(.05)
        driver.interact(horse)
        deadline=time.monotonic()+10
        while early.mounts.get(driver_id)!=horse or driver_id not in early.players:
            if early.error or time.monotonic()>deadline:
                raise RuntimeError(early.error or "Existing client did not receive the ordinary driver mount before the tandem interaction")
            time.sleep(.05)
        driver_only=state("state","observer-state")
        record["driver_mount_before_passenger"]={
            "native_driver_root":driver_only["driver_root"],
            "wire_driver_vehicle":early.mounts.get(driver_id),
            "horse":horse,
        }
        if driver_only["driver_root"]!=horse:
            raise AssertionError("The ordinary driver mount did not persist before the tandem interaction")
        rider.interact(horse)
        deadline=time.monotonic()+10
        initial=state("state","observer-state")
        while not initial["joined"] and time.monotonic()<deadline:
            time.sleep(.05)
            initial=state("state","observer-state")
        if not initial["joined"]:raise AssertionError("Real vanilla UseEntity interactions did not create a shared ride")
        late=VanillaClient(mc,port,"VanillaLate");clients.append(late);issue("tp VanillaLate 12 8 8");time.sleep(2)
        def settled_observers(phase, target_z=None):
            deadline = time.monotonic() + 8
            while True:
                native = state("state", "observer-state")
                existing = early.observe(native)
                late_view = late.observe(native)
                view = {"native": native, "existing_observer": existing, "late_observer": late_view}
                if (observer_snapshot_passed(view, phase)
                        and (phase != "movement" or horse_moved(record["initial"]["native"], native))
                        and (target_z is None or abs(native["horse_position"][2]-target_z)<.0625)):
                    return view
                if time.monotonic() >= deadline:
                    raise TimeoutError("Native follower and both observers did not agree during " + phase)
                time.sleep(.1)

        record["initial"] = settled_observers("initial")
        frame_alias="EntityVelocity" if mc=="1.7.10" else "EntityHeadLook" if mc=="1.8.9" else "EntityTeleport"
        deadline=time.monotonic()+max(60,args.client_ticks/10)
        while any(sum(alias==frame_alias and entity==initial["seat"] for alias,entity,_ in client.traces)<args.client_ticks+24
                  for client in (early, late)):
            if time.monotonic()>deadline:raise TimeoutError("Insufficient observed native correction frames")
            time.sleep(.1)
        stationary_traces = {
            role: (list(client.traces), client.entities[initial["seat"]].get("spawn_data", 55))
            for role, client in (("existing", early), ("late", late))
        }
        if mc=="1.12.2":
            x,y,z=record["initial"]["native"]["horse_position"]
            for step in range(1,31):
                driver.send(0x10,struct.pack(">dddff",x,y,z+step*.05,0.0,0.0))
                time.sleep(.05)
            record["movement"]=settled_observers("movement",target_z=z+1.5)
            record["driver_vehicle_move_accepted"]=True
        else:
            state("moving","observer-move")
            record["movement"] = settled_observers("movement")
        early.command("/tandemount vertical +")
        early.command("/tandemount rear +")
        record["live_adjustment"] = settled_observers("live_adjustment")
        record["settings_menu"]["encoded_click_command_applied"] = {"/tandemount vertical +", "/tandemount rear +"} <= clicks
        record["vanilla_login"]=dict(early.record);record["real_client_interaction_mounted"]=True
        lifetime={}
        for role, (stationary_trace, spawn_data) in stationary_traces.items():
            trace=folder/(role+"-client-trace.tsv")
            frames=[]
            for alias,entity,data in stationary_trace:
                if entity!=initial["seat"]:continue
                frames.append(alias+"\t"+data)
                if alias==frame_alias:frames.append("tick")
            trace.write_text("\n".join(frames)+"\n")
            lifetime[role]=client_probe.probe(mc,args.java,args.cache,args.cache/"official-client",trace,initial["seat"],
                spawn_data,args.client_ticks,work=folder/(role+"-client"))
        record["native_client_lifetime"]=lifetime
        record["native_attachment"]={"shared_horse_root":all(value["native"]["seat_root"]==value["native"]["horse"]
            and all(value[observer]["seat_has_horse_root"] and value[observer]["rider_has_horse_root"] for observer in ["existing_observer","late_observer"])
            for value in [record["initial"],record["movement"],record["live_adjustment"]]),
            "mode":"early-follower","human_visual":"unverified"}
        rider.close();clients.remove(rider);time.sleep(1);issue("tandemount_native_test observer-disconnected");time.sleep(.5)
        record["status"] = "passed"
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
    network_policy=f'@{fml}.common.network.NetworkCheckHandler public boolean networkPolicy(java.util.Map<String,String> remote, {fml}.relauncher.Side from) {{ if (from != {fml}.relauncher.Side.CLIENT) return true; String version = remote.get("tandemount_native_tests"); return version == null ? !Boolean.getBoolean("tandemount.native.requireClient") : version.equals("1"); }}' if mc!="1.12.2" else ""
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
        subprocess.run([str(args.java/"bin/javac"),"-source","8","-target","8","-Xlint:-path","-encoding","UTF-8","-cp",os.pathsep.join(map(str,[api,artifact,args.cache/"asm-5.0.3.jar"])),"-d",str(classes),str(source),str(b.ROOT/"forge/legacy/native-test/NativeChecks.java")],check=True)
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
    command=[str(args.java/"bin/java"),"-Xms256m","-Xmx1g","-XX:ActiveProcessorCount=2","-Dlog4j2.formatMsgNoLookups=true",*properties,"-jar",str(launch),"nogui"]
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
    distribution,launcher=install(mc,args)
    workspace=RuntimeWorkspace(args.cache/"native-runs",mc+"-"+uuid.uuid4().hex,distribution)
    folder=workspace.directory
    launch=folder/launcher.name
    vanilla_jar=folder/f"minecraft_server.{mc}.jar"
    server_identity={"minecraft":mc,"platform":"forge","sdk":b.SDK[mc],
                     "launch_files":{p.name:sha(p) for p in (launch,vanilla_jar)}}
    artifact=args.artifacts/b.artifact_name("forge",mc)
    if not artifact.exists():raise SystemExit(f"Build the exact artifact first: {artifact}")
    with zipfile.ZipFile(artifact) as z:
        if f"Tandemount-Minecraft: {mc}\n".encode() not in z.read("META-INF/MANIFEST.MF"):raise SystemExit("Exact-version artifact mismatch")
    mods=folder/"mods";mods.mkdir(exist_ok=True);shutil.copy2(artifact,mods/artifact.name)
    test_mod=native_test_mod(mc,args,artifact,mods)
    test_sources_sha256=legacy_fixture_digest(b.ROOT)
    test_mod_digest=sha(test_mod)
    (folder/"eula.txt").write_text("# Explicitly accepted by the --accept-eula caller for this disposable fixture.\neula=true\n")
    port=args.port
    (folder/"server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=tandemount-native-fixture\nlevel-type=FLAT\nspawn-protection=0\nmax-players=8\nview-distance=3\ngenerate-structures=false\nspawn-monsters=true\nspawn-animals=true\nallow-flight=true\n")
    logfile=folder/"native-test.log"
    process,output,lines,reader,ready=launch_native(folder,launch,args)
    checks=[];vanilla={"status":"not-run"};observers={"status":"not-run"};restart={"status":"not-run"};network_policy={"status":"not-applicable"};stop_clients=[]
    if ready:
        # Keep the fixture within the normal login area. Teleporting a whole
        # group here from a distant random spawn can leave old native trackers
        # comparing their previous coordinates until the first mount moves.
        for text in ["setworldspawn 8 5 8","gamerule doMobSpawning false","tandemount status","tandemount help","tandemount vertical +","tandemount rear +","tandemount vertical NaN","tandemount reset"]:
            process.stdin.write(text+"\n");process.stdin.flush();time.sleep(.25)
        process.stdin.write("tandemount_native_test\n");process.stdin.flush()
        check_deadline=time.monotonic()+45
        while time.monotonic()<check_deadline and process.poll() is None:
            try:line=output.get(timeout=1)
            except queue.Empty:continue
            if '"name":"suite-complete"' in line or '"name":"suite-exception"' in line:break
        vanilla=vanilla_login(mc,port)
        observers=protocol_observers(mc,port,process,output,args,folder)
        incompatible=vanilla_login(mc,port,fixture_version="incompatible") if mc=="1.7.10" else None
        try:
            stop_driver=VanillaClient(mc,port,"StopDriver");stop_clients.append(stop_driver);stop_driver.movement_ready.wait(10);time.sleep(.1)
            stop_rider=VanillaClient(mc,port,"StopRider");stop_clients.append(stop_rider);stop_rider.movement_ready.wait(10);time.sleep(.1)
            process.stdin.write("tandemount_native_test stop-fixture\n");process.stdin.flush();time.sleep(.5)
        except Exception as ex:checks.append({"name":"native-stop-fixture-started","passed":False,"error":str(ex)})
    stop_native(process,reader);logfile.write_text("".join(lines))
    for client in stop_clients:client.close()
    if ready:
        second,second_output,second_lines,second_reader,second_ready=launch_native(folder,launch,args)
        stop_native(second,second_reader)
        restart_log=folder/"native-restart.log";restart_log.write_text("".join(second_lines))
        restart_checks=parsed_checks(second_lines)
        first_checks=parsed_checks(lines)
        restart_passed=(second_ready and second.returncode==0
            and any(c["name"]=="native-server-stop-cleans-seat" and c["passed"] for c in first_checks)
            and any(c["name"]=="restart-seat-absent-from-native-world" and c["passed"] for c in restart_checks))
        restart={"status":"passed" if restart_passed else "failed","checks":restart_checks,"exit_code":second.returncode,"log":str(restart_log),"log_sha256":sha(restart_log)}
        if mc!="1.12.2":
            policy,policy_output,policy_lines,policy_reader,policy_ready=launch_native(folder,launch,args,["-Dtandemount.native.requireClient=true"])
            def expire_vanilla(client_port):
                policy.stdin.write(f"tandemount_native_test expire-vanilla {client_port}\n")
                policy.stdin.flush()
            denied=vanilla_login(mc,port,on_forge_hello=expire_vanilla if mc=="1.7.10" else None) if policy_ready else {}
            stop_native(policy,policy_reader)
            policy_log=folder/"native-network-policy.log";policy_log.write_text("".join(policy_lines));policy_checks=parsed_checks(policy_lines)
            expected="No modded response received" if mc=="1.7.10" else "requires FML/Forge"
            policy_passed=policy_ready and policy.returncode==0 and not denied.get("play_join_game") and expected in denied.get("error","")
            if incompatible is not None:
                policy_passed=(policy_passed and incompatible["client_mod_channels_sent"]
                    and not incompatible["play_join_game"] and "Mod rejections" in incompatible.get("error","")
                    and any(c["name"]=="client-required-server-retains-forge-handshake-deadline" and c["passed"] for c in policy_checks))
            network_policy={"status":"passed" if policy_passed else "failed","checks":policy_checks,"vanilla_rejection":denied,
                            "incompatible_mod_list":incompatible,"exit_code":policy.returncode,"log":str(policy_log),"log_sha256":sha(policy_log)}
    body="".join(lines)
    checks.append({"name":"native-bootstrap","passed":ready and "[Tandemount] Ready" in body})
    checks.append({"name":"console-commands-and-configuration","passed":ready and "Tandemount settings saved." in body and "Settings unchanged:" in body})
    checks.append({"name":"native-stop-saves-world-cleanly","passed":"Saving chunks for level" in body and "Exception stopping the server" not in body})
    native=parsed_checks(lines)
    passed=({"suite-complete", "real-vanilla-disconnect-cleans-seat"} <= {c["name"] for c in native}
            and all(c["passed"] for c in native))
    result={"schema":2,"completed_at":datetime.now(timezone.utc).isoformat(),"minecraft":mc,"platform":"forge","sdk":b.SDK[mc],"server":server_identity,"artifact":str(artifact.resolve()),"artifact_sha256":sha(mods/artifact.name),"exit_code":process.returncode,"native_checks":checks,"synthetic_behavior":{"status":"passed" if passed else "failed-or-not-run","checks":native},"vanilla_socket_login":vanilla,"protocol_observers":observers,"restart":restart,"network_policy":network_policy,"renderer_analysis":{"status":"passed-static-official-client-bytecode-audit","evidence":"docs/LEGACY-RENDERER.md"},"human_visual":"unverified","log":str(logfile),"log_sha256":sha(logfile),"test_sources_sha256":test_sources_sha256,"test_mod_sha256":test_mod_digest}
    menu=observers.get("settings_menu",{});lifetime=observers.get("native_client_lifetime",{})
    result["client_lifetime"]=lifetime
    result["final_checks"]=[
        {"name":"command-chat-has-color-and-style","passed":menu.get("status")=="passed"},
        {"name":"command-chat-has-clickable-adjustments","passed":menu.get("status")=="passed"},
        {"name":"command-click-adjustment-applies","passed":menu.get("encoded_click_command_applied",False)},
        {"name":"vanilla-client-seat-survives-native-ticks","passed":lifetime_passed(lifetime, mc, args.client_ticks)},
        *[{"name":name,"passed":any(c["name"]==name and c["passed"] for c in native)} for name in ["fresh-config-uses-owner-defaults","missing-settings-use-owner-defaults","saved-settings-remain-preserved","reset-restores-owner-defaults"]],
    ]
    if mc=="1.12.2":
        result["final_checks"].append({"name":"shared-ride-driver-vehicle-move-accepted","passed":observers.get("driver_vehicle_move_accepted") is True})
    result["status"]="passed" if passed and all(c["passed"] for c in checks+result["final_checks"]) and vanilla["status"]=="passed" and observers["status"]=="passed" and restart["status"]=="passed" and network_policy["status"] in ["passed","not-applicable"] and process.returncode==0 else "failed"
    result=workspace.finish(result)
    (workspace.retained/"native-results.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps({k:result[k] for k in ["minecraft","artifact_sha256","completed_at","status"]}))
    return result

def compile_only(mc,args):
    """Compile the exact native fixture without accepting an EULA or starting a server."""
    artifact=args.artifacts/b.artifact_name("forge",mc)
    if not artifact.exists():raise SystemExit(f"Build the exact artifact first: {artifact}")
    with zipfile.ZipFile(artifact) as z:
        if f"Tandemount-Minecraft: {mc}\n".encode() not in z.read("META-INF/MANIFEST.MF"):raise SystemExit("Exact-version artifact mismatch")
    mods=args.cache/"compile-only"/mc/"mods";mods.mkdir(parents=True,exist_ok=True)
    test_mod=native_test_mod(mc,args,artifact,mods)
    result={"schema":2,"minecraft":mc,"platform":"forge","sdk":b.SDK[mc],"status":"compile-only-passed","artifact":str(artifact.resolve()),"artifact_sha256":sha(artifact),"test_mod":str(test_mod.resolve()),"test_mod_sha256":sha(test_mod),"test_sources_sha256":legacy_fixture_digest(b.ROOT),"native_behavior":"not-run","renderer_analysis":"passed-static-official-client-bytecode-audit; see docs/LEGACY-RENDERER.md","human_visual":"unverified"}
    print(json.dumps(result));return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--minecraft",choices=b.SDK);p.add_argument("--accept-eula",action="store_true");p.add_argument("--compile-only",action="store_true",help="compile native fixtures without starting a server or writing EULA acceptance")
    p.add_argument("--cache",type=Path,default=b.DEFAULT_CACHE);p.add_argument("--java",type=Path)
    p.add_argument("--port",type=int,default=25844);p.add_argument("--client-ticks",type=int,default=CLIENT_TICKS)
    p.add_argument("--artifacts",type=Path);p.add_argument("--output",type=Path)
    args=p.parse_args();args.java=b.java8_home(args.java);args.cache=args.cache.resolve();args.artifacts=args.artifacts or args.cache/"artifacts";args.cache.mkdir(parents=True,exist_ok=True)
    if args.compile_only:
        args.output=args.output or b.ROOT/".agents/legacy-native-compile-results.json"
        results=[compile_only(mc,args) for mc in ([args.minecraft] if args.minecraft else b.SDK)]
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps({"schema":1,"targets":results},indent=2)+"\n")
        return
    args.output=args.output or b.ROOT/LEGACY_REPORT
    results=[run(mc,args) for mc in ([args.minecraft] if args.minecraft else b.SDK)]
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({"schema":1,"targets":results},indent=2)+"\n")
    if any(r["status"]!="passed" for r in results):raise SystemExit(1)

if __name__=="__main__":main()
