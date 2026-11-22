"""Regress exact 1.7.10 wire layouts seen in native loopback evidence."""

import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import uuid
import zlib

spec = importlib.util.spec_from_file_location("plugin_runtime", Path(__file__).with_name("plugin-runtime.py"))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class NativePacketClient:
    name = "Observer"
    ids = {"PacketPlayOutSpawnEntity": 14, "PacketPlayOutSpawnEntityLiving": 15,
           "PacketPlayOutNamedEntitySpawn": 12,
           "PacketPlayOutAttachEntity": 27, "PacketPlayOutEntityTeleport": 24}

    def __init__(self, attachments, attach_before_spawns=False):
        # Native 1.7.10: SpawnEntity uses VarInt; AttachEntity and EntityTeleport
        # use fixed 32-bit IDs. The leash discriminator follows both IDs.
        spawns = [("game", 12, "00"), ("game", 12, "01"), ("game", 15, "0364"), ("game", 14, "0446")]
        mounts = [("game", 27, struct.pack(">iiB", *edge).hex()) for edge in attachments]
        self.packets = mounts + spawns if attach_before_spawns else spawns + mounts
        self.packets.extend(("game", 24, struct.pack(">iiiiBB", 4, x, 2064, 226, 0, 0).hex())
                            for x in (248, 245, 243))

    def identifier(self, stage, direction, *names, default=None):
        return next((self.ids[n] for n in names if n in self.ids), default)


class PluginProtocolTest(unittest.TestCase):
    graph = {"seat": 4, "horse": 3, "driver": 0, "rider": 1,
             "seat_falling_block": 1, "seat_nonliving": 1}

    def results(self, edges, attach_before_spawns=False):
        return {r["assertion"]: r["status"] for r in runtime.packet_checks(
            [NativePacketClient(edges, attach_before_spawns)], self.graph, "1.7.10")}

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

    def test_legacy_mount_needs_both_endpoints_before_the_attachment(self):
        results = self.results([(0, 3, 0), (1, 4, 0)], attach_before_spawns=True)
        self.assertEqual(results["observer-horse-has-only-driver-on-client"], "failed")
        self.assertEqual(results["observer-passenger-seat-edge-received"], "failed")

    def test_plugin_client_sends_complete_26_3_teleport_acceptance(self):
        client = object.__new__(runtime.Client)
        client.minecraft = "26.3"
        client.position = (10.0, 20.0, 30.0, 40.0, 50.0)
        sent = []
        client.send = lambda identifier, body: sent.append((identifier, body))
        packet = runtime.vi(42) + struct.pack(">ddddddffI", 1.0, 64.0, -4.0, 0.0, 0.0, 0.0, 5.0, 15.0, 1 | 8)
        client.acknowledge_position(77, packet)
        self.assertEqual(sent, [(77, runtime.vi(42) + struct.pack(">dddff", 11.0, 64.0, -4.0, 45.0, 15.0))])
        self.assertEqual(client.position, (11.0, 64.0, -4.0, 45.0, 15.0))

    def test_plugin_client_preserves_earlier_teleport_id_only_contract(self):
        for minecraft, packet in [("1.12.2", struct.pack(">dddffB", 1.0, 64.0, -4.0, 0.0, 0.0, 0) + runtime.vi(42)),
                                  ("1.21.11", runtime.vi(42) + b"remaining fields")]:
            client = object.__new__(runtime.Client)
            client.minecraft, client.position = minecraft, (0.0,) * 5
            sent = []
            client.send = lambda identifier, body: sent.append((identifier, body))
            client.acknowledge_position(77, packet)
            self.assertEqual(sent, [(77, runtime.vi(42))])

    def test_protocol_5_position_response_distinguishes_feet_and_eye_y(self):
        client = object.__new__(runtime.Client)
        client.minecraft = '1.7.10'
        client.ids = {'play.serverbound.PacketPlayInPositionLook': 6}
        sent = []
        client.send = lambda identifier, body: sent.append((identifier, body))
        client.acknowledge_legacy_position(struct.pack('>dddff?', 8.5, 65.62000000476837, 12.5, 90.0, 15.0, False))
        self.assertEqual(sent, [(6, struct.pack('>ddddff?', 8.5, 64.0, 65.62000000476837, 12.5, 90.0, 15.0, False))])
        self.assertEqual(client.position, (8.5, 64.0, 12.5, 90.0, 15.0))

    def test_vehicle_movement_ground_flag_starts_at_1_21_4(self):
        for version, length in [('1.21.2', 32), ('1.21.3', 32), ('1.21.4', 33), ('26.3', 33)]:
            client = object.__new__(runtime.Client)
            client.minecraft = version
            client.ids = {'game.serverbound.minecraft:move_vehicle': 32}
            sent = []
            client.send = lambda identifier, body: sent.append((identifier, body))
            client.move_vehicle(8.0, 64.0, 8.0)
            self.assertEqual(sent[0][0], 32)
            self.assertEqual(len(sent[0][1]), length, version)
            self.assertEqual(sent[0][1][:32], struct.pack('>dddff', 8.0, 64.0, 8.0, 0.0, 0.0))

    def test_saved_world_scan_covers_26_3_dimension_directories_and_rejects_helper(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            fixture = root / "plugins/Fixture"
            fixture.mkdir(parents=True)
            helper = uuid.UUID("42f6c23f-48bd-4ee6-a3b9-030d16342db1")
            (fixture / "seat-uuids.txt").write_text(str(helper) + "\n")
            region = root / "world/dimensions/minecraft/overworld/entities/r.0.0.mca"
            region.parent.mkdir(parents=True)
            def save(payload):
                compressed = zlib.compress(payload)
                header = bytearray(8192)
                header[:4] = (2 << 8 | 1).to_bytes(4, "big")
                chunk = struct.pack(">IB", len(compressed) + 1, 2) + compressed
                region.write_bytes(header + chunk.ljust(4096, b"\0"))
            save(b"saved native entity data without helper")
            check = runtime.saved_world_checks(root, fixture)[0]
            self.assertEqual(check["status"], "passed", check)
            self.assertEqual(check["detail"]["native_mca_chunks_inspected"], 1)
            save(b"saved entity UUID:" + helper.bytes)
            check = runtime.saved_world_checks(root, fixture)[0]
            self.assertEqual(check["status"], "failed", check)
            self.assertEqual(check["detail"]["matches"][0]["helper"], str(helper))

    def test_active_save_needs_native_probe_and_cannot_hide_an_earlier_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            fixture = Path(folder)
            file = fixture / "assertions.jsonl"
            self.assertEqual(runtime.active_save_checks(fixture)[0]["status"], "failed")
            file.write_text(json.dumps({"assertion": "unrelated-state", "status": "passed"}) + "\n")
            self.assertEqual(runtime.active_save_checks(fixture)[0]["status"], "failed")
            proof = {"assertion": "active-seat-refuses-native-serialization", "status": "passed"}
            file.write_text(json.dumps(proof) + "\n")
            self.assertEqual(runtime.active_save_checks(fixture)[0]["status"], "passed")
            failure = {**proof, "status": "failed"}
            file.write_text(json.dumps(failure) + "\n" + json.dumps(proof) + "\n")
            self.assertEqual(runtime.active_save_checks(fixture)[0]["status"], "failed")


if __name__ == "__main__":
    unittest.main()
