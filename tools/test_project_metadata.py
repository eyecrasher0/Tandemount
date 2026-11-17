"""The emitted runtime filename is a public release contract."""

import unittest

from project_metadata import artifact_name, metadata


class ReleaseIdentityTest(unittest.TestCase):
    def test_plugin_cores_share_bukkit_filename(self):
        for core in ("bukkit", "paper", "spigot", "purpur", "folia"):
            self.assertEqual(artifact_name(core, "26.3"), "tandemount-bukkit-1.0.0+26.3.jar")

    def test_mod_loaders_have_separate_files(self):
        names = {artifact_name(loader, "1.20.6") for loader in ("fabric", "forge", "neoforge")}
        self.assertEqual(names, {f"tandemount-{loader}-1.0.0+1.20.6.jar" for loader in ("fabric", "forge", "neoforge")})

    def test_project_version_changes_without_changing_game_version(self):
        self.assertEqual(artifact_name("forge", "1.7.10", "1.0.1-rc.2"), "tandemount-forge-1.0.1-rc.2+1.7.10.jar")

    def test_filename_rejects_path_and_unknown_platform_inputs(self):
        for args in (("forge", "../26.3"), ("../forge", "26.3"), ("forge", "26.3", "../1.0.0")):
            with self.subTest(args=args), self.assertRaises(ValueError):
                artifact_name(*args)

    def test_license_and_owner_identity(self):
        value = metadata()
        self.assertEqual((value["license"], value["author"], value["github"]), ("GPL-3.0-only", "EyeCrasher", "eyecrasher0/Tandemount"))


if __name__ == "__main__":
    unittest.main()
