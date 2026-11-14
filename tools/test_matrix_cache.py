"""Exercise native-evidence reuse after byte-identical target rebuilds."""

import json
from pathlib import Path
import tempfile
import unittest

from matrix_verify import rebuilt_cache, sha


class MatrixCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifact = self.root / 'plugin.jar'
        self.artifact.write_bytes(b'Exact built artifact')
        self.fixture = self.root / 'fixture.jar'
        self.fixture.write_bytes(b'Exact executable native fixture')
        self.native = self.root / 'native.json'
        self.native.write_text(json.dumps({'artifact_sha256': sha(self.artifact), 'fixture_sha256': sha(self.fixture)}))
        self.record = {'result': 'pass', 'source_fingerprint': 'old-source', 'runner_fingerprint': 'same-runner',
                       'artifact_sha256': sha(self.artifact), 'native_report_sha256': sha(self.native)}

    def reuse(self):
        return rebuilt_cache(self.record, self.artifact, self.native, 'new-source', 'same-runner', self.fixture)

    def test_identical_artifact_runner_fixture_and_report_retain_real_execution(self):
        cached = self.reuse()
        self.assertEqual(cached['source_fingerprint'], 'new-source')
        self.assertEqual(cached['previous_source_fingerprint'], 'old-source')
        self.assertTrue(cached['native_reused_after_identical_rebuild'])

    def test_different_executable_artifact_requires_new_native_run(self):
        self.artifact.write_bytes(b'Changed behavior')
        self.assertIsNone(self.reuse())

    def test_changed_test_fixture_requires_new_native_run(self):
        self.fixture.write_bytes(b'New native assertions')
        self.assertIsNone(self.reuse())

    def test_changed_raw_native_evidence_requires_new_run(self):
        self.native.write_text('{}')
        self.assertIsNone(self.reuse())

    def test_changed_runner_requires_new_native_run(self):
        self.record['runner_fingerprint'] = 'previous-runner'
        self.assertIsNone(self.reuse())

    def test_missing_runner_provenance_or_failed_result_cannot_be_reused(self):
        self.record.pop('runner_fingerprint')
        self.assertIsNone(self.reuse())
        self.record['runner_fingerprint'] = 'same-runner'
        self.record['result'] = 'fail'
        self.assertIsNone(self.reuse())


if __name__ == '__main__':
    unittest.main()
