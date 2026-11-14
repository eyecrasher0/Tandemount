#!/usr/bin/env python3
"""Isolated Paper 26.3 acceptance test using native-encoded client packets."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import queue
import shutil
import struct
import subprocess
import threading
import time
import uuid

from runtime_storage import RuntimeWorkspace

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('TANDEMOUNT_CACHE', ROOT / '.agents/cache'))
spec = importlib.util.spec_from_file_location('plugin_native_protocol', ROOT / 'tools/plugin-runtime.py')
protocol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protocol)

def native_nbt(data):
    offset = 0
    def take(count):
        nonlocal offset
        raw = data[offset:offset + count]
        if len(raw) != count: raise ValueError('Truncated native NBT')
        offset += count
        return raw
    def number(code): return struct.unpack('>' + code, take(struct.calcsize('>' + code)))[0]
    def text(): return take(number('H')).decode('utf-8')
    def payload(kind):
        if kind == 0: return None
        if kind in (1,2,3,4,5,6): return number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
        if kind == 7: return list(take(number('i')))
        if kind == 8: return text()
        if kind == 9:
            child, count = number('B'), number('i')
            return [payload(child) for _ in range(count)]
        if kind == 10:
            result = {}
            while True:
                child = number('B')
                if child == 0: return result
                name = text()
                result[name] = payload(child)
        if kind in (11,12): return [number('i' if kind == 11 else 'q') for _ in range(number('i'))]
        raise ValueError('Unexpected native NBT type ' + str(kind))
    value = payload(number('B'))
    return value, offset

def nodes(value):
    if isinstance(value, dict):
        yield value
        for item in value.values(): yield from nodes(item)
    elif isinstance(value,list):
        for item in value: yield from nodes(item)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plugin',type=Path,required=True)
    parser.add_argument('--fixture',type=Path,required=True)
    parser.add_argument('--label',required=True)
    parser.add_argument('--port',type=int,default=25842)
    parser.add_argument('--accept-eula',action='store_true')
    args = parser.parse_args()
    if not args.accept_eula: parser.error('Disposable native tests require explicit EULA acceptance')
    metadata = json.loads((CACHE/'servers/paper/26.3/distribution.json').read_text())
    workspace = RuntimeWorkspace(CACHE/'paper263-owner-runs', 'owner-'+args.label+'-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6],Path(metadata['file']).parent,extra_names=('cache',))
    run = workspace.directory
    plugins=run/'plugins';plugins.mkdir()
    shutil.copy2(args.plugin,plugins/'Tandemount.jar');shutil.copy2(args.fixture,plugins/'TandemountNativeFixture.jar')
    config=plugins/'Tandemount';config.mkdir()
    (config/'config.yml').write_text((ROOT/'plugin/src/main/resources/config.yml').read_text().replace('debug: false','debug: true'))
    (run/'eula.txt').write_text('eula=true\n')
    (run/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nwhite-list=false\nnetwork-compression-threshold=-1\nlevel-type=FLAT\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nenforce-secure-profile=false\nspawn-animals=false\nspawn-monsters=false\nallow-nether=true\nlevel-seed=1\n')
    (run/'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\nspawn-limits:\n  monsters: 0\n  animals: 0\n')
    command=[protocol.java_for(25),'-Xms256M','-Xmx1G','-XX:ActiveProcessorCount=2','-Dtandemount.client.jar='+str(CACHE/'plugin-renderer-audit/26.3-client.jar'),'-jar',str(run/Path(metadata['file']).name),'nogui']
    log=(run/'server.log').open('w');server=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    lines=queue.Queue()
    def drain():
        for line in server.stdout: log.write(line);log.flush();lines.put(line)
    thread=threading.Thread(target=drain,daemon=True);thread.start()
    clients=[];checks=[];observations={};chat=[]
    result={'kind':'real-client-owner-regression','server':metadata,'artifact_sha256':hashlib.sha256(args.plugin.read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(args.fixture.read_bytes()).hexdigest(),'run':str(run),'status':'failed','assertions':checks}
    fixture=plugins/'TandemountNativeFixture'
    def console(text):server.stdin.write(text+'\n');server.stdin.flush()
    def await_file(file,timeout=30):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            if server.poll() is not None:raise RuntimeError('Native server exited')
            try:return file.read_text()
            except OSError:time.sleep(.1)
        raise TimeoutError(str(file))
    def state():
        try:return json.loads((fixture/'owner-state.json').read_text())
        except json.JSONDecodeError:time.sleep(.05);return state()
    def check(name,value,detail):checks.append({'assertion':name,'status':'passed' if value else 'failed','detail':detail})
    def send_command(client,text):
        client.send(client.identifier('game','serverbound','minecraft:chat_command','ServerboundChatCommandPacket'),protocol.string(text.lstrip('/')))
    try:
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            line=lines.get(timeout=10)
            if 'Done (' in line:break
            if 'could not initialize' in line:raise RuntimeError(line)
        else:raise TimeoutError('Native startup')
        console('tmfixture prepare')
        prepare_deadline=time.monotonic()+60
        while True:
            phase=json.loads(await_file(fixture/'phase.json'))
            if phase.get('phase')=='prepared':break
            if phase.get('phase')=='failed' or time.monotonic()>prepare_deadline:raise RuntimeError('Fixture preparation failed: '+str(phase))
            time.sleep(.1)
        ids=protocol.properties(fixture/'protocol.properties');version=protocol.ping(args.port)
        for name in ['TmDriver','TmRider','TmThird']:
            client=protocol.Client(name,args.port,version,ids,'26.3');clients.append(client)
            if not client.joined.wait(15):raise RuntimeError(client.errors)
            # The real vanilla loaded notification is required by the native
            # interaction handler. Its upstream packet has an empty body.
            ready=client.identifier('game','serverbound','minecraft:player_loaded','ServerboundPlayerLoadedPacket')
            client.send(ready)
            time.sleep(.3)
        time.sleep(1);console('tmfixture owner-start')
        values={}
        for line in await_file(fixture/'owner-wire.properties').splitlines():
            if line and not line.startswith('#') and '=' in line:
                key,value=line.split('=',1);values[key]=value
        def click(client,hand='MAIN_HAND'):
            client.send(client.identifier('game','serverbound','minecraft:interact','ServerboundInteractPacket'),base64.b64decode(values['interact.'+hand]))
        time.sleep(.3);console('tmfixture owner-cancel');time.sleep(.1);click(clients[1]);time.sleep(.3)
        observations['cancelled']=state();check('real-client-cancelled-interaction-keeps-rider-unmounted',not state()['mounted'],observations['cancelled'])
        console('tmfixture owner-allow');time.sleep(.1);click(clients[1]);time.sleep(.5)
        observations['initial']=state();time.sleep(2)
        observations['persisted']=state();check('real-client-right-click-mount-persists',state()['mounted'] and state()['nonOpRiders'],observations)
        for _ in range(3):click(clients[1]);click(clients[1],'OFF_HAND');time.sleep(.15)
        observations['repeated']=state();check('real-client-repeated-click-keeps-ride',state()['mounted'],observations['repeated'])
        start=state()
        for step in range(40):
            clients[0].steer(int(values['horse']));clients[0].move_vehicle(start['horseX'],start['horseY'],start['horseZ']+(step+1)*.035,0,0);time.sleep(.05)
        clients[0].steer(int(values['horse']),False);time.sleep(.5)
        observations['movement']=state();check('real-client-mounted-movement-keeps-ride',state()['mounted'] and abs(state()['horseZ']-start['horseZ'])>.1,observations['movement'])
        spawn_id=clients[2].identifier('game','clientbound','minecraft:add_entity','ClientboundAddEntityPacket')
        for stage,identifier,body in clients[2].packets:
            if stage=='game' and identifier==spawn_id and protocol.read_vi(bytes.fromhex(body))[0]==state()['riderVehicle']:
                (fixture/'owner-spawn.bin').write_bytes(bytes.fromhex(body));break
        else:raise RuntimeError('Observer did not receive native seat spawn')
        console('tmfixture owner-decodespawn')
        result['received_spawn']=json.loads(await_file(fixture/'owner-spawn-decoded.json'))
        check('real-client-seat-spawn-state-matches-native',result['received_spawn']['entityId']==state()['riderVehicle'] and result['received_spawn']['blockStateId']==state()['seatBlockStateId'] and result['received_spawn']['remainingBytes']==0,{'wire':result['received_spawn'],'native':state()})
        metadata_id=clients[2].identifier('game','clientbound','minecraft:set_entity_data','ClientboundSetEntityDataPacket')
        for stage,identifier,body in clients[2].packets:
            if stage=='game' and identifier==metadata_id and protocol.read_vi(bytes.fromhex(body))[0]==state()['riderVehicle']:
                (fixture/'owner-metadata.bin').write_bytes(bytes.fromhex(body));break
        else:raise RuntimeError('Observer did not receive native seat metadata')
        console('tmfixture owner-decodemetadata')
        result['received_metadata']=json.loads(await_file(fixture/'owner-metadata-decoded.json'))
        check('real-client-seat-no-gravity-matches-native',result['received_metadata']['entityId']==state()['riderVehicle'] and result['received_metadata']['noGravity'] is True and state()['seatNoGravity'] is True and result['received_metadata']['remainingBytes']==0,{'wire':result['received_metadata'],'native':state()})
        console('tmfixture owner-admin');time.sleep(.1);send_command(clients[2],'tandemount');time.sleep(.5)
        chat_id=clients[2].identifier('game','clientbound','minecraft:system_chat','ClientboundSystemChatPacket')
        for stage,identifier,body in clients[2].packets:
            if stage=='game' and identifier==chat_id:
                try:component,used=native_nbt(bytes.fromhex(body));chat.append(component)
                except Exception as error:chat.append({'decode_error':str(error),'body':body})
        selected=[value for value in chat if 'Tandemount' in str(value) or 'tandemount' in str(value) or 'Higher' in str(value)]
        styled=[node for value in selected for node in nodes(value) if node.get('color') and (node.get('bold') or node.get('underlined'))]
        check('command-chat-has-color-and-style',bool(styled),selected)
        clickable=[]
        for value in selected:
            for node in nodes(value):
                event=node.get('click_event',node.get('clickEvent',{}))
                if event.get('action')=='run_command':clickable.append(event.get('command',event.get('value','')))
        wanted={'/tandemount vertical +','/tandemount vertical -','/tandemount rearward +','/tandemount rearward -'}
        check('command-chat-has-clickable-adjustments',wanted.issubset(clickable),clickable)
        previous=state();chosen=next((v for v in clickable if v=='/tandemount vertical +'),None)
        if chosen:send_command(clients[2],chosen);time.sleep(.4)
        check('command-click-adjustment-applies',chosen is not None and abs(state()['vertical']-previous['vertical']-1/16)<1e-9 and state()['rearward']==previous['rearward'],{'before':previous,'after':state(),'clicked':chosen})
        result['observations']=observations;result['decoded_chat']=chat
        result['status']='passed' if all(r['status']=='passed' for r in checks) else 'failed'
    except Exception as error:result['error']=str(error)
    finally:
        for client in clients:
            client.close();(run/(client.name+'-packets.json')).write_text(json.dumps({'packets':client.packets,'errors':client.errors},indent=2)+'\n')
        if server.poll() is None:
            console('stop')
            try:server.wait(35)
            except subprocess.TimeoutExpired:server.terminate();server.wait(10)
        thread.join(timeout=2);log.close();result['exit_code']=server.returncode
        result=workspace.finish(result);path=Path(result['run'])/'result.json';path.write_text(json.dumps(result,indent=2)+'\n')
        evidence=ROOT/'.agents/owner/evidence';evidence.mkdir(parents=True,exist_ok=True);(evidence/(args.label+'.json')).write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({'status':result['status'],'error':result.get('error'),'result':str(path),'assertions':[(r['assertion'],r['status']) for r in checks]}),flush=True)
    raise SystemExit(0 if result['status']=='passed' else 1)

if __name__=='__main__':main()
