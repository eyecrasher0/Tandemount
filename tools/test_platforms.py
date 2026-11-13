"""Behavior checks for exact-version release metadata resolution."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from resolve_platforms import Research, VERSIONS, java_for, neoforge_prefix, select_sdk


class VersionsTest(unittest.TestCase):
    def test_all_requested_versions_are_distinct(self):
        self.assertEqual(len(VERSIONS), 38)
        self.assertEqual(len(set(VERSIONS)), 38)

    def test_java_boundaries(self):
        for version, expected in [
            ("1.7.10", 8), ("1.12.2", 8), ("1.16.5", 8), ("1.17", 16),
            ("1.17.1", 16), ("1.18", 17), ("1.20.4", 17), ("1.20.5", 21),
            ("1.21.11", 21), ("26.1", 25), ("26.3", 25),
        ]:
            with self.subTest(version=version):
                self.assertEqual(java_for(version), expected)

    def test_numeric_sdk_order_ignores_input_order(self):
        self.assertEqual(select_sdk(["21.1.99", "21.1.266", "21.1.100"], "21.1."), "21.1.266")

    def test_stable_sdk_preferred_over_beta(self):
        self.assertEqual(select_sdk(["21.1.2", "21.1.3-beta"], "21.1."), "21.1.2")

    def test_beta_only_upstream_is_available(self):
        self.assertEqual(select_sdk(["21.2.1-beta"], "21.2."), "21.2.1-beta")

    def test_adjacent_version_does_not_supply_missing_sdk(self):
        self.assertIsNone(select_sdk(["1.20.6-50.2.15"], "1.20.5-"))

    def test_neoforge_exact_prefixes(self):
        for version, expected in [
            ("1.20.1", None), ("1.20.2", "20.2."), ("1.21", "21.0."),
            ("1.21.11", "21.11."), ("26.1", "26.1.0."),
            ("26.1.1", "26.1.1."), ("26.3", "26.3.0."),
        ]:
            with self.subTest(version=version):
                self.assertEqual(neoforge_prefix(version), expected)


class SpigotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "BuildData"
        self.repo.mkdir()
        self.git("init", "--quiet")
        self.refs = {}
        for version in ("1.20", "1.20.1", "1.21.11_unobfuscated", "1.21.11"):
            (self.repo / "info.json").write_text(json.dumps({"minecraftVersion": version}))
            self.git("add", "info.json")
            self.git("-c", "user.name=Tandemount Tests", "-c", "user.email=tests@example.invalid",
                     "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", version)
            self.refs[version] = self.git("rev-parse", "HEAD").decode().strip()
        fixtures = self

        class FixtureResearch(Research):
            def __init__(self):
                self.output = Path(fixtures.temp.name) / "evidence"
                self.output.mkdir()
                self.builddata = fixtures.repo / ".git"
                self.responses = {}

            def fetch(self, url, kind="json", absent=False):
                if absent:
                    return self.responses.get(url)
                return self.responses[url]

        self.research = FixtureResearch()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.PIPE)

    def metadata(self, name, version):
        return {"name": name, "refs": {"BuildData": self.refs[version]}, "javaVersions": [61, 65]}

    def test_named_exact_release_uses_pinned_official_git_object(self):
        self.research.responses["https://hub.spigotmc.org/versions/1.20.json"] = self.metadata("1", "1.20")
        result = self.research.spigot("1.20")
        self.assertTrue(result["available"])
        self.assertEqual(result["minecraft"], "1.20")
        self.assertEqual(result["revision"], "1")

    def test_aliased_name_falls_back_to_exact_historical_release(self):
        self.research.responses.update({
            "https://hub.spigotmc.org/versions/1.20.json": self.metadata("2", "1.20.1"),
            "https://hub.spigotmc.org/versions/3774.json": self.metadata("3774", "1.20"),
        })
        result = self.research.spigot("1.20")
        self.assertTrue(result["available"])
        self.assertEqual(result["revision"], "3774")
        self.assertEqual(result["minecraft"], "1.20")

    def test_wrong_historical_release_is_also_rejected(self):
        self.research.responses.update({
            "https://hub.spigotmc.org/versions/1.20.json": self.metadata("2", "1.20.1"),
            "https://hub.spigotmc.org/versions/3774.json": self.metadata("3774", "1.20.1"),
        })
        result = self.research.spigot("1.20")
        self.assertFalse(result["available"])
        self.assertEqual(len(result["aliases_rejected"]), 2)

    def test_unobfuscated_revision_is_not_silently_mislabeled(self):
        self.research.responses["https://hub.spigotmc.org/versions/1.21.11.json"] = self.metadata(
            "new", "1.21.11_unobfuscated")
        result = self.research.spigot("1.21.11")
        self.assertFalse(result["available"])
        self.assertEqual(result["aliases_rejected"][0]["actual_minecraft"], "1.21.11_unobfuscated")

    def test_missing_upstream_stays_unavailable(self):
        self.assertFalse(self.research.spigot("1.8.9")["available"])


if __name__ == "__main__":
    unittest.main()
