"""Decode actual vanilla command components and native fixture wire bodies."""
import base64
import json
import struct


def wire_properties(path):
    return {key: value for line in path.read_text().splitlines()
            if line and not line.startswith('#') and '=' in line
            for key, value in [line.split('=', 1)]}


def native_nbt(data):
    offset = 0
    def take(count):
        nonlocal offset
        if count < 0 or offset + count > len(data):
            raise ValueError('Truncated native chat NBT')
        raw = data[offset:offset + count]
        offset += count
        return raw
    def number(code):
        return struct.unpack('>' + code, take(struct.calcsize('>' + code)))[0]
    def text():
        return take(number('H')).decode('utf-8')
    def payload(kind):
        if kind == 0:
            return None
        if kind in (1, 2, 3, 4, 5, 6):
            return number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind])
        if kind == 7:
            return list(take(number('i')))
        if kind == 8:
            return text()
        if kind == 9:
            child, count = number('B'), number('i')
            return [payload(child) for _ in range(count)]
        if kind == 10:
            result = {}
            while True:
                child = number('B')
                if child == 0:
                    return result
                name = text()
                result[name] = payload(child)
        if kind in (11, 12):
            return [number('i' if kind == 11 else 'q') for _ in range(number('i'))]
        raise ValueError('Unexpected native chat NBT type ' + str(kind))
    return payload(number('B'))


def nodes(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from nodes(item)
    elif isinstance(value, list):
        for item in value:
            yield from nodes(item)


def component(body, read_varint):
    try:
        length, offset = read_varint(body)
        return json.loads(body[offset:offset + length].decode())
    except (ValueError, UnicodeError, IndexError):
        return native_nbt(body)


def menu_checks(client, read_varint):
    ids = {client.identifier('game', 'clientbound', 'minecraft:system_chat', 'ClientboundSystemChatPacket'),
           client.identifier('game', 'clientbound', 'minecraft:chat', 'PacketPlayOutChat', 'ClientboundChatPacket')}
    components, failures = [], []
    for stage, identifier, raw in client.packets:
        if stage != 'game' or identifier not in ids:
            continue
        try:
            value = component(bytes.fromhex(raw), read_varint)
        except (ValueError, IndexError, struct.error) as error:
            failures.append(str(error))
            continue
        if any(text in str(value) for text in ('Tandemount', 'tandemount', 'Higher', 'Rearward')):
            components.append(value)
    styled, clickable = [], []
    for value in components:
        for node in nodes(value):
            if node.get('color') and (node.get('bold') or node.get('underlined')):
                styled.append(node)
            event = node.get('click_event', node.get('clickEvent', {}))
            if event.get('action') == 'run_command':
                clickable.append(event.get('command', event.get('value', '')))
    wanted = {'/tandemount vertical +', '/tandemount vertical -',
              '/tandemount rearward +', '/tandemount rearward -'}
    return [
        {'assertion': 'command-chat-has-color-and-style', 'status': 'passed' if styled else 'failed',
         'detail': {'received_components': components, 'decode_errors': failures}},
        {'assertion': 'command-chat-has-clickable-adjustments',
         'status': 'passed' if wanted.issubset(clickable) else 'failed', 'detail': clickable},
    ]


def send_native(client, wire, action):
    if action == 'interact':
        keys = [key for key in ('interact.0.at', 'interact.0.plain') if key in wire]
        if not keys:
            raise ValueError('Exact SDK exported no native interaction packet')
        identifier = client.identifier('game', 'serverbound', 'minecraft:interact', wire['interact.class'])
    else:
        keys = ['command.' + str(action) + '.body']
        identifier = client.identifier('game', 'serverbound', 'minecraft:chat_command',
                                       'minecraft:chat', wire['command.class'])
    if identifier is None:
        raise ValueError('Native packet ID missing for ' + str(action))
    # Vanilla tries precise interaction first, then ordinary interaction when
    # the target returns PASS. Export both original native packet forms.
    for key in keys:
        client.send(identifier, base64.b64decode(wire[key]))
