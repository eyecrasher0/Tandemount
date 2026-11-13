"""Test the protocol probe's wire contract; these are not Minecraft behavior tests."""

import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest
import zlib

from vanilla_protocol import VanillaClient, frame, load_ids, read_varint, receive, text, varint


class FramingTest(unittest.TestCase):
    def test_varints_round_trip_protocol_values(self):
        for value in [0, 1, 127, 128, 255, 340, 765, 2**31-1, 2**32-1]:
            with self.subTest(value=value):
                encoded = varint(value)
                self.assertEqual(read_varint(encoded), (value, len(encoded)))

    def test_overlong_and_truncated_varints_are_rejected(self):
        for raw in [b"\x80"*6, b"\xff\xff\xff\xff\x7f"]:
            with self.assertRaises(ValueError):
                read_varint(raw)
        with self.assertRaises(EOFError):
            read_varint(b"\x80")

    def test_framing_with_and_without_compression(self):
        for threshold in [None, 0, 10, 1000]:
            left, right = socket.socketpair()
            try:
                left.sendall(frame(300, b"payload" * 10, threshold))
                self.assertEqual(receive(right, threshold is not None), (300, b"payload" * 10))
            finally:
                left.close()
                right.close()

    def test_false_expanded_length_is_rejected(self):
        left, right = socket.socketpair()
        try:
            body = varint(200) + zlib.compress(b"\x01short")
            left.sendall(varint(len(body)) + body)
            with self.assertRaisesRegex(ValueError, "length mismatch"):
                receive(right, True)
        finally:
            left.close()
            right.close()

    def test_native_properties_keep_numeric_ids_and_ignore_identity_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "native.properties"
            path.write_text("# Native export\nminecraft=1.21.1\nplatform=fabric\nport=1234\n"
                            "game.clientbound.minecraft\\:login=41\n"
                            "game.clientbound.class.ClientboundLoginPacket=41\n")
            ids = load_ids(path)
            self.assertEqual(ids["port"], 1234)
            self.assertEqual(ids["game.clientbound.minecraft:login"], 41)
            self.assertNotIn("minecraft", ids)


class ProbeFixture:
    """A wire-level fixture with arbitrary IDs, deliberately not a game server."""
    def __init__(self, reject=False):
        self.reject = reject
        self.socket = socket.socket()
        self.socket.bind(("127.0.0.1", 0))
        self.socket.listen()
        self.socket.settimeout(5)
        self.port = self.socket.getsockname()[1]
        self.errors = []
        self.responses = []
        self.finished = threading.Event()
        self.workers = []
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        try:
            for _ in range(2):
                connection, _ = self.socket.accept()
                worker = threading.Thread(target=self.handle, args=(connection,), daemon=True)
                self.workers.append(worker)
                worker.start()
        except Exception as error:
            self.errors.append(str(error))

    def handle(self, connection):
        try:
            connection.settimeout(5)
            identifier, body = receive(connection)
            if identifier != 0:
                raise ValueError("Expected fixture handshake")
            _, offset = read_varint(body)
            length, offset = read_varint(body, offset)
            stage, _ = read_varint(body, offset + length + 2)
            if stage == 1:
                receive(connection)
                connection.sendall(frame(0, text(json.dumps({"version": {"protocol": 767}}))))
                return
            receive(connection)
            if self.reject:
                connection.sendall(frame(0, text('{"text":"Rejected by test fixture"}')))
                return
            connection.sendall(frame(3, varint(8)))
            connection.sendall(frame(4, varint(42) + text("fixture:loader_query"), 8))
            self.responses.append(receive(connection, True))
            connection.sendall(frame(2, b"fixture-login-success", 8))
            self.responses.append(receive(connection, True))
            connection.sendall(frame(31, varint(0), 8))
            self.responses.append(receive(connection, True))
            connection.sendall(frame(34, b"", 8))
            self.responses.append(receive(connection, True))
            connection.sendall(frame(55, b"fixture-join-game", 8))
            self.finished.set()
            try:
                while connection.recv(1024):
                    pass
            except (TimeoutError, ConnectionResetError):
                pass
        except Exception as error:
            self.errors.append(str(error))
            self.finished.set()
        finally:
            connection.close()

    def close(self):
        self.socket.close()
        self.thread.join(timeout=2)
        for thread in self.workers:
            thread.join(timeout=2)


class LoginTest(unittest.TestCase):
    IDS = {
        "login.serverbound.minecraft:login_acknowledged": 7,
        "configuration.clientbound.minecraft:select_known_packs": 31,
        "configuration.serverbound.minecraft:select_known_packs": 32,
        "configuration.clientbound.minecraft:finish_configuration": 34,
        "configuration.serverbound.minecraft:finish_configuration": 35,
        "game.clientbound.minecraft:login": 55,
    }

    def test_probe_declines_loader_queries_and_uses_exported_game_ids(self):
        fixture = ProbeFixture()
        client = None
        try:
            client = VanillaClient(fixture.port, "1.21.1", self.IDS, timeout=3)
            result = client.verify(timeout=3, remain=0.05)
            self.assertTrue(result["joined_play"], result)
            self.assertTrue(result["connection_held"], result)
            self.assertEqual(result["custom_queries_declined"], 1)
            self.assertEqual(fixture.responses, [(2, varint(42)+b"\0"), (7, b""), (32, b"\0"), (35, b"")])
            self.assertEqual(fixture.errors, [])
        finally:
            if client:
                client.close()
            fixture.close()

    def test_login_rejection_is_observable(self):
        fixture = ProbeFixture(reject=True)
        client = None
        try:
            client = VanillaClient(fixture.port, "1.21.1", self.IDS, timeout=3)
            result = client.verify(timeout=3, remain=0)
            self.assertFalse(result["joined_play"])
            self.assertTrue(any("login was rejected" in error for error in result["errors"]), result)
        finally:
            if client:
                client.close()
            fixture.close()


if __name__ == "__main__":
    unittest.main()
