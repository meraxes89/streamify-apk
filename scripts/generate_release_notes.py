#!/usr/bin/env python3
"""generate_release_notes.py — Assembles formatted platform-categorized release notes.

Scans the staged binaries in the release directory, generates markdown download tables
grouped by platform (Android, Linux, Windows, macOS, Web), and appends the matching
changelog section from CHANGELOG.md.
"""

import argparse
import os
import re
from pathlib import Path


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


def main():
    parser = argparse.ArgumentParser(description="Generate release notes with platform downloads.")
    parser.add_argument("--tag", required=True, help="Release tag (e.g., v1.0.2-beta)")
    parser.add_argument("--repo", default="meraxes89/streamify-apk", help="GitHub repo (owner/repo)")
    parser.add_argument("--release-dir", required=True, help="Directory containing staged binaries")
    parser.add_argument("--changelog", default="CHANGELOG.md", help="Path to CHANGELOG.md")
    parser.add_argument("--output", required=True, help="Output path for the generated markdown")
    args = parser.parse_args()

    tag = args.tag
    repo = args.repo
    release_dir = Path(args.release_dir)
    changelog_path = Path(args.changelog)
    output_path = Path(args.output)

    base_url = f"https://github.com/{repo}/releases/download/{tag}"

    files = [
        f.name for f in release_dir.iterdir()
        if f.is_file() and f.name != "SHA256SUMS" and f.name != output_path.name
    ] if release_dir.exists() else []

    android_files = []
    linux_files = []
    windows_files = []
    macos_files = []
    web_files = []

    for f in sorted(files):
        if f.endswith(".apk"):
            android_files.append(f)
        elif f.endswith((".deb", ".rpm")):
            linux_files.append(f)
        elif f.endswith((".msi", ".exe")):
            windows_files.append(f)
        elif f.endswith((".dmg", ".pkg")):
            macos_files.append(f)
        elif f.endswith(".zip"):
            web_files.append(f)

    lines = []
    lines.append(f"# Streamify {tag}\n")
    lines.append("## Downloads by Platform\n")

    # Android section
    if android_files:
        lines.append("### Android\n")
        lines.append("| Package | Architecture | Description |")
        lines.append("| :--- | :--- | :--- |")
        for f in android_files:
            url = f"{base_url}/{f}"
            if "vivo" in f.lower():
                desc = "Vivo App Store variant"
                arch = "ARM64" if "arm64" in f else ("ARMv7" if "armeabi" in f else "Universal")
            elif "arm64" in f:
                desc = "Optimized package for modern 64-bit devices"
                arch = "ARM64"
            elif "armeabi" in f:
                desc = "For older 32-bit devices (e.g. Vivo 1716 / V7+)"
                arch = "ARMv7"
            elif "x86_64" in f:
                desc = "For Android emulators and x86 tablets"
                arch = "x86_64"
            elif "universal" in f or (f.startswith("Streamify-v") and not any(k in f for k in ["arm64", "armeabi", "x86_64"])):
                desc = "Recommended for most modern devices (Android 8.0+)"
                arch = "Universal"
            else:
                desc = "Standard Android release package"
                arch = "Universal"
            lines.append(f"| [{f}]({url}) | {arch} | {desc} |")
        lines.append("")

    # Linux section
    if linux_files:
        lines.append("### Linux (Desktop)\n")
        lines.append("| Package | Target Distribution | Installation |")
        lines.append("| :--- | :--- | :--- |")
        for f in linux_files:
            url = f"{base_url}/{f}"
            if f.endswith(".deb"):
                lines.append(f"| [{f}]({url}) | Debian / Ubuntu / Mint | `sudo apt install ./{f}` |")
            elif f.endswith(".rpm"):
                lines.append(f"| [{f}]({url}) | Fedora / RHEL / openSUSE | `sudo dnf install ./{f}` |")
            else:
                lines.append(f"| [{f}]({url}) | Linux | Standard package |")
        lines.append("")

    # Windows section
    if windows_files:
        lines.append("### Windows (Desktop)\n")
        lines.append("| Package | Type | Details |")
        lines.append("| :--- | :--- | :--- |")
        for f in windows_files:
            url = f"{base_url}/{f}"
            lines.append(f"| [{f}]({url}) | WiX Installer | Windows 10 / 11 64-bit |")
        lines.append("")

    # macOS section
    if macos_files:
        lines.append("### macOS (Desktop)\n")
        lines.append("| Package | Architecture | Details |")
        lines.append("| :--- | :--- | :--- |")
        for f in macos_files:
            url = f"{base_url}/{f}"
            lines.append(f"| [{f}]({url}) | Universal | Apple Silicon (M1/M2/M3/M4) & Intel |")
        lines.append("")

    # Web & Server section
    if web_files:
        lines.append("### Web & Server Relay\n")
        lines.append("| Package | Type | Details |")
        lines.append("| :--- | :--- | :--- |")
        for f in web_files:
            url = f"{base_url}/{f}"
            lines.append(f"| [{f}]({url}) | Kotlin/Wasm Client | Web client archive for browser or Fastify server |")
        lines.append("")

    # Checksums note
    lines.append("---\n")
    lines.append("### Verification Checksums")
    lines.append(f"SHA-256 checksums for all release binaries are published in [`SHA256SUMS`]({base_url}/SHA256SUMS).\n")

    # Changelog section
    changelog_section = parse_changelog(changelog_path, tag)
    if changelog_section:
        lines.append("---\n")
        lines.append(f"## What's New in {tag}\n")
        lines.append(changelog_section)
        lines.append("")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"Generated release notes at {output_path} ({len(lines)} lines)")


if __name__ == "__main__":
    main()
