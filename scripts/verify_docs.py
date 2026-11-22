#!/usr/bin/env python3
"""verify_docs.py — documentation integrity gate.

Checks, across all markdown files outside legacy/vendored trees:
  1. LINKS — every repo-relative markdown link target exists on disk.
  2. PINS — every `<!-- doc-verified-against: <sha> -->` pin must be an
     ancestor of origin/main (staleness protocol, docs/adr/README.md).
     A non-ancestor pin is an ERROR; a pin older than the tip of
     origin/main is reported as STALE (warning, listed for follow-up).

Exit codes: 0 = clean, 1 = errors found.
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", ".claude", ".github", "node_modules", "legacy", "build",
             "bundles", ".state", "public", "target", "gradle"}
PIN_RE = re.compile(r"<!--\s*doc-verified-against:\s*([0-9a-f]{40})\s*-->")
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)[^)]*\)")


def git(args):
    return subprocess.run(["git", "-C", str(REPO)] + args,
                          capture_output=True, text=True)


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
                errors.append(f"{f.relative_to(REPO)}: pin {sha[:10]} "
                              "is not an ancestor of origin/main")
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

    print(f"verify_docs: scanned {len(md_files)} markdown files, "
          f"{pins} verification pins")
    for s in stale:
        print(f"  STALE  {s}")
    for e in errors:
        print(f"  ERROR  {e}")
    print(f"verify_docs: {'PASS' if not errors else 'FAIL'} "
          f"({len(errors)} errors, {len(stale)} stale)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
