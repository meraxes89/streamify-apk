#!/usr/bin/env python3
"""make_version_json.py — Builds the static version.json manifest for unmetered CDN updates.

Outputs a lightweight (~1 KB) manifest pinning artifacts and checksums to the release tag,
defusing API rate limits and release drift.
"""

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path


def extract_version_code(toml_path: Path) -> int:
    if not toml_path.exists():
        return 0
    text = toml_path.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'version-code\s*=\s*["\']?(\d+)["\']?', text)
    if m:
        return int(m.group(1))
    return 0


def parse_changelog(changelog_path: Path, tag: str) -> str:
    if not changelog_path.exists():
        return ""
    text = changelog_path.read_text(encoding="utf-8", errors="replace")
    clean_tag = tag.lstrip("v")
    pattern = re.compile(
        rf"^##\s+\[{re.escape(clean_tag)}\](?:\s+—\s+[^\n]+)?\n(.*?)(?=\n##\s+\[|\Z)",
        re.MULTILINE | re.DOTALL,
    )
    match = pattern.search(text)
    if match:
        return match.group(1).strip()
    return ""


def parse_checksums(sums_path: Path) -> dict[str, str]:
    mapping = {}
    if not sums_path.exists():
        return mapping
    for line in sums_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            sha, filename = parts[0], parts[1].lstrip("*")
            mapping[filename] = sha
    return mapping


def determine_channel(tag: str) -> str:
    clean = tag.lower()
    if "beta" in clean:
        return "beta"
    if "rc" in clean:
        return "rc"
    if "alpha" in clean:
        return "alpha"
    return "stable"


def build_manifest(
    tag: str,
    repo: str,
    version_code: int,
    checksums: dict[str, str],
    changelog: str,
    min_supported_version_code: int = 0,
    is_breaking: bool = False,
    git_sha: str = "",
    recalled_shas: list[str] | None = None,
    published_at: str | None = None,
) -> dict:
    base_url = f"https://github.com/{repo}/releases/download/{tag}"
    artifacts = {}

    # Identify artifacts for each platform and architecture
    for filename, sha in checksums.items():
        url = f"{base_url}/{filename}"
        if filename.endswith(".apk"):
            if "vivo" in filename.lower():
                if "arm64" in filename.lower():
                    artifacts["android-vivo-arm64"] = {"url": url, "sha256": sha}
                elif "armeabi" in filename.lower():
                    artifacts["android-vivo-armeabi-v7a"] = {"url": url, "sha256": sha}
                else:
                    artifacts["android-vivo-universal"] = {"url": url, "sha256": sha}
                    artifacts["android-vivo"] = {"url": url, "sha256": sha}
                continue
            if "arm64" in filename.lower():
                artifacts["android-arm64-v8a"] = {"url": url, "sha256": sha}
            elif "armeabi" in filename.lower():
                artifacts["android-armeabi-v7a"] = {"url": url, "sha256": sha}
            elif "x86_64" in filename.lower():
                artifacts["android-x86_64"] = {"url": url, "sha256": sha}
            elif "universal" in filename.lower() or (filename.startswith("Streamify-v") and not any(k in filename for k in ["arm64", "armeabi", "x86_64", "vivo"])):
                artifacts["android-universal"] = {"url": url, "sha256": sha}
                artifacts["android"] = {"url": url, "sha256": sha}
        elif filename.endswith(".msi") or filename.endswith(".exe"):
            artifacts["windows-x64"] = {"url": url, "sha256": sha}
            artifacts["windows"] = {"url": url, "sha256": sha}
        elif filename.endswith(".dmg") or filename.endswith(".pkg"):
            artifacts["macos-universal"] = {"url": url, "sha256": sha}
            artifacts["macos"] = {"url": url, "sha256": sha}
        elif filename.endswith(".deb"):
            artifacts["linux-deb"] = {"url": url, "sha256": sha}
            artifacts["linux"] = {"url": url, "sha256": sha}
        elif filename.endswith(".rpm"):
            artifacts["linux-rpm"] = {"url": url, "sha256": sha}
            if "linux" not in artifacts:
                artifacts["linux"] = {"url": url, "sha256": sha}
        elif filename.endswith(".AppImage"):
            artifacts["linux-appimage"] = {"url": url, "sha256": sha}
            if "linux" not in artifacts:
                artifacts["linux"] = {"url": url, "sha256": sha}
        elif filename.endswith(".ipa") or ("ios" in filename.lower() and filename.endswith(".zip")):
            artifacts["ios-arm64"] = {"url": url, "sha256": sha}
            artifacts["ios"] = {"url": url, "sha256": sha}
        elif filename.endswith(".zip") and "wasm" in filename:
            artifacts["wasm"] = {"url": url, "sha256": sha}

    if "android" not in artifacts and "android-universal" in artifacts:
        artifacts["android"] = artifacts["android-universal"]

    if not published_at:
        published_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    clean_version = tag.lstrip("v")
    channel = determine_channel(tag)

    return {
        "schemaVersion": 1,
        "version": tag,
        "versionCode": version_code,
        "gitSha": git_sha,
        "recalledShas": recalled_shas or [],
        "minSupportedVersionCode": min_supported_version_code,
        "isBreaking": is_breaking,
        "channel": channel,
        "publishedAt": published_at,
        "changelog": changelog,
        "artifacts": artifacts,
        "signatureHex": "",
    }


