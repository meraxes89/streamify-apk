#!/usr/bin/env python3
"""
jni_parity_check.py — Kotlin<->native JNI ABI parity tripwire (audit #44 P0-1,
AMP-09).

FORWARD parity (Kotlin -> Native): an `external fun` declared in Kotlin with
no matching export in any shipped .so is a *deferred* UnsatisfiedLinkError:
it detonates at the first call site on a real device, never in JVM unit
tests (the mock mesh path never loads the native libs). This script closes
that gap at build time.

REVERSE parity (Native -> Kotlin, AMP-09): a `Java_*` export compiled into
the binary whose declaring Kotlin class/function no longer exists is dead
symbol weight — it bloats every shipped .so, enlarges the CodeQL/attack
surface, and is exactly how the seven ghost bridges amputated in Phase 0
(AMP-01..07) survived unnoticed for so long. Per A_TIER_PLAN invariant #4
("Bi-directional Parity Enforcement ... no uncalled native symbols may
exist in shipped binaries") and DEC-05 ("Zero-Tolerance for Ghost
Bridges"), both directions are enforced.

Two modes:
  --source  diff Kotlin `external fun` names against JNI symbol names parsed
            from native/** and rust/src/** (fast, no toolchain; catches
            missing implementations and dead exports at review time)
  --nm      diff against `nm -D --defined-only` output on the actually built
            .so files (authoritative; catches "implemented but not compiled
            into the CMake/cargo target")

Exit 0 on full bi-directional parity; exit 1 on any violation (forward
missing or reverse dangling); exit 2 on config errors (e.g. no .so found).

Symbol convention: com.streamify.app.data.NativeBridge.fooBar ->
  Java_com_streamify_app_data_NativeBridge_fooBar
Overloads append __<mangled-args>, so a match is either an exact symbol
name or a __-suffixed overload of it. Escaped name segments (_1 _2 _3) are
unescaped before comparison.

KNOWN_DANGLING_EXPORTS: EMPTY since Phase 4's clean-parity pass. The 64
dangler entries grandfathered when this gate landed in Phase 1 — Rust
bridges for Kotlin classes removed in earlier migrations (mesh / chunk-
sync / connect / gesture-physics / waveform / radio), plus legacy
NativeBridge wrappers superseded by renamed Kotlin bindings — were
amputated together with the UniFFI migration of the Jam control plane
(create_jam_session / apply_jam_delta / get_jam_state_hash on
StreamifyEngine). Every remaining export now maps to an active Kotlin
caller, so `--strict` and the default run are the same check. The set
stays in the code (empty) so a future regression can still be triaged
into "new dangler" vs "known debt" without re-adding scaffolding: ADDING
entries requires a justification comment and an owning follow-up.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APP_MAIN = REPO / "app/src/main/java"

EXTERNAL_FUN_RE = re.compile(
    r"^\s*(?:(?:private|internal|protected|public|override)\s+|@[A-Za-z]+\s+)*"
    r"external\s+fun\s+(\w+)",
    re.MULTILINE,
)
SYM_RE = re.compile(r"(Java_[A-Za-z0-9_]+)")

# Legacy grandfather set — EMPTY since the Phase 4 clean-parity pass
# amputated all 64 entries (see module docstring). Every remaining
# export maps to an active Kotlin external fun; --strict is now the
# same check as the default run. Do not add entries without an
# owning follow-up noted here.
KNOWN_DANGLING_EXPORTS: set[str] = set()


def kotlin_surface() -> dict[str, set[str]]:
    """Auto-discover every Kotlin class in the application tree that declares
    `external fun`s. Returns {expected JNI prefix -> fun names}.

    The prefix for com.streamify.app.data.NativeBridge is
    Java_com_streamify_app_data_NativeBridge_. Limitation: only top-level
    classes are mapped (a `external fun` on a nested class mangles the `$`
    in its binary name); none exist today, and DEC-01 (UniFFI) replaces this
    whole surface with scaffolding checksums.
    """
    surface: dict[str, set[str]] = {}
    for kt in APP_MAIN.rglob("*.kt"):
        try:
            src = kt.read_text(encoding="utf-8")
        except OSError:
            continue
        funs = EXTERNAL_FUN_RE.findall(src)
        if not funs:
            continue
        pkg = re.search(r"^package\s+([\w.]+)", src, re.MULTILINE)
        fqn = f"{pkg.group(1)}.{kt.stem}" if pkg else kt.stem
        surface.setdefault("Java_" + fqn.replace(".", "_") + "_", set()).update(funs)
    return surface


def source_exports() -> set[str]:
    """JNI export symbol names declared in C/C++/Rust sources."""
    exports: set[str] = set()
    # Workspace (Discussion #120): Rust sources live under the crates/ tree.
    roots = [REPO / "native", REPO / "rust" / "crates"]
    exts = {".cc", ".cpp", ".c", ".h", ".hpp", ".rs"}
    for root in roots:
        if not root.exists():
            continue
        for f in root.rglob("*"):
            if f.suffix not in exts:
                continue
            # kissfft/third-party vendored trees carry no JNI surface
            if "kissfft" in str(f):
                continue
            try:
                text = f.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for m in SYM_RE.finditer(text):
                sym = m.group(1).rstrip("_")
                if not sym.endswith("_"):
                    exports.add(sym)
    return exports


def nm_exports(so_files: list[Path], nm_bin: str) -> set[str]:
    exports: set[str] = set()
    for so in so_files:
        out = subprocess.run(
            [nm_bin, "-D", "--defined-only", str(so)],
            capture_output=True, text=True, check=False,
        )
        if out.returncode != 0:
            print(f"jni-parity: nm failed on {so}: {out.stderr.strip()}", file=sys.stderr)
            sys.exit(2)
        for line in out.stdout.splitlines():
            parts = line.split()
            # "0000000000012340 T Java_com_..." (addr may be empty)
            if len(parts) >= 3 and parts[1] in ("T", "t", "W", "w"):
                exports.add(parts[2])
            elif len(parts) == 2 and parts[0] in ("T", "t", "W", "w"):
                exports.add(parts[1])
    return exports


def check_forward(declared: dict[str, list[str]], exports: set[str]) -> list[str]:
    """Kotlin external funs with no matching native export."""
    missing = []
    for name, bases in sorted(declared.items()):
        ok = any(
            any(sym == b + name or sym.startswith(b + name + "__")
                for sym in exports)
            for b in bases
        )
        if not ok:
            missing.append(name)
    return missing


def _jni_unescape(segment: str) -> str:
    """Undo JNI name escaping in a single name segment."""
    return (segment
            .replace("_1", "_")
            .replace("_2", ";")
            .replace("_3", "["))


def check_reverse(surface: dict[str, set[str]],
                  exports: set[str]) -> list[tuple[str, str]]:
    """Native Java_* exports with no active Kotlin external fun declaration.

    Returns [(symbol, reason)] for every dangling export.
    """
    danglers: list[tuple[str, str]] = []
    for sym in sorted(exports):
        owner = next((p for p in surface if sym.startswith(p)), None)
        if owner is None:
            danglers.append(
                (sym, "no Kotlin class in the app tree declares this prefix"))
            continue
        rest = sym[len(owner):]
        base = _jni_unescape(rest.split("__", 1)[0])
        if base not in surface[owner]:
            danglers.append((sym, f"'{base}' is not declared on {owner.rstrip('_').lstrip('Java_')}"))
    return danglers


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", action="store_true", help="source-level parity")
    ap.add_argument("--nm", action="store_true", help="parity against built .so files")
    ap.add_argument("--nm-bin", default="nm")
    ap.add_argument("--so-glob", default="app/build/**/*.so",
                    help="glob for built shared objects (relative to repo root)")
    ap.add_argument("--strict", action="store_true",
                    help="audit without the KNOWN_DANGLING_EXPORTS grandfather "
                         "set (identical to the default run while the set is "
                         "empty — kept so a future grandfathered entry is "
                         "always auditable)")
    args = ap.parse_args()

    if not (args.source or args.nm):
        args.source = True  # default: source mode

    surface = kotlin_surface()
    if not surface:
        print("jni-parity: no external fun declarations found under "
              f"{APP_MAIN} — Kotlin surface vanished?", file=sys.stderr)
        return 2
    declared: dict[str, list[str]] = {}
    for prefix, funs in surface.items():
        for name in funs:
            declared.setdefault(name, []).append(prefix)

    if args.source:
        exports = source_exports()
        origin = "C/C++/Rust sources"
    else:
        sos = [p for p in REPO.glob(args.so_glob) if p.is_file()]
        if not sos:
            print(f"jni-parity: no .so files matched {args.so_glob} — "
                  "build the native libs first", file=sys.stderr)
            return 2
        print(f"jni-parity: scanning {len(sos)} shared object(s):")
        for s in sorted(set(p.name for p in sos)):
            print(f"  - {s}")
        exports = nm_exports(sos, args.nm_bin)
        # Only the Java_* subset constitutes the Kotlin-bound JNI surface.
        # Everything else a .so dynamically exports — JNI_OnLoad/OnUnload
        # lifecycle hooks, C++ mangled names, plain-C FFI exports like
        # rust_*/streamify_* — is out of scope for this gate.
        exports = {s for s in exports if s.startswith("Java_")}
        origin = f"{len(sos)} built .so file(s)"

    allowlist = set() if args.strict else KNOWN_DANGLING_EXPORTS

    print(f"jni-parity: {len(declared)} external fun(s) across "
          f"{len(surface)} class(es) vs {len(exports)} JNI export(s) from {origin}")

    failures = 0

    # ---- forward: every declared external fun must have a native export ----
    missing = check_forward(declared, exports)
    if missing:
        failures += 1
        print(f"\njni-parity: FAIL — {len(missing)} external fun(s) with NO "
              f"resolvable native export:")
        for name in missing:
            print(f"  - {name}")
        print("\nThese become UnsatisfiedLinkError at the first call on a "
              "real device. Implement the export or remove the declaration.")

    # ---- reverse: every native export must map to an active declaration ----
    danglers = check_reverse(surface, exports)
    violations = [(s, why) for s, why in danglers if s not in allowlist]
    grandfathered = [s for s, _ in danglers if s in allowlist]
    if violations:
        failures += 1
        print(f"\njni-parity: FAIL — {len(violations)} exported symbol(s) with "
              f"NO active Kotlin external fun declaration:")
        for sym, why in violations:
            print(f"  - {sym}  ({why})")
        print("\nDead native symbols must not ship: bind them to a Kotlin "
              "declaration or delete the export (A_TIER_PLAN invariant #4, "
              "DEC-05 zero-tolerance for ghost bridges).")
    if grandfathered:
        print(f"jni-parity: {len(grandfathered)} grandfathered legacy export(s) "
              f"tracked in KNOWN_DANGLING_EXPORTS (follow-up amputation).")

    # ---- allowlist hygiene: entries whose symbols no longer exist ----------
    stale = sorted(allowlist - exports)
    if stale:
        if args.source:
            # --source scans the complete deterministic tree, so a stale
            # entry means the symbol was deleted without pruning the list.
            failures += 1
            print(f"\njni-parity: FAIL — {len(stale)} stale "
                  f"KNOWN_DANGLING_EXPORTS entr(ies):")
            for s in stale:
                print(f"  - {s}")
            print("\nThe export disappeared; prune the grandfather set so the "
                  "debt list only ever shrinks.")
        else:
            # --nm sees only the artifacts that were actually built; warn.
            print(f"jni-parity: warning — {len(stale)} allowlisted symbol(s) "
                  f"absent from the scanned .so set (partial build?).",
                  file=sys.stderr)

    if failures:
        print(f"\njni-parity: FAIL — bi-directional parity violated.")
        return 1

    if grandfathered:
        print("jni-parity: OK — bi-directional parity holds "
              f"({len(grandfathered)} grandfathered legacy export(s), 0 new danglers).")
    else:
        print("jni-parity: OK — bi-directional parity holds; every declared "
              "external fun has a matching export and every export maps to "
              "an active Kotlin declaration.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
