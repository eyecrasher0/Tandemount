#!/usr/bin/env python3
"""Export the exact committed source tree and verify every archived file."""

import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = frozenset({".agents", ".sober", ".env", "server.properties", "eula.txt"})


def export(root, ref, output, version):
    if not version or any(c not in "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_" for c in version):
        raise ValueError("Unsafe version label")
    commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", ref + "^{commit}"], text=True).strip()
    tree = subprocess.check_output(["git", "-C", str(root), "ls-tree", "-r", "-z", commit])
    files = {}
    for row in tree.split(b"\0"):
        if not row:
            continue
        metadata, name_bytes = row.split(b"\t", 1)
        mode, kind, object_id = metadata.split()
        name = name_bytes.decode("utf-8")
        if any(part in FORBIDDEN for part in Path(name).parts):
            raise ValueError("Private/runtime file is tracked: " + name)
        if kind != b"blob" or mode == b"120000":
            raise ValueError("Source export requires regular files: " + name)
        files[name] = object_id.decode("ascii")
    if "LICENSE" not in files or "README.md" not in files:
        raise ValueError("Source archive requires LICENSE and README.md")
    prefix = f"Tandemount-{version}/"
    raw = subprocess.check_output(["git", "-C", str(root), "archive", "--format=zip", "--prefix=" + prefix, commit])
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = [name for name in archive.namelist() if not name.endswith("/")]
        if len(names) != len(set(names)) or {name.removeprefix(prefix) for name in names} != set(files):
            raise ValueError("Export file inventory differs from the committed source tree")
        for name in names:
            expected = subprocess.check_output(["git", "-C", str(root), "cat-file", "blob", files[name.removeprefix(prefix)]])
            if archive.read(name) != expected:
                raise ValueError("Exported source differs from commit: " + name)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_bytes(raw)
    temporary.replace(output)
    return {"source_commit": commit, "files": len(files), "sha256": hashlib.sha256(raw).hexdigest(),
            "file": str(output), "version": version}


def main():
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(export(ROOT, args.ref, args.output, args.version), indent=2))


if __name__ == "__main__":
    main()
