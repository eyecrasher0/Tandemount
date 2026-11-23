"""Ensure report collection cannot promote missing or failed native behavior."""

from pathlib import Path
import tempfile
import unittest

from collect_release import MODERN, MOD_ATTACHMENT, PLUGIN, PLUGIN_ATTACHMENT, legacy_fixture_digest, normalize


class NativeCollectionTest(unittest.TestCase):
    def proof(self, minecraft, artifact):
        probe = {"kind": "original-client-native-graph-ticks", "receivedNativeState": True,
                 "originalPacketCodecsConsumed": 50, "probe_exit_code": 0,
                 "inputs": {"native_artifact_sha256": artifact},
                 "receivedStateTicks": {"ticks": 1200, "rootErrors": 0, "removedObservations": 0,
                                        "maximumRelativeDrift": 0},
                 "ticks": 1200, "rootErrors": 0, "removedObservations": 0,
                 "maximumRelativeMotionError": .0002, "interactionRays": {"cases": 32, "helperHits": 0}}
        return {"status": "passed", "minecraft": minecraft, "artifact_sha256": artifact,
                "probes": {"existing": probe, "late": probe}}

    def modern(self):
        return {"minecraft": "1.21.1", "platform": "forge", "sha256": "a" * 64,
                "server": {"sdk": "1.21.1-52.1.14"}, "native_fixture": True,
                "bootstrap": "passed", "connected_clients": True, "restart_passed": True,
                "exit_code": 0, "error": "", "checks": sorted({n for v in [*MODERN.values(), *MOD_ATTACHMENT.values()] for n in v}),
                "restart_fixture": {"native_fixture": True},
                "official_client_attachment": self.proof("1.21.1", "a" * 64)}

    def test_complete_report_preserves_named_evidence(self):
        report = normalize(self.modern(), "modern")
        self.assertEqual(report["result"], "pass", report)
        self.assertIn("late-observer-native-seat-passenger-edge", report["check_evidence"]["late-observer"])

    def test_packet_count_alone_does_not_prove_late_mount_edge(self):
        raw = self.modern()
        raw["checks"].remove("late-observer-native-seat-passenger-edge")
        report = normalize(raw, "modern")
        self.assertEqual(report["result"], "fail")
        self.assertIn("late-observer", report["incomplete_checks"])

    def test_missing_world_transfer_cannot_pass_from_overall_fixture(self):
        raw = self.modern()
        raw["checks"].remove("native-player-world-change-cleans-own-ride")
        self.assertIs(normalize(raw, "modern")["checks"]["world-change"], False)

    def test_mod_server_pairing_does_not_prove_received_client_graph_or_lifetime(self):
        for name in (name for required in MOD_ATTACHMENT.values() for name in required):
            with self.subTest(assertion=name):
                raw = self.modern()
                raw["checks"].remove(name)
                self.assertEqual(normalize(raw, "modern")["result"], "fail")

    def test_mod_numeric_settings_do_not_prove_new_defaults_or_working_click_controls(self):
        from collect_release import FINAL_SETTINGS
        for name in FINAL_SETTINGS:
            with self.subTest(assertion=name):
                raw = self.modern()
                raw["checks"].remove(name)
                self.assertIn("settings", normalize(raw, "modern")["incomplete_checks"])

    def legacy(self, minecraft="1.12.2"):
        sdk, carrier = {
            "1.7.10": ("10.13.4.1614", "signed-size-slime"),
            "1.8.9": ("11.15.1.2318", "marker-armor-stand"),
            "1.12.2": ("14.23.5.2864", "area-effect-cloud"),
        }[minecraft]
        checks = [
            "ordinary-native-driver-mounted", "uncancelled-native-interaction-joins",
            "movement-and-turn-pose-with-driver-preserved",
            "third-rider-rejected-without-disruption", "normal-native-dismount-cleans-seat",
            "driver-dismount-cleans-passenger", "cancelled-interaction-no-join",
            "ordinary-player-settings-permission-denied", "vertical-independent-and-live", "rear-independent-and-live",
            "nonfinite-config-rejected", "command-chat-has-color-and-style", "command-chat-has-clickable-adjustments",
            "command-click-adjustment-applies", "missing-settings-use-owner-defaults", "saved-settings-remain-preserved",
            "fresh-config-uses-owner-defaults", "reset-restores-owner-defaults", "multiple-rides-isolated",
            "native-disconnect-cleans-seat", "real-vanilla-disconnect-cleans-seat", "same-identity-reconnect-can-ride",
            "passenger-death-cleans-seat", "driver-death-cleans-seat", "horse-death-cleans-passenger",
            "native-passenger-dimension-transfer-completes", "native-passenger-world-change-cleans-seat",
            "native-driver-dimension-transfer-completes", "native-driver-world-change-cleans-seat",
            "native-passenger-teleport-cleans-seat", "native-driver-teleport-cleans-seat",
            "native-chunk-unload-cleans-without-reloading", "native-server-stop-cleans-seat",
            "restart-seat-absent-from-native-world",
        ]
        if minecraft != "1.7.10":
            checks += ["cancelled-dismount-stays-mounted", "cancelled-driver-dismount-keeps-ride"]
        if minecraft == "1.8.9":
            checks += ["cancelled-outer-passenger-dismount-keeps-ride", "cancelled-outer-driver-dismount-keeps-ride",
                       "allowed-passenger-dismount-fires-both-events-once", "allowed-driver-dismount-fires-both-events-once"]
        steering = ("native-first-rider-can-steer-shared-ride" if minecraft == "1.12.2"
                    else "native-driver-input-turns-and-moves-shared-ride")
        checks.append(steering)
        final_checks = [{"name": "vanilla-client-seat-survives-native-ticks", "passed": True}]
        observers = {"status": "passed", "real_client_interaction_mounted": True,
                     "native_attachment": {"mode": "early-follower", "shared_horse_root": False}}
        if minecraft == "1.12.2":
            observers["driver_vehicle_move_accepted"] = True
            final_checks.append({"name": "shared-ride-driver-vehicle-move-accepted", "passed": True})
        received = {"seat_spawned": True, "driver_spawned": True, "rider_spawned": True,
                    "driver_on_horse": True, "rider_on_seat": True, "position_matches_native": True}
        for phase, horse, yaw, feet in (
            ("initial", [8, 5, 8], 0, [8, 5.8125, 7.5]),
            ("movement", [20, 5, 8], 90, [20.5, 5.8125, 8]),
            ("live_adjustment", [20, 5, 8], 90, [20.5625, 5.875, 8]),
        ):
            observers[phase] = {"native": {"joined": True, "horse_position": horse, "horse_yaw": yaw, "rider_feet": feet},
                                "existing_observer": received.copy(), "late_observer": received.copy()}
        probe = {"minecraft": minecraft, "carrier": carrier, "status": "passed", "probe_exit_code": 0,
                 "ticks": 264, "spawn_settling_ticks": 24, "removed": False, "unobstructed_picking": True,
                 "native_packets_consumed": 300, "official_client_sha256": "d" * 64,
                 "packet_sequence_sha256": "e" * 64, "probe_report_sha256": "f" * 64}
        return {"minecraft": minecraft, "platform": "forge", "artifact_sha256": "c" * 64,
                "server": {"minecraft": minecraft, "platform": "forge", "sdk": sdk},
                "status": "passed", "exit_code": 0, "restart": {"status": "passed"},
                "vanilla_socket_login": {"status": "passed", "ordered_play_login": True},
                "network_policy": {"status": "not-applicable" if minecraft == "1.12.2" else "passed"},
                "synthetic_behavior": {"status": "passed", "checks": [{"name": name, "passed": True} for name in checks]},
                "protocol_observers": observers, "client_lifetime": {role: probe.copy() for role in ("existing", "late")},
                "final_checks": final_checks}

    def test_all_three_exact_legacy_forge_followers_qualify(self):
        for minecraft in ("1.7.10", "1.8.9", "1.12.2"):
            with self.subTest(minecraft=minecraft):
                report = normalize(self.legacy(minecraft), "legacy")
                self.assertEqual(report["result"], "pass", report)
                self.assertEqual(report["attachment_mode"], "early-follower")
        self.assertEqual(normalize(self.legacy("1.7.10"), "legacy")["checks"]["cancelled-dismount"], "not-applicable")

    def test_legacy_steering_eligibility_alone_does_not_prove_driver_movement(self):
        raw = self.legacy()
        del raw["protocol_observers"]["driver_vehicle_move_accepted"]
        report = normalize(raw, "legacy")
        self.assertEqual(report["result"], "fail")
        self.assertIs(report["checks"]["late-observer"], True)
        self.assertIn("driver-control", report["incomplete_checks"])

    def test_forge_189_requires_each_outer_dismount_veto(self):
        for role in ("driver", "passenger"):
            with self.subTest(role=role):
                raw = self.legacy("1.8.9")
                checks = raw["synthetic_behavior"]["checks"]
                checks.remove({"name": f"cancelled-outer-{role}-dismount-keeps-ride", "passed": True})
                report = normalize(raw, "legacy")
                self.assertIn("cancelled-dismount", report["incomplete_checks"])

    def test_legacy_requires_real_follower_geometry_before_believing_observers(self):
        for phase, feet in (("initial", [8, 5, 7.5]), ("movement", [8, 5.8125, 7.5]),
                            ("live_adjustment", [20.5, 5.875, 7.9375])):
            with self.subTest(phase=phase):
                raw = self.legacy()
                raw["protocol_observers"][phase]["native"]["rider_feet"] = feet
                report = normalize(raw, "legacy")
                self.assertEqual(report["result"], "fail")
                self.assertIn("late-observer", report["incomplete_checks"])

    def test_legacy_requires_both_received_mounts_and_both_original_client_probes(self):
        for role, edge in (("existing", "driver_on_horse"), ("late", "rider_on_seat")):
            with self.subTest(observer=role):
                raw = self.legacy()
                del raw["protocol_observers"]["movement"][role + "_observer"][edge]
                self.assertIn("late-observer", normalize(raw, "legacy")["incomplete_checks"])
                raw = self.legacy()
                del raw["client_lifetime"][role]
                self.assertIn("mount", normalize(raw, "legacy")["incomplete_checks"])

    def test_legacy_original_client_pass_requires_complete_replay(self):
        for field, value in (("ticks", 263), ("unobstructed_picking", False), ("packet_sequence_sha256", "")):
            with self.subTest(field=field):
                raw = self.legacy()
                raw["client_lifetime"]["late"][field] = value
                self.assertIn("mount", normalize(raw, "legacy")["incomplete_checks"])

    def test_failed_final_client_probe_overrides_legacy_server_success(self):
        raw = self.legacy()
        raw["final_checks"] = [{"name": "vanilla-client-seat-survives-native-ticks", "passed": False}]
        report = normalize(raw, "legacy")
        self.assertEqual(report["result"], "fail")
        self.assertIs(report["checks"]["mount"], False)

    def test_failed_process_cannot_pass_from_finished_fixture(self):
        raw = self.modern()
        raw["exit_code"] = 1
        self.assertEqual(normalize(raw, "modern")["result"], "fail")

    def test_lifetime_check_name_alone_cannot_replace_bound_original_client_probes(self):
        for key in (None, "artifact_sha256", "probes"):
            with self.subTest(missing=key):
                raw = self.modern()
                if key is None:
                    del raw["official_client_attachment"]
                else:
                    del raw["official_client_attachment"][key]
                self.assertEqual(normalize(raw, "modern")["result"], "fail")

    def test_fabric_absent_event_is_never_fabricated_as_pass(self):
        raw = self.modern()
        raw["platform"] = "fabric"
        raw["server"] = {"loader": "0.20.1"}
        raw["checks"] = [n for n in raw["checks"] if not n.startswith("loader-")]
        self.assertEqual(normalize(raw, "modern")["result"], "fail")
        raw["loader_cancellation"] = {"status": "not-applicable", "reason": "Loader-only SDK has no event bus"}
        report = normalize(raw, "modern")
        self.assertEqual(report["result"], "pass", report)
        self.assertEqual(report["checks"]["cancelled-dismount"], "not-applicable")

    def test_duplicate_pass_does_not_hide_native_failure(self):
        raw = {"minecraft": "1.12.2", "platform": "paper", "server": {"build": "1620"},
               "artifact_sha256": "b" * 64, "kind": "native-runtime", "status": "passed",
               "restart": "passed", "exit_code": 0,
               "official_client_attachment": self.proof("1.12.2", "b" * 64),
               "assertions": [{"assertion": n, "status": "passed"} for n in sorted({n for v in [*PLUGIN.values(), *PLUGIN_ATTACHMENT.values()] for n in v})]}
        self.assertEqual(normalize(raw, "plugin")["result"], "pass")
        raw["assertions"].insert(0, {"assertion": "ordinary-non-op-passenger-mounted", "status": "failed"})
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIs(report["checks"]["mount"], False)

    def plugin(self):
        return {"minecraft": "26.3", "platform": "paper", "server": {"build": "249"},
                "artifact_sha256": "b" * 64, "kind": "native-runtime", "status": "passed",
                "restart": "passed", "exit_code": 0,
                "official_client_attachment": self.proof("26.3", "b" * 64),
                "assertions": [{"assertion": name, "status": "passed"}
                               for name in sorted({name for required in [*PLUGIN.values(), *PLUGIN_ATTACHMENT.values()] for name in required})]}

    def test_server_event_alone_cannot_prove_real_client_mounting(self):
        raw = self.plugin()
        raw["assertions"] = [item for item in raw["assertions"]
                             if item["assertion"] not in {"real-client-right-click-mount-persists",
                                                          "real-client-repeated-click-keeps-ride",
                                                          "real-client-mounted-movement-keeps-ride"}]
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIn("mount", report["incomplete_checks"])

    def test_numeric_settings_alone_cannot_prove_formatted_working_controls(self):
        raw = self.plugin()
        raw["assertions"] = [item for item in raw["assertions"]
                             if item["assertion"] not in {"command-chat-has-color-and-style",
                                                          "command-chat-has-clickable-adjustments",
                                                          "command-click-adjustment-applies"}]
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIn("settings", report["incomplete_checks"])

    def test_server_seat_survival_cannot_prove_vanilla_client_lifetime(self):
        raw = self.plugin()
        raw["assertions"] = [item for item in raw["assertions"]
                             if item["assertion"] != "vanilla-client-seat-survives-native-ticks"]
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIn("mount", report["incomplete_checks"])

    def test_independent_follower_cannot_pass_any_native_attachment_contract(self):
        for version in ("1.7.10", "1.8.9", "1.12.2", "1.16.5", "1.19.4", "1.20.4", "1.20.5", "26.3"):
            with self.subTest(minecraft=version):
                raw = self.plugin()
                raw["minecraft"] = version
                raw["official_client_attachment"]["minecraft"] = version
                missing = {name for required in PLUGIN_ATTACHMENT.values() for name in required}
                raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] not in missing]
                report = normalize(raw, "plugin")
                self.assertEqual(report["result"], "fail")
                self.assertIn("mount", report["incomplete_checks"])
                self.assertIn("late-observer", report["incomplete_checks"])

    def test_server_graph_alone_cannot_prove_existing_and_late_client_attachment(self):
        for name in ("tmthird-received-native-attachment-shares-horse-root",
                     "tmlate-received-native-attachment-shares-horse-root",
                     "tmthird-attachment-has-no-periodic-position-follow-packets",
                     "tmlate-attachment-has-no-periodic-position-follow-packets"):
            with self.subTest(assertion=name):
                raw = self.plugin()
                raw["minecraft"] = "1.20.4"
                raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] != name]
                self.assertIn("late-observer", normalize(raw, "plugin")["incomplete_checks"])

    def test_numeric_offsets_alone_cannot_prove_new_default_and_preservation_contract(self):
        raw = self.plugin()
        raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] not in {
            "missing-settings-use-owner-defaults", "saved-settings-remain-preserved",
            "fresh-config-uses-owner-defaults", "reset-restores-owner-defaults"}]
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIn("settings", report["incomplete_checks"])

    def test_attached_graph_does_not_require_world_follower_packets_or_driver_only_graph(self):
        for version in ("1.7.10", "1.12.2", "1.19.4", "1.20.4", "1.20.5", "26.3"):
            with self.subTest(minecraft=version):
                raw = self.plugin()
                raw["minecraft"] = version
                raw["official_client_attachment"]["minecraft"] = version
                old_follower_checks = {"tmlate-horse-has-only-driver-on-client", "tmlate-seat-position-updates-received"}
                raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] not in old_follower_checks]
                self.assertEqual(normalize(raw, "plugin")["result"], "pass")


class LegacyFixtureBindingTest(unittest.TestCase):
    inputs = ("tools/legacy-native-test.py", "forge/legacy/native-test/NativeChecks.java", "tools/legacy-client-test.py",
              "forge/legacy/client-test/ClientChecks.java", "tools/legacy-build.py", "tools/project_metadata.py",
              "tools/runtime_storage.py", "tools/legacy_evidence.py", "project.json")

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for name in self.inputs:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())

    def test_each_native_and_original_client_input_invalidates_evidence(self):
        for name in self.inputs:
            with self.subTest(input=name):
                before = legacy_fixture_digest(self.root)
                (self.root / name).write_bytes(b"changed executable probe input")
                self.assertNotEqual(before, legacy_fixture_digest(self.root))

    def test_missing_original_client_probe_cannot_use_the_old_native_digest(self):
        (self.root / "forge/legacy/client-test/ClientChecks.java").unlink()
        with self.assertRaises(FileNotFoundError):
            legacy_fixture_digest(self.root)


if __name__ == "__main__":
    unittest.main()
