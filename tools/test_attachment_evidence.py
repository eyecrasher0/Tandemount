"""Reject incomplete, stale and non-client attachment report contracts."""
import copy
import unittest

from attachment_evidence import passed_probe


class OfficialClientEvidenceTest(unittest.TestCase):
    def report(self):
        return {"kind": "original-client-native-graph-ticks", "receivedNativeState": True,
                "originalPacketCodecsConsumed": 80, "probe_exit_code": 0,
                "inputs": {"native_artifact_sha256": "a" * 64},
                "receivedStateTicks": {"ticks": 1200, "rootErrors": 0, "removedObservations": 0,
                                       "maximumRelativeDrift": 0},
                "ticks": 1200, "rootErrors": 0, "removedObservations": 0,
                "maximumRelativeMotionError": .0002,
                "interactionRays": {"cases": 32, "helperHits": 0}}

    def test_complete_received_packet_and_native_tick_contract_passes(self):
        self.assertTrue(passed_probe(self.report(), "a" * 64))

    def test_each_missing_client_component_fails_closed(self):
        report = self.report()
        for key in report:
            with self.subTest(component=key):
                incomplete = copy.deepcopy(report)
                del incomplete[key]
                self.assertFalse(passed_probe(incomplete, "a" * 64))

    def test_another_artifact_cannot_reuse_client_proof(self):
        self.assertFalse(passed_probe(self.report(), "b" * 64))

    def test_wrong_geometry_root_removal_or_raycast_is_not_a_lifetime_pass(self):
        for section, key, value in (("receivedStateTicks", "rootErrors", 1),
                                    ("receivedStateTicks", "removedObservations", 1),
                                    ("receivedStateTicks", "maximumRelativeDrift", .1),
                                    ("interactionRays", "helperHits", 1)):
            with self.subTest(section=section, field=key):
                report = self.report()
                report[section][key] = value
                self.assertFalse(passed_probe(report, "a" * 64))


if __name__ == "__main__":
    unittest.main()
