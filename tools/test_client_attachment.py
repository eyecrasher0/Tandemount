"""Exercise the captured native-packet and helper-state input contracts."""
import base64
import json
from pathlib import Path
import tempfile
import unittest

from client_attachment import captured_packets, normalize_capture, received_state, stationary_capture_ns, write_received_state
from vanilla_protocol import varint


class CaptureInputTest(unittest.TestCase):
    def state(self):
        return {"horse":10,"horsePosition":[8,64,8],"driver":1,"rider":2,
                "riderVehicle":25,"vertical":.8125,"rearward":.5,
                "helpers":[{"id":25,"type":"BLOCK_DISPLAY","parent":10,
                            "x":8,"y":65.4,"z":7.5,"pose":"STANDING","yaw":0}]}

    def test_plugin_and_mod_exports_keep_the_same_native_graph_meaning(self):
        state=self.state()
        plugin=normalize_capture(state)
        mod=normalize_capture({"mounted_state":{**state,"height":.8125,"rear":.5,
             "helpers":[{"id":25,"type":"block_display","vehicle":10,
                         "position":[8,65.4,7.5],"pose":"STANDING","yaw":0}]}})
        for key in ("horse","driver","rider","riderVehicle","height","rear"):
            self.assertEqual(plugin[key],mod[key])
        for key in ("id","type","vehicle","position","pose","yaw"):
            self.assertEqual(plugin["helpers"][0][key],mod["helpers"][0][key])

    def test_missing_horse_position_is_never_inferred_from_a_helper_offset(self):
        state=self.state()
        del state["horsePosition"]
        with self.assertRaisesRegex(ValueError,"actual horse position"):
            normalize_capture(state)

    def test_received_bytes_respect_native_ids_and_observation_time(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            protocol=root/"protocol.properties"
            protocol.write_text("game.clientbound.minecraft:set_entity_data=12\n"
                                "configuration.clientbound.minecraft:set_entity_data=99\n"
                                "game.clientbound.minecraft:move_entity_pos=4\n")
            trace=root/"trace.json"
            trace.write_text(json.dumps({"packets":[
                {"at_ns":10,"id":12,"base64":base64.b64encode(b"native state").decode()},
                {"at_ns":11,"id":4,"base64":base64.b64encode(b"motion excluded").decode()},
                {"at_ns":20,"id":12,"base64":base64.b64encode(b"later state").decode()}],"errors":[]}))
            output=root/"state.tsv"
            self.assertEqual(received_state(trace,protocol,output,15),1)
            self.assertEqual(output.read_text(),"ClientboundSetEntityDataPacket\t"+b"native state".hex()+"\n")

    def test_plugin_hex_and_mod_base64_preserve_identical_packet_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            protocol=root/"protocol.properties"
            protocol.write_text("game.clientbound.minecraft\\:set_passengers=30\n")
            source=root/"trace.json";first=root/"first.tsv";second=root/"second.tsv"
            source.write_text(json.dumps({"packets":[["game",30,"01ff00"]],"received_monotonic_ns":[10],"errors":[]}))
            received_state(source,protocol,first)
            source.write_text(json.dumps({"packets":[{"at_ns":10,"id":30,"base64":"Af8A"}],"errors":[]}))
            received_state(source,protocol,second)
            self.assertEqual(first.read_bytes(),second.read_bytes())

    def test_plugin_native_names_keep_the_same_game_state_and_skip_login_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol=root/"protocol.properties";trace=root/"trace.json"
            protocol.write_text("play.clientbound.PacketPlayOutMount=30\n")
            trace.write_text(json.dumps({"packets":[["login",30,"ffff"],["game",30,"0102"]],
                                         "received_monotonic_ns":[1,2],"errors":[]}))
            output=root/"state.tsv"
            self.assertEqual(received_state(trace,protocol,output),1)
            self.assertEqual(output.read_text(),"ClientboundSetPassengersPacket\t0102\n")

    def test_detached_follower_replay_includes_received_teleport(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            protocol = root / "protocol.properties"
            protocol.write_text("play.clientbound.PacketPlayOutMount=75\n"
                                "play.clientbound.PacketPlayOutEntityTeleport=86\n")
            trace = root / "packets.json"
            teleport = ("15" + "4020000000000000" + "40504a6666666666" + "401e000000000000"
                        + "000001")
            trace.write_text(json.dumps({"packets": [["game", 75, "150104"], ["game", 86, teleport]],
                                         "received_monotonic_ns": [1, 2], "errors": []}))
            output = root / "state.tsv"
            self.assertEqual(write_received_state(captured_packets(trace, protocol, 2), output,
                                                  include_teleports=True), 2)
            self.assertEqual(output.read_text().splitlines()[1], "ClientboundTeleportEntityPacket\t" + teleport)

    def test_protocol_errors_and_empty_state_are_not_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol=root/"protocol.properties";trace=root/"trace.json"
            protocol.write_text("game.clientbound.minecraft:set_passengers=30\n")
            for raw in ({"errors":["disconnected"],"packets":[]},{"errors":[],"packets":[]}):
                with self.subTest(raw=raw):
                    trace.write_text(json.dumps(raw))
                    with self.assertRaises(ValueError):
                        received_state(trace,protocol,root/"state.tsv")

    def test_stationary_window_requires_the_captured_mount_graph(self):
        state = normalize_capture(self.state())
        packets = [(index, "ClientboundAddEntityPacket", varint(entity))
                   for index,entity in enumerate((10,1,2,25,90))]
        packets += [(10, "ClientboundSetPassengersPacket", bytes((10,2,1,25))),
                    (20, "ClientboundSetPassengersPacket", bytes((25,1,2))),
                    (30, "ClientboundMoveEntityPacket$PosRot", varint(10)),
                    (40, "ClientboundSetEntityDataPacket", varint(90))]
        self.assertEqual(stationary_capture_ns(packets,state,50),20)
        for packet in [(45,"ClientboundSetPassengersPacket",bytes((90,1,2))),
                       (45,"ClientboundRemoveEntitiesPacket",bytes((1,25)))]:
            with self.subTest(packet=packet), self.assertRaisesRegex(ValueError, "ride is incomplete"):
                stationary_capture_ns(packets + [packet], state, 50)


if __name__=="__main__":
    unittest.main()
