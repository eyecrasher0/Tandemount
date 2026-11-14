#!/usr/bin/env python3
"""Real loopback observers and native passenger graphs; no human visual claim."""
import argparse
import base64
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import time
from vanilla_protocol import VanillaClient, load_ids, read_varint, varint, text


class Client(VanillaClient):
    def __init__(self, *args, **kwargs):
        self.raw = []
        self.parents = {}
        super().__init__(*args, **kwargs)

    def play(self, identifier, body):
        self.raw.append((time.monotonic_ns(), identifier, bytes(body)))
        if identifier == self.packet("game", "clientbound", "set_passengers", "ClientboundSetPassengersPacket"):
            vehicle, offset = read_varint(body)
            count, offset = read_varint(body, offset)
            for passenger, parent in list(self.parents.items()):
                if parent == vehicle:
                    del self.parents[passenger]
            for _ in range(count):
                passenger, offset = read_varint(body, offset)
                self.parents[passenger] = vehicle
        super().play(identifier, body)

    def root(self, entity):
        seen = set()
        while entity in self.parents:
            if entity in seen:
                return -2
            seen.add(entity)
            entity = self.parents[entity]
        return entity

    def click(self, entity):
        self.send(self.packet("game", "serverbound", "interact", "ServerboundInteractPacket"),
                  varint(entity) + varint(0) + varint(0) + b"\0")

    def command(self, value):
        payload = text(value.lstrip("/"))
        if tuple(map(int, self.version.split("."))) < (1, 20, 5):
            # Exact unsigned argument-signature/last-seen layout used by these
            # vanilla releases. The command contains no signed chat arguments.
            payload += struct.pack(">qq", int(time.time() * 1000), 0) + varint(0) + varint(0) + b"\0\0\0"
        self.send(self.packet("game", "serverbound", "chat_command", "ServerboundChatCommandPacket"), payload)

    def steer(self, forward):
        modern = tuple(map(int, self.version.split("."))) >= (1, 21, 2)
        payload = bytes([1 if forward else 0]) if modern else struct.pack(">ffB", 0.0, 1.0 if forward else 0.0, 0)
        self.send(self.packet("game", "serverbound", "player_input", "ServerboundPlayerInputPacket"), payload)

    def move_vehicle(self, x, y, z, yaw):
        payload = struct.pack(">dddff", x, y, z, yaw, 0)
        if tuple(map(int, self.version.split("."))) >= (1, 21, 4):
            payload += b"\x01"
        self.send(self.packet("game", "serverbound", "move_vehicle", "ServerboundMoveVehiclePacket"), payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--ids", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    ids, clients, checks = load_ids(args.ids), [], []
    directory = args.ids.parent
    state_file = directory / "tandemount-wire-state.json"
    sequence = 0

    def state():
        if not state_file.exists():
            return {}
        value = json.loads(state_file.read_text())
        if value.get("error"):
            raise AssertionError("Native wire fixture: " + value["error"])
        return value

    def wait(predicate, description, timeout=12):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = state()
            if predicate(value):
                return value
            errors = [error for client in clients for error in client.errors]
            if errors:
                raise AssertionError(errors)
            time.sleep(.05)
        raise AssertionError(description + ": " + json.dumps(state()))

    def request(action):
        nonlocal sequence
        sequence += 1
        temp = directory / "tandemount-wire-request.tmp"
        temp.write_text(str(sequence) + " " + action + "\n")
        temp.replace(directory / "tandemount-wire-request.txt")
        return wait(lambda value: value.get("sequence") == sequence, action)

    def connect(name):
        client = Client(args.port, args.minecraft, ids, name=name, timeout=45)
        clients.append(client)
        outcome = client.verify(timeout=20, remain=.2)
        if not outcome["joined_play"] or outcome["errors"]:
            raise AssertionError(outcome)
        loaded = client.packet("game", "serverbound", "player_loaded", "ServerboundPlayerLoadedPacket")
        if loaded is not None:
            client.send(loaded)
        return client

    result = {"kind": "real-loopback-native-passenger-graph", "minecraft": args.minecraft,
              "visual": "not-run", "checks": checks, "result": "fail", "errors": []}
    try:
        driver, rider, existing = [connect(name) for name in ("TmWireDriver", "TmWireRider", "TmWireObserver")]
        initial = request("setup")
        time.sleep(.75)
        driver.click(initial["horse"])
        wait(lambda value: value.get("driverVehicle") == initial["horse"], "ordinary driver right click")
        mount_started = time.monotonic_ns()
        rider.click(initial["horse"])
        mounted = wait(lambda value: value.get("riderRoot") == initial["horse"], "ordinary second rider right click")
        wait(lambda value: existing.root(value["rider"]) == value["horse"]
             and existing.parents.get(value["driver"]) == value["horse"], "existing observer native graph")
        checks.append("existing-observer-received-native-attachment-shares-horse-root")
        mount_finished = time.monotonic_ns()
        existing.click(initial["horse"])
        time.sleep(.25)
        if state()["observerVehicle"] != -1:
            raise AssertionError("Third rider mounted")
        checks.append("real-client-third-rider-rejected")
        late = connect("TmWireLate")
        wait(lambda value: late.root(value["rider"]) == value["horse"]
             and late.parents.get(value["driver"]) == value["horse"], "late observer native graph")
        checks.append("late-observer-received-native-attachment-shares-horse-root")
        request("admin")
        existing.command("tandemount")
        chat_id = existing.packet("game", "clientbound", "system_chat", "ClientboundSystemChatPacket")
        def command_chat():
            return b"\n".join(body for _, identifier, body in existing.raw if identifier == chat_id)
        wait(lambda value: b"/tandemount height +" in command_chat(), "actual command menu response")
        chat = command_chat()
        if not any(color in chat for color in (b"#55FF55", b"#55ff55", b"green")):
            raise AssertionError("Actual command chat has no control color")
        checks.append("command-chat-has-color-and-style")
        commands = re.findall(rb"/tandemount (?:height|rear) [+-]", chat)
        if len(set(commands)) != 4:
            raise AssertionError("Actual command chat lacks adjustment click events")
        checks.append("command-chat-has-clickable-adjustments")
        chosen = next(command for command in commands if command.endswith(b"height +"))
        existing.command(chosen.decode())
        wait(lambda value: value.get("height") == .875 and value.get("rear") == .5, "actual clicked adjustment command")
        checks.append("command-click-adjustment-applies")
        existing.command("tandemount reset")
        wait(lambda value: value.get("height") == .8125 and value.get("rear") == .5, "actual reset command")
        checks.append("reset-restores-owner-defaults")
        # Track only native position packets after the graph is established.
        before_motion = state()
        mark = time.monotonic_ns()
        start = state()["horsePosition"]
        driver.steer(True)
        for step in range(16):
            driver.move_vehicle(start[0] + step * .04, start[1] + math.sin(step * math.pi / 15) * .4,
                                start[2] + step * .08, 0)
            time.sleep(.05)
        driver.steer(False)
        driven = wait(lambda value: abs(value["horsePosition"][2] - start[2]) > .5
                      and value["riderRoot"] == value["horse"] and value["driverVehicle"] == value["horse"], "native first-driver vehicle movement")
        checks.append("real-client-first-driver-controls-native-horse")
        time.sleep(1)
        motion_end = time.monotonic_ns()
        helpers = {helper["id"] for helper in state()["helpers"]}
        position_ids = {existing.packet("game", "clientbound", name, alternate) for name, alternate in (
            ("move_entity_pos", "ClientboundMoveEntityPacket$Pos"), ("move_entity_pos_rot", "ClientboundMoveEntityPacket$PosRot"),
            ("teleport_entity", "ClientboundTeleportEntityPacket"), ("entity_position_sync", "ClientboundEntityPositionSyncPacket"))}
        follow = []
        for when, identifier, body in existing.raw:
            if when >= mark and identifier in position_ids:
                entity, _ = read_varint(body)
                if entity in helpers:
                    follow.append({"entity": entity, "id": identifier})
        if follow:
            raise AssertionError("Independent helper position packets: " + json.dumps(follow))
        checks.append("attachment-has-no-periodic-position-follow-packets")
        result.update(result="pass", joined_play=True, connection_held=True, initial_state=initial,
                      mounted_state=mounted, before_motion_state=before_motion, final_state=state(),
                      mount_window={"start_ns": mount_started, "end_ns": mount_finished},
                      movement_window={"start_ns": mark, "end_ns": motion_end}, observer_parent_graphs={client.name: client.parents for client in clients})
    except Exception as error:
        result["errors"].append(str(error))
    finally:
        traces = {}
        for client in clients:
            client.close()
            trace = directory / (client.name + "-wire.json")
            trace.write_text(json.dumps({"minecraft": args.minecraft, "protocol": ids["protocol"], "name": client.name, "parents": client.parents,
                "packets": [{"at_ns": when, "state": "game", "id": identifier, "base64": base64.b64encode(body).decode()} for when, identifier, body in client.raw],
                "errors": client.errors}, indent=2) + "\n")
            traces[client.name] = {"file": trace.name, "sha256": hashlib.sha256(trace.read_bytes()).hexdigest()}
        result["packet_traces"] = traces
        result["protocol_export"] = {"file": args.ids.name, "sha256": hashlib.sha256(args.ids.read_bytes()).hexdigest()}
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return 0 if result["result"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
