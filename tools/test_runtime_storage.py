"""Exercise disposable workspace staging without Minecraft or external state."""

import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from runtime_storage import RuntimeWorkspace


class RuntimeStorageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = self.root / "official-sdk"
        (self.sdk / "libraries").mkdir(parents=True)
        (self.sdk / "libraries/library.jar").write_bytes(b"Library fixture")
        (self.sdk / "server.jar").write_bytes(b"Server fixture")
        (self.sdk / "distribution.json").write_text('{"minecraft":"fixture"}')

    def test_fast_runtime_copies_sdk_and_retains_evidence_with_original_sdk_links(self):
        with patch.dict(os.environ, {"TANDEMOUNT_RUN_CACHE": str(self.root / "fast")}):
            workspace = RuntimeWorkspace(self.root / "retained", "run-one", self.sdk)
            run = workspace.directory
            self.assertFalse((run / "server.jar").is_symlink())
            self.assertEqual((run / "libraries/library.jar").read_bytes(), b"Library fixture")
            (run / "console.log").write_text("Behavior fixture passed\n")
            (run / "world").mkdir()
            (run / "world/level.dat").write_bytes(b"World fixture")
            report = workspace.finish({"run_directory": str(run), "nested": {"file": str(run / "console.log")}})
        retained = self.root / "retained/run-one"
        self.assertFalse(run.exists())
        self.assertEqual((retained / "console.log").read_text(), "Behavior fixture passed\n")
        self.assertEqual((retained / "world/level.dat").read_bytes(), b"World fixture")
        self.assertTrue((retained / "server.jar").is_symlink())
        self.assertEqual((retained / "libraries/library.jar").read_bytes(), b"Library fixture")
        self.assertEqual(report["nested"]["file"], str(retained / "console.log"))
        self.assertEqual((self.sdk / "server.jar").read_bytes(), b"Server fixture")

    def test_default_workspace_uses_existing_distribution_without_copying(self):
        with patch.dict(os.environ, {}, clear=True):
            workspace = RuntimeWorkspace(self.root / "retained", "run-two", self.sdk)
            self.assertTrue((workspace.directory / "server.jar").is_symlink())
            report = workspace.finish({"run_directory": str(workspace.directory)})
            self.assertEqual(report["runtime_storage"], "distribution-symlinks")

    def test_plugin_patched_cache_is_staged_without_reusing_sdk_worlds(self):
        (self.sdk / "cache").mkdir()
        (self.sdk / "cache/patched.jar").write_bytes(b"Patched SDK fixture")
        (self.sdk / "world").mkdir()
        (self.sdk / "world/level.dat").write_bytes(b"Never copy an existing SDK world")
        with patch.dict(os.environ, {"TANDEMOUNT_RUN_CACHE": str(self.root / "fast")}):
            workspace = RuntimeWorkspace(self.root / "retained", "plugin-run", self.sdk, extra_names=("cache",))
            self.assertEqual((workspace.directory / "cache/patched.jar").read_bytes(), b"Patched SDK fixture")
            self.assertFalse((workspace.directory / "world").exists())
            workspace.finish({"run": str(workspace.directory)})
        self.assertTrue((self.root / "retained/plugin-run/cache").is_symlink())
        self.assertEqual((self.sdk / "world/level.dat").read_bytes(), b"Never copy an existing SDK world")


if __name__ == "__main__":
    unittest.main()
