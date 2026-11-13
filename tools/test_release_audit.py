"""Exercise the release artifact/evidence contract with compiled fixture JARs."""

import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from release_audit import REQUIRED_CHECKS, audit, child, digest
from resolve_platforms import VERSIONS


class ReleaseAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compile_dir = tempfile.TemporaryDirectory()
        directory = Path(cls.compile_dir.name)
        source = directory / "AuditFixture.java"
        source.write_text("public final class AuditFixture { public static int riders() { return 2; } }\n")
        subprocess.run(["javac", "--release", "8", str(source)], check=True, capture_output=True)
        cls.class_bytes = source.with_suffix(".class").read_bytes()

    @classmethod
    def tearDownClass(cls):
        cls.compile_dir.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.license = b"Release audit fixture license contract\n"
        (self.root / "LICENSE").write_bytes(self.license)
        self.jar = self.root / "Tandemount.jar"
        self.write_jar()
        self.matrix = {"targets": [
            {"minecraft": v, "platforms": {"forge": {"available": v == "1.7.10"}}}
            for v in VERSIONS]}
        self.artifact = {"id": "forge-1.7.10", "file": "Tandemount.jar", "sha256": digest(self.jar),
                         "java_min": 8, "minecraft": ["1.7.10"], "platforms": ["forge"],
                         "native_results": ["forge-1.7.10.json"]}
        self.manifest = {"schema": 1, "name": "Tandemount", "license": "GPL-3.0-only",
                         "source_commit": "a" * 40, "artifacts": [self.artifact]}
        self.report = {"minecraft": "1.7.10", "platform": "forge", "kind": "native",
                       "artifact_sha256": self.artifact["sha256"], "result": "pass",
                       "server_version": "Fixture server metadata", "checks": {k: True for k in REQUIRED_CHECKS}}
        self.save_report()

    def write_jar(self, extra=None):
        with zipfile.ZipFile(self.jar, "w") as jar:
            jar.writestr("AuditFixture.class", self.class_bytes)
            jar.writestr("LICENSE", self.license)
            jar.writestr("mcmod.info", '[{"modid":"tandemount","version":"1.0.0"}]')
            for key, value in (extra or {}).items():
                jar.writestr(key, value)

    def save_report(self):
        (self.evidence / "forge-1.7.10.json").write_text(json.dumps(self.report))

    def run_audit(self):
        return audit(self.root, self.matrix, self.manifest, self.evidence)

    def assert_failed(self, message):
        result = self.run_audit()
        self.assertEqual(result["result"], "fail")
        self.assertTrue(any(message in error for error in result["errors"]), result)

    def test_matching_artifact_and_complete_native_contract_pass(self):
        result = self.run_audit()
        self.assertEqual(result["result"], "pass", result)
        self.assertEqual(result["native_verified_targets"], 1)

    def test_modified_artifact_is_rejected(self):
        self.write_jar({"changed.txt": "different build"})
        self.assert_failed("Artifact SHA256 mismatch")

    def test_evidence_for_a_previous_build_is_rejected(self):
        self.report["artifact_sha256"] = "0" * 64
        self.save_report()
        self.assert_failed("Native evidence artifact hash mismatch")

    def test_only_bootstrap_evidence_is_insufficient(self):
        self.report["checks"] = {"bootstrap": True}
        self.save_report()
        self.assert_failed("Native checks incomplete")

    def test_model_evidence_cannot_be_presented_as_native(self):
        self.report["kind"] = "model"
        self.save_report()
        self.assert_failed("Native server execution evidence incomplete")

    def test_failed_native_check_is_rejected(self):
        self.report["checks"]["driver-control"] = False
        self.save_report()
        self.assert_failed("driver-control")

    def test_missing_available_target_is_rejected(self):
        self.matrix["targets"][1]["platforms"]["forge"]["available"] = True
        self.assert_failed("do not cover all requested available targets")

    def test_unavailable_target_claim_is_rejected(self):
        self.artifact["minecraft"].append("1.8.9")
        self.assert_failed("Unavailable upstream targets")

    def test_missing_native_result_stays_unverified(self):
        self.artifact["native_results"] = []
        self.assert_failed("Native coverage missing")

    def test_wrong_platform_descriptor_is_rejected(self):
        self.matrix["targets"][0]["platforms"] = {"paper": {"available": True}}
        self.artifact["platforms"] = ["paper"]
        self.assert_failed("Plugin descriptor missing")

    def test_duplicate_targets_are_rejected(self):
        other = copy.deepcopy(self.artifact)
        other["id"] = "duplicate"
        other["file"] = "second.jar"
        (self.root / "second.jar").write_bytes(self.jar.read_bytes())
        self.manifest["artifacts"].append(other)
        self.assert_failed("Targets declared by several artifacts")

    def test_path_escape_is_rejected(self):
        self.artifact["file"] = "../outside.jar"
        self.assert_failed("Path must remain inside")

    def test_embedded_runtime_files_are_rejected(self):
        self.write_jar({"server.properties": "sensitive-runtime-config"})
        self.artifact["sha256"] = digest(self.jar)
        self.assert_failed("Private/runtime files in JAR")

    def test_license_mismatch_is_rejected(self):
        (self.root / "LICENSE").write_text("different license")
        self.assert_failed("Packaged GPL-3.0 text")


if __name__ == "__main__":
    unittest.main()
