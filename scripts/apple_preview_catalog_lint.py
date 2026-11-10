#!/usr/bin/env python3
"""
apple_preview_catalog_lint.py — WS-B B2's enforcement gate (AHEAD.md):

    "Zero `PreviewCatalog` references in production code paths (previews /
    tests exempt — enforce with a lint script mirroring docs_ledger_check.py)"

RULES
-----
Scanned: iosApp/Sources/**/*.swift
Exempt:
  * iosApp/Sources/StreamifyIOS/Views/Support/PreviewCatalog.swift (the fixture
    home — fixtures live there BY DESIGN);
  * any line inside a `#Preview { … }` / `#Preview("…") { … }` block (brace-
    balanced scan from the marker to the matching close);
  * any line inside a PREVIEW-HARNESS declaration — the pre-Xcode-16 style
    this codebase carries: `private struct SomeDemo`, `struct X_Previews`
    (PreviewProvider), and file-scope `private let/var preview…` fixture
    constants. These declarations exist ONLY to back #Preview blocks; the
    name patterns (…Demo / …Previews / preview…) are narrow enough that a
    production type can never accidentally match (and the production API
    surface — public/internal views, their initializers and bodies — is
    never exempted by name).
  * the Tests tree (not under Sources).
Flagged:
  * any other occurrence of the token `PreviewCatalog.` — default argument
    values, body reads, stored-property initializers: all production paths.

Exit 0 = clean; exit 1 prints one diagnostic line per violation.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "iosApp" / "Sources"
FIXTURE_HOME = "Views/Support/PreviewCatalog.swift"
TOKEN = re.compile(r"PreviewCatalog\.")
PREVIEW_MARK = re.compile(r"#Preview(\s*\(\"[^\"]*\"\))?\s*(\{|:)")
HARNESS_DECL = re.compile(
    r"^[ \t]*(?:private[ \t]+)?(?:final[ \t]+)?struct[ \t]+([A-Za-z_][A-Za-z0-9_]*)"
    r"|^[ \t]*private[ \t]+(?:let|var)[ \t]+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)


def _brace_span(text: str, open_pos: int):
    """Span from `open_pos` (a '{') to its matching close; None if unbalanced."""
    depth = 0
    j = open_pos
    in_string = False
    escape = False
    triple = False
    while j < len(text):
        c = text[j]
        if escape:
            escape = False
        elif in_string:
            if c == "\\":
                escape = True
            elif triple and c == '"' and text[j - 2 : j + 1] == '"""':
                in_string = False
            elif not triple and c == '"':
                in_string = False
        elif c == '"':
            in_string = True
            triple = text.startswith('"""', j)
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return (open_pos, j + 1)
        j += 1
    return None


def harness_spans(text: str):
    """Spans of preview-harness declarations (see the header's exemption 3)."""
    spans = []
    for match in HARNESS_DECL.finditer(text):
        name = match.group(1) or match.group(2) or ""
        if not (name.endswith("Demo") or "Previews" in name or name.startswith("preview")):
            continue
        if match.group(1):  # struct — brace-balance from the first '{'
            brace = text.find("{", match.end())
            if brace >= 0:
                span = _brace_span(text, brace)
                if span:
                    spans.append((match.start(), span[1]))
        else:  # file-scope let/var — span to the next column-0 line
            end = len(text)
            for later in re.finditer(r"^[A-Za-z@#/]", text[match.end():], re.MULTILINE):
                end = match.end() + later.start()
                break
            spans.append((match.start(), end))
    return spans


def preview_spans(text: str):
    """(start, end) character spans of #Preview blocks — brace balanced."""
    spans = []
    for mark in PREVIEW_MARK.finditer(text):
        i = text.find("{", mark.end() - 1)
        if i < 0:
            continue
        depth = 0
        j = i
        in_string = False
        escape = False
        triple = False
        while j < len(text):
            c = text[j]
            if escape:
                escape = False
            elif in_string:
                if c == "\\":
                    escape = True
                elif triple and text.startswith('"""', j - 2) and j >= i + 2 and c == '"':
                    if text[j - 2 : j + 1] == '"""' and text[j - 3 : j] != '\\"\\"\\"':
                        in_string = False
                elif not triple and c == '"':
                    in_string = False
            elif c == '"':
                in_string = True
                triple = text.startswith('"""', j)
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    spans.append((mark.start(), j + 1))
                    break
            j += 1
    return spans


def in_spans(pos, spans):
    return any(start <= pos < end for start, end in spans)


def main():
    violations = []
    for path in sorted(SOURCES.rglob("*.swift")):
        rel = path.relative_to(ROOT)
        if str(rel).endswith(FIXTURE_HOME):
            continue
        text = path.read_text()
        spans = preview_spans(text) + harness_spans(text)
        offset = 0
        for line_no, line in enumerate(text.splitlines(), start=1):
            stripped = line.lstrip()
            if not stripped.startswith("//"):
                # a comment is not a code path (but the offset ALWAYS
                # advances — a skipped line must never drift the spans).
                for token in TOKEN.finditer(line):
                    if in_spans(offset + token.start(), spans):
                        continue
                    snippet = line.strip()
                    if len(snippet) > 110:
                        snippet = snippet[:107] + "..."
                    violations.append(
                        f"{rel}:{line_no}: production PreviewCatalog reference: {snippet}"
                    )
                    break
            offset += len(line) + 1
    if violations:
        print("PreviewCatalog lint: FAIL (production references remain)")
        for v in violations:
            print("  " + v)
        print(
            "\nFix: flip initializer defaults to empty/neutral values, move "
            "fixtures into #Preview blocks (previews/tests are exempt), and "
            "pass live-lane data from the shell."
        )
        return 1
    print("PreviewCatalog lint: clean (zero production references)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
