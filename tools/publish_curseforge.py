#!/usr/bin/env python3
"""Upload hash-audited Tandemount files to existing CurseForge projects."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import publish_modrinth

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://minecraft.curseforge.com/api"
USER_AGENT = "Tandemount/1.0.0 (https://github.com/eyecrasher0/Tandemount)"


def digest(path, algorithm="sha256"):
    h = hashlib.new(algorithm)
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def request_json(method, url, token, body=None, content_type="application/json"):
    headers = {"X-Api-Token": token, "Accept": "application/json", "User-Agent": USER_AGENT}
    if body is not None:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=240) as response:
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")
            if error.code == 429 and attempt < 4:
                # A definitive rate-limit rejection is safe to retry; an
                # unknown transport outcome remains a ledger-blocking error.
                try:
                    delay = min(60, max(1, int(error.headers.get("Retry-After", "5"))))
                except ValueError:
                    delay = 5
                time.sleep(delay)
                continue
            raise RuntimeError(f"CurseForge {method} {url} failed ({error.code}): {detail[:2000]}") from None
        except urllib.error.URLError as error:
            raise RuntimeError(f"CurseForge {method} {url} failed: {error.reason}") from None


def multipart(metadata, path):
    boundary = "tandemount-" + uuid.uuid4().hex
    path = Path(path)
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        b'Content-Disposition: form-data; name="metadata"\r\n',
        b"Content-Type: application/json; charset=utf-8\r\n\r\n",
        json.dumps(metadata, separators=(",", ":")).encode("utf-8"),
        b"\r\n",
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'.encode(),
        b"Content-Type: application/java-archive\r\n\r\n",
        path.read_bytes(),
        b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ])
    return body, f"multipart/form-data; boundary={boundary}"


def metadata_for(entry, project, version_names, plan):
    family = entry["family"]
    mc = entry["minecraft"][0]
    platform_names = {"paper": "Paper", "spigot": "Spigot", "purpur": "Purpur", "folia": "Folia",
                      "forge": "Forge", "fabric": "Fabric", "neoforge": "NeoForge"}
    game_versions = [mc] + ([platform_names[family], "Server"] if family != "plugin" else [])
    if set(game_versions) - version_names:
        raise ValueError("Unverified CurseForge game version names")
    implementation = ", ".join(platform_names[name] for name in entry["platforms"])
    return {
        "changelog": publish_modrinth.release_changelog(project, entry, plan),
        "changelogType": "markdown",
        "displayName": f"Tandemount {project['version']} | {implementation} | Minecraft {mc}",
        # The documented names field avoids confusing equal version names
        # across historical Mod/Bukkit gameVersionTypeID groups.
        "gameVersionNames": game_versions,
        "releaseType": "release",
        "isMarkedForManualRelease": False,
    }


def validate_version_names(records, plan):
    available = {record["name"] for record in records}
    loader_names = {"forge": "Forge", "fabric": "Fabric", "neoforge": "NeoForge"}
    required_versions = {entry["minecraft"][0] for entry in plan["entries"]}
    required_loaders = {entry["family"] for entry in plan["entries"] if entry["family"] != "plugin"}
    missing_versions = sorted(required_versions - available)
    missing_loaders = sorted(loader for loader in required_loaders if loader_names.get(loader) not in available)
    missing_environments = ["Server"] if required_loaders and "Server" not in available else []
    if missing_versions or missing_loaders or missing_environments:
        raise ValueError("CurseForge version tags are incomplete: " + json.dumps({
            "missing_versions": missing_versions, "missing_loaders": missing_loaders, "missing_environments": missing_environments,
        }))
    return required_versions | {loader_names[loader] for loader in required_loaders} | ({"Server"} if required_loaders else set())


def write_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, default=ROOT / ".agents/release/plan.json")
    parser.add_argument("--manifest", type=Path, default=ROOT / "build/release/artifacts.json")
    parser.add_argument("--state", type=Path, default=ROOT / ".agents/release/curseforge-state.json")
    parser.add_argument("--mods-project-id", type=int, help="CurseForge Mods project created in the Authors Console")
    parser.add_argument("--plugins-project-id", type=int, help="CurseForge Bukkit Plugins project created in the Authors Console")
    parser.add_argument("--apply", action="store_true", help="Upload to the two explicitly supplied CurseForge projects")
    args = parser.parse_args()
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        matrix = json.loads((ROOT / "platforms.json").read_text(encoding="utf-8"))
        project = json.loads((ROOT / "release/project.json").read_text(encoding="utf-8"))
        targets = publish_modrinth.validate_plan(plan, matrix, args.manifest, project)
        summary = {"mode": "apply" if args.apply else "dry-run", "entries": len(plan["entries"]),
                   "targets": targets,
                   "projects": {"mods": args.mods_project_id, "plugins": args.plugins_project_id}}
        if not args.apply:
            print(json.dumps(summary, indent=2))
            return 0
        token = os.environ.get("CURSEFORGE_TOKEN")
        if not token:
            raise ValueError("CURSEFORGE_TOKEN is not set")
        if not args.mods_project_id or not args.plugins_project_id:
            raise ValueError("Create both CurseForge projects in the Authors Console and provide their IDs")
        if args.mods_project_id < 1 or args.plugins_project_id < 1 or args.mods_project_id == args.plugins_project_id:
            raise ValueError("Mods and Bukkit Plugins must be two distinct positive CurseForge project IDs")
        records = request_json("GET", BASE + "/game/versions", token)
        version_names = validate_version_names(records, plan)
        state = json.loads(args.state.read_text(encoding="utf-8")) if args.state.is_file() else {"schema": 1, "files": {}}
        if state.get("schema") != 1:
            raise ValueError("Unsupported CurseForge upload-state schema")
        receipts = []
        for entry in plan["entries"]:
            key = entry["id"]
            file_hash = entry["sha256"]
            current = state["files"].get(key)
            if current:
                if current.get("sha256") != file_hash:
                    raise ValueError("CurseForge upload ledger has a conflicting artifact hash for " + key)
                if current.get("status") == "uploaded":
                    receipts.append(current)
                    continue
                if current.get("status") == "pending" or current.get("status") == "ambiguous":
                    raise ValueError("CurseForge upload outcome is ambiguous for " + key + "; inspect the Authors Console before retrying")
                if current.get("status") != "rejected":
                    raise ValueError("Unknown CurseForge ledger state for " + key)

            project_id = args.plugins_project_id if entry["family"] == "plugin" else args.mods_project_id
            payload = metadata_for(entry, project, version_names, plan)
            state["files"][key] = {"status": "pending", "sha256": file_hash,
                                   "project_id": project_id, "display_name": payload["displayName"]}
            write_state(args.state, state)
            body, content_type = multipart(payload, entry["file"])
            try:
                response = request_json("POST", f"{BASE}/projects/{project_id}/upload-file", token,
                                        body=body, content_type=content_type)
            except RuntimeError as error:
                rejected = any(f"failed ({status})" in str(error) for status in (400, 401, 403, 404, 413, 422, 429))
                state["files"][key]["status"] = "rejected" if rejected else "ambiguous"
                state["files"][key]["error"] = str(error)
                write_state(args.state, state)
                raise
            file_id = response.get("id") if isinstance(response, dict) else None
            if not isinstance(file_id, int):
                state["files"][key]["status"] = "ambiguous"
                write_state(args.state, state)
                raise ValueError("CurseForge did not return a numeric file ID for " + key)
            receipt = {"status": "uploaded", "sha256": file_hash, "project_id": project_id,
                       "file_id": file_id, "display_name": payload["displayName"],
                       "uploaded_at": datetime.now(timezone.utc).isoformat(),
                       "remote_hash_verified": False}
            state["files"][key] = receipt
            write_state(args.state, state)
            receipts.append(receipt)
        result = {**summary, "result": "upload-accepted-review-pending",
                  "remote_hash_verification": "not-provided-by-the-documented-upload-response",
                  "uploaded_files": receipts}
        print(json.dumps(result, indent=2))
        return 0
    except (OSError, KeyError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(json.dumps({"result": "fail", "error": str(error)}, indent=2), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
