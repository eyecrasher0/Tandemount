"""Ensure report collection cannot promote missing or failed native behavior."""

import unittest

from collect_release import MODERN, PLUGIN, PLUGIN_26_3_ATTACHMENT, normalize


class NativeCollectionTest(unittest.TestCase):
    def modern(self):
        return {"minecraft": "1.21.1", "platform": "forge", "sha256": "a" * 64,
                "server": {"sdk": "1.21.1-52.1.14"}, "native_fixture": True,
                "bootstrap": "passed", "connected_clients": True, "restart_passed": True,
                "exit_code": 0, "error": "", "checks": sorted({n for v in MODERN.values() for n in v}),
                "restart_fixture": {"native_fixture": True}}

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

    def test_failed_process_cannot_pass_from_finished_fixture(self):
        raw = self.modern()
        raw["exit_code"] = 1
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
               "assertions": [{"assertion": n, "status": "passed"} for n in sorted({n for v in PLUGIN.values() for n in v})]}
        self.assertEqual(normalize(raw, "plugin")["result"], "pass")
        raw["assertions"].insert(0, {"assertion": "ordinary-non-op-passenger-mounted", "status": "failed"})
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIs(report["checks"]["mount"], False)

    def plugin(self):
        return {"minecraft": "26.3", "platform": "paper", "server": {"build": "249"},
                "artifact_sha256": "b" * 64, "kind": "native-runtime", "status": "passed",
                "restart": "passed", "exit_code": 0,
                "assertions": [{"assertion": name, "status": "passed"}
                               for name in sorted({name for required in [*PLUGIN.values(), *PLUGIN_26_3_ATTACHMENT.values()] for name in required})]}

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

    def test_independent_follower_cannot_pass_the_26_3_native_attachment_contract(self):
        raw = self.plugin()
        missing = {name for required in PLUGIN_26_3_ATTACHMENT.values() for name in required}
        raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] not in missing]
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIn("mount", report["incomplete_checks"])
        self.assertIn("late-observer", report["incomplete_checks"])

    def test_26_3_attachment_requirements_do_not_claim_older_platform_implementation(self):
        raw = self.plugin()
        raw["minecraft"] = "1.12.2"
        missing = {name for required in PLUGIN_26_3_ATTACHMENT.values() for name in required}
        raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] not in missing]
        self.assertEqual(normalize(raw, "plugin")["result"], "pass")

    def test_numeric_offsets_alone_cannot_prove_new_default_and_preservation_contract(self):
        raw = self.plugin()
        raw["assertions"] = [item for item in raw["assertions"] if item["assertion"] not in {
            "missing-settings-use-owner-defaults", "saved-settings-remain-preserved",
            "fresh-config-uses-owner-defaults", "reset-restores-owner-defaults"}]
        report = normalize(raw, "plugin")
        self.assertEqual(report["result"], "fail")
        self.assertIn("settings", report["incomplete_checks"])


if __name__ == "__main__":
    unittest.main()
