#!/usr/bin/env python3
"""verify_docs.py — documentation integrity gate.

Checks, across all markdown files outside legacy/vendored trees:
  1. LINKS — every repo-relative markdown link target exists on disk.
  2. PINS — every `<!-- doc-verified-against: <sha> -->` pin must be an
     ancestor of origin/main (staleness protocol, docs/adr/README.md).
     A non-ancestor pin is an ERROR; a pin older than the tip of
     origin/main is reported as STALE (warning, listed for follow-up).
  3. SYSTEM PAGES — docs/systems/Sxx-*.md carry the required frontmatter,
     their ID matches the filename, they are indexed in docs/SYSTEMS.md,
     they carry a pin, and every repo-root-relative `sources:` path exists.
  4. RFC INDEX — every RFC file is listed in docs/rfc/README.md.

Exit codes: 0 = clean, 1 = errors found.
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", ".claude", ".github", "node_modules", "legacy", "build",
             "bundles", ".state", "public", "target", "gradle", "nano"}
PIN_RE = re.compile(r"<!--\s*doc-verified-against:\s*([0-9a-f]{40})\s*-->")
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)[^)]*\)")
SYSTEMS_DIR = REPO / "docs" / "systems"
RFC_DIR = REPO / "docs" / "rfc"
SYSTEM_FIELDS = ("id", "title", "tier", "status", "owner", "sources", "tests")
ROW_ID_RE = re.compile(r"^\|\s*(S\d{2})\s*\|", re.M)


def git(args):
    return subprocess.run(["git", "-C", str(REPO)] + args,
                          capture_output=True, text=True)


def parse_list(raw):
    """Parse a frontmatter value like `[a, b]` into individual paths."""
    raw = raw.strip()
    if raw.startswith("[") and raw.endswith("]"):
        raw = raw[1:-1]
    return [item.strip().strip("`\"'") for item in raw.split(",") if item.strip()]


def check_system_pages(errors):
    index = REPO / "docs" / "SYSTEMS.md"
    index_text = index.read_text(encoding="utf-8") if index.exists() else ""
    if not index_text:
        errors.append("docs/SYSTEMS.md: missing subsystem index")
        index_ids = set()
    else:
        index_ids = set(ROW_ID_RE.findall(index_text))

    pages = sorted(SYSTEMS_DIR.glob("S??-*.md")) if SYSTEMS_DIR.exists() else []
    if not pages:
        errors.append("docs/systems/: no Sxx system pages found")
        return set()

    submodule_live = (REPO / "core" / "common" / "build.gradle.kts").exists()
    page_ids = set()
    for page in pages:
        rel = page.relative_to(REPO)
        text = page.read_text(encoding="utf-8", errors="replace")
        if not text.startswith("---\n"):
            errors.append(f"{rel}: missing YAML frontmatter")
            continue
        frontmatter = text.split("---", 2)[1]
        fields = dict(re.findall(r"^([a-z_]+):\s*(.*)$", frontmatter, re.M))
        for key in SYSTEM_FIELDS:
            if key not in fields:
                errors.append(f"{rel}: frontmatter missing `{key}`")
        sid = fields.get("id", "").strip()
        page_ids.add(sid)
        if sid and not page.name.startswith(sid + "-"):
            errors.append(f"{rel}: id `{sid}` does not match filename")
        if sid and sid not in index_ids:
            errors.append(f"{rel}: id `{sid}` missing from docs/SYSTEMS.md")
        if not PIN_RE.search(text):
            errors.append(f"{rel}: missing doc-verified-against pin")
        for source in parse_list(fields.get("sources", "")):
            if source.startswith("core/") and not submodule_live:
                continue  # submodule not materialized in this checkout
            if not (REPO / source).exists():
                errors.append(f"{rel}: source path does not exist -> {source}")

    for sid in sorted(index_ids - page_ids):
        errors.append(f"docs/SYSTEMS.md: `{sid}` has no system page")
    return page_ids


def check_rfc_index(errors):
    index = RFC_DIR / "README.md"
    text = index.read_text(encoding="utf-8") if index.exists() else ""
    for rfc in sorted(RFC_DIR.glob("RFC-*.md")):
        if rfc.name not in text:
            errors.append(f"docs/rfc/README.md: {rfc.name} not listed")


def main():
    errors, stale, pins = [], [], 0

    tip = git(["rev-parse", "origin/main"])
    tip_sha = tip.stdout.strip() if tip.returncode == 0 else None
    if tip_sha is None:
        tip_sha = git(["rev-parse", "HEAD"]).stdout.strip()

    md_files = []
    for p in REPO.rglob("*.md"):
        rel = p.relative_to(REPO)
        if not any(part in SKIP_DIRS or part == "archive" or part.endswith(".git") for part in rel.parts):
            md_files.append(p)

    for f in md_files:
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in PIN_RE.finditer(text):
            pins += 1
            sha = m.group(1)
            anc = git(["merge-base", "--is-ancestor", sha, tip_sha])
            if anc.returncode != 0:
                anc_head = git(["merge-base", "--is-ancestor", sha, "HEAD"])
                if anc_head.returncode != 0:
                    errors.append(f"{f.relative_to(REPO)}: pin {sha[:10]} "
                                  "is not an ancestor of origin/main or HEAD")
            elif sha != tip_sha:
                stale.append(f"{f.relative_to(REPO)}: pin {sha[:10]} "
                             f"older than tip {tip_sha[:10]}")
        for m in LINK_RE.finditer(text):
            target = m.group(1).strip()
            if target.startswith(("http://", "https://", "mailto:", "#", "file://")):
                continue
            target = target.split("#")[0].lstrip("<").rstrip(">")
            if not target or any(c in target for c in ",;$*{}()<>"):
                continue
            resolved = (f.parent / target).resolve()
            if not resolved.exists():
                errors.append(f"{f.relative_to(REPO)}: broken link -> {target}")

    page_ids = check_system_pages(errors)
    check_rfc_index(errors)

    print(f"verify_docs: scanned {len(md_files)} markdown files, "
          f"{pins} verification pins, {len(page_ids)} system pages")
    for s in stale:
        print(f"  STALE  {s}")
    for e in errors:
        print(f"  ERROR  {e}")
    print(f"verify_docs: {'PASS' if not errors else 'FAIL'} "
          f"({len(errors)} errors, {len(stale)} stale)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
