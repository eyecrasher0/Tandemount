"""Check exact game/platform declarations before they reach release services."""

import unittest

from publication_plan import entries_for


class PublicationMetadataTest(unittest.TestCase):
    def artifact(self, pairs):
        return {"id": "test", "file": "binary.jar", "sha256": "0" * 64, "java_min": 8,
                "targets": [{"minecraft": mc, "platform": platform} for mc, platform in pairs]}

    def test_exact_plugin_core_tags_preserve_available_platforms(self):
        result = entries_for({"artifacts": [self.artifact([("1.12.2", "paper"), ("1.12.2", "spigot")])]})
        self.assertEqual(result[0]["minecraft"], ["1.12.2"])
        self.assertEqual(result[0]["platforms"], ["paper", "spigot"])
        self.assertEqual(result[0]["family"], "plugin")

    def test_different_game_releases_cannot_be_flattened_into_false_core_support(self):
        artifact = self.artifact([("1.7.10", "paper"), ("26.3", "paper"), ("26.3", "folia")])
        with self.assertRaisesRegex(ValueError, "exact-game artifacts"):
            entries_for({"artifacts": [artifact]})

    def test_mod_artifact_retains_exact_loader(self):
        result = entries_for({"artifacts": [self.artifact([("1.21.1", "neoforge")])]})
        self.assertEqual(result[0]["family"], "neoforge")

    def test_unrelated_loader_families_cannot_share_a_publication(self):
        with self.assertRaisesRegex(ValueError, "unrelated mod loader"):
            entries_for({"artifacts": [self.artifact([("1.21.1", "fabric"), ("1.21.1", "forge")])]})

    def test_duplicate_versions_are_rejected_before_upload(self):
        artifact = self.artifact([("1.21.1", "fabric")])
        with self.assertRaisesRegex(ValueError, "Duplicate publication entry"):
            entries_for({"artifacts": [artifact, artifact]})


if __name__ == "__main__":
    unittest.main()
