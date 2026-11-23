"""Ensure report collection cannot promote missing or failed native behavior."""

from pathlib import Path
import tempfile
import unittest

from collect_release import LEGACY, MODERN, MOD_ATTACHMENT, PLUGIN, PLUGIN_ATTACHMENT, legacy_fixture_digest, normalize


class NativeCollectionTest(unittest.TestCase):
    def server(self, minecraft, platform, **build):
        return {"minecraft": minecraft, "platform": platform, "launcher": "server.jar",
                "launch_files": {"server.jar": "e" * 64}, **build}

    def legacy_probe(self, minecraft, carrier):
        return {"minecraft": minecraft, "status": "passed", "probe_exit_code": 0, "ticks": 1200,
                "removed": False, "unobstructed_picking": True, "carrier": carrier,
                "official_client_sha256": "e" * 64, "packet_sequence_sha256": "f" * 64,
                "probe_report_sha256": "a" * 64}

    def proof(self, minecraft, artifact):
        if minecraft in ("1.7.10", "1.12.2"):
            carrier = {"1.7.10": "signed-size-slime", "1.12.2": "marker-armor-stand"}[minecraft]
            return {"status": "passed", "minecraft": minecraft, "artifact_sha256": artifact,
                    "probes": {role: self.legacy_probe(minecraft, carrier) for role in ("existing", "late")}}
        probe = {"kind": "original-client-native-graph-ticks", "receivedNativeState": True,
                 "originalPacketCodecsConsumed": 50, "probe_exit_code": 0,
                 "attachmentMode": "native-horse-root" if tuple(map(int, minecraft.split("."))) >= (1, 19, 4) else "early-follower",
                 "inputs": {"native_artifact_sha256": artifact},
                 "receivedStateTicks": {"ticks": 1200, "rootErrors": 0, "removedObservations": 0,
                                        "maximumRelativeDrift": 0},
                 "ticks": 1200, "rootErrors": 0, "removedObservations": 0,
                 "maximumRelativeMotionError": .0002, "interactionRays": {"cases": 32, "helperHits": 0}}
        return {"status": "passed", "minecraft": minecraft, "artifact_sha256": artifact,
                "probes": {"existing": probe, "late": probe}}

    def modern(self):
        return {"minecraft": "1.21.1", "platform": "forge", "sha256": "a" * 64,
                "server": self.server("1.21.1", "forge", sdk="1.21.1-52.1.14"), "native_fixture": True,
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
            "1.7.10": ("1.7.10-10.13.4.1614-1.7.10", "signed-size-slime"),
            "1.8.9": ("1.8.9-11.15.1.2318-1.8.9", "inert-marker-armor-stand"),
            "1.12.2": ("1.12.2-14.23.5.2864", "barrier-falling-block"),
        }[minecraft]
        return {"minecraft": minecraft, "platform": "forge", "sdk": sdk,
                "server": self.server(minecraft, "forge", sdk=sdk),
                "artifact_sha256": "c" * 64, "status": "passed", "exit_code": 0,
                "restart": {"status": "passed"},
                "vanilla_socket_login": {"status": "passed", "ordered_play_login": True},
                "synthetic_behavior": {"status": "passed"},
                "protocol_observers": {"status": "passed", "real_client_interaction_mounted": True,
                                       "native_attachment": {"mode": "early-follower"}},
                "client_lifetime": {role: self.legacy_probe(minecraft, carrier) for role in ("existing", "late")},
                "network_policy": {"status": "not-applicable" if minecraft == "1.12.2" else "passed"},
                "native_checks": [{"name": name, "passed": True}
                                  for name in sorted({n for v in [*LEGACY.values(), *MOD_ATTACHMENT.values()] for n in v})]}

    def test_legacy_follower_needs_real_mount_and_both_original_client_probes(self):
        for version in ("1.7.10", "1.8.9", "1.12.2"):
            with self.subTest(minecraft=version):
                raw = self.legacy(version)
                self.assertEqual(normalize(raw, "legacy")["result"], "pass")
                raw["client_lifetime"].pop("late")
                self.assertEqual(normalize(raw, "legacy")["result"], "fail")
                raw = self.legacy(version)
                raw["protocol_observers"]["real_client_interaction_mounted"] = False
                self.assertEqual(normalize(raw, "legacy")["result"], "fail")

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
        raw["server"] = self.server(raw["minecraft"], "fabric", loader="0.20.1")
        raw["checks"] = [n for n in raw["checks"] if not n.startswith("loader-")]
        self.assertEqual(normalize(raw, "modern")["result"], "fail")
        raw["loader_cancellation"] = {"status": "not-applicable", "reason": "Loader-only SDK has no event bus"}
        report = normalize(raw, "modern")
        self.assertEqual(report["result"], "pass", report)
        self.assertEqual(report["checks"]["cancelled-dismount"], "not-applicable")

    def test_duplicate_pass_does_not_hide_native_failure(self):
        raw = self.plugin()
        self.assertEqual(normalize(raw, "plugin")["result"], "pass")
        raw["assertions"].insert(0, {"assertion": "ordinary-non-op-passenger-mounted", "status": "failed"})
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIs(report["checks"]["mount"], False)

    def plugin(self, minecraft="26.3"):
        attached = tuple(map(int, minecraft.split("."))) >= (1, 19, 4)
        required = [*PLUGIN.values(), *PLUGIN_ATTACHMENT.values()] if attached else [
            *PLUGIN.values(), ("tmthird-horse-has-only-driver-on-client", "tmthird-seat-position-updates-received")]
        return {"minecraft": minecraft, "platform": "paper",
                "server": self.server(minecraft, "paper", build="fixture-build"),
                "artifact_sha256": "b" * 64, "kind": "native-runtime", "status": "passed",
                "restart": "passed", "exit_code": 0,
                "official_client_attachment": self.proof(minecraft, "b" * 64),
                "assertions": [{"assertion": name, "status": "passed"}
                               for name in sorted({name for group in required for name in group})]}

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
        for version in ("1.19.4", "1.20.4", "1.20.5", "26.3"):
            with self.subTest(minecraft=version):
                raw = self.plugin(version)
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
                raw = self.plugin("1.20.4")
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
        for version in ("1.19.4", "1.20.4", "1.20.5", "26.3"):
            with self.subTest(minecraft=version):
                raw = self.plugin(version)
                old_follower_checks = {"tmlate-horse-has-only-driver-on-client", "tmlate-seat-position-updates-received"}
                raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] not in old_follower_checks]
                self.assertEqual(normalize(raw, "plugin")["result"], "pass")

    def test_early_plugin_needs_its_observed_follower_and_position_updates(self):
        raw = self.plugin("1.12.2")
        self.assertEqual(normalize(raw, "plugin")["result"], "pass")
        raw["assertions"] = [item for item in raw["assertions"]
                             if item["assertion"] != "tmthird-seat-position-updates-received"]
        self.assertIn("late-observer", normalize(raw, "plugin")["incomplete_checks"])


class LegacyFixtureBindingTest(unittest.TestCase):
    def test_each_native_and_original_client_input_invalidates_evidence(self):
        paths = ("tools/legacy-native-test.py", "mods/legacy/native-test/NativeChecks.java",
                 "tools/legacy-client-test.py", "mods/legacy/client-test/ClientChecks.java")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in paths:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
            for name in paths:
                with self.subTest(input=name):
                    before = legacy_fixture_digest(root)
                    (root / name).write_bytes(b"changed executable probe input")
                    self.assertNotEqual(before, legacy_fixture_digest(root))

    def test_missing_original_client_probe_cannot_use_the_old_native_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                legacy_fixture_digest(Path(directory))


if __name__ == "__main__":
    unittest.main()
