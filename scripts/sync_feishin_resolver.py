#!/usr/bin/env python3
"""sync_feishin_resolver.py — the vendored resolver mirror (P2-A).

The feishin fork carries a byte-identical MIRROR of the resolver stack that
lives in `front-end/web` (the resolver stream — the single source of truth
for resolution behavior). This script is the only thing that writes the
mirror; CI (web-build.yml → resolver-mirror-sync) runs `--check` on every
`front-end/**` PR so the mirror can never drift:

  - editing the resolver in front-end/web without re-syncing  → CI red
  - hand-editing the mirror instead of the resolver tree        → CI red

That is the two-stream guardrail made mechanical: resolution fixes land in
the resolver repo (front-end/web), the fork consumes them, and nobody
"fixes resolution behavior from the app side."

Usage:
  python3 scripts/sync_feishin_resolver.py          # copy source → mirror
  python3 scripts/sync_feishin_resolver.py --check  # CI: verify only
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

MIRROR_ROOT = "front-end/feishin/src/renderer/api/streamify/playback/resolver"

# (source, mirror destination, declared mechanical rewrites)
# The rewrites are the ONLY sanctioned divergence: they re-point the two
# imports that referenced the web app's wasm-bridge module (lib/engine.ts)
# at the fork-authored relay knob (lib/relayBase.ts) — the wasm bridge must
# not vendor.
MANIFEST: list[tuple[str, str, list[tuple[str, str]]]] = [
    (
        "front-end/web/src/lib/ytSession.ts",
        f"{MIRROR_ROOT}/lib/ytSession.ts",
        [
            (
                "import { relayBaseCandidate } from './engine';",
                "import { relayBaseCandidate } from './relayBase';",
            )
        ],
    ),
    ("front-end/web/src/lib/gsfLane.ts", f"{MIRROR_ROOT}/lib/gsfLane.ts", []),
    ("front-end/web/src/audio/resolve.ts", f"{MIRROR_ROOT}/audio/resolve.ts", []),
    (
        "front-end/web/src/audio/get-stream-url.ts",
        f"{MIRROR_ROOT}/audio/get-stream-url.ts",
        [],
    ),
    (
        "front-end/web/src/audio/chunkFetcher.ts",
        f"{MIRROR_ROOT}/audio/chunkFetcher.ts",
        [],
    ),
    ("front-end/web/src/audio/types.ts", f"{MIRROR_ROOT}/audio/types.ts", []),
    (
        "front-end/web/src/audio/webcodecsDecoder.ts",
        f"{MIRROR_ROOT}/audio/webcodecsDecoder.ts",
        [],
    ),
    (
        "front-end/web/src/audio/botguard/protocol.ts",
        f"{MIRROR_ROOT}/audio/botguard/protocol.ts",
        [],
    ),
    (
        "front-end/web/src/audio/botguard/potSession.ts",
        f"{MIRROR_ROOT}/audio/botguard/potSession.ts",
        [],
    ),
    (
        "front-end/web/src/audio/botguard/potMinter.ts",
        f"{MIRROR_ROOT}/audio/botguard/potMinter.ts",
        [
            (
                "import { relayBaseCandidate } from '../../lib/engine';",
                "import { relayBaseCandidate } from '../../lib/relayBase';",
            )
        ],
    ),
]

# Fork-authored (no upstream twin): the relay knob. Existence-checked only.
FORK_AUTHORED = [f"{MIRROR_ROOT}/lib/relayBase.ts"]


def transform(text: str, rewrites: list[tuple[str, str]]) -> str:
    out = text
    for old, new in rewrites:
        if old not in out:
            raise SystemExit(f"rewrite anchor missing in source: {old!r}")
        out = out.replace(old, new, 1)
    return out


def main() -> int:
    check = "--check" in sys.argv[1:]
    drift: list[str] = []

    for src_rel, dst_rel, rewrites in MANIFEST:
        src = REPO / src_rel
        dst = REPO / dst_rel
        if not src.is_file():
            raise SystemExit(f"source missing: {src_rel}")
        expected = transform(src.read_text(encoding="utf-8"), rewrites)
        if check:
            actual = (
                dst.read_text(encoding="utf-8") if dst.is_file() else "<missing>"
            )
            if actual != expected:
                drift.append(
                    f"DRIFT: {dst_rel}\n"
                    f"  expected: byte-identical to {src_rel}"
                    f" (+ {len(rewrites)} declared rewrite)"
                    + ("" if dst.is_file() else "  [mirror file missing]")
                )
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(expected, encoding="utf-8")
            print(f"synced {dst_rel}")

    for fork_file in FORK_AUTHORED:
        if not (REPO / fork_file).is_file():
            drift.append(f"MISSING fork-authored file: {fork_file}")

    if drift:
        print("\n".join(drift), file=sys.stderr)
        print(
            "\nresolver mirror out of sync — resolution fixes land in "
            "front-end/web ONLY; re-run scripts/sync_feishin_resolver.py "
            "in the same PR.",
            file=sys.stderr,
        )
        return 1

    if check:
        print(
            f"OK: resolver mirror byte-identical "
            f"({len(MANIFEST)} vendored files, {len(FORK_AUTHORED)} fork-authored)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
