"""Regress exact 1.7.10 wire layouts seen in native loopback evidence."""

import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location("plugin_runtime", Path(__file__).with_name("plugin-runtime.py"))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class NativePacketClient:
    name = "Observer"
    ids = {"PacketPlayOutSpawnEntity": 14, "PacketPlayOutSpawnEntityLiving": 15,
           "PacketPlayOutAttachEntity": 27, "PacketPlayOutEntityTeleport": 24}

    def __init__(self, attachments):
        # Native 1.7.10: SpawnEntity uses VarInt; AttachEntity and EntityTeleport
        # use fixed 32-bit IDs. The leash discriminator follows both IDs.
        self.packets = [("game", 14, "0446")]
        self.packets.extend(("game", 27, struct.pack(">iiB", *edge).hex()) for edge in attachments)
        self.packets.extend(("game", 24, struct.pack(">iiiiBB", 4, x, 2064, 226, 0, 0).hex())
                            for x in (248, 245, 243))

    def identifier(self, stage, direction, *names, default=None):
        return next((self.ids[n] for n in names if n in self.ids), default)


class PluginProtocolTest(unittest.TestCase):
    graph = {"seat": 4, "horse": 3, "driver": 0, "rider": 1,
             "seat_falling_block": 1, "seat_nonliving": 1}

    def results(self, edges):
        return {r["assertion"]: r["status"] for r in runtime.packet_checks(
            [NativePacketClient(edges)], self.graph, "1.7.10")}

    def test_fixed_width_ids_and_trailing_leash_flag(self):
        results = self.results([(0, 3, 0), (1, 4, 0)])
        self.assertTrue(all(v == "passed" for v in results.values()), results)

    def test_leash_packet_does_not_prove_passenger_mount(self):
        results = self.results([(0, 3, 0), (1, 4, 1)])
        self.assertEqual(results["observer-passenger-seat-edge-received"], "failed")

    def test_missing_mount_packets_do_not_pass_from_spawn_alone(self):
        results = self.results([])
        self.assertEqual(results["observer-horse-has-only-driver-on-client"], "failed")
        self.assertEqual(results["observer-passenger-seat-edge-received"], "failed")


if __name__ == "__main__":
    unittest.main()
