"""Evidence shared by the legacy Forge runner and release collector."""

import hashlib
import math
from pathlib import Path
import re


LEGACY_REPORT = Path(".agents/legacy-native-results.json")
CLIENT_TICKS = 240
LEGACY_CARRIERS = {"1.7.10": "signed-size-slime", "1.8.9": "marker-armor-stand", "1.12.2": "area-effect-cloud"}
OBSERVER_OFFSETS = {"initial": (.8125, .5), "movement": (.8125, .5), "live_adjustment": (.875, .5625)}
OBSERVERS = ("existing", "late")
OBSERVER_FLAGS = ("seat_spawned", "driver_spawned", "rider_spawned", "driver_on_horse",
                  "rider_on_seat", "position_matches_native")


def legacy_fixture_digest(root):
    paths = ("tools/legacy-native-test.py", "forge/legacy/native-test/NativeChecks.java",
             "tools/legacy-client-test.py", "forge/legacy/client-test/ClientChecks.java",
             "tools/legacy-build.py", "tools/project_metadata.py", "tools/runtime_storage.py",
             "tools/legacy_evidence.py", "project.json")
    hasher = hashlib.sha256()
    for name in paths:
        hasher.update(name.encode() + b"\0" + (root / name).read_bytes() + b"\0")
    return hasher.hexdigest()


def horse_moved(initial, current):
    return math.dist(initial["horse_position"], current["horse_position"]) > 1


def observer_snapshot_passed(snapshot, phase):
    native = snapshot.get("native", {})
    if native.get("joined") is not True:
        return False
    try:
        horse_x, horse_y, horse_z = native["horse_position"]
        rider_x, rider_y, rider_z = native["rider_feet"]
        yaw = math.radians(native["horse_yaw"])
        height, rear = OBSERVER_OFFSETS[phase]
        actual = (rider_x - horse_x, rider_y - horse_y, rider_z - horse_z)
        expected = (math.sin(yaw) * rear, height, -math.cos(yaw) * rear)
    except (KeyError, TypeError, ValueError):
        return False
    if not all(math.isclose(a, b, rel_tol=0, abs_tol=1e-6) for a, b in zip(actual, expected)):
        return False
    for role in OBSERVERS:
        observer = snapshot.get(role + "_observer", {})
        if observer.get("reader_error") or any(observer.get(flag) is not True for flag in OBSERVER_FLAGS):
            return False
    return True


def lifetime_passed(probes, minecraft, minimum_ticks=CLIENT_TICKS):
    carrier = LEGACY_CARRIERS.get(minecraft)
    return carrier is not None and set(probes) == set(OBSERVERS) and all(
        probe.get("minecraft") == minecraft and probe.get("carrier") == carrier
        and probe.get("status") == "passed" and probe.get("probe_exit_code") == 0
        and probe.get("spawn_settling_ticks") == 24 and probe.get("ticks", 0) >= minimum_ticks + 24
        and probe.get("removed") is False and probe.get("unobstructed_picking") is True
        and probe.get("native_packets_consumed", 0) > 0
        and all(re.fullmatch(r"[0-9a-f]{64}", probe.get(key) or "")
                for key in ("official_client_sha256", "packet_sequence_sha256", "probe_report_sha256"))
        for probe in probes.values()
    )
