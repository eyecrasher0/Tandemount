"""Offline checks for Modrinth release metadata and upload form encoding."""

import json
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

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

    def test_unknown_post_outcome_is_not_retried_into_duplicate_versions(self):
        with patch("publish_modrinth.urllib.request.urlopen", side_effect=urllib.error.URLError("response lost")) as send:
            with self.assertRaisesRegex(RuntimeError, "outcome is unknown"):
                publish_modrinth.request("POST", "https://example.test/version", body=b"release")
        self.assertEqual(send.call_count, 1)

    def test_read_only_request_can_retry_transport_failure(self):
        with patch("publish_modrinth.urllib.request.urlopen", side_effect=[urllib.error.URLError("temporary"), io.BytesIO(b'{"id":"version"}')]) as send:
            with patch("publish_modrinth.time.sleep"):
                self.assertEqual(publish_modrinth.request("GET", "https://example.test/version"), {"id": "version"})
        self.assertEqual(send.call_count, 2)

    def test_release_changelog_links_the_matching_complete_source_archive(self):
        text = publish_modrinth.release_changelog(self.project)
        self.assertIn('/releases/download/tandemount-1.0.0/Tandemount-1.0.0-source.zip', text)
        self.assertIn('/blob/tandemount-1.0.0/docs/INSTALL.md', text)

    def test_provider_description_resolves_repo_links_at_release_tag(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'README.md').write_text('[Install](docs/INSTALL.md) [License](https://example.test/license) [Here](#section)')
            with patch('publish_modrinth.ROOT', root):
                text = publish_modrinth.project_body(self.project)
        self.assertIn('[Install](https://github.com/eyecrasher0/Tandemount/blob/tandemount-1.0.0/docs/INSTALL.md)', text)
        self.assertIn('[License](https://example.test/license)', text)
        self.assertIn('[Here](#section)', text)

    def test_resume_identifies_owner_through_the_projects_team(self):
        user = {"id": "Owner123", "username": "EyeCrasher"}
        remote = {"id": "Project1", "team": "Team1234", "slug": "tandemount"}
        members = [{"user": user, "role": "Owner", "accepted": True}]
        with patch('publish_modrinth.request', side_effect=[user, remote, members]) as send:
            self.assertEqual(publish_modrinth.load_project('token', 'tandemount', 'EyeCrasher'), remote)
        self.assertEqual(send.call_args_list[-1].args[:2],
                         ('GET', publish_modrinth.V2 + '/team/Team1234/members'))

    def test_resume_rejects_nonowner_unaccepted_or_different_user_ids(self):
        user = {"id": "Owner123", "username": "EyeCrasher"}
        remote = {"id": "Project1", "team": "Team1234", "author": "EyeCrasher"}
        for member in ({"user": user, "role": "Member", "accepted": True},
                       {"user": user, "role": "Owner", "accepted": False},
                       {"user": {"id": "Other123", "username": "EyeCrasher"}, "role": "Owner", "accepted": True}):
            with self.subTest(member=member), patch('publish_modrinth.request', side_effect=[user, remote, [member]]) as send:
                with self.assertRaisesRegex(ValueError, 'owned by the authenticated'):
                    publish_modrinth.load_project('token', 'tandemount', 'EyeCrasher')
                self.assertTrue(all(call.args[0] == 'GET' for call in send.call_args_list))

    def test_empty_owned_draft_receives_release_metadata_and_loses_its_icon(self):
        remote = {"id": "Project1", "title": "Tandemount", "slug": "tandemount", "status": "draft",
                  "source_url": None, "license": {"id": "LicenseRef-Unknown"}, "icon_url": "https://example.test/icon.webp"}
        updated = {**remote, "source_url": self.project['source_url'], "license": {"id": "GPL-3.0-only"}, "icon_url": None}
        with patch('publish_modrinth.request', side_effect=[None, None, updated]) as send, \
                patch('publish_modrinth.list_versions', return_value=[]):
            self.assertEqual(publish_modrinth.prepare_existing_project('token', remote, self.project, 'release text'), updated)
        calls = send.call_args_list
        payload = json.loads(calls[0].kwargs['body'])
        self.assertEqual(payload['license_id'], 'GPL-3.0-only')
        self.assertEqual(payload['body'], 'release text')
        self.assertEqual(payload['source_url'], self.project['source_url'])
        self.assertNotIn('is_draft', payload)
        self.assertEqual(calls[1].args[:2], ('DELETE', publish_modrinth.V2 + '/project/Project1/icon'))

    def test_unconfigured_existing_project_with_files_is_not_overwritten(self):
        remote = {"id": "Project1", "title": "Tandemount", "slug": "tandemount", "status": "draft",
                  "source_url": None, "license": {"id": "LicenseRef-Unknown"}}
        with patch('publish_modrinth.request') as send, patch('publish_modrinth.list_versions', return_value=[{'id': 'Existing'}]):
            with self.assertRaisesRegex(ValueError, 'empty matching draft'):
                publish_modrinth.prepare_existing_project('token', remote, self.project, 'release text')
        send.assert_not_called()

    def test_conflicting_source_or_license_is_not_overwritten_even_in_an_empty_draft(self):
        remote = {"id": "Project1", "title": "Tandemount", "slug": "tandemount", "status": "draft"}
        for metadata in ({'source_url': 'https://example.test/other', 'license': {'id': 'LicenseRef-Unknown'}},
                         {'source_url': None, 'license': {'id': 'MIT'}}):
            with self.subTest(metadata=metadata), patch('publish_modrinth.request') as send, \
                    patch('publish_modrinth.list_versions', return_value=[]):
                with self.assertRaisesRegex(ValueError, 'empty matching draft'):
                    publish_modrinth.prepare_existing_project('token', {**remote, **metadata}, self.project, 'release text')
                send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
