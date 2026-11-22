"""Minimal vanilla socket client for local server verification.

Gameplay packet IDs come from the running server's exported registry. This
client acknowledges standard login/configuration packets and never advertises
a mod loader or custom registry. It is a protocol probe, not a renderer.
"""

import hashlib
import json
import socket
import struct
import threading
import time
import zlib

MAX_PACKET = 16 * 1024 * 1024


def varint(number):
    value = number & 0xffffffff
    output = bytearray()
    while True:
        byte = value & 0x7f
        value >>= 7
        output.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(output)


def read_varint(data, offset=0):
    value = 0
    for shift in range(0, 35, 7):
        if offset >= len(data):
            raise EOFError("Incomplete VarInt")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7f) << shift
        if byte < 0x80:
            if shift == 28 and byte > 0x0f:
                raise ValueError("VarInt exceeds 32 bits")
            return value, offset
    raise ValueError("VarInt exceeds five bytes")


def text(value):
    encoded = value.encode("utf-8")
    return varint(len(encoded)) + encoded


def exact(sock, count):
    result = bytearray()
    while len(result) < count:
        chunk = sock.recv(count - len(result))
        if not chunk:
            raise EOFError("Local server closed the connection")
        result.extend(chunk)
    return bytes(result)


def receive(sock, compressed=False):
    length_bytes = bytearray()
    for _ in range(5):
        byte = exact(sock, 1)[0]
        length_bytes.append(byte)
        if byte < 0x80:
            break
    length, _ = read_varint(length_bytes)
    if not 0 < length <= MAX_PACKET:
        raise ValueError("Invalid packet length")
    data = exact(sock, length)
    if compressed:
        expanded, offset = read_varint(data)
        if expanded > MAX_PACKET:
            raise ValueError("Oversized compressed packet")
        if expanded:
            decoder = zlib.decompressobj()
            data = decoder.decompress(data[offset:], expanded + 1)
            if len(data) != expanded or not decoder.eof or decoder.unused_data:
                raise ValueError("Compressed packet length mismatch")
        else:
            data = data[offset:]
    identifier, offset = read_varint(data)
    return identifier, data[offset:]


def frame(identifier, payload=b"", threshold=None):
    data = varint(identifier) + payload
    if threshold is not None:
        data = varint(len(data)) + zlib.compress(data) if len(data) >= threshold else b"\0" + data
    return varint(len(data)) + data


def ping(port):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        sock.sendall(frame(0, varint(0) + text("localhost") + struct.pack(">H", port) + varint(1)))
        sock.sendall(frame(0))
        identifier, body = receive(sock)
        if identifier != 0:
            raise ValueError("Unexpected server status response")
        length, offset = read_varint(body)
        if offset + length != len(body):
            raise ValueError("Invalid server status text length")
        return json.loads(body[offset:].decode())


