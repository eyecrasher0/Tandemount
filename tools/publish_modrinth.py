#!/usr/bin/env python3
"""Upload an audited Tandemount release to Modrinth with hash-based resume."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from release_audit import DEFAULT_MANIFEST

ROOT = Path(__file__).resolve().parents[1]
V2 = "https://api.modrinth.com/v2"
V3 = "https://api.modrinth.com/v3"
USER_AGENT = "eyecrasher0/Tandemount/1.0.0 (https://github.com/eyecrasher0/Tandemount)"


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def request(method, url, token=None, body=None, content_type="application/json", optional_404=False):
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if token:
        headers["Authorization"] = token
    if body is not None:
        headers["Content-Type"] = content_type
    request_object = urllib.request.Request(url, data=body, headers=headers, method=method)
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request_object, timeout=180) as response:
                data = response.read()
                if not data:
                    return None
                return json.loads(data)
        except urllib.error.HTTPError as error:
            data = error.read().decode("utf-8", "replace")
            if optional_404 and error.code == 404:
                return None
            if error.code == 429 and attempt < 4:
                try:
                    delay = min(60, max(1, int(error.headers.get("Retry-After", "5"))))
                except ValueError:
                    delay = 5
                time.sleep(delay)
                continue
            raise RuntimeError(f"Modrinth {method} {url} failed ({error.code}): {data[:2000]}") from None
        except urllib.error.URLError as error:
            if method == "POST":
                # Creation may already have succeeded before the response was
                # lost. The outer resume path reconciles projects/versions by
                # slug, version number and artifact hash before any next POST.
                raise RuntimeError(f"Modrinth POST outcome is unknown; inspect remote state before retrying: {error.reason}") from None
            if attempt < 4:
                time.sleep(2 ** attempt)
                continue
            raise RuntimeError(f"Modrinth {method} {url} failed: {error.reason}") from None


def multipart(fields, files=()):
    boundary = "tandemount-" + uuid.uuid4().hex
    chunks = []
    for name, value, mime in fields:
        chunks.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n'.encode(),
            f"Content-Type: {mime}\r\n\r\n".encode(),
            value.encode("utf-8"), b"\r\n",
        ])
    for field, path, mime in files:
        path = Path(path)
        chunks.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{field}"; filename="{path.name}"\r\n'.encode(),
            f"Content-Type: {mime}\r\n\r\n".encode(),
            path.read_bytes(), b"\r\n",
        ])
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def expected_targets(matrix):
    return {(target["minecraft"], platform)
            for target in matrix["targets"]
            for platform, state in target["platforms"].items()
            if state["available"]}


def validate_plan(plan, matrix, manifest_path, project):
    if plan.get("schema") != 1 or plan.get("audit", {}).get("result") != "pass":
        raise ValueError("A passing publication plan is required")
    if plan.get("manifest_sha256") != digest(manifest_path, "sha256"):
        raise ValueError("Release manifest changed after the publication plan was created")
    source_commit = plan.get("source", {}).get("source_commit")
    if not source_commit:
        raise ValueError("Publication plan is missing the source commit")
    current_commit = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()
    if current_commit != source_commit:
        raise ValueError("Publication plan does not name the current source commit")
    if subprocess.check_output(["git", "-C", str(ROOT), "status", "--porcelain"], text=True).strip():
        raise ValueError("Commit all source changes before publishing")
    if plan["tag"] != "tandemount-" + project["version"]:
        raise ValueError("Publication plan tag does not match the project version")
    archive = Path(plan["source"]["file"])
    verification_path = Path(plan["verification"])
    if digest(archive, "sha256") != plan["source"]["sha256"]:
        raise ValueError("Staged source archive SHA-256 mismatch")
    verification_bytes = verification_path.read_bytes()
    if hashlib.sha256(verification_bytes).hexdigest() != plan["verification_sha256"]:
        raise ValueError("Staged verification report SHA-256 mismatch")
    verification = json.loads(verification_bytes)
    if verification.get("result") != "pass" or verification.get("source_commit") != source_commit:
        raise ValueError("Verification report must pass for the planned source commit")
    verified = {(report["minecraft"], report["platform"]): report["artifact_sha256"]
                for report in verification["native_reports"] if report["result"] == "pass"}

    expected = expected_targets(matrix)
    declared = set()
    version_numbers = set()
    for entry in plan.get("entries", []):
        family = entry["family"]
        mc_versions = entry["minecraft"]
        platforms = entry["platforms"]
        if len(mc_versions) != 1 or not platforms:
            raise ValueError("Every version must name one exact Minecraft release and its server platforms")
        if family == "plugin":
            pairs = {(mc_versions[0], platform) for platform in platforms}
        else:
            if len(platforms) != 1 or platforms[0] != family:
                raise ValueError("Mod artifact loader metadata is ambiguous")
            pairs = {(mc_versions[0], family)}
        if any(verified.get(pair) != entry["sha256"] for pair in pairs):
            raise ValueError("Verification report does not cover the staged artifact for " + entry["id"])
        if declared & pairs:
            raise ValueError("Publication plan contains duplicate exact targets")
        declared.update(pairs)
        number = entry["version_number"]
        if number in version_numbers:
            raise ValueError("Publication plan contains duplicate version numbers")
        version_numbers.add(number)
        artifact = Path(entry["file"])
        if not artifact.is_file():
            raise ValueError("Missing staged artifact: " + str(artifact))
        for algorithm in ("sha256", "sha1", "sha512"):
            if digest(artifact, algorithm) != entry[algorithm]:
                raise ValueError(f"Staged artifact {algorithm} mismatch: {artifact.name}")
        release_evidence_assets(project, entry, plan)
    if declared != expected:
        raise ValueError(f"Publication plan target mismatch: missing={sorted(expected - declared)}, extra={sorted(declared - expected)}")
    if len(plan["entries"]) != plan.get("audit", {}).get("artifacts"):
        raise ValueError("Publication entries do not match the audited artifact count")
    return len(expected)


def project_payload(project, body):
    return {
        "project_type": "mod",
        "slug": project.get("modrinth_slug", project["id"]),
        "title": project["name"],
        "description": project["summary"],
        "body": body,
        "categories": project.get("modrinth_categories", ["game-mechanics", "transportation"]),
        "client_side": "unsupported",
        "server_side": "required",
        "license_id": project["license"],
        "source_url": project["source_url"],
        "issues_url": project["issues_url"],
        "is_draft": True,
    }


def release_evidence_assets(project, entry, plan):
    batches = project.get("published_batches", {}).get(plan["tag"])
    if batches is None:
        return Path(plan["source"]["file"]).name, Path(plan["verification"]).name
    for batch in batches:
        target = batch["artifacts"].get(entry["sha256"])
        if (target and target["minecraft"] == entry["minecraft"][0]
                and set(entry["platforms"]) <= set(target["platforms"])):
            return batch["source"], batch["verification"]
    raise ValueError(f"Published release {plan['tag']} lacks matching evidence for {entry['sha256']}")


def release_changelog(project, entry, plan):
    repository = project["source_url"].rstrip("/")
    version = project["version"]
    archive, verification = release_evidence_assets(project, entry, plan)
    tag = urllib.parse.quote(plan["tag"], safe="")
    assets = f"{repository}/releases/download/{tag}"
    return (f"Tandemount {version} release.\n\n"
            f"[Complete GPL source]({assets}/{urllib.parse.quote(archive, safe='')}) · "
            f"[Build and installation]({repository}/blob/{tag}/docs/INSTALL.md) · "
            f"[Verification report]({assets}/{urllib.parse.quote(verification, safe='')})")


def version_payload(project_id, entry, project, plan):
    if entry["family"] == "plugin":
        loaders = entry["platforms"]
    else:
        loaders = [entry["family"]]
    mc = entry["minecraft"][0]
    return {
        "name": f"{project['name']} {project['version']} — {', '.join(loaders)} — Minecraft {mc}",
        "version_number": entry["version_number"],
        "changelog": release_changelog(project, entry, plan),
        "dependencies": [],
        "game_versions": [mc],
        "version_type": "release",
        "loaders": loaders,
        "featured": False,
        "status": "draft",
        "project_id": project_id,
        "file_parts": ["artifact"],
        "primary_file": "artifact",
        "environment": "dedicated_server_only",
    }


def project_body(project):
    readme = (ROOT / "README.md").read_text(encoding="utf-8").strip()
    repository = project["source_url"].rstrip("/")

    def source_link(link, image):
        url = urllib.parse.urlsplit(link)
        if url.scheme or link.startswith("#"):
            return link
        view = "raw" if image else "blob"
        if not image and (ROOT / urllib.parse.unquote(url.path)).is_dir():
            view = "tree"
        return f"{repository}/{view}/main/{link.removeprefix('./')}"

    readme = re.sub(r"((?<!!)\[[^\]]+\]\()([^)\s]+)(\))",
                    lambda match: match[1] + source_link(match[2], False) + match[3],
                    readme)
    readme = re.sub(r"""(<img\b[^>]*?\ssrc=["'])([^"']+)(["'])""",
                    lambda match: match[1] + source_link(match[2], True) + match[3], readme)
    return readme + "\n"


def load_project(token, slug, expected_username):
    user = request("GET", V2 + "/user", token=token)
    if user.get("username", "").casefold() != expected_username.casefold():
        raise ValueError(f"Modrinth token belongs to {user.get('username')!r}, expected {expected_username!r}")
    remote = request("GET", V2 + "/project/" + urllib.parse.quote(slug, safe=""), token=token, optional_404=True)
    if remote:
        team = remote.get("team")
        if not team or not user.get("id"):
            raise ValueError("Modrinth did not identify the authenticated user or project team")
        members = request("GET", V2 + "/team/" + urllib.parse.quote(team, safe="") + "/members", token=token)
        owned = any(member.get("user", {}).get("id") == user["id"]
                    and member.get("accepted") is True and member.get("role", "").casefold() == "owner"
                    for member in members)
        if not owned:
            raise ValueError("The Modrinth project is not owned by the authenticated EyeCrasher account")
        return remote
    return None


def prepare_existing_project(token, remote, project, body):
    """Initialize the owner's empty draft, or refresh this matching project."""
    license_value = remote.get("license")
    license_id = license_value.get("id") if isinstance(license_value, dict) else license_value
    matches = remote.get("source_url") == project["source_url"] and license_id == project["license"]
    if not matches:
        empty_draft = (remote.get("status") == "draft"
                       and remote.get("slug") == project.get("modrinth_slug", project["id"])
                       and remote.get("title") == project["name"]
                       and remote.get("source_url") in (None, "", project["source_url"])
                       and license_id in (None, "", "LicenseRef-Unknown", project["license"])
                       and not list_versions(token, remote["id"]))
        if not empty_draft:
            raise ValueError("Existing Modrinth project must match the source and license, or be an empty matching draft")
    payload = project_payload(project, body)
    for field in ("project_type", "slug", "is_draft"):
        payload.pop(field)
    url = V2 + "/project/" + urllib.parse.quote(remote["id"], safe="")
    request("PATCH", url, token=token, body=json.dumps(payload).encode("utf-8"))
    updated = request("GET", url, token=token)
    updated_license = updated.get("license")
    updated_license_id = updated_license.get("id") if isinstance(updated_license, dict) else updated_license
    if updated.get("source_url") != project["source_url"] or updated_license_id != project["license"]:
        raise ValueError("Modrinth did not retain the matching source and license")
    return updated


