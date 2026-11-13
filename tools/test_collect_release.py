"""Ensure report collection cannot promote missing or failed native behavior."""

import unittest

from collect_release import MODERN, PLUGIN, normalize


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


if __name__ == "__main__":
    unittest.main()
