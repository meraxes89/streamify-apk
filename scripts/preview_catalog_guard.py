#!/usr/bin/env python3
"""
preview_catalog_guard.py — WS-B B2's drift-catcher lint (mirrors
scripts/docs_ledger_check.py's discipline): PreviewCatalog is the PREVIEW
lane's fixture catalog and may NEVER appear in a production code path.

Rules
-----
Scanned: iosApp/Sources/**/*.swift (production sources).
Exempt (in order):
  1. iosApp/Sources/StreamifyIOS/Views/Support/PreviewCatalog.swift itself
     (the fixture definitions);
  2. `//` line comments and `/* ... */` block comments (stripped first —
     prose MAY name the catalog);
  3. `#Preview { ... }` macro blocks and `PreviewProvider` conformance
     bodies (brace matched);
  4. PRIVATE preview scaffolding: a `private`/`fileprivate` declaration
     (struct / class / let / var / func) whose PreviewCatalog usage is
     reachable ONLY from #Preview blocks — proven by reference analysis
     (every identifier reference outside the declaration's own extent must
     itself sit inside an exempt zone; transitively closed). This covers
     the codebase's `private struct SomeDemo: View` helpers and
     `private let preview…` fixture arrays without allowlisting names.

Anything else containing the token `PreviewCatalog` is drift and fails the
run with file:line context. Tests are out of scope by design (fixtures are
the sanctioned test currency) — CI's XCTest lane governs those.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCES_ROOT = REPO_ROOT / "iosApp" / "Sources"
CATALOG_FILE = "PreviewCatalog.swift"
TOKEN = "PreviewCatalog"

DECL_RE = re.compile(
    r"\b(?:private|fileprivate)\s+(?:final\s+)?(?:struct|class|let|var|func)\s+(\w+)"
)

DOCSTRING = __doc__ or ""


def strip_comments(src: str) -> str:
    """Blank out // and /* */ comments, preserving line structure so line
    numbers stay meaningful."""
    out: list[str] = []
    in_block = False
    for line in src.splitlines():
        kept = ""
        i = 0
        while i < len(line):
            if in_block:
                end = line.find("*/", i)
                if end == -1:
                    i = len(line)
                else:
                    in_block = False
                    i = end + 2
                continue
            slash_slash = line.find("//", i)
            slash_star = line.find("/*", i)
            if slash_slash != -1 and (slash_star == -1 or slash_slash < slash_star):
                i = len(line)
            elif slash_star != -1:
                in_block = True
                i = slash_star + 2
            else:
                kept += line[i:]
                i = len(line)
        out.append(kept)
    return "\n".join(out)


def brace_match(lines: list[str], start_idx: int) -> int | None:
    """Index of the line closing the brace block that opens at/after
    `start_idx` (0-based)."""
    depth = 0
    opened = False
    for idx in range(start_idx, len(lines)):
        for ch in lines[idx]:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth == 0:
                    return idx
    return None


def statement_end(lines: list[str], start_idx: int) -> int:
    """End line (inclusive, 0-based) of a `let`/`var` statement: the first
    line where brackets/parens balance after the declaration line."""
    depth = 0
    for idx in range(start_idx, len(lines)):
        for ch in lines[idx]:
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
        if depth <= 0:
            return idx
    return start_idx


def preview_ranges(lines: list[str]) -> list[tuple[int, int]]:
    """0-based inclusive line ranges of #Preview macro blocks and
    PreviewProvider conformance bodies."""
    ranges: list[tuple[int, int]] = []
    n = len(lines)
    for i, line in enumerate(lines):
        if "#Preview" in line:
            if "{" in line:
                end = brace_match(lines, i)
            else:
                end = None
                for j in range(i + 1, n):
                    if "{" in lines[j]:
                        end = brace_match(lines, j)
                        break
            if end is not None:
                ranges.append((i, end))
        elif "PreviewProvider" in line and "{" in line:
            end = brace_match(lines, i)
            if end is not None:
                ranges.append((i, end))
    return ranges


def private_decl_extents(lines: list[str]) -> dict[str, tuple[int, int]]:
    """0-based inclusive extents of private/fileprivate declarations."""
    extents: dict[str, tuple[int, int]] = {}
    for i, line in enumerate(lines):
        m = DECL_RE.search(line)
        if not m:
            continue
        name = m.group(1)
        if name in extents:
            continue
        if re.search(r"\b(struct|class)\b", line):
            end = brace_match(lines, i)
            if end is None:
                end = i
        elif re.search(r"\b(let|var)\b", line):
            end = statement_end(lines, i)
        else:  # func
            end = brace_match(lines, i)
            if end is None:
                end = statement_end(lines, i)
        extents[name] = (i, end)
    return extents


def guard_file(path: Path) -> list[str]:
    try:
        src = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        return [f"{path.relative_to(REPO_ROOT)}: unreadable ({exc})"]

    stripped = strip_comments(src)
    lines = stripped.splitlines()
    rel = str(path.relative_to(REPO_ROOT))

    exempt: list[tuple[int, int]] = list(preview_ranges(lines))
    extents = private_decl_extents(lines)

    def in_exempt(idx: int) -> bool:
        return any(start <= idx <= end for start, end in exempt)

    def token_lines_in(start: int, end: int) -> bool:
        return any(TOKEN in lines[idx] for idx in range(start, end + 1))

    # Transitively exempt private declarations whose PreviewCatalog usage
    # is only reachable from already-exempt zones: a candidate is exempt
    # when EVERY reference to its name outside its own extent sits inside
    # an exempt zone (or there are no outside references at all).
    changed = True
    exempted_names: set[str] = set()
    while changed:
        changed = False
        for name, (start, end) in extents.items():
            if name in exempted_names:
                continue
            if not token_lines_in(start, end):
                # No fixture usage inside — nothing to exempt (it may still
                # be scaffolding, but it does not affect this guard).
                continue
            ok = True
            for i, line in enumerate(lines):
                if start <= i <= end:
                    continue
                if re.search(rf"\b{name}\b", line):
                    if not in_exempt(i):
                        ok = False
                        break
            if ok:
                exempt.append((start, end))
                exempted_names.add(name)
                changed = True

    drift: list[str] = []
    for i, line in enumerate(lines):
        if TOKEN not in line:
            continue
        if in_exempt(i):
            continue
        drift.append(f"{rel}:{i + 1}: {line.strip()}")
    return drift


def main() -> int:
    if not SOURCES_ROOT.is_dir():
        print(f"::error::{SOURCES_ROOT} not found — run from the repo root")
        return 2

    all_drift: list[str] = []
    for path in sorted(SOURCES_ROOT.rglob("*.swift")):
        if path.name == CATALOG_FILE:
            continue
        all_drift.extend(guard_file(path))

    if all_drift:
        print(
            "::error::PreviewCatalog references in production code paths "
            "(WS-B B2 drift — fixtures are the preview/test lane ONLY; "
            "exempt zones: #Preview blocks, PreviewProvider bodies, private "
            "preview-only scaffolding, comments)"
        )
        for entry in all_drift:
            print(f"  {entry}")
        return 1

    print(
        "preview_catalog_guard: clean — zero PreviewCatalog references in "
        "production code paths (previews/tests exempt by design)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
