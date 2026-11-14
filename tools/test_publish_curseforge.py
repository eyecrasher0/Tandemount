"""Offline checks for the CurseForge upload metadata and safe resume ledger."""

import unittest
from unittest.mock import patch
import io
import urllib.error

import publish_curseforge


class CurseForgePublishTests(unittest.TestCase):
    def test_mod_upload_tags_exact_game_version_and_loader(self):
        entry = {"id": "forge-1.21.11", "family": "forge", "minecraft": ["1.21.11"],
                 "platforms": ["forge"], "sha256": "a" * 64}
        metadata = publish_curseforge.metadata_for(
            entry, {"version": "1.0.0", "source_url": "https://example.test/source"},
            {"1.21.11", "Forge", "Server"},
        )
        self.assertEqual(metadata["gameVersionNames"], ["1.21.11", "Forge", "Server"])
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
            {"id": 301, "name": "Server", "gameVersionTypeID": 75208},
        ]
        resolved = publish_curseforge.validate_version_names(records, plan)
        self.assertEqual(resolved, {"26.3", "1.7.10", "NeoForge", "Server"})

    def test_equal_names_in_historical_groups_do_not_choose_an_arbitrary_numeric_id(self):
        plan = {"entries": [{"minecraft": ["1.21.11"], "family": "forge"}]}
        records = [
            {"id": 1, "name": "1.21.11", "gameVersionTypeID": 1},
            {"id": 2, "name": "1.21.11", "gameVersionTypeID": 615},
            {"id": 3, "name": "Forge", "gameVersionTypeID": 68441},
            {"id": 4, "name": "Server", "gameVersionTypeID": 75208},
        ]
        self.assertEqual(publish_curseforge.validate_version_names(records, plan), {"1.21.11", "Forge", "Server"})

    def test_missing_requested_release_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "missing_versions"):
            publish_curseforge.validate_version_names([], {"entries": [{"minecraft": ["1.7.10"], "family": "plugin"}]})

    def test_definitive_rate_limit_rejection_can_retry_same_upload(self):
        limited = urllib.error.HTTPError("https://example.test/upload", 429, "Limited", {"Retry-After": "1"}, io.BytesIO(b"limited"))
        with patch("publish_curseforge.urllib.request.urlopen", side_effect=[limited, io.BytesIO(b'{"id":42}')]) as send:
            with patch("publish_curseforge.time.sleep") as sleep:
                self.assertEqual(publish_curseforge.request_json("POST", "https://example.test/upload", "fixture", body=b"jar"), {"id": 42})
        self.assertEqual(send.call_count, 2)
        sleep.assert_called_once_with(1)

    def test_unknown_upload_outcome_is_never_automatically_retried(self):
        with patch("publish_curseforge.urllib.request.urlopen", side_effect=urllib.error.URLError("response lost")) as send:
            with self.assertRaises(RuntimeError):
                publish_curseforge.request_json("POST", "https://example.test/upload", "fixture", body=b"jar")
        self.assertEqual(send.call_count, 1)


if __name__ == "__main__":
    unittest.main()
