"""Verify source releases against Git's committed blob contract."""

from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from source_archive import export


class SourceArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        (self.root / "LICENSE").write_text("Fixture license\n")
        (self.root / "README.md").write_text("# Source fixture\n")
        (self.root / "src").mkdir()
        (self.root / "src/file.txt").write_text("Committed fixture output\n")
        self.git("add", ".")
        self.commit()
        self.output = self.root / "out/source.zip"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.PIPE)

    def commit(self):
        self.git("-c", "user.name=Tandemount Tests", "-c", "user.email=tests@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Fixture")

    def test_untracked_material_and_local_edits_do_not_enter_committed_source(self):
        (self.root / "src/file.txt").write_text("Uncommitted work\n")
        (self.root / ".agents").mkdir()
        (self.root / ".agents/private.txt").write_text("Private fixture material\n")
        result = export(self.root, "HEAD", self.output, "1.0.0")
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.read("Tandemount-1.0.0/src/file.txt"), b"Committed fixture output\n")
            self.assertFalse(any(".agents" in name for name in archive.namelist()))
        self.assertEqual(result["files"], 3)

    def test_repeat_export_is_byte_identical(self):
        first = export(self.root, "HEAD", self.output, "1.0.0")
        again = export(self.root, "HEAD", self.root / "another.zip", "1.0.0")
        self.assertEqual(first["sha256"], again["sha256"])

    def test_accidentally_tracked_private_material_stops_release(self):
        (self.root / ".agents").mkdir()
        (self.root / ".agents/private.txt").write_text("Private fixture material\n")
        self.git("add", ".agents/private.txt")
        self.commit()
        with self.assertRaisesRegex(ValueError, "Private/runtime file is tracked"):
            export(self.root, "HEAD", self.output, "1.0.0")

    def test_missing_license_stops_release(self):
        self.git("rm", "LICENSE")
        self.commit()
        with self.assertRaisesRegex(ValueError, "requires LICENSE"):
            export(self.root, "HEAD", self.output, "1.0.0")

    def test_version_cannot_escape_archive_prefix(self):
        with self.assertRaisesRegex(ValueError, "Unsafe version label"):
            export(self.root, "HEAD", self.output, "../../outside")


if __name__ == "__main__":
    unittest.main()
