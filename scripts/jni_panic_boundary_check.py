#!/usr/bin/env python3
"""
jni_panic_boundary_check.py — audit #44 P2-5 convention enforcement.

House rule (jni_bridge_p2p.rs header, verbatim): "Every entry point is
wrapped in `catch_unwind` — a panic must never unwind across the FFI
boundary into ART." A Rust panic crossing JNI aborts the whole process;
it is not catchable as a Java exception.

This script enforces the rule mechanically: every `Java_*` export in
rust/src/jni_bridge*.rs must invoke `catch_unwind` INSIDE its own body
(brace-matched, not merely present somewhere in the file). Exempting a
future export requires an explicit `// panic-boundary-exempt: <reason>`
comment on the declaration line — visible in review, greppable forever.

Exit 1 on any unwrapped export.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BRIDGES = sorted((REPO / "rust" / "src").glob("jni_bridge*.rs"))

FN_RE = re.compile(r"\bfn\s+(Java_[A-Za-z0-9_]+)\s*\(")


def fn_body_spans(text: str):
    """Yields (name, body_text) for each top-level fn in the file."""
    for m in FN_RE.finditer(text):
        # walk to the opening brace of the body, then brace-match
        i = text.find("{", m.end())
        if i < 0:
            continue
        depth, j = 0, i
        while j < len(text):
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        yield m.group(1), text[i : j + 1], m.group(0)


def main() -> int:
    if not BRIDGES:
        print("jni-panic-boundary: no jni_bridge*.rs files found", file=sys.stderr)
        return 2
    bad = []
    total = 0
    for f in BRIDGES:
        text = f.read_text(encoding="utf-8", errors="ignore")
        for name, body, decl in fn_body_spans(text):
            total += 1
            # The declaration line itself (up to the body brace).
            decl_line_start = text.rfind("\n", 0, text.find("{", text.find(decl))) + 1
            decl_line = text[decl_line_start : text.find("{", text.find(decl))]
            if "panic-boundary-exempt" in decl_line:
                continue
            if "catch_unwind" not in body:
                bad.append(f"{f.name}: {name}")
    print(f"jni-panic-boundary: {total} JNI export(s) across {len(BRIDGES)} "
          f"bridge file(s); catch_unwind required in every body.")
    if bad:
        print(f"jni-panic-boundary: FAIL — {len(bad)} export(s) WITHOUT a "
              f"panic boundary:")
        for b in bad:
            print(f"  - {b}")
        print("\nA panic unwinding across JNI aborts the process. Wrap the "
              "body in catch_unwind (house pattern: default-return inside "
              "the Ok arm), or annotate the declaration with "
              "'// panic-boundary-exempt: <reason>' if provably total.")
        return 1
    print("jni-panic-boundary: OK — every JNI export carries a catch_unwind "
          "panic boundary.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
