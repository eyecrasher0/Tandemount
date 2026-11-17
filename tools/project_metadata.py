"""Shared release identity and the owner's runtime filename contract."""

import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?")
MINECRAFT = re.compile(r"\d+\.\d+(?:\.\d+)?")
PLUGIN_PLATFORMS = frozenset({"bukkit", "plugin", "paper", "spigot", "purpur", "folia"})


def metadata(root=ROOT):
    value = json.loads((Path(root) / "project.json").read_text(encoding="utf-8"))
    required = ("name", "id", "version", "group", "author", "license", "github")
    if not isinstance(value, dict) or any(not isinstance(value.get(k), str) or not value[k].strip() for k in required):
        raise ValueError("Project metadata requires nonempty identity fields")
    if not re.fullmatch(r"[a-z][a-z0-9_]*", value["id"]) or not VERSION.fullmatch(value["version"]):
        raise ValueError("Invalid project identifier or version")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value["github"]):
        raise ValueError("Invalid GitHub repository identity")
    return value


def artifact_name(platform, minecraft, version=None):
    """Return one safe exact-game runtime filename; fixtures use other names."""
    identity = metadata()
    project_version = identity["version"] if version is None else version
    if not isinstance(project_version, str) or not VERSION.fullmatch(project_version):
        raise ValueError("Invalid project version")
    if not isinstance(minecraft, str) or not MINECRAFT.fullmatch(minecraft):
        raise ValueError("Invalid exact Minecraft release")
    family = "bukkit" if platform in PLUGIN_PLATFORMS else platform
    if family not in {"bukkit", "fabric", "forge", "neoforge"}:
        raise ValueError("Unknown server platform: " + str(platform))
    return f"{identity['id']}-{family}-{project_version}+{minecraft}.jar"
