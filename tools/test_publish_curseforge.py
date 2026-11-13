"""Offline checks for the CurseForge upload metadata and safe resume ledger."""

import unittest

import publish_curseforge


class CurseForgePublishTests(unittest.TestCase):
    def test_mod_upload_tags_exact_game_version_and_loader(self):
        entry = {"id": "forge-1.21.11", "family": "forge", "minecraft": ["1.21.11"],
                 "platforms": ["forge"], "sha256": "a" * 64}
        metadata = publish_curseforge.metadata_for(
            entry, {"version": "1.0.0", "source_url": "https://example.test/source"},
            {"1.21.11": 14417, "forge": 7498},
        )
        self.assertEqual(metadata["gameVersions"], [14417, 7498])
        self.assertIn("Forge", metadata["displayName"])
        self.assertFalse(metadata["isMarkedForManualRelease"])

    def test_plugin_upload_uses_exact_minecraft_tag_for_bukkit_project(self):
        entry = {"id": "plugin-1.21.11", "family": "plugin", "minecraft": ["1.21.11"],
                 "platforms": ["folia", "paper", "purpur", "spigot"], "sha256": "b" * 64}
        metadata = publish_curseforge.metadata_for(
            entry, {"version": "1.0.0", "source_url": "https://example.test/source"},
            {"1.21.11": 14417},
        )
        self.assertEqual(metadata["gameVersions"], [14417])
        self.assertIn("Folia, Paper, Purpur, Spigot", metadata["displayName"])

    def test_game_version_and_loader_ids_are_resolved_from_official_records(self):
        plan = {"entries": [
            {"minecraft": ["26.3"], "family": "neoforge"},
            {"minecraft": ["1.7.10"], "family": "plugin"},
        ]}
        records = [
            {"id": 101, "name": "26.3", "gameVersionTypeID": 1},
            {"id": 102, "name": "1.7.10", "gameVersionTypeID": 1},
            {"id": 201, "name": "NeoForge", "gameVersionTypeID": 68441},
        ]
        resolved = publish_curseforge.resolve_version_ids(records, plan)
        self.assertEqual(resolved["26.3"], 101)
        self.assertEqual(resolved["1.7.10"], 102)
        self.assertEqual(resolved["neoforge"], 201)

    def test_duplicate_official_game_version_match_is_rejected(self):
        plan = {"entries": [{"minecraft": ["1.21.11"], "family": "forge"}]}
        records = [
            {"id": 1, "name": "1.21.11", "gameVersionTypeID": 1},
            {"id": 2, "name": "1.21.11", "gameVersionTypeID": 1},
            {"id": 3, "name": "Forge", "gameVersionTypeID": 68441},
        ]
        with self.assertRaises(ValueError):
            publish_curseforge.resolve_version_ids(records, plan)


if __name__ == "__main__":
    unittest.main()
