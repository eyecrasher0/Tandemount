"""Offline checks for Modrinth release metadata and upload form encoding."""

import json
from pathlib import Path
import tempfile
import unittest

import publish_modrinth


class ModrinthPublishTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.project = json.loads((publish_modrinth.ROOT / "release/project.json").read_text())

    def test_plugin_and_mod_versions_get_exact_loader_and_game_tags(self):
        plugin = {
            "family": "plugin", "platforms": ["folia", "paper", "spigot"],
            "minecraft": ["1.21.11"], "version_number": "1.0.0+plugin-mc1.21.11",
        }
        mod = {
            "family": "neoforge", "platforms": ["neoforge"],
            "minecraft": ["26.3"], "version_number": "1.0.0+neoforge-mc26.3",
        }
        plugin_payload = publish_modrinth.version_payload("AbCd1234", plugin, self.project)
        mod_payload = publish_modrinth.version_payload("AbCd1234", mod, self.project)
        self.assertEqual(plugin_payload["loaders"], ["folia", "paper", "spigot"])
        self.assertEqual(plugin_payload["game_versions"], ["1.21.11"])
        self.assertEqual(mod_payload["loaders"], ["neoforge"])
        self.assertEqual(mod_payload["game_versions"], ["26.3"])
        self.assertEqual(plugin_payload["environment"], "dedicated_server_only")
        self.assertEqual(plugin_payload["status"], "draft")
        self.assertEqual(plugin_payload["file_parts"], ["artifact"])

    def test_project_is_created_as_draft_without_an_icon(self):
        payload = publish_modrinth.project_payload(self.project, "release text")
        self.assertEqual(payload["project_type"], "mod")
        self.assertTrue(payload["is_draft"])
        self.assertEqual(payload["license_id"], "GPL-3.0-only")
        self.assertNotIn("icon", payload)

    def test_multipart_contains_json_and_exact_binary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "fixture.jar"
            artifact.write_bytes(b"\xca\xfe\xba\xbe\x00\x00\x00\x3d")
            body, content_type = publish_modrinth.multipart(
                [("data", '{"version_number":"1.0.0"}', "application/json")],
                [("artifact", artifact)],
            )
        boundary = content_type.split("boundary=", 1)[1]
        self.assertIn(f'filename="fixture.jar"'.encode(), body)
        self.assertIn(b'{"version_number":"1.0.0"}', body)
        self.assertIn(b"\xca\xfe\xba\xbe\x00\x00\x00\x3d", body)
        self.assertTrue(body.endswith(f"--{boundary}--\r\n".encode()))

    def test_platform_availability_resolves_to_206_exact_targets(self):
        matrix = json.loads((publish_modrinth.ROOT / "platforms.json").read_text())
        targets = publish_modrinth.expected_targets(matrix)
        self.assertEqual(len(matrix["targets"]), 38)
        self.assertEqual(len(targets), 206)


if __name__ == "__main__":
    unittest.main()
