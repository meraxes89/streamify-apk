#!/usr/bin/env python3
"""
jni_parity_check.py — Kotlin↔native JNI ABI parity tripwire (audit #44 P0-1).

A `external fun` declared in Kotlin with no matching export in any shipped
.so is a *deferred* UnsatisfiedLinkError: it detonates at the first call
site on a real device, never in JVM unit tests (the mock mesh path never
loads the native libs). This script closes that gap at build time.

Two modes:
  --source  diff `external fun` names against JNI symbol names parsed from
            native/jni/*.{cc,cpp,h} and rust/src/jni_bridge*.rs  (fast,
            no toolchain; catches missing implementations)
  --nm      diff against `nm -D --defined-only` output on the actually
            built .so files (authoritative; catches "implemented but not
            compiled into the CMake/cargo target")

Exit 1 on any missing export; exit 2 on config errors (e.g. no .so found).

Symbol convention: com.streamify.app.data.NativeBridge.fooBar  →
  Java_com_streamify_app_data_NativeBridge_fooBar
Overloads append __<mangled-args>, so a match is either an exact symbol
name or a __-suffixed overload of it.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KT_BRIDGE = REPO / "app/src/main/java/com/streamify/app/data/NativeBridge.kt"

# Class FQN -> expected JNI prefix. Every external fun in the repo today
# lives on NativeBridge (single-file audit surface). When a new bridge
# class appears, add it here — the script then covers it automatically.
KT_CLASSES = {
    "NativeBridge.kt": "com.streamify.app.data.NativeBridge",
}

EXTERNAL_FUN_RE = re.compile(
    r"^\s*(?:(?:private|internal|protected|public|override)\s+|@[A-Za-z]+\s+)*"
    r"external\s+fun\s+(\w+)",
    re.MULTILINE,
)


def kotlin_declared() -> dict[str, list[str]]:
    """external fun name -> list of (class, expected JNI symbol base)."""
    declared: dict[str, list[tuple[str, str]]] = {}
    for fname, fqn in KT_CLASSES.items():
        path = KT_BRIDGE.parent / fname
        src = path.read_text(encoding="utf-8")
        base = "Java_" + fqn.replace(".", "_") + "_"
        for name in EXTERNAL_FUN_RE.findall(src):
            declared.setdefault(name, []).append(base)
    return {n: bases for n, bases in declared.items() if bases}


def source_exports() -> set[str]:
    """JNI export symbol names declared in C/C++/Rust sources."""
    exports: set[str] = set()
    sym_re = re.compile(r"(Java_[A-Za-z0-9_]+)")
    roots = [REPO / "native", REPO / "rust/src"]
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
            for m in sym_re.finditer(text):
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


def check(declared: dict[str, list[str]], exports: set[str]) -> list[str]:
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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", action="store_true", help="source-level parity")
    ap.add_argument("--nm", action="store_true", help="parity against built .so files")
    ap.add_argument("--nm-bin", default="nm")
    ap.add_argument("--so-glob", default="app/build/**/*.so",
                    help="glob for built shared objects (relative to repo root)")
    args = ap.parse_args()

    if not (args.source or args.nm):
        args.source = True  # default: source mode

    declared = kotlin_declared()
    if not declared:
        print("jni-parity: no external fun declarations found — "
              "KT_CLASSES mapping stale?", file=sys.stderr)
        return 2

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
        origin = f"{len(sos)} built .so file(s)"

    print(f"jni-parity: {len(declared)} external fun(s) vs {len(exports)} "
          f"JNI export(s) from {origin}")

    missing = check(declared, exports)
    if missing:
        print(f"\njni-parity: FAIL — {len(missing)} external fun(s) with NO "
              f"resolvable native export:")
        for name in missing:
            print(f"  - {name}")
        print("\nThese become UnsatisfiedLinkError at the first call on a "
              "real device. Implement the export or remove the declaration.")
        return 1

    print("jni-parity: OK — every declared external fun has a matching export.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
