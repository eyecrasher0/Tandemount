"""Exercise native-evidence reuse after byte-identical target rebuilds."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from matrix_verify import rebuilt_cache, same_source_cache, runner_sources, sources, sha


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

    def test_same_source_fast_path_requires_the_attested_runner(self):
        for runner in ('changed-runner', None):
            with self.subTest(runner=runner):
                self.record['runner_fingerprint'] = runner
                self.assertIsNone(same_source_cache(self.record, self.artifact, self.native,
                                                    'old-source', 'same-runner', self.fixture))


class RunnerFingerprintTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        names = ('plugin-build.py', 'plugin-runtime.py', 'vanilla_protocol.py', 'runtime_storage.py',
                 'cache_servers.py', 'modern-build.py', 'modern-native.py', 'modern-client.py',
                 'project_metadata.py')
        for name in names:
            self.write('tools/' + name, 'initial runner input')
        self.write('platforms.json', '{}')
        self.write('project.json', '{"version":"1.0.0"}')
        self.write('LICENSE', 'license fixture')
        self.write('plugin/src/test/client/ClientProbe.java', 'initial standalone client probe input')
        self.patch = patch('matrix_verify.ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def test_project_version_is_an_input_to_both_matrix_fingerprints(self):
        for mode in ('plugins', 'modern'):
            with self.subTest(mode=mode):
                self.write('project.json', '{"version":"1.0.0"}')
                before = sources(mode), runner_sources(mode)
                self.write('project.json', '{"version":"1.0.1"}')
                self.assertNotEqual(before[0], sources(mode))
                self.assertNotEqual(before[1], runner_sources(mode))

    def test_changed_standalone_client_probe_changes_plugin_runner_only(self):
        before = runner_sources('plugins'), sources('plugins'), runner_sources('modern'), sources('modern')
        self.write('plugin/src/test/client/ClientProbe.java', 'different client lifecycle behavior')
        after = runner_sources('plugins'), sources('plugins'), runner_sources('modern'), sources('modern')
        self.assertNotEqual(before[0], after[0])
        self.assertNotEqual(before[1], after[1])
        self.assertEqual(before[2:], after[2:])

    def test_added_or_changed_plugin_helpers_invalidate_plugin_proof(self):
        for name in ('plugin-owner-regression.py', 'plugin_protocol.py', 'client_lifetime.py'):
            with self.subTest(name=name):
                before = runner_sources('plugins'), sources('plugins')
                self.write('tools/' + name, 'first executable helper input')
                added = runner_sources('plugins'), sources('plugins')
                self.assertNotEqual(before, added)
                self.write('tools/' + name, 'changed executable helper input')
                self.assertNotEqual(added, (runner_sources('plugins'), sources('plugins')))

    def test_added_or_changed_modern_wire_and_client_probes_invalidate_modern_proof(self):
        for name in ('tools/modern-wire.py', 'tools/modern-client-components.py',
                     'mods/modern/src/test/client/InteractionPoseProbe.java'):
            with self.subTest(input=name):
                before = runner_sources('modern'), sources('modern')
                other_before = runner_sources('plugins'), sources('plugins')
                self.write(name, 'first executable probe input')
                added = runner_sources('modern'), sources('modern')
                self.assertNotEqual(before, added)
                self.write(name, 'changed executable probe input')
                self.assertNotEqual(added, (runner_sources('modern'), sources('modern')))
                self.assertEqual(other_before, (runner_sources('plugins'), sources('plugins')))

    def test_shared_official_client_runner_changes_invalidate_both_families(self):
        for name in ('tools/client_attachment.py', 'tools/attachment_evidence.py',
                     'tests/client/AttachmentClientProbe.java'):
            with self.subTest(input=name):
                before = tuple((runner_sources(mode), sources(mode)) for mode in ('modern', 'plugins'))
                self.write(name, 'first executable shared client input')
                added = tuple((runner_sources(mode), sources(mode)) for mode in ('modern', 'plugins'))
                for old, new in zip(before, added):
                    self.assertNotEqual(old, new)
                self.write(name, 'changed executable shared client input')
                changed = tuple((runner_sources(mode), sources(mode)) for mode in ('modern', 'plugins'))
                for old, new in zip(added, changed):
                    self.assertNotEqual(old, new)


if __name__ == '__main__':
    unittest.main()
