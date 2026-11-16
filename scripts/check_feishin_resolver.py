#!/usr/bin/env python3
"""check_feishin_resolver.py — the canonical resolver integrity check (P5-A).

History: the feishin fork used to carry a byte-identical MIRROR of the
resolver stack that lived in `front-end/web` (P2-A's two-stream rule),
kept honest by `scripts/sync_feishin_resolver.py --check` in CI. The P5-A
cutover flips the polarity: `front-end/web` is archived to
`legacy/front-end-web` (read-only history, out of every active lane), and
the fork's tree at

    front-end/feishin/src/renderer/api/streamify/playback/resolver/

is THE canonical source of truth for resolution behavior. There is no
second copy to drift against anymore — so the sync check is retired and
replaced by this self-contained integrity check on the canonical tree:

  1. EXISTENCE — the 10 vendored files + the fork-authored relay knob are
     all present (a deleted file must be a loud red, not a silent gap).
  2. HEADER — every file still opens with its provenance header naming its
     own path (catches truncated/pasted-in rewrites).
  3. IMPORT INTEGRITY — the tree stays self-contained and consumable by
     both the feishin bundle and the standalone corpus runner:
       - every relative import resolves inside the tree;
       - bare imports are limited to the sanctioned runtime deps
         (youtubei.js / googlevideo / bgutils-js);
       - nothing imports `lib/engine` (the retired wasm-bridge seam —
         the one import that must never come back);
       - the two relayBase rewrites stay in place (ytSession + potMinter
         point at the fork-authored knob, not the web app's bridge).
  4. CONTRACT — get-stream-url.ts still exports `getStreamUrl` and
     `recentResolveLog` (the engine + daily SABR corpus depend on them).

Wired into the Tier-1 preflight of .github/workflows/ci.yml (runs on
every event; pure source check, no toolchain, no network).

Usage:
  python3 scripts/check_feishin_resolver.py          # from repo root
  python3 scripts/check_feishin_resolver.py --verbose
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ROOT = "front-end/feishin/src/renderer/api/streamify/playback/resolver"

# The P2-A manifest, frozen at the cutover: 10 vendored files (originally
# synced from front-end/web) + the fork-authored relay knob.
VENDORED = [
    "lib/ytSession.ts",
    "lib/gsfLane.ts",
    "audio/resolve.ts",
    "audio/get-stream-url.ts",
    "audio/chunkFetcher.ts",
    "audio/types.ts",
    "audio/webcodecsDecoder.ts",
    "audio/botguard/protocol.ts",
    "audio/botguard/potSession.ts",
    "audio/botguard/potMinter.ts",
]
FORK_AUTHORED = ["lib/relayBase.ts"]

# Bare (package) imports the tree may depend on. Everything else (and any
# absolute '/@/…' alias) would break the standalone corpus runner and drag
# feishin internals into the resolver graph.
ALLOWED_PACKAGES = ("youtubei.js", "googlevideo", "bgutils-js")

# The two sanctioned relayBase rewrites (the old sync script's declared
# mechanical rewrites, now locked in place by this check).
EXPECTED_SNIPPETS = {
    "lib/ytSession.ts": "from './relayBase'",
    "audio/botguard/potMinter.ts": "from '../../lib/relayBase'",
    # The engine + corpus consumption contract.
    "audio/get-stream-url.ts": "recentResolveLog",
}

IMPORT_RE = re.compile(
    r"""(?:^|\n)\s*(?:import\s[^'"]*?from\s*|import\s*|export\s[^'"]*?from\s*|import\(\s*)['"]([^'"]+)['"]""",
)


def check_existence(files: list[str], findings: list[str], verbose: bool) -> list[Path]:
    paths: list[Path] = []
    for rel in files:
        p = REPO / ROOT / rel
        if not p.is_file():
            findings.append(f"missing file: {ROOT}/{rel}")
        else:
            paths.append(p)
            if verbose:
                print(f"ok: {ROOT}/{rel}")
    return paths


def check_header(path: Path, findings: list[str]) -> None:
    """The provenance header must open the file and name its own path.

    Leading `/// <reference …>` directives (gsfLane.ts carries the vite
    client types reference) are legal before the block comment.
    """
    rel = path.relative_to(REPO / ROOT).as_posix()
    text = path.read_text(encoding="utf-8")
    stripped = text.lstrip()
    while stripped.startswith("///"):
        stripped = stripped.split("\n", 1)[1].lstrip() if "\n" in stripped else ""
    if not stripped.startswith("/*"):
        findings.append(f"{ROOT}/{rel}: missing provenance header (file must open with /* */)")
        return
    head = stripped[:600]
    if rel not in head:
        findings.append(
            f"{ROOT}/{rel}: header does not name its own path ({rel!r} not in the opening comment)"
        )


def resolve_relative(target: Path, spec: str) -> Path | None:
    base = target.parent
    for candidate in (
        base / spec,
        base / f"{spec}.ts",
        base / f"{spec}.tsx",
        base / spec / "index.ts",
    ):
        if candidate.is_file():
            return candidate
    return None


def check_imports(path: Path, findings: list[str]) -> None:
    rel = path.relative_to(REPO / ROOT).as_posix()
    text = path.read_text(encoding="utf-8")
    mirror_root = REPO / ROOT

    for spec in IMPORT_RE.findall(text):
        if spec.startswith("."):
            resolved = resolve_relative(path, spec)
            if resolved is None:
                findings.append(f"{ROOT}/{rel}: relative import {spec!r} does not resolve")
                continue
            try:
                resolved.relative_to(mirror_root)
            except ValueError:
                findings.append(
                    f"{ROOT}/{rel}: relative import {spec!r} escapes the resolver tree "
                    f"(→ {resolved.relative_to(REPO).as_posix()})"
                )
            # The retired wasm-bridge seam must never come back.
            if "engine" in resolved.name and resolved.parent == mirror_root / "lib":
                findings.append(
                    f"{ROOT}/{rel}: imports {spec!r} — the wasm-bridge module is retired; "
                    "the relay knob lives in lib/relayBase.ts"
                )
        elif spec.startswith("/"):
            findings.append(
                f"{ROOT}/{rel}: absolute alias import {spec!r} — the resolver tree must stay "
                "self-contained (feishin aliases would break the standalone corpus runner)"
            )
        elif not spec.startswith("node:"):
            if not any(spec == pkg or spec.startswith(f"{pkg}/") for pkg in ALLOWED_PACKAGES):
                findings.append(
                    f"{ROOT}/{rel}: bare import {spec!r} is outside the sanctioned deps "
                    f"({', '.join(ALLOWED_PACKAGES)})"
                )


def check_snippets(findings: list[str], verbose: bool) -> None:
    for rel, needle in EXPECTED_SNIPPETS.items():
        p = REPO / ROOT / rel
        if not p.is_file():
            continue  # already flagged by the existence check
        if needle not in p.read_text(encoding="utf-8"):
            findings.append(f"{ROOT}/{rel}: expected snippet {needle!r} missing (contract drift)")
        elif verbose:
            print(f"ok: {ROOT}/{rel} carries {needle!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    findings: list[str] = []
    all_files = VENDORED + FORK_AUTHORED

    paths = check_existence(all_files, findings, args.verbose)
    for path in paths:
        check_header(path, findings)
        check_imports(path, findings)
    check_snippets(findings, args.verbose)

    if findings:
        print("resolver integrity check FAILED — the canonical tree is damaged:", file=sys.stderr)
        for f in findings:
            print(f"  - {f}", file=sys.stderr)
        print(
            "\nThe resolver tree at front-end/feishin/.../resolver/ is the CANONICAL source "
            "of truth since the P5-A cutover (legacy/front-end-web is archived history). "
            "Fix the tree directly — there is no second copy to re-sync from.",
            file=sys.stderr,
        )
        return 1

    print(
        f"OK: resolver tree intact ({len(VENDORED)} vendored + "
        f"{len(FORK_AUTHORED)} fork-authored; headers, imports and contract verified)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