def validate_provider_tags(plan, project):
    loaders = request("GET", V2 + "/tag/loader")
    game_versions = request("GET", V2 + "/tag/game_version")
    categories = request("GET", V2 + "/tag/category")
    licenses = request("GET", V2 + "/tag/license")
    by_loader = {item["name"]: item for item in loaders}
    required_loaders = set()
    required_versions = set()
    for entry in plan["entries"]:
        required_versions.update(entry["minecraft"])
        required_loaders.update(entry["platforms"] if entry["family"] == "plugin" else [entry["family"]])
    missing_loaders = required_loaders - by_loader.keys()
    missing_versions = required_versions - {item["version"] for item in game_versions}
    unsupported = [name for name in sorted(required_loaders)
                   if "mod" not in by_loader[name].get("supported_project_types", [])]
    required_categories = set(project.get("modrinth_categories", []))
    category_rows = {item["name"]: item for item in categories}
    missing_categories = required_categories - category_rows.keys()
    wrong_categories = [name for name in sorted(required_categories)
                        if category_rows.get(name, {}).get("project_type") not in (None, "mod")]
    license_ids = {item.get("short", item.get("id")) for item in licenses}
    if (project.get("modrinth_requested_visibility") != "unlisted"
            or set(project.get("modrinth_disclosures", [])) != {"ai_content_code", "ai_content_text"}):
        raise ValueError("Modrinth visibility or AI disclosure metadata is incomplete")
    if (missing_loaders or missing_versions or unsupported or missing_categories or wrong_categories
            or project["license"] not in license_ids):
        raise ValueError("Modrinth tags do not match the audited matrix: " + json.dumps({
            "missing_loaders": sorted(missing_loaders), "missing_versions": sorted(missing_versions),
            "loaders_incompatible_with_project": unsupported, "missing_categories": sorted(missing_categories),
            "categories_incompatible_with_project": wrong_categories,
            "license_id_present": project["license"] in license_ids,
        }))