def main():
    parser = argparse.ArgumentParser(description="Generate version.json update manifest.")
    parser.add_argument("--tag", required=True, help="Release tag (e.g. v1.0.3-beta)")
    parser.add_argument("--repo", default="meraxes89/streamify-apk", help="GitHub repo (owner/repo)")
    parser.add_argument("--version-code", type=int, default=None, help="Numeric version code")
    parser.add_argument("--min-version-code", type=int, default=0, help="Minimum supported version code")
    parser.add_argument("--breaking", action="store_true", help="Flag release as containing breaking changes")
    parser.add_argument("--git-sha", default=None, help="Authoritative Git commit SHA for build tie-breaking")
    parser.add_argument("--recalled-shas", nargs="*", default=[], help="List of recalled git commit SHAs")
    parser.add_argument("--sums", default="release/SHA256SUMS", help="Path to SHA256SUMS file")
    parser.add_argument("--notes", default=None, help="Path to release_body.md or release_notes.md")
    parser.add_argument("--changelog", default="CHANGELOG.md", help="Path to CHANGELOG.md")
    parser.add_argument("--toml", default="gradle/libs.versions.toml", help="Path to libs.versions.toml")
    parser.add_argument("--output", default=None, help="Output path (stdout if omitted)")
    args = parser.parse_args()

    tag = args.tag
    version_code = args.version_code
    if version_code is None:
        version_code = extract_version_code(Path(args.toml))

    git_sha = args.git_sha
    if not git_sha:
        try:
            import subprocess
            git_sha = subprocess.check_output(["git", "rev-parse", "--short=8", "HEAD"], text=True).strip()
        except Exception:
            git_sha = ""

    checksums = parse_checksums(Path(args.sums))

    changelog_text = ""
    if args.notes and Path(args.notes).exists():
        changelog_text = Path(args.notes).read_text(encoding="utf-8", errors="replace").strip()
    elif args.changelog:
        changelog_text = parse_changelog(Path(args.changelog), tag)

    manifest = build_manifest(
        tag=tag,
        repo=args.repo,
        version_code=version_code,
        checksums=checksums,
        changelog=changelog_text,
        min_supported_version_code=args.min_version_code,
        is_breaking=args.breaking,
        git_sha=git_sha,
        recalled_shas=args.recalled_shas,
    )

    json_str = json.dumps(manifest, indent=2, ensure_ascii=False)
    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json_str, encoding="utf-8")
        print(f"Generated {out_path} ({len(json_str.encode('utf-8'))} bytes)")
    else:
        print(json_str)


if __name__ == "__main__":
    main()