def load_ids(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.replace("\\:", ":").replace("\\=", "=").strip()
        if key in {"minecraft", "platform"}:
            continue
        result[key] = int(value.strip())
    return result


def accept_teleport(version, body, previous=(0.0, 0.0, 0.0, 0.0, 0.0)):
    parts = tuple(map(int, version.split(".")))
    if parts < (1, 21, 2):
        x, y, z, yaw, pitch, flags = struct.unpack_from(">dddffB", body)
        identifier, _ = read_varint(body, 33)
        position = tuple(value + previous[index] if flags & (1 << index) else value
                         for index, value in enumerate((x, y, z, yaw, pitch)))
        return varint(identifier), position
    identifier, offset = read_varint(body)
    if parts < (26, 3):
        return varint(identifier), previous
    # The official 26.3 record adds the client's resulting position/rotation to
    # teleport acknowledgements; 26.1/26.2 still contain only the VarInt id.
    x, y, z = struct.unpack_from(">ddd", body, offset)
    yaw, pitch = struct.unpack_from(">ff", body, offset + 48)
    flags = struct.unpack_from(">I", body, offset + 56)[0]
    values = [x, y, z, yaw, pitch]
    values = [value + previous[index] if flags & (1 << index) else value
              for index, value in enumerate(values)]
    return varint(identifier) + struct.pack(">dddff", *values), tuple(values)


class VanillaClient:
    def __init__(self, port, version, ids, name="TmVanillaTest", timeout=30):
        self.port, self.version, self.ids, self.name = port, version, ids, name
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.threshold = None
        self.stage = "login"
        self.joined = threading.Event()
        self.stopped = threading.Event()
        self.closed = False
        self.errors = []
        self.observed = []
        self.custom_queries_declined = 0
        self.position = (0.0, 0.0, 0.0, 0.0, 0.0)
        self.lock = threading.Lock()
        protocol = ping(port)["version"]["protocol"]
        self.send(0, varint(protocol) + text("localhost") + struct.pack(">H", port) + varint(2))
        parts = tuple(map(int, version.split(".")))
        tail = b""
        if parts >= (1, 20, 2):
            tail = hashlib.md5(("OfflinePlayer:" + name).encode(), usedforsecurity=False).digest()
        elif parts in {(1, 19), (1, 19, 1), (1, 19, 2)}:
            tail = b"\0" if parts == (1, 19) else b"\0\0"
        elif parts >= (1, 19, 3):
            tail = b"\0"
        self.send(0, text(name) + tail)
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def packet(self, stage, direction, *names, default=None):
        stages = (stage, "play" if stage == "game" else stage)
        for part in stages:
            for name in names:
                for suffix in (name, "minecraft:" + name if ":" not in name else name):
                    for candidate in (suffix, "class." + suffix):
                        key = f"{part}.{direction}.{candidate}"
                        if key in self.ids:
                            return self.ids[key]
        return default

    def send(self, identifier, body=b""):
        if identifier is None:
            raise ValueError("Required packet ID missing from native export")
        with self.lock:
            self.sock.sendall(frame(identifier, body, self.threshold))

    def read(self):
        try:
            while not self.closed:
                identifier, body = receive(self.sock, self.threshold is not None)
                self.observed.append({"stage": self.stage, "id": identifier, "bytes": len(body)})
                if self.stage == "login":
                    if identifier == 0:
                        raise RuntimeError("Vanilla login was rejected: " + body[:256].hex())
                    if identifier == 3:
                        self.threshold, _ = read_varint(body)
                    elif identifier == 4:
                        transaction, _ = read_varint(body)
                        # A real vanilla client reports an unsupported loader
                        # login query instead of completing an FML handshake.
                        response = self.packet("login", "serverbound", "custom_query_answer", "ServerboundCustomQueryAnswerPacket", default=2)
                        self.send(response, varint(transaction) + b"\0")
                        self.custom_queries_declined += 1
                    elif identifier == 2:
                        acknowledge = self.packet("login", "serverbound", "login_acknowledged", "ServerboundLoginAcknowledgedPacket")
                        if acknowledge is None and tuple(map(int, self.version.split("."))) >= (1, 20, 2):
                            raise ValueError("Native export lacks login acknowledgement ID")
                        if acknowledge is None:
                            self.stage = "game"
                        else:
                            self.send(acknowledge)
                            self.stage = "configuration"
                elif self.stage == "configuration":
                    self.configure(identifier, body)
                else:
                    self.play(identifier, body)
        except Exception as error:
            if not self.closed:
                self.errors.append(str(error))
        finally:
            self.stopped.set()

    def configure(self, identifier, body):
        for token, alternate in (("keep_alive", "ClientboundKeepAlivePacket"), ("ping", "ClientboundPingPacket")):
            if identifier == self.packet("configuration", "clientbound", token, alternate):
                response = "pong" if token == "ping" else token
                self.send(self.packet("configuration", "serverbound", response), body)
                return
        if identifier == self.packet("configuration", "clientbound", "select_known_packs", "ClientboundSelectKnownPacks"):
            self.send(self.packet("configuration", "serverbound", "select_known_packs", "ServerboundSelectKnownPacks"), varint(0))
        elif identifier == self.packet("configuration", "clientbound", "finish_configuration", "ClientboundFinishConfigurationPacket"):
            self.send(self.packet("configuration", "serverbound", "finish_configuration", "ServerboundFinishConfigurationPacket"))
            self.stage = "game"
        elif identifier == self.packet("configuration", "clientbound", "disconnect", "ClientboundDisconnectPacket"):
            raise RuntimeError("Configuration rejected the vanilla client: " + body[:256].hex())

    def play(self, identifier, body):
        join = self.packet("game", "clientbound", "login", "ClientboundLoginPacket", "PacketPlayOutLogin")
        if identifier == join:
            self.joined.set()
        if identifier == self.packet("game", "clientbound", "disconnect", "ClientboundDisconnectPacket", "PacketPlayOutKickDisconnect"):
            raise RuntimeError("Play connection rejected the vanilla client: " + body[:256].hex())
        if identifier == self.packet("game", "clientbound", "keep_alive", "ClientboundKeepAlivePacket", "PacketPlayOutKeepAlive"):
            self.send(self.packet("game", "serverbound", "keep_alive", "ServerboundKeepAlivePacket", "PacketPlayInKeepAlive"), body)
        elif identifier == self.packet("game", "clientbound", "player_position", "ClientboundPlayerPositionPacket", "PacketPlayOutPosition"):
            confirm = self.packet("game", "serverbound", "accept_teleportation", "ServerboundAcceptTeleportationPacket", "PacketPlayInTeleportAccept")
            if confirm is not None:
                payload, self.position = accept_teleport(self.version, body, self.position)
                self.send(confirm, payload)

    def verify(self, timeout=30, remain=1):
        deadline = time.monotonic() + timeout
        while not self.joined.is_set() and not self.stopped.is_set() and time.monotonic() < deadline:
            self.joined.wait(min(0.1, max(0, deadline - time.monotonic())))
        if self.joined.is_set():
            self.stopped.wait(remain)
        return {"joined_play": self.joined.is_set(), "connection_held": self.joined.is_set() and not self.stopped.is_set(),
                "loader_handshake_advertised": False, "custom_queries_declined": self.custom_queries_declined,
                "packets_received": len(self.observed), "errors": list(self.errors), "visual": "not-run"}

    def close(self):
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        self.thread.join(timeout=3)
