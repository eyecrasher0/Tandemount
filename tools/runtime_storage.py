"""Optional fast storage for disposable SDK runs, with retained evidence."""

import hashlib
import json
import os
from pathlib import Path
import shutil


SDK_DIRECTORIES = ("libraries", "versions", "cache")
SDK_CONFIG = ("fabric-server-launcher.properties", "unix_args.txt")


def sdk_hashes(directory):
    paths = list(directory.glob("*.jar")) + [directory / config for config in SDK_CONFIG]
    for name in SDK_DIRECTORIES:
        paths.extend((directory / name).rglob("*"))
    result = {}
    for path in sorted(paths):
        if (not path.is_file() or path.name.endswith("-installer.jar")
                or (path.suffix != ".jar" and path.name not in SDK_CONFIG)):
            continue
        with path.open("rb") as source:
            result[path.relative_to(directory).as_posix()] = hashlib.file_digest(source, "sha256").hexdigest()
    return result


def server_identity(target, platform, metadata, directory):
    minecraft = target["minecraft"]
    selected = target["platforms"][platform]
    if (metadata.get("minecraft"), metadata.get("platform")) != (minecraft, platform):
        raise ValueError("The installed server names a different exact target")
    files = sdk_hashes(directory)
    if not files or files != metadata.get("launch_files"):
        raise ValueError("Installed server inputs differ from the recorded launcher and SDK hashes")
    result = {"minecraft": minecraft, "platform": platform, "launch_files": files}
    field = {"spigot": "revision", "fabric": "loader", "forge": "sdk", "neoforge": "sdk"}.get(platform, "build")
    value = metadata.get(field)
    if not isinstance(value, str) or not value or (field in selected and value != selected[field]):
        raise ValueError("Installed server build differs from the exact target")
    result[field] = value
    if platform == "spigot":
        if metadata.get("refs") != selected["refs"]:
            raise ValueError("Installed Spigot repository refs differ from the exact target")
        result["refs"] = metadata["refs"]
        result["buildtools_sha256"] = metadata["buildtools_sha256"]
    if platform in {"paper", "purpur", "folia", "spigot"}:
        name = Path(metadata["file"]).name
        if files.get(name) != metadata["sha256"]:
            raise ValueError("Installed Bukkit server differs from the distribution hash")
        result["distribution_sha256"] = files[name]
        if platform != "spigot":
            checksums = metadata["checksums"]
            if not checksums:
                raise ValueError("Installed Bukkit server differs from the upstream checksum")
            for algorithm, expected in checksums.items():
                with (directory / name).open("rb") as source:
                    if hashlib.file_digest(source, algorithm).hexdigest() != expected:
                        raise ValueError("Installed Bukkit server differs from the upstream checksum")
        result["launcher"] = name
    if platform == "fabric":
        with (directory / "server.jar").open("rb") as source:
            if hashlib.file_digest(source, "sha1").hexdigest() != target["downloads"]["server"]["sha1"]:
                raise ValueError("Installed Fabric server differs from the official Minecraft server")
        result["launcher"] = "fabric-server-launch.jar"
    if platform in {"forge", "neoforge"}:
        args = metadata.get("args_files", [])
        launchers = args or metadata.get("launcher_jars", [])
        if len(launchers) != 1:
            raise ValueError("Expected one exact loader startup target")
        result["launcher"] = launchers[0]
    if result["launcher"] not in files:
        raise ValueError("The chosen launcher is absent from the verified server inputs")
    for field in ("installer_sha256", "installer_sha1"):
        if field in metadata:
            result[field] = metadata[field]
    return result


def installed_identity(cache, target, platform):
    minecraft = target["minecraft"]
    if platform in {"paper", "purpur", "folia", "spigot"}:
        folder = cache / "servers" / platform / minecraft
    else:
        folder = cache / "modern" / "servers" / (platform + "-" + minecraft)
    return server_identity(target, platform, json.loads((folder / "distribution.json").read_text()), folder)


class RuntimeWorkspace:
    def __init__(self, retained_root, name, distribution, extra_names=()):
        self.retained = retained_root / name
        fast = os.environ.get("TANDEMOUNT_RUN_CACHE")
        self.directory = Path(fast).expanduser().resolve() / name if fast else self.retained
        if self.directory.resolve() == self.retained.resolve():
            fast = None
            self.directory = self.retained
        self.fast = bool(fast)
        self.directory.mkdir(parents=True, exist_ok=False)
        self.sdk_sources = []
        for source in distribution.iterdir():
            if source.name.endswith("-installer.jar"):
                continue
            if source.name not in {"libraries", "versions", *SDK_CONFIG, *extra_names} and not source.name.endswith(".jar"):
                continue
            target = self.directory / source.name
            if self.fast:
                if source.is_dir():
                    shutil.copytree(source, target)
                else:
                    shutil.copy2(source, target)
            else:
                target.symlink_to(source, target_is_directory=source.is_dir())
            self.sdk_sources.append(source)

    def finish(self, report):
        if not self.fast:
            report["runtime_storage"] = "distribution-symlinks"
            return report
        # Only copies this workspace itself created are removed. Retained
        # references point back to the original immutable, hash-identified SDK.
        for source in self.sdk_sources:
            copied = self.directory / source.name
            if copied.is_dir() and not copied.is_symlink():
                shutil.rmtree(copied)
            else:
                copied.unlink()
            copied.symlink_to(source, target_is_directory=source.is_dir())
        self.retained.parent.mkdir(parents=True, exist_ok=True)
        if self.retained.exists():
            raise FileExistsError("Retained evidence directory already exists: " + str(self.retained))
        before, after = str(self.directory), str(self.retained)
        shutil.move(str(self.directory), str(self.retained))
        def rewrite(value):
            if isinstance(value, dict):
                return {key: rewrite(item) for key, item in value.items()}
            if isinstance(value, list):
                return [rewrite(item) for item in value]
            if isinstance(value, str) and value.startswith(before):
                return after + value[len(before):]
            return value
        report = rewrite(report)
        report["runtime_storage"] = "fast-staged-with-retained-evidence"
        return report
