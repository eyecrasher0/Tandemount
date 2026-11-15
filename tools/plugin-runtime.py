#!/usr/bin/env python3
"""Run disposable exact upstream servers and real loopback protocol clients.

Packet IDs are exported by the loaded native server, not copied from a protocol wiki.
The fixture emits native state assertions. Client packet observations are separate
from human visual checks. This never reads or modifies any existing server world.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import struct
import subprocess
import threading
import time
import uuid
import zlib

from vanilla_protocol import accept_teleport
from runtime_storage import RuntimeWorkspace
from plugin_protocol import wire_properties, menu_checks, send_native

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('TANDEMOUNT_CACHE', ROOT / '.agents/cache'))
LOCAL_JAVAS = json.loads((ROOT / '.agents/toolchains.json').read_text()) if (ROOT / '.agents/toolchains.json').is_file() else {}

def java_for(version, override=None):
    home = override or os.environ.get('TANDEMOUNT_JAVA_' + str(version)) or LOCAL_JAVAS.get(str(version)) or os.environ.get('JAVA_HOME')
    java = str(Path(home) / 'bin/java') if home else shutil.which('java')
    if not java:
        raise RuntimeError('Provide --java-home or TANDEMOUNT_JAVA_' + str(version) + ' for this exact upstream.')
    return java


def vi(value):
    value &= 0xffffffff
    out = bytearray()
    while True:
        part = value & 127
        value >>= 7
        out.append(part | (128 if value else 0))
        if not value:
            return bytes(out)


def read_vi(data, offset=0):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError('Oversized VarInt')


def string(value):
    data = value.encode()
    return vi(len(data)) + data


def recv_exact(sock, amount):
    result = bytearray()
    while len(result) < amount:
        data = sock.recv(amount - len(result))
        if not data:
            raise EOFError('Server closed loopback client')
        result += data
    return bytes(result)


def recv_frame(sock, compressed=False):
    length = 0
    for shift in range(0, 35, 7):
        byte = recv_exact(sock, 1)[0]
        length |= (byte & 127) << shift
        if byte < 128:
            break
    data = recv_exact(sock, length)
    if compressed:
        size, offset = read_vi(data)
        data = zlib.decompress(data[offset:]) if size else data[offset:]
    packet_id, offset = read_vi(data)
    return packet_id, data[offset:]


def properties(file):
    result = {}
    for line in file.read_text().splitlines():
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        result[key.replace('\\:', ':').replace('\\=', '=')] = int(value)
    return result


class Client:
    def __init__(self, name, port, protocol, ids, minecraft):
        self.name, self.ids, self.minecraft = name, ids, minecraft
        self.sock = socket.create_connection(('127.0.0.1', port), 10)
        self.sock.settimeout(40)
        self.compressed = False
        self.stage = 'login'
        self.alive = True
        self.joined = threading.Event()
        self.packets = []
        self.packet_times = []
        self.errors = []
        self.lock = threading.Lock()
        self.position = (0.0, 0.0, 0.0, 0.0, 0.0)
        self.send(0, vi(protocol) + string('localhost') + struct.pack('>H', port) + vi(2))
        modern = minecraft.startswith('26.') or tuple(int(x) for x in minecraft.split('.')) >= (1, 20, 2)
        tail = uuid.uuid3(uuid.NAMESPACE_DNS, name).bytes if modern else b''
        if minecraft in ('1.19', '1.19.1', '1.19.2'):
            tail = b'\x00' + (b'\x00' if minecraft != '1.19' else b'')
        elif minecraft.startswith('1.19.') or minecraft in ('1.20', '1.20.1'):
            tail = b'\x00'
        self.send(0, string(name) + tail)
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def identifier(self, stage, direction, *names, default=None):
        aliases = {
            'minecraft:login_acknowledged': 'ServerboundLoginAcknowledgedPacket',
            'minecraft:select_known_packs': ('Serverbound' if direction == 'serverbound' else 'Clientbound') + 'SelectKnownPacks',
            'minecraft:finish_configuration': ('Serverbound' if direction == 'serverbound' else 'Clientbound') + 'FinishConfigurationPacket',
            'minecraft:keep_alive': ('Serverbound' if direction == 'serverbound' else 'Clientbound') + 'KeepAlivePacket',
            'minecraft:player_position': 'ClientboundPlayerPositionPacket',
            'minecraft:accept_teleportation': 'ServerboundAcceptTeleportationPacket',
            'minecraft:player_input': 'ServerboundPlayerInputPacket',
            'minecraft:move_vehicle': 'ServerboundMoveVehiclePacket',
        }
        names = tuple(names) + tuple(aliases[name] for name in names if name in aliases)
        for name in names:
            for prefix in (stage + '.' + direction + '.', ('play' if stage == 'game' else stage) + '.' + direction + '.'):
                key = prefix + name
                if key in self.ids:
                    return self.ids[key]
        return default

    def send(self, packet_id, body=b''):
        data = vi(packet_id) + body
        if self.compressed:
            data = vi(0) + data
        with self.lock:
            self.sock.sendall(vi(len(data)) + data)

    def read(self):
        try:
            while self.alive:
                packet_id, body = recv_frame(self.sock, self.compressed)
                self.packets.append((self.stage, packet_id, body.hex()))
                self.packet_times.append(time.monotonic_ns())
                if self.stage == 'login':
                    if packet_id == 0:
                        raise RuntimeError('Login disconnect: ' + repr(body))
                    if packet_id == 3:
                        self.compressed = True
                    elif packet_id == 2:
                        acknowledge = self.identifier('login', 'serverbound', 'minecraft:login_acknowledged')
                        if acknowledge is None:
                            self.stage = 'game'
                        else:
                            self.send(acknowledge)
                            self.stage = 'configuration'
                elif self.stage == 'configuration':
                    known = self.identifier('configuration', 'clientbound', 'minecraft:select_known_packs')
                    finish = self.identifier('configuration', 'clientbound', 'minecraft:finish_configuration')
                    keepalive = self.identifier('configuration', 'clientbound', 'minecraft:keep_alive')
                    if packet_id == known:
                        self.send(self.identifier('configuration', 'serverbound', 'minecraft:select_known_packs'), vi(0))
                    elif packet_id == finish:
                        self.send(self.identifier('configuration', 'serverbound', 'minecraft:finish_configuration'))
                        self.stage = 'game'
                    elif packet_id == keepalive:
                        self.send(self.identifier('configuration', 'serverbound', 'minecraft:keep_alive'), body)
                else:
                    self.joined.set()
                    keepalive = self.identifier('game', 'clientbound', 'minecraft:keep_alive', 'PacketPlayOutKeepAlive', default=0 if self.minecraft == '1.7.10' else None)
                    if packet_id == keepalive:
                        response = self.identifier('game', 'serverbound', 'minecraft:keep_alive', 'PacketPlayInKeepAlive', default=0 if self.minecraft == '1.7.10' else None)
                        self.send(response, body)
                    position = self.identifier('game', 'clientbound', 'minecraft:player_position', 'PacketPlayOutPosition')
                    if packet_id == position:
                        confirm = self.identifier('game', 'serverbound', 'minecraft:accept_teleportation', 'PacketPlayInTeleportAccept')
                        if self.minecraft == '1.7.10':
                            self.acknowledge_legacy_position(body)
                        elif confirm is not None:
                            self.acknowledge_position(confirm, body)
        except Exception as error:
            if self.alive:
                self.errors.append(str(error))
        finally:
            self.alive = False

    def acknowledge_position(self, confirm, body):
        payload, self.position = accept_teleport(self.minecraft, body, self.position)
        self.send(confirm, payload)

    def acknowledge_legacy_position(self, body):
        # Paper's protocol 5 sends eye Y; its native movement check expects
        # feet Y plus stance Y in the client's PositionLook response.
        x, eye_y, z, yaw, pitch = struct.unpack_from('>dddff', body)
        feet_y = eye_y - 1.6200000047683716
        response = self.identifier('game', 'serverbound', 'PacketPlayInPositionLook')
        if response is None:
            raise ValueError('Native protocol export lacks the legacy PositionLook ID')
        self.position = (x, feet_y, z, yaw, pitch)
        self.send(response, struct.pack('>ddddff?', x, feet_y, eye_y, z, yaw, pitch, False))

    def steer(self, horse_id, forward=True):
        packet_id = self.identifier('game', 'serverbound', 'minecraft:player_input', 'PacketPlayInSteerVehicle')
        if packet_id is None:
            return
        modern = self.minecraft.startswith('26.') or self.minecraft.startswith('1.21.') and int(self.minecraft.split('.')[2]) >= 2
        body = bytes([1 if forward else 0]) if modern else struct.pack('>ffB', 0.0, 1.0 if forward else 0.0, 0)
        if self.minecraft == '1.7.10':
            body += b'\x00'
        self.send(packet_id, body)

    def move_vehicle(self, x, y, z, yaw=0.0, pitch=0.0):
        packet_id = self.identifier('game', 'serverbound', 'minecraft:move_vehicle', 'PacketPlayInVehicleMove')
        if packet_id is not None:
            body = struct.pack('>dddff', x, y, z, yaw, pitch)
            if tuple(map(int, self.minecraft.split('.'))) >= (1, 21, 4):
                body += b'\x01'
            self.send(packet_id, body)

    def close(self):
        self.alive = False
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


def packet_checks(clients, graph, minecraft):
    checks = []
    def check(name, passed, detail):
        checks.append({'assertion': name, 'status': 'passed' if passed else 'failed', 'detail': detail})
    oldest = minecraft == '1.7.10'
    for client in clients:
        spawned, mounts, poses, types, helper_positions = set(), [], [], [], []
        helpers = [graph['helper_' + str(i)] for i in range(graph.get('helper_count', 0))]
        spawn_ids = {client.identifier('game', 'clientbound', 'minecraft:add_entity', 'PacketPlayOutSpawnEntity', 'ClientboundAddEntityPacket'),
                     client.identifier('game', 'clientbound', 'minecraft:add_mob', 'PacketPlayOutSpawnEntityLiving', 'ClientboundAddMobPacket')}
        mount_id = client.identifier('game', 'clientbound', 'minecraft:set_passengers', 'PacketPlayOutMount', 'ClientboundSetPassengersPacket')
        attach_id = client.identifier('game', 'clientbound', 'PacketPlayOutAttachEntity')
        position_ids = {client.identifier('game', 'clientbound', 'minecraft:entity_position_sync', 'ClientboundEntityPositionSyncPacket'),
                        client.identifier('game', 'clientbound', 'minecraft:teleport_entity', 'PacketPlayOutEntityTeleport', 'ClientboundTeleportEntityPacket')}
        relative_position_ids = {client.identifier('game', 'clientbound', 'minecraft:move_entity_pos', 'PacketPlayOutRelEntityMove', 'ClientboundMoveEntityPacket$Pos'),
                                 client.identifier('game', 'clientbound', 'minecraft:move_entity_pos_rot', 'PacketPlayOutRelEntityMoveLook', 'ClientboundMoveEntityPacket$PosRot')}
        for stage, packet_id, raw in client.packets:
            if stage != 'game': continue
            body = bytes.fromhex(raw)
            try:
                if packet_id in spawn_ids:
                    entity, offset = read_vi(body)
                    spawned.add(entity)
                    if entity == graph['seat']:
                        if oldest: species = body[offset]
                        elif packet_id == client.identifier('game', 'clientbound', 'PacketPlayOutSpawnEntity') and tuple(int(v) for v in minecraft.split('.')) < (1, 19): species = body[offset + 16]
                        else: species, _ = read_vi(body, offset + 16)
                        types.append(species)
                if packet_id == mount_id:
                    vehicle, offset = read_vi(body); length, offset = read_vi(body, offset)
                    passengers = []
                    for _ in range(length):
                        passenger, offset = read_vi(body, offset); passengers.append(passenger)
                    mounts.append((vehicle, passengers))
                if oldest and packet_id == attach_id and len(body) >= 9 and body[8] == 0:
                    passenger, vehicle = struct.unpack('>ii', body[:8]); mounts.append((vehicle, [passenger]))
                if packet_id in position_ids:
                    if oldest:
                        entity, offset = struct.unpack_from('>i', body)[0], 4
                    else:
                        entity, offset = read_vi(body)
                    if entity in helpers: helper_positions.append(entity)
                    if entity == graph['seat']:
                        if oldest: pose = tuple(v / 32.0 for v in struct.unpack('>iii', body[offset:offset + 12]))
                        else: pose = struct.unpack('>ddd', body[offset:offset + 24])
                        poses.append(pose)
                if packet_id in relative_position_ids:
                    entity, _ = read_vi(body)
                    if entity in helpers: helper_positions.append(entity)
            except (ValueError, IndexError, struct.error):
                pass
        prefix = client.name.lower() + '-'
        check(prefix + 'native-vanilla-seat-spawn-received', graph['seat'] in spawned and bool(types) and all(v > 0 for v in types), 'Actual clientbound protocol frames; native IDs and vanilla spawn packet')
        display = graph.get('seat_block_display') == 1
        seat_kind = 'block-display' if display else 'falling-block'
        check(prefix + 'client-seat-uses-nonliving-' + seat_kind,
              graph.get('seat_block_display' if display else 'seat_falling_block') == 1 and graph.get('seat_nonliving') == 1
              and graph['seat'] in spawned, 'Native fixture confirms the exact nonliving vanilla seat type; loopback client received the same seat entity ID')
        check(prefix + 'passenger-seat-edge-received', any(v == graph['seat'] and p == [graph['rider']] for v, p in mounts), 'Actual clientbound mount graph')
        if display:
            chain = [(graph['horse'], [graph['driver'], helpers[0]])]
            chain.extend((parent, [child]) for parent,child in zip(helpers, helpers[1:]))
            check(prefix + 'received-native-attachment-shares-horse-root', all(any(v == parent and p == children for v,p in mounts) for parent,children in chain)
                  and all(entity in spawned for entity in helpers), 'Actual received bounded native passenger graph shares the horse root with its ordinary driver first')
            check(prefix + 'attachment-has-no-periodic-position-follow-packets', not helper_positions,
                  {'absolute_seat_packets': len(poses), 'absolute_helper_packets': len(helper_positions),
                   'scope': 'All native attached helpers; no independent absolute or relative position broadcasts'})
        else:
            check(prefix + 'horse-has-only-driver-on-client', any(v == graph['horse'] and p == [graph['driver']] for v, p in mounts)
                  and not any(v == graph['horse'] and graph['seat'] in p for v, p in mounts), 'Actual clientbound graph preserves native horse control and separate seat position')
            check(prefix + 'seat-position-updates-received', len(poses) >= 3, 'Native absolute position updates observed in socket frames; no human visual claim')
    return checks


def saved_world_checks(run, fixture_config):
    file = fixture_config / 'seat-uuids.txt'
    uuids = [uuid.UUID(line) for line in file.read_text().splitlines()] if file.exists() else []
    hits, sectors = [], 0
    regions = sorted({file for world in run.glob('world*') if world.is_dir() and not world.is_symlink()
                      for file in world.rglob('*.mca') if file.parent.name in {'region', 'entities'}})
    for region in regions:
        data = region.read_bytes()
        for chunk in range(1024):
            location = int.from_bytes(data[chunk * 4:chunk * 4 + 4], 'big')
            offset = (location >> 8) * 4096
            if not offset: continue
            length = int.from_bytes(data[offset:offset + 4], 'big')
            kind = data[offset + 4]
            body = data[offset + 5:offset + 4 + length]
            if kind == 2: body = zlib.decompress(body)
            elif kind == 1:
                import gzip
                body = gzip.decompress(body)
            elif kind != 3: raise ValueError('Unknown MCA compression ' + str(kind))
            sectors += 1
            for helper in uuids:
                if helper.bytes in body or helper.bytes[:8] in body and helper.bytes[8:] in body:
                    hits.append({'file': str(region.relative_to(run)), 'chunk': chunk, 'helper': str(helper)})
    return [{'assertion': 'native-saved-world-contains-no-helper-uuid', 'status': 'passed' if uuids and sectors and not hits else 'failed',
             'detail': {'observed_helper_uuids': len(uuids), 'native_mca_chunks_inspected': sectors, 'matches': hits}}]


def active_save_checks(fixture_config):
    """Require the actual live native serialization probe, not pre-existing MCA files."""
    path = fixture_config / 'assertions.jsonl'
    assertions = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
    matching = [entry for entry in assertions if entry.get('assertion') == 'active-seat-refuses-native-serialization']
    passed = bool(matching) and all(entry.get('status') == 'passed' for entry in matching)
    return [{'assertion': 'active-seat-refuses-native-serialization', 'status': 'passed' if passed else 'failed',
             'detail': {'native_probe_records': matching, 'scope': 'Live mounted seat refuses the native serialization entry point; final world save is checked after shutdown.'}}]


def ping(port):
    with socket.create_connection(('127.0.0.1', port), 5) as sock:
        data = vi(0) + vi(0) + string('localhost') + struct.pack('>H', port) + vi(1)
        sock.sendall(vi(len(data)) + data + b'\x01\x00')
        packet_id, body = recv_frame(sock)
        size, offset = read_vi(body)
        return json.loads(body[offset:offset + size])['version']['protocol']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--platform', choices=['paper', 'purpur', 'folia', 'spigot'], required=True)
    parser.add_argument('--minecraft', required=True)
    parser.add_argument('--accept-eula', action='store_true', help='Owner has accepted the Minecraft EULA for this disposable test')
    parser.add_argument('--port', type=int, default=25741)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--bootstrap-only', action='store_true')
    parser.add_argument('--no-restart', action='store_true', help='Diagnostic only: omit the native restart check')
    parser.add_argument('--fixture', type=Path, default=ROOT / '.agents/plugin-build/tandemount-1.0.0-fixture.jar')
    parser.add_argument('--plugin', type=Path)
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('--accept-eula is required; this runner does not accept legal terms implicitly.')
    if args.plugin is None:
        args.plugin = ROOT / ('.agents/plugin-build/tandemount-1.0.0-' + args.minecraft + '-plugin.jar')
        if not args.plugin.is_file(): args.plugin = ROOT / '.agents/plugin-fixture-build/tandemount-1.0.0-plugin.jar'
    metadata = json.loads((CACHE / 'servers' / args.platform / args.minecraft / 'distribution.json').read_text())
    sdk = Path(metadata['file']).parent
    workspace = RuntimeWorkspace(CACHE / 'plugin-runs' / args.platform / args.minecraft,
                                 args.platform + '-' + args.minecraft + '-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6],
                                 sdk, extra_names=('cache',))
    run = workspace.directory
    (run / 'plugins').mkdir()
    shutil.copy2(args.plugin, run / 'plugins/Tandemount.jar')
    shutil.copy2(args.fixture, run / 'plugins/TandemountNativeFixture.jar')
    config = run / 'plugins/Tandemount'
    config.mkdir()
    shutil.copy2(ROOT / 'plugin/src/main/resources/config.yml', config / 'config.yml')
    with (config / 'config.yml').open('a') as file:
        file.write('\ndebug: true\n')
    fixture_config = run / 'plugins/TandemountNativeFixture'
    (run / 'eula.txt').write_text('eula=true\n')
    (run / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nnetwork-compression-threshold=-1\nlevel-type=FLAT\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=8\nenforce-secure-profile=false\nspawn-animals=false\nspawn-monsters=false\nallow-nether=true\nlevel-seed=1\n')
    (run / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n  shutdown-message: Tandemount disposable test complete\nspawn-limits:\n  monsters: 0\n  animals: 0\n  water-animals: 0\n  ambient: 0\n')
    java = java_for(int(metadata['java']), args.java_home)
    command = [str(java), '-Xms256M', '-Xmx1G', '-XX:ActiveProcessorCount=2', '-DPaper.IgnoreJavaVersion=true', '-jar', str(run / Path(metadata['file']).name), 'nogui']
    log = (run / 'server.log').open('w')
    server = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    def drain():
        for line in server.stdout:
            log.write(line); log.flush(); lines.put(line)
    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    clients = []
    result = {'schema': 1, 'platform': args.platform, 'minecraft': args.minecraft,
              'server': metadata, 'artifact': str(args.plugin),
              'artifact_sha256': hashlib.sha256(args.plugin.read_bytes()).hexdigest(),
              'fixture': str(args.fixture), 'fixture_sha256': hashlib.sha256(args.fixture.read_bytes()).hexdigest(),
              'kind': 'native-runtime', 'visual_verification': 'not-performed',
              'run': str(run), 'command': command, 'status': 'failed', 'assertions': []}
    deadline = time.monotonic() + args.timeout
    def command_line(text):
        server.stdin.write(text + '\n'); server.stdin.flush()
    def phase():
        try:
            return json.loads((fixture_config / 'phase.json').read_text())
        except (OSError, json.JSONDecodeError):
            return {}
    try:
        ready = False
        while time.monotonic() < deadline and server.poll() is None:
            try:
                line = lines.get(timeout=0.2)
                print(line, end='', flush=True)
                if 'Done (' in line:
                    ready = True; break
                if 'could not initialize' in line or 'Error occurred while enabling' in line:
                    raise RuntimeError('Plugin initialization failed')
            except queue.Empty:
                pass
        if not ready:
            raise RuntimeError('Native server did not finish startup')
        result['bootstrap'] = 'passed'
        if args.bootstrap_only:
            result['status'] = 'passed'
        else:
            command_line('tmfixture prepare')
            while phase().get('phase') != 'prepared' and time.monotonic() < deadline:
                if phase().get('phase') == 'failed':
                    raise RuntimeError('Fixture preparation failed: ' + str(phase()))
                time.sleep(0.1)
            ids = properties(fixture_config / 'protocol.properties')
            protocol = ping(args.port)
            result['protocol'] = protocol
            for name in ('TmDriver', 'TmRider', 'TmThird'):
                client = Client(name, args.port, protocol, ids, args.minecraft)
                clients.append(client)
                if not client.joined.wait(15):
                    raise RuntimeError(name + ' did not join: ' + repr(client.errors))
                loaded = client.identifier('game', 'serverbound', 'minecraft:player_loaded', 'ServerboundPlayerLoadedPacket')
                if loaded is not None: client.send(loaded)
                time.sleep(0.3)
            time.sleep(1)
            command_line('tmfixture wire-start')
            late = None
            handled = set()
            while time.monotonic() < deadline:
                current = phase()
                if current.get('phase') == 'failed':
                    raise RuntimeError('Fixture failed: ' + str(current))
                if current.get('phase') == 'wire-cancel' and 'wire-cancel' not in handled:
                    handled.add('wire-cancel')
                    wire = wire_properties(fixture_config / 'wire-packets.properties')
                    send_native(clients[1], wire, 'interact'); time.sleep(.3)
                    command_line('tmfixture wire-cancelled')
                if current.get('phase') == 'wire-mount' and 'wire-mount' not in handled:
                    handled.add('wire-mount')
                    send_native(clients[1], wire, 'interact'); time.sleep(.3)
                    command_line('tmfixture wire-mounted')
                if current.get('phase') == 'wire-menu' and 'wire-menu' not in handled:
                    handled.add('wire-menu')
                    send_native(clients[2], wire, 0); time.sleep(.5)
                    result['menu_assertions'] = menu_checks(clients[2], read_vi)
                    if not all(row['status'] == 'passed' for row in result['menu_assertions']):
                        raise RuntimeError('Received clickable command menu failed validation')
                    send_native(clients[2], wire, 1); time.sleep(.3)
                    send_native(clients[2], wire, 'interact'); time.sleep(.1)
                    for _ in range(3): send_native(clients[1], wire, 'interact'); time.sleep(.1)
                    command_line('tmfixture wire-menu-finish')
                if current.get('phase') == 'drive' and late is None:
                    horse_id, x, y, z, yaw, pitch = map(float, current['value'].split(','))
                    for step in range(30):
                        clients[0].steer(int(horse_id))
                        clients[0].move_vehicle(x, y, z + (step + 1) * 0.04, yaw, pitch)
                        time.sleep(0.05)
                    clients[0].steer(int(horse_id), False)
                    late = Client('TmLate', args.port, protocol, ids, args.minecraft)
                    clients.append(late)
                    if not late.joined.wait(15):
                        raise RuntimeError('Late observer did not join: ' + repr(late.errors))
                    time.sleep(3)
                    graph = properties(fixture_config / 'initial-graph.properties')
                    result['packet_assertions'] = packet_checks([clients[2], late], graph, args.minecraft)
                    command_line('tmfixture late')
                if current.get('phase') == 'finish':
                    command_line('tmfixture finish')
                if current.get('phase') == 'disconnect' and 'disconnect' not in handled:
                    handled.add('disconnect')
                    clients[1].close()
                    time.sleep(0.5)
                    command_line('tmfixture disconnected')
                if current.get('phase') == 'reconnect' and 'reconnect' not in handled:
                    handled.add('reconnect')
                    again = Client('TmRider', args.port, protocol, ids, args.minecraft)
                    clients.append(again)
                    if not again.joined.wait(15):
                        raise RuntimeError('Reconnect did not join: ' + repr(again.errors))
                    time.sleep(0.7)
                    command_line('tmfixture rejoined')
                if current.get('phase') == 'save' and 'save' not in handled:
                    handled.add('save')
                    command_line('tmfixture saveprobe')
                if current.get('phase') == 'save-probed' and 'save-probed' not in handled:
                    handled.add('save-probed')
                    result['active_save_assertions'] = active_save_checks(fixture_config)
                    if current.get('failures') != 0:
                        failures = [row['assertion'] for row in result['assertions'] if row['status'] != 'passed']
                        raise RuntimeError('Native fixture assertions failed: ' + ', '.join(failures))
                    if not all(a['status'] == 'passed' for a in result['active_save_assertions']):
                        raise RuntimeError('Live native seat serialization was not proven to be refused')
                    command_line('tmfixture finish')
                if current.get('phase') == 'complete':
                    result['status'] = 'passed' if current['failures'] == 0 and 'save-probed' in handled and 'wire-menu' in handled and all(a['status'] == 'passed' for a in result.get('packet_assertions', []) + result.get('active_save_assertions', []) + result.get('menu_assertions', [])) else 'failed'
                    break
                time.sleep(0.1)
            else:
                raise TimeoutError('Fixture did not complete')
            if result['status'] == 'passed' and not args.no_restart:
                for client in clients: client.close()
                command_line('stop'); server.wait(40)
                reader.join(timeout=2)
                result['save_assertions'] = saved_world_checks(run, fixture_config)
                if not all(a['status'] == 'passed' for a in result['save_assertions']):
                    raise RuntimeError('A native helper UUID was saved, or world save evidence is missing')
                log.close()
                log = (run / 'restart.log').open('w')
                server = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
                lines = queue.Queue()
                reader = threading.Thread(target=drain, daemon=True)
                reader.start()
                restart_deadline = time.monotonic() + args.timeout
                while time.monotonic() < restart_deadline and server.poll() is None:
                    try:
                        line = lines.get(timeout=0.2)
                        if 'Done (' in line: break
                        if 'could not initialize' in line or 'Error occurred while enabling' in line:
                            raise RuntimeError('Plugin restart initialization failed')
                    except queue.Empty: pass
                else: raise RuntimeError('Native restart did not finish startup')
                command_line('tmfixture restartcheck')
                while time.monotonic() < restart_deadline and phase().get('phase') != 'restart-complete': time.sleep(0.1)
                if phase().get('phase') != 'restart-complete' or phase().get('failures') != 0:
                    raise RuntimeError('Native restart helper cleanup failed')
                result['restart'] = 'passed'
                result['restart_log'] = str(run / 'restart.log')
            if (fixture_config / 'assertions.jsonl').exists():
                result['assertions'] = [json.loads(line) for line in (fixture_config / 'assertions.jsonl').read_text().splitlines()]
    except Exception as error:
        result['status'] = 'failed'
        result['error'] = str(error)
        print('NATIVE TEST ERROR:', error, flush=True)
    finally:
        for client in clients:
            client.close()
            (run / (client.name + '-packets.json')).write_text(json.dumps({'packets': client.packets,
                'received_monotonic_ns': client.packet_times, 'errors': client.errors}, indent=2))
        if server.poll() is None:
            try:
                command_line('stop')
                server.wait(30)
            except Exception:
                server.terminate()
                try: server.wait(10)
                except subprocess.TimeoutExpired: server.kill(); server.wait()
        reader.join(timeout=2)
        log.close()
        result['exit_code'] = server.returncode
        result['server_log'] = str(run / 'server.log')
        if (fixture_config / 'assertions.jsonl').exists():
            result['assertions'] = [json.loads(line) for line in (fixture_config / 'assertions.jsonl').read_text().splitlines()]
        result = workspace.finish(result)
        report_path = Path(result['run']) / 'result.json'
        report_path.write_text(json.dumps(result, indent=2) + '\n')
        evidence = ROOT / '.agents/plugin-evidence'
        evidence.mkdir(parents=True, exist_ok=True)
        (evidence / (args.platform + '-' + args.minecraft + '.json')).write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({'status': result['status'], 'error': result.get('error'), 'result': str(report_path)}))
    raise SystemExit(0 if result['status'] == 'passed' else 1)


if __name__ == '__main__':
    main()