def create_project(token, payload):
    body, content_type = multipart(
        [("data", json.dumps(payload, separators=(",", ":")), "application/json")],
        [("icon", ROOT / "branding/icon.png", "image/png")],
    )
    return request("POST", V2 + "/project", token=token, body=body, content_type=content_type)


def ensure_disclosure(token, project_id, project):
    uses = ["code", "text"]
    note = project["modrinth_disclosure_note"]
    current = request("GET", f"{V3}/project/{urllib.parse.quote(project_id, safe='')}/disclosures", token=token)
    records = current.get("disclosures", []) if isinstance(current, dict) else []
    existing = next((row for row in records if row.get("type") == "ai_content"), None)
    if existing and existing.get("note") == note and sorted(existing.get("uses", [])) == uses:
        return "already-correct"
    body = {"set": [{"type": "ai_content", "note": note, "uses": uses}], "remove": []}
    request("PATCH", f"{V3}/project/{urllib.parse.quote(project_id, safe='')}/disclosures", token=token,
            body=json.dumps(body).encode("utf-8"))
    return "updated"


def list_versions(token, project_id):
    url = V2 + "/project/" + urllib.parse.quote(project_id, safe="") + "/version"
    result = request("GET", url, token=token)
    if not isinstance(result, list):
        raise ValueError("Unexpected Modrinth version-list response")
    return result


