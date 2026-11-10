#!/usr/bin/env python3
"""
docs_ledger_check.py — TRUTH-02 docs-vs-reality lint (house gate).

Purpose: keep the repository's *current-state* documentation honest against
the tree, mechanically. This is the drift-catcher the 2026-11-09 lead
maintainer audit demanded after finding: a README badge for a workflow that
had been deleted, a "Verified Releases" section that stopped 41 tags short,
a CHANGELOG silent on the entire multiplatform program, an issue-template
link that 404s, and ledger checkboxes unchecked for code that had shipped.

Checks (all local, no network, < 5 s):
  1. WORKFLOW REFERENCES — every workflow file referenced by a badge or a
     .github/workflows/<name> URL in the curated doc set must exist in
     .github/workflows/. Catches the extreme-test-matrix.yml badge class.
  2. RELEASE TAGS — every /releases/tag/<tag> link in README.md must name a
     tag that exists in the repository (git tag --list). Catches the
     "releases stop at build-650 while tags run to build-691" class.
  3. RELATIVE LINKS — every repo-relative file link in the curated doc set
     must resolve to a real file (http(s)/mailto anchors ignored, '#'
     fragments stripped). Catches the config.yml docs/ARCHITECTURE.md 404.
  4. STALE CLAIMS — per-file denylists for strings that describe the RETIRED
     CI workflow as current ("18-shard", "extreme-test-matrix.yml"...).
     CHANGELOG.md is exempt: history may legitimately name retired
     workflows past tense.
  5. CHECKED-BOX FILE CLAIMS — every CHECKED ledger checkbox
     (``- [x] ... `path/to/file.ext` ...``) whose text backticks a
     repo-relative file path must reference an existing file. Catches
     "checked but fictional" boxes — the reverse direction of ledger drift.

Exit code: 0 = honest, 1 = drift found (each finding printed with file:line).

Wired into the Tier-1 preflight job of .github/workflows/ci.yml (runs on
every event; pure source check, no toolchain, no network).

Usage:
    python3 scripts/docs_ledger_check.py            # from repo root
    python3 scripts/docs_ledger_check.py --verbose # also print OK lines
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Files whose workflow references, release links and relative links are
# policed. Root README + contributor-facing docs + issue templates.
CURATED_DOCS = [
    "README.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "docs/playbooks/CI_TESTING_GUIDE.md",
    "docs/architecture/ARCHITECTURE.md",
    "docs/architecture/MULTIPLATFORM_CHECKBOX.MD",
    "docs/architecture/SWIFT_MIGRATION_PLAN.MD",
    ".github/ISSUE_TEMPLATE/config.yml",
]

# Ledger files whose CHECKED boxes get the file-claim audit (check 5).
LEDGER_FILES = [
    "docs/architecture/MULTIPLATFORM_CHECKBOX.MD",
    "docs/architecture/SWIFT_MIGRATION_PLAN.MD",
    "docs/architecture/A_Checkbox.md",
]

# Per-file denylists: strings that must NOT appear (the retired workflow
# described as current). CHANGELOG.md deliberately absent — history is
# allowed to mention retired workflows in the past tense.
STALE_CLAIMS = {
    "README.md": [
        "extreme-test-matrix.yml",
        "18-shard",
        "18 shard",
    ],
    "docs/playbooks/CI_TESTING_GUIDE.md": [
        "extreme-test-matrix.yml",
        "18-shard",
        "8 isolated verification targets",
        "emulator-matrix",
        "gatekeeper-api-probe",
    ],
    "docs/architecture/ARCHITECTURE.md": [
        "extreme-test-matrix.yml",
    ],
    "CONTRIBUTING.md": [
        "extreme-test-matrix",
    ],
    ".github/ISSUE_TEMPLATE/config.yml": [],
}

WORKFLOW_REF_RE = re.compile(
    r"(?:workflows/)([A-Za-z0-9_.\-]+\.ya?ml)"
    r"|actions/workflows/([A-Za-z0-9_.\-]+\.ya?ml)/badge\.svg"
)
RELEASE_TAG_RE = re.compile(r"/releases/tag/([A-Za-z0-9_.\-+]+)")
MARKDOWN_LINK_RE = re.compile(r"\]\(([^)\s]+)\)")
CHECKBOX_RE = re.compile(r"^\s*-\s*\[x\]\s+(.*)$", re.IGNORECASE)
BACKTICK_PATH_RE = re.compile(r"`([A-Za-z0-9_./\-]+\.[A-Za-z0-9]+)`")
URL_SCHEME_RE = re.compile(r"^[a-z][a-z0-9+.\-]*://", re.IGNORECASE)

# Extensions that make a backticked token a repo path claim (not code, not
# a flag, not a bare identifier).
PATH_EXT_RE = re.compile(
    r"\.(?:rs|kt|kts|swift|mm|m|c|cc|cpp|h|hpp|py|sh|yml|yaml|toml|sql|md|"
    r"udl|json|xml|gradle|properties|pro|txt|MF)$",
    re.IGNORECASE,
)
# Backticked tokens that carry a path-looking extension but are NOT repo
# paths (generated artifacts, platform files, toolchain names).
PATH_ALLOWLIST = {
    ".so",
    ".dll",
    ".dylib",
    ".ipa",
    ".dmg",
    ".deb",
    ".msi",
    ".apk",
    ".a",
    ".xcframework",
    ".tbd",
    "local.properties",
}
# Ledger-checkbox lines matching these are pure policy/intent with a
# path-shaped word inside prose (e.g. a version string like 1.20240401.01.00)
def _is_repo_path(candidate: str) -> bool:
    ext = Path(candidate).suffix.lower()
    if not ext:
        return False
    if ext in PATH_ALLOWLIST or ext in (".o",):
        return False
    if not PATH_EXT_RE.search(candidate):
        return False
    if candidate.startswith(("http", "file:", "/")):
        return False
    if re.match(r"^\d+(\.\d+)+$", candidate):  # version strings
        return False
    return True


def git_tags() -> set[str]:
    """All tags reachable in this clone (preflight checks out fetch-depth 0,
    which fetches tags; empty set on any git failure → tag check no-ops
    rather than false-failing)."""
    try:
        out = subprocess.run(
            ["git", "tag", "--list"],
            cwd=REPO_ROOT, capture_output=True, text=True, timeout=30,
        )
        if out.returncode == 0:
            return {line.strip() for line in out.stdout.splitlines() if line.strip()}
    except (OSError, subprocess.TimeoutExpired):
        pass
    return set()


def read(path: str) -> list[str]:
    target = REPO_ROOT / path
    if not target.is_file():
        print(f"FATAL: curated doc missing from tree: {path}")
        sys.exit(66)
    return target.read_text(encoding="utf-8", errors="replace").splitlines()


def check_workflow_refs(path: str, lines: list[str], workflows: set[str], findings: list[str]) -> None:
    """Check 1 — referenced workflows must exist."""
    for i, line in enumerate(lines, 1):
        for match in WORKFLOW_REF_RE.finditer(line):
            name = match.group(1) or match.group(2)
            if name and name not in workflows:
                findings.append(
                    f"{path}:{i}: references workflow '{name}' which does not exist "
                    f"in .github/workflows/ (stale badge/link)"
                )


def check_release_tags(path: str, lines: list[str], tags: set[str], findings: list[str]) -> None:
    """Check 2 — /releases/tag/<tag> links must name real tags (README only;
    needs a tags-bearing clone)."""
    if path != "README.md" or not tags:
        return
    for i, line in enumerate(lines, 1):
        for match in RELEASE_TAG_RE.finditer(line):
            tag = match.group(1)
            if tag not in tags:
                findings.append(
                    f"{path}:{i}: links release tag '{tag}' which does not exist "
                    f"in this repository"
                )


def check_relative_links(path: str, lines: list[str], findings: list[str]) -> None:
    """Check 3 — repo-relative file links must resolve."""
    base = REPO_ROOT / Path(path).parent
    for i, line in enumerate(lines, 1):
        for match in MARKDOWN_LINK_RE.finditer(line):
            target = match.group(1)
            if target.startswith(("#", "mailto:")) or URL_SCHEME_RE.match(target):
                continue
            fragmentless = target.split("#", 1)[0].strip()
            if not fragmentless:
                continue
            candidate = (base / fragmentless).resolve()
            if not candidate.exists():
                findings.append(
                    f"{path}:{i}: relative link '{target}' does not resolve "
                    f"to a file in the tree"
                )


def check_stale_claims(path: str, lines: list[str], findings: list[str]) -> None:
    """Check 4 — per-file denylist of retired-workflow descriptions."""
    deny = STALE_CLAIMS.get(path, [])
    for i, line in enumerate(lines, 1):
        low = line.lower()
        for needle in deny:
            if needle.lower() in low:
                findings.append(
                    f"{path}:{i}: stale claim '{needle}' — describes the retired "
                    f"workflow as current"
                )


def check_checked_boxes(path: str, lines: list[str], findings: list[str]) -> None:
    """Check 5 — a CHECKED box that names a repo file must name one that exists."""
    for i, line in enumerate(lines, 1):
        checked = CHECKBOX_RE.match(line)
        if not checked:
            continue
        body = checked.group(1)
        for match in BACKTICK_PATH_RE.finditer(body):
            candidate = match.group(1)
            if not _is_repo_path(candidate):
                continue
            if candidate.startswith(("docs/", "scripts/", "app/", "rust/", "native/",
                                     "iosApp/", "shared/", "desktop/", "supabase/",
                                     "fixtures/", "tools/", ".github/", "gradle")):
                if not (REPO_ROOT / candidate).exists():
                    findings.append(
                        f"{path}:{i}: checked box backticks '{candidate}' which does "
                        f"not exist in the tree (fictional claim)"
                    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--verbose", action="store_true", help="print OK lines too")
    args = parser.parse_args()

    workflows = {
        p.name for p in (REPO_ROOT / ".github" / "workflows").glob("*.y*ml")
        if p.is_file()
    }
    tags = git_tags()

    findings: list[str] = []
    for path in CURATED_DOCS:
        lines = read(path)
        check_workflow_refs(path, lines, workflows, findings)
        check_release_tags(path, lines, tags, findings)
        check_relative_links(path, lines, findings)
        check_stale_claims(path, lines, findings)

    for path in LEDGER_FILES:
        if (REPO_ROOT / path).is_file():
            check_checked_boxes(path, read(path), findings)

    if findings:
        print("── docs_ledger_check: DRIFT FOUND ─────────────────────────────")
        for finding in findings:
            print(f"  {finding}")
        print(f"── {len(findings)} finding(s). Fix the docs (or the tree), not the lint.")
        return 1

    print("docs_ledger_check: PASS — curated docs match the tree "
          f"({len(CURATED_DOCS)} docs, {len(LEDGER_FILES)} ledgers, "
          f"{len(workflows)} workflows, {len(tags)} tags checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
