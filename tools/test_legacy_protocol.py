"""Exercise mount packet receipt against the observed vanilla spawn order."""

import importlib.util
from pathlib import Path
import struct
import unittest


spec = importlib.util.spec_from_file_location("legacy_native", Path(__file__).with_name("legacy-native-test.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class LegacyMountPacketsTest(unittest.TestCase):
    def observer(self, minecraft):
        client = runner.VanillaClient.__new__(runner.VanillaClient)
        client.mc = minecraft
        client.oldest = minecraft == "1.7.10"
        client.modern = minecraft == "1.12.2"
        client.play = True
        client.self_id = 1
        client.traces = []
        client.players = set()
        client.packet_counts = {}
        client.entities = {}
        client.mounts = {}
        return client

    def test_1122_does_not_remember_edges_to_entities_that_spawn_later(self):
        client = self.observer("1.12.2")
        mounts = bytes([3, 2, 1, 2])
        client.packet(0x43, mounts)
        self.assertEqual(client.mounts, {})
        client.packet(0x00, b"\x03" + bytes(16) + b"\x03" + struct.pack(">dddBBihhh", 8, 65, 8, 0, 0, 0, 0, 0, 0))
        self.assertEqual(client.mounts, {})
        client.packet(0x43, mounts)
        self.assertEqual(client.mounts, {1: 3})
        client.packet(0x05, b"\x02" + bytes(16) + struct.pack(">dddBB", 8, 65, 8, 0, 0) + b"\xff")
        self.assertEqual(client.mounts, {1: 3})
        client.packet(0x43, mounts)
        self.assertEqual(client.mounts, {1: 3, 2: 3})
        client.packet(0x43, bytes([3, 0]))
        self.assertEqual(client.mounts, {})

    def test_older_attach_packets_keep_the_same_known_entity_requirement(self):
        for minecraft in ("1.7.10", "1.8.9"):
            with self.subTest(minecraft=minecraft):
                client = self.observer(minecraft)
                attach = struct.pack(">iiB", 1, 3, 0)
                client.packet(0x1B, attach)
                self.assertEqual(client.mounts, {})
                client.packet(0x0E, b"\x03\x4e" + struct.pack(">iiiBBi", 256, 2080, 256, 0, 0, 0))
                client.packet(0x1B, attach)
                self.assertEqual(client.mounts, {1: 3})
                client.packet(0x1B, struct.pack(">iiB", 1, -1, 0))
                self.assertEqual(client.mounts, {1: -1})