def upload_version(token, project_id, entry, project, plan):
    payload = version_payload(project_id, entry, project, plan)
    body, content_type = multipart(
        [("data", json.dumps(payload, separators=(",", ":")), "application/json")],
        [("artifact", entry["file"], "application/java-archive")],
    )
    return request("POST", V2 + "/version", token=token, body=body, content_type=content_type)


def hash_matches(version, entry):
    files = version.get("files", [])
    return any(file.get("hashes", {}).get("sha512") == entry["sha512"] for file in files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, default=ROOT / ".agents/release/plan.json")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--state", type=Path, default=ROOT / ".agents/release/modrinth-state.json")
    parser.add_argument("--apply", action="store_true", help="Create the unlisted project and upload all audited files")
    args = parser.parse_args()
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        matrix = json.loads((ROOT / "platforms.json").read_text(encoding="utf-8"))
        project = json.loads((ROOT / "release/project.json").read_text(encoding="utf-8"))
        targets = validate_plan(plan, matrix, args.manifest, project)
        summary = {"mode": "apply" if args.apply else "dry-run", "entries": len(plan["entries"]), "targets": targets,
                   "project_slug": project.get("modrinth_slug", project["id"]),
                   "requested_visibility": "unlisted"}
        if not args.apply:
            print(json.dumps(summary, indent=2))
            return 0
        token = os.environ.get("MODRINTH_TOKEN")
        if not token:
            raise ValueError("MODRINTH_TOKEN is not set")
        validate_provider_tags(plan, project)
        username = "EyeCrasher"
        slug = project.get("modrinth_slug", project["id"])
        body = project_body(project)
        payload = project_payload(project, body)
        remote = load_project(token, slug, username)
        if remote:
            remote = prepare_existing_project(token, remote, project, body)
            project_id = remote["id"]
        else:
            remote = create_project(token, payload)
            project_id = remote["id"]
            args.state.parent.mkdir(parents=True, exist_ok=True)
            args.state.write_text(json.dumps({"project_id": project_id, "slug": slug, "status": remote.get("status")}, indent=2) + "\n")

        disclosure_status = ensure_disclosure(token, project_id, project)
        versions = list_versions(token, project_id)
        by_number = {}
        for version in versions:
            by_number.setdefault(version.get("version_number"), []).append(version)
        uploaded, skipped = [], []
        for entry in plan["entries"]:
            matches = by_number.get(entry["version_number"], [])
            if matches:
                if len(matches) != 1 or not hash_matches(matches[0], entry):
                    raise ValueError("Remote Modrinth version exists with a conflicting file: " + entry["version_number"])
                skipped.append({"version_number": entry["version_number"], "version_id": matches[0]["id"], "sha512": entry["sha512"]})
                continue
            version = upload_version(token, project_id, entry, project, plan)
            if not hash_matches(version, entry):
                raise ValueError("Modrinth returned a different file hash for " + entry["version_number"])
            uploaded.append({"version_number": entry["version_number"], "version_id": version["id"], "sha512": entry["sha512"]})

        all_versions = list_versions(token, project_id)
        status_updates = []
        for version in all_versions:
            if version.get("version_number") not in version_numbers_from_plan(plan):
                continue
            if version.get("status") == "unlisted" and version.get("requested_status") == "unlisted":
                continue
            update = {"status": "unlisted", "requested_status": "unlisted"}
            request("PATCH", V2 + "/version/" + urllib.parse.quote(version["id"], safe=""), token=token,
                    body=json.dumps(update).encode("utf-8"))
            status_updates.append(version["version_number"])
        request("PATCH", V2 + "/project/" + urllib.parse.quote(project_id, safe=""), token=token,
                body=json.dumps({"requested_status": "unlisted"}).encode("utf-8"))
        final_project = request("GET", V2 + "/project/" + urllib.parse.quote(project_id, safe=""), token=token)
        if final_project.get("requested_status") != "unlisted":
            raise ValueError("Modrinth did not retain the requested unlisted project status")
        if final_project.get("status") not in {"unlisted", "processing"}:
            raise ValueError("Modrinth has not accepted the project for unlisted publication: " + str(final_project.get("status")))
        final_versions = list_versions(token, project_id)
        verified = {v.get("version_number"): v for v in final_versions}
        for entry in plan["entries"]:
            version = verified.get(entry["version_number"])
            if not version or not hash_matches(version, entry):
                raise ValueError("Post-upload verification failed for " + entry["version_number"])
            if version.get("status") not in {"unlisted", "processing"}:
                raise ValueError("Modrinth version did not reach an unlisted/processing state: " + entry["version_number"])
        result = {**summary, "project_id": project_id, "project_status": final_project.get("status"),
                  "requested_status": final_project.get("requested_status"), "disclosure": disclosure_status,
                  "uploaded": uploaded, "already_present": skipped, "versions_set_unlisted": status_updates,
                  "verified_versions": len(plan["entries"]),
                  "result": "submitted-for-unlisted-review" if final_project.get("status") == "processing"
                  else "unlisted-and-hash-verified"}
        args.state.parent.mkdir(parents=True, exist_ok=True)
        args.state.write_text(json.dumps({"project_id": project_id, "slug": slug,
                                          "status": final_project.get("status"),
                                          "requested_status": final_project.get("requested_status")}, indent=2) + "\n")
        print(json.dumps(result, indent=2))
        return 0
    except (OSError, KeyError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(json.dumps({"result": "fail", "error": str(error)}, indent=2), file=sys.stderr)
        return 1


def version_numbers_from_plan(plan):
    return {entry["version_number"] for entry in plan["entries"]}


if __name__ == "__main__":
    sys.exit(main())
