"""Offline checks for the CurseForge upload metadata and safe resume ledger."""

import unittest

import publish_curseforge


class CurseForgePublishTests(unittest.TestCase):
    def test_mod_upload_tags_exact_game_version_and_loader(self):
        entry = {"id": "forge-1.21.11", "family": "forge", "minecraft": ["1.21.11"],
                 "platforms": ["forge"], "sha256": "a" * 64}
        metadata = publish_curseforge.metadata_for(
            entry, {"version": "1.0.0", "source_url": "https://example.test/source"},
            {"1.21.11", "Forge"},
        )
        self.assertEqual(metadata["gameVersionNames"], ["1.21.11", "Forge"])
        self.assertIn("Forge", metadata["displayName"])
        self.assertFalse(metadata["isMarkedForManualRelease"])

    def test_plugin_upload_uses_exact_minecraft_tag_for_bukkit_project(self):
        entry = {"id": "plugin-1.21.11", "family": "plugin", "minecraft": ["1.21.11"],
                 "platforms": ["folia", "paper", "purpur", "spigot"], "sha256": "b" * 64}
        metadata = publish_curseforge.metadata_for(
            entry, {"version": "1.0.0", "source_url": "https://example.test/source"},
            {"1.21.11"},
        )
        self.assertEqual(metadata["gameVersionNames"], ["1.21.11"])
        self.assertIn("Folia, Paper, Purpur, Spigot", metadata["displayName"])

    def test_game_version_and_loader_names_are_verified_from_official_records(self):
        plan = {"entries": [
            {"minecraft": ["26.3"], "family": "neoforge"},
            {"minecraft": ["1.7.10"], "family": "plugin"},
        ]}
        records = [
            {"id": 101, "name": "26.3", "gameVersionTypeID": 1},
            {"id": 102, "name": "1.7.10", "gameVersionTypeID": 1},
            {"id": 201, "name": "NeoForge", "gameVersionTypeID": 68441},
        ]
        resolved = publish_curseforge.validate_version_names(records, plan)
        self.assertEqual(resolved, {"26.3", "1.7.10", "NeoForge"})

    def test_equal_names_in_historical_groups_do_not_choose_an_arbitrary_numeric_id(self):
        plan = {"entries": [{"minecraft": ["1.21.11"], "family": "forge"}]}
        records = [
            {"id": 1, "name": "1.21.11", "gameVersionTypeID": 1},
            {"id": 2, "name": "1.21.11", "gameVersionTypeID": 615},
            {"id": 3, "name": "Forge", "gameVersionTypeID": 68441},
        ]
        self.assertEqual(publish_curseforge.validate_version_names(records, plan), {"1.21.11", "Forge"})

    def test_missing_requested_release_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "missing_versions"):
            publish_curseforge.validate_version_names([], {"entries": [{"minecraft": ["1.7.10"], "family": "plugin"}]})


if __name__ == "__main__":
    unittest.main()
