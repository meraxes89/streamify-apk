#!/usr/bin/env python3
"""
jni_panic_boundary_check.py — audit #44 P2-5 convention enforcement.

House rule (jni_bridge_p2p.rs header, verbatim): "Every entry point is
wrapped in `catch_unwind` — a panic must never unwind across the FFI
boundary into ART." A Rust panic crossing JNI aborts the whole process;
it is not catchable as a Java exception.

C6 / AHEAD WS-C — the SAME law now covers the C++ tree, through the
JNI_SHIELD macro pair (native/jni/JniShield.h, the C++ twin of the Rust
catch_unwind discipline): a C++ exception escaping a JNI `extern "C"`
edge is `std::terminate` at the ABI boundary — the whole process dies,
uncatchable from Kotlin. `JNI_SHIELD(sentinel)` opens a `try {` block
scoped to the rest of the export body; `JNI_SHIELD_END(sentinel)`
closes it with a `catch (...)` that maps ANY escaping exception onto
the export's own documented error convention — the exact per-type table
the Rust bridges use (jint -> -10, jlong -> 0, jboolean -> JNI_FALSE,
jfloat/jdouble -> 0, jobject-family -> nullptr, void -> swallowed).

This script enforces the rule mechanically: every `Java_*` export in
rust/crates/streamify-ffi/src/jni_bridge*.rs must invoke `catch_unwind`
INSIDE its own body, and every `Java_*` export in the C++ JNI TUs
(native/jni/jni_bridge.cc, native/jni/jni_bridge_dsp.cc, native/src/
jni_bridge_audio_sinks.cpp) must carry the `JNI_SHIELD` token INSIDE
its own body (brace-matched in both languages, not merely present
somewhere in the file). JNI_OnLoad / JNI_OnUnload lifecycle hooks are
not Java_* exports and are out of scope. Exempting a future export
requires an explicit `// panic-boundary-exempt: <reason>` comment on
the declaration line — visible in review, greppable forever.

Exit 1 on any unwrapped export.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
# Workspace (Discussion #120): the JNI bridges moved to the umbrella
# streamify-ffi crate.
RUST_BRIDGES = sorted((REPO / "rust" / "crates" / "streamify-ffi" / "src").glob("jni_bridge*.rs"))
# C6: the C++ JNI translation units carry the same panic-boundary
# contract via the JNI_SHIELD macro pair (native/jni/JniShield.h).
CPP_TUS = [
    REPO / "native" / "jni" / "jni_bridge.cc",
    REPO / "native" / "jni" / "jni_bridge_dsp.cc",
    REPO / "native" / "src" / "jni_bridge_audio_sinks.cpp",
]

FN_RE = re.compile(r"\bfn\s+(Java_[A-Za-z0-9_]+)\s*\(")
# C++ flavor: a Java_* name counts as an export only when JNIEXPORT
# precedes it on the (multi-line) declaration — filters doc-comment and
# call-site mentions of Java_* symbols.
CPP_FN_RE = re.compile(r"\b(Java_[A-Za-z0-9_]+)\s*\(")
CPP_EXPORT_LOOKBACK = 200  # chars of lookback for the JNIEXPORT token


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


def match_brace(text: str, open_idx: int) -> int:
    """Index of the `}` matching the `{` at open_idx (fn_body_spans
    technique), or -1 if unbalanced."""
    depth, j = 0, open_idx
    while j < len(text):
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def cpp_export_spans(text: str):
    """Yields (name, body_text, decl_text) for each JNIEXPORT Java_*
    export in a C++ TU. body_text is the brace-matched function body;
    decl_text spans the whole declaration (from the JNIEXPORT line to
    the body brace) so the exemption comment can sit on any decl line.
    """
    for m in CPP_FN_RE.finditer(text):
        lb_start = max(0, m.start() - CPP_EXPORT_LOOKBACK)
        lookback = text[lb_start : m.start()]
        ni = lookback.rfind("JNIEXPORT")
        if ni < 0:
            continue  # not a JNI export declaration
        # walk past the (multi-line) parameter list to the body brace
        i = text.find("{", m.end())
        if i < 0:
            continue
        end = match_brace(text, i)
        if end < 0:
            continue
        decl_start = text.rfind("\n", 0, lb_start + ni) + 1
        yield m.group(1), text[i : end + 1], text[decl_start:i]


def main() -> int:
    if not RUST_BRIDGES and not any(p.exists() for p in CPP_TUS):
        print("jni-panic-boundary: no jni_bridge*.rs / C++ JNI TU files found",
              file=sys.stderr)
        return 2
    bad = []
    total = 0
    files = 0
    # ---- Rust bridges: catch_unwind inside every Java_* body ----
    for f in RUST_BRIDGES:
        files += 1
        text = f.read_text(encoding="utf-8", errors="ignore")
        count = 0
        for name, body, decl in fn_body_spans(text):
            total += 1
            count += 1
            # The declaration line itself (up to the body brace).
            decl_line_start = text.rfind("\n", 0, text.find("{", text.find(decl))) + 1
            decl_line = text[decl_line_start : text.find("{", text.find(decl))]
            if "panic-boundary-exempt" in decl_line:
                continue
            if "catch_unwind" not in body:
                bad.append(f"{f.name}: {name}")
        print(f"jni-panic-boundary: {f.name}: {count} export(s) — "
              f"catch_unwind required in every body.")
    # ---- C++ TUs: the JNI_SHIELD token inside every Java_* body ----
    for f in CPP_TUS:
        if not f.exists():
            print(f"jni-panic-boundary: {f.name}: MISSING — C++ JNI TU not "
                  f"found at {f}", file=sys.stderr)
            return 2
        files += 1
        text = f.read_text(encoding="utf-8", errors="ignore")
        count = 0
        for name, body, decl in cpp_export_spans(text):
            total += 1
            count += 1
            if "panic-boundary-exempt" in decl:
                continue
            if "JNI_SHIELD" not in body:
                bad.append(f"{f.name}: {name}")
        print(f"jni-panic-boundary: {f.name}: {count} export(s) — "
              f"JNI_SHIELD required in every body.")
    print(f"jni-panic-boundary: {total} JNI export(s) across {files} "
          f"bridge file(s); catch_unwind (Rust) / JNI_SHIELD (C++) required "
          f"in every body.")
    if bad:
        print(f"jni-panic-boundary: FAIL — {len(bad)} export(s) WITHOUT a "
              f"panic boundary:")
        for b in bad:
            print(f"  - {b}")
        print("\nA panic or C++ exception unwinding across JNI aborts the "
              "process. Wrap the body in catch_unwind (Rust; house pattern: "
              "default-return inside the Ok arm) or in the "
              "JNI_SHIELD(sentinel) / JNI_SHIELD_END(sentinel) pair (C++; "
              "native/jni/JniShield.h maps any escape onto the export's "
              "sentinel), or annotate the declaration with "
              "'// panic-boundary-exempt: <reason>' if provably total.")
        return 1
    print("jni-panic-boundary: OK — every JNI export carries a panic "
          "boundary (catch_unwind in Rust, JNI_SHIELD in C++).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
