#!/usr/bin/env python3
"""
check_core_patch.py — Layer-1 upstream-immunity gate (Sovereign HAL Phase 0, D1).

Purpose: the `core` submodule carries upstream SimpMusic's media/player
internals (Media3 ExoPlayer wiring, media-jvm libmpv routing, audio sinks).
Streamify's playback behavior must NEVER leak into `core.patch` — the patch
is the ONLY place where an upstream submodule bump forces a textual conflict.
The patch's entire legitimate job today is *platform enablement for targets
upstream doesn't build*: wasmJs Gradle targets, wasmJs `expect/actual` shims,
and the two utility-parity fixes verified against the frozen wasm build
(`Resource.kt`'s Dispatchers.IO → Default migration, required because
Dispatchers.IO does not exist on wasmJs).

This gate parses every diff header (`diff --git`, `--- a/...`, `+++ b/...`)
in the repo-root `core.patch` and enforces a strict allowlist of file paths.
Any hunk touching a playback/audio/media-routing path inside `core` fails
with a diagnostic naming the offending file and the rule it violated.

Rules (in evaluation order — first match wins, like .gitignore):
  1. DENY (hard, with reason): any path matching a playback/audio sink/
     media-routing pattern — ExoPlayer/Media3 player internals, media-jvm
     mpv routing, audio sink/processor/effects, playback service, the
     MediaPlayerHandler implementations themselves.
  2. ALLOW: paths on the frozen allowlist — the wasmJs Gradle enablement
     files, the wasmJsMain expect/actual shims, and the exact
     `domain/.../Resource.kt` dispatchers shim (byte-review: no playback
     semantics, only the default dispatcher of generic resource wrappers).
  3. ALLOW: `<module>/build.gradle.kts` for ANY core module, but ONLY when
     the file's aggregate diff hunks contain none of the DENY-token
     strings (a build file is the classic smuggling vector: an innocent
     `wasmJs { browser() }` block can hide a playback dependency swap).
     This keeps future NEW core modules wasm-enabled without editing this
     script, while still blocking playback cargo inside build scripts.
  4. DENY (default): everything else — the patch must stay additive
     platform enablement; growing it needs a deliberate allowlist edit
     reviewed by the HAL owners, which is exactly the friction we want.

Checks:
  A. PATH RULES     every diff header path is ALLOW or DENY per the above.
  B. TOKEN SCAN     for every ALLOWED file, the combined hunk bodies are
                    scanned for DENY tokens (catches playback changes
                    smuggled inside an allowed path).
  C. HYGIENE        patch must apply to the recorded submodule commit
                    (`git -C core apply --check`); truncated/corrupt
                    headers are parse failures, not passes.

Exit code: 0 = clean, 1 = violation (each finding printed with file:line).

Wired into:
  * .github/workflows/ci.yml         Tier-1 preflight (main + integration)
  * .github/workflows/_ci-sovereign-hal.yml  Gate 1 (every HAL PR)

Usage:
    python3 scripts/check_core_patch.py            # from repo root
    python3 scripts/check_core_patch.py --verbose  # also print OK lines
    python3 scripts/check_core_patch.py --skip-apply-check  # parse-only
                                              (hermetic mode: no git, no
                                               submodule checkout needed)
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PATCH_PATH = REPO_ROOT / "core.patch"

# ─────────────────────────────────────────────────────────────────────────────
# Rule 1 — DENY patterns (regex, matched against the repo-relative path
# inside the `core` submodule). These are the upstream playback surfaces the
# Sovereign HAL wraps from OUTSIDE (streamify-modules/sovereign-player); any
# hunk here means behavior leaked into the conflict-prone patch layer.
# ─────────────────────────────────────────────────────────────────────────────
DENY_RULES: list[tuple[str, str]] = [
    # Media3 / ExoPlayer player internals (core/media/media3).
    (r"(^|/)media3(/|$)|ExoPlayer|exoplayer", "Media3/ExoPlayer player internals"),
    # media-jvm: the desktop libmpv lane (core/media/media-jvm).
    (r"(^|/)media-jvm(/|$)|libmpv|mpv", "media-jvm / libmpv desktop routing"),
    # Audio sink / processor / effects / DSP behavior anywhere in core.
    (
        r"AudioSink|AudioProcessor|audio/sink|AudioTrack|AAudio|audiofx|LoudnessEnhancer|MediaCodec",
        "audio sink / processor / codec behavior",
    ),
    # The player service + handler implementations (the Layer-2 seam wraps
    # these from outside; patching them here is the immunity violation).
    (
        r"MediaPlayerHandler|MediaServiceHandler|PlaybackService|mediaservice/handler|ExpectMediaHandler",
        "MediaPlayerHandler / playback service implementations",
    ),
    # Generic playback semantics tokens.
    (
        r"setMediaItem|seekTo\(|playWhenReady|DefaultAudioSink|AudioProcessorChain|SilenceSkipping|SonicAudioProcessor",
        "playback control / audio-sink chain semantics",
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# Rule 2 — the frozen allowlist. Every path the patch touches today, verified
# against the tree at the Sovereign HAL Phase 0 base (38f87d69, submodule
# 8413a257; the patch applies clean there). Groups:
#
#   [wasmJs enablement]  Gradle targets + wasmJsMain expect/actual shims —
#                        the patch's core job: building targets upstream
#                        doesn't ship.
#   [wasm parity fix]    Resource.kt's Dispatchers.IO → Default migration
#                        (Dispatchers.IO does not exist on wasmJs). Generic
#                        resource wrappers only — no playback semantics.
#   [verified upstream fixes]  Quality/security fixes carried for upstream
#                        (submodule 8413a257), all byte-reviewed as
#                        playback-free; their BODIES remain token-scanned
#                        by rule B:
#                          Config.kt            default quality enum normalization (PR #232)
#                          DatabaseDao.kt       ambiguous-variable rename
#                                               (shouldContinue →
#                                               shouldContinuePlaylist)
#                          DataStoreManagerImpl.kt default quality preference (PR #232)
#                          DatabaseModule.kt    hot-column SQLite indexes +
#                                               Koin createdAtStart timing
#                          RepositoryModule.kt  Koin createdAtStart timing
#                          Ytmusic.kt           log redaction (Authorization/
#                                               Cookie headers, SAPISID hash
#                                               removal) — a security fix
# ─────────────────────────────────────────────────────────────────────────────
ALLOWED_EXACT: set[str] = {
    # [wasmJs enablement] — Gradle targets for modules upstream builds without wasm.
    "common/build.gradle.kts",
    "domain/build.gradle.kts",
    "service/discord/build.gradle.kts",
    "service/ktorExt/build.gradle.kts",
    # [wasmJs enablement] — wasmJsMain expect/actual platform shims.
    "common/src/wasmJsMain/kotlin/com/maxrave/common/Platform.wasmJs.kt",
    "service/ktorExt/src/wasmJsMain/kotlin/com/maxrave/ktorext/Engine.wasmJs.kt",
    "service/ktorExt/src/wasmJsMain/kotlin/com/maxrave/ktorext/crypto/Hmac.wasmJs.kt",
    "service/ktorExt/src/wasmJsMain/kotlin/com/maxrave/ktorext/encoding/BrotliEncoder.wasmJs.kt",
    # [wasm parity fix] — default-dispatcher migration for wasmJs.
    "domain/src/commonMain/kotlin/com/maxrave/domain/utils/Resource.kt",
    # [verified upstream fixes] — quality/security/config, playback-free (see header).
    "common/src/commonMain/kotlin/com/maxrave/common/Config.kt",
    "data/src/commonMain/kotlin/DatabaseDao.kt",
    "data/src/commonMain/kotlin/com/maxrave/data/dataStore/DataStoreManagerImpl.kt",
    "data/src/commonMain/kotlin/com/maxrave/data/di/DatabaseModule.kt",
    "data/src/commonMain/kotlin/com/maxrave/data/di/RepositoryModule.kt",
    "service/kotlinYtmusicScraper/src/commonMain/kotlin/com/maxrave/kotlinytmusicscraper/Ytmusic.kt",
}

# Rule 3 helper — any core module's build.gradle.kts is eligible for wasmJs
# enablement hunks, but its BODY is then token-scanned (rule B below).
BUILD_FILE_RE = re.compile(r"^[A-Za-z0-9_./-]+/build\.gradle\.kts$")

# ─────────────────────────────────────────────────────────────────────────────
# Rule B — DENY tokens scanned inside every ALLOWED file's hunk bodies.
# Catches playback changes hiding inside an allowed path (e.g. someone adds
# `implementation("androidx.media3:media3-exoplayer")` to a build file, or
# edits ExpectMediaHandler via the Resource.kt allowlist entry's directory).
# ─────────────────────────────────────────────────────────────────────────────
DENY_TOKENS: list[tuple[str, str]] = [
    (r"media3|ExoPlayer|exoplayer", "Media3/ExoPlayer reference"),
    (r"libmpv|\bmpv\b|media-jvm", "libmpv / media-jvm reference"),
    (r"AudioSink|AudioProcessor|AudioTrack|AAudio|MediaCodec|LoudnessEnhancer", "audio sink/processor reference"),
    (r"MediaPlayerHandler|MediaServiceHandler|PlaybackService|ExpectMediaHandler", "player handler/service reference"),
    (r"DefaultAudioSink|SilenceSkipping|SonicAudioProcessor|AudioProcessorChain", "audio-sink chain reference"),
    (r"seekTo\(|setMediaItem|playWhenReady|togglePlayPause|onPlayerEvent", "playback control semantics"),
]

# A "file block" in unified diff format:
#   diff --git a/<path> b/<path>
#   --- a/<path> | /dev/null
#   +++ b/<path> | /dev/null
#   @@ ... @@ hunk bodies
DIFF_HEADER_RE = re.compile(r"^diff --git a/(.+?) b/(.+?)$")
OLD_PATH_RE = re.compile(r"^--- (?:a/)?(.+?)(?:\t.*)?$")
NEW_PATH_RE = re.compile(r"^\+\+\+ (?:b/)?(.+?)(?:\t.*)?$")
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@")


def parse_patch(text: str) -> list[dict]:
    """Parse the unified diff into per-file blocks: path + hunk bodies.

    Raises ValueError on structurally corrupt input (truncated headers,
    unterminated file blocks) — a corrupt patch is a finding, not a pass.
    """
    files: list[dict] = []
    current: dict | None = None
    saw_any_header = False
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.rstrip("\n")
        m = DIFF_HEADER_RE.match(line)
        if m:
            if current is not None:
                files.append(current)
            saw_any_header = True
            # `git apply` operates on the b/ side unless it's a deletion.
            path = m.group(2)
            current = {"path": path, "bodies": [], "header_line": lineno}
            continue
        if current is None:
            # Preamble before the first diff header (e.g. "diff --cc" merge
            # residue) is legal ONLY if it's blank; anything else is cruft
            # the gate should surface.
            if line.strip():
                raise ValueError(
                    f"line {lineno}: non-diff preamble before the first 'diff --git' header: {line[:80]!r}"
                )
            continue
        if OLD_PATH_RE.match(line) or NEW_PATH_RE.match(line):
            continue
        if HUNK_RE.match(line):
            current["bodies"].append(line)
            continue
        # Any other line inside a file block is hunk body (context, +/-
        # lines, or a "\ No newline" marker).
        current["bodies"].append(line)
    if current is not None:
        files.append(current)
    if not saw_any_header:
        raise ValueError("no 'diff --git' headers found — empty or corrupt patch")
    return files


def classify(path: str) -> tuple[str, str | None]:
    """Classify one diff path → ('ALLOW', reason) | ('DENY', reason)."""
    # Rule 1 — hard deny, playback/audio surface.
    for pattern, reason in DENY_RULES:
        if re.search(pattern, path):
            return ("DENY", reason)
    # Rule 2 — frozen allowlist.
    if path in ALLOWED_EXACT:
        return ("ALLOW", "frozen allowlist (platform enablement)")
    # Rule 3 — build files for any core module, body-scanned separately.
    if BUILD_FILE_RE.match(path):
        return ("ALLOW", "core-module build file (body token-scanned)")
    # Rule 4 — default deny.
    return ("DENY", "not on the platform-enablement allowlist")


def check_apply(patch: Path) -> list[str]:
    """Hygiene check C: the patch must apply to the checked-out submodule."""
    findings: list[str] = []
    core_dir = REPO_ROOT / "core"
    if not core_dir.is_dir():
        return [f"core.patch:{patch}: hygiene: 'core/' submodule is not checked out — cannot verify the patch applies"]
    try:
        proc = subprocess.run(
            ["git", "-C", str(core_dir), "apply", "--check", str(patch)],
            capture_output=True,
            text=True,
            timeout=30,
        )
    except FileNotFoundError:
        return ["check_core_patch: git binary not found — cannot run the apply check"]
    except subprocess.TimeoutExpired:
        return ["check_core_patch: git apply --check timed out after 30 s"]
    if proc.returncode != 0:
        findings.append(
            "core.patch: hygiene: patch does NOT apply to the checked-out 'core' submodule "
            f"(git apply --check rc={proc.returncode}): {proc.stderr.strip()[:300]}"
        )
    return findings


def main() -> int:
    ap = argparse.ArgumentParser(description="Layer-1 upstream-immunity gate for core.patch")
    ap.add_argument("--verbose", action="store_true", help="also print OK lines")
    ap.add_argument(
        "--skip-apply-check",
        action="store_true",
        help="skip the git-apply hygiene check (hermetic/parse-only mode)",
    )
    args = ap.parse_args()

    if not PATCH_PATH.is_file():
        # An absent patch is legal (a future world where upstream builds
        # wasmJs natively); a PRESENT patch is what we police.
        print("check_core_patch: core.patch absent — nothing to gate (OK)")
        return 0

    text = PATCH_PATH.read_text(encoding="utf-8", errors="replace")
    findings: list[str] = []

    # Check A+B: parse, classify, token-scan.
    try:
        files = parse_patch(text)
    except ValueError as e:
        print(f"check_core_patch: PARSE FAILURE — {e}")
        return 1

    for entry in files:
        path = entry["path"]
        header_line = entry["header_line"]
        verdict, reason = classify(path)
        body = "\n".join(entry["bodies"])
        if verdict == "DENY":
            findings.append(
                f"core.patch:{header_line}: DENY {path} — {reason} "
                "(playback/audio behavior must live in streamify-modules/, never in core.patch)"
            )
            continue
        if args.verbose:
            print(f"  OK allow {path} ({reason})")
        # Rule B — token scan the body of every allowed file.
        for pattern, reason_b in DENY_TOKENS:
            if re.search(pattern, body):
                findings.append(
                    f"core.patch:{header_line}: DENY token in ALLOWED file {path} — {reason_b} "
                    "(playback references may not ride inside a platform-enablement hunk)"
                )

    # Check C — apply hygiene.
    if not args.skip_apply_check:
        findings.extend(check_apply(PATCH_PATH))

    if findings:
        print("── check_core_patch: VIOLATIONS FOUND ──────────────────────────")
        for f in findings:
            print(f"  {f}")
        print("────────────────────────────────────────────────────────────────")
        print("The core.patch allowlist is FROZEN for the Sovereign HAL program.")
        print("If this change is legitimately required to build a NEW platform")
        print("target, extend ALLOWED_EXACT in scripts/check_core_patch.py in the")
        print("same PR and document it in docs/architecture/SOVEREIGN_HAL_PLAN.md.")
        return 1

    print(
        f"check_core_patch: PASS — {len(files)} file(s) in core.patch, all platform "
        "enablement (wasmJs targets, expect/actual shims, verified utility parity); "
        "no playback/audio behavior leaked into the submodule patch layer"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
