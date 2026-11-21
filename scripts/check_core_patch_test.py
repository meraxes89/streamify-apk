#!/usr/bin/env python3
"""Negative + positive tests for scripts/check_core_patch.py (hermetic:
runs the gate against synthetic patches, restoring the real core.patch)."""
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "scripts" / "check_core_patch.py"
PATCH = REPO / "core.patch"


def run_with_patch(patch_text: str) -> tuple[int, str]:
    orig = PATCH.read_text()
    PATCH.write_text(patch_text)
    try:
        p = subprocess.run(
            [sys.executable, str(SCRIPT), "--skip-apply-check"],
            capture_output=True, text=True,
        )
        return p.returncode, p.stdout
    finally:
        PATCH.write_text(orig)


def expect_deny(name: str, patch_text: str, needle: str) -> None:
    rc, out = run_with_patch(patch_text)
    assert rc == 1, f"{name}: expected rc=1, got {rc}:\n{out}"
    assert needle in out, f"{name}: expected {needle!r} in output:\n{out}"
    print(f"  PASS {name} (denied)")


def expect_allow(name: str, patch_text: str) -> None:
    rc, out = run_with_patch(patch_text)
    assert rc == 0, f"{name}: expected rc=0, got {rc}:\n{out}"
    print(f"  PASS {name} (allowed)")


# 1. Media3 player path → hard DENY.
expect_deny(
    "media3 path",
    "diff --git a/media/media3/src/commonMain/kotlin/foo.kt b/media/media3/src/commonMain/kotlin/foo.kt\n"
    "--- a/media/media3/src/commonMain/kotlin/foo.kt\n"
    "+++ b/media/media3/src/commonMain/kotlin/foo.kt\n"
    "@@ -1,3 +1,4 @@\n"
    "+import androidx.media3.exoplayer.audio.DefaultAudioSink\n",
    "Media3",
)

# 2. Playback token smuggled inside an allowed build file → DENY.
expect_deny(
    "token smuggled in build file",
    "diff --git a/common/build.gradle.kts b/common/build.gradle.kts\n"
    "--- a/common/build.gradle.kts\n"
    "+++ b/common/build.gradle.kts\n"
    "@@ -1,3 +1,5 @@\n"
    "+    implementation(\"androidx.media3:media3-exoplayer\")\n"
    "+    api(project(\":media-jvm\"))\n",
    "token in ALLOWED",
)

# 3. Unknown wasmJs shim (not on allowlist, not a build file) → default DENY.
expect_deny(
    "unknown wasmJs file",
    "diff --git a/service/ktorExt/src/wasmJsMain/kotlin/com/maxrave/ktorext/NewShim.kt b/service/ktorExt/src/wasmJsMain/kotlin/com/maxrave/ktorext/NewShim.kt\n"
    "--- a/service/ktorExt/src/wasmJsMain/kotlin/com/maxrave/ktorext/NewShim.kt\n"
    "+++ b/service/ktorExt/src/wasmJsMain/kotlin/com/maxrave/ktorext/NewShim.kt\n"
    "@@ -0,0 +1,2 @@\n"
    "+package com.maxrave.ktorext\n"
    "+actual fun getEngine(): Int = 0\n",
    "not on the platform-enablement allowlist",
)

# 4. Clean build-file wasm enablement → ALLOW.
expect_allow(
    "clean build-file wasm hunk",
    "diff --git a/data/build.gradle.kts b/data/build.gradle.kts\n"
    "--- a/data/build.gradle.kts\n"
    "+++ b/data/build.gradle.kts\n"
    "@@ -41,6 +44,10 @@ kotlin {\n"
    "+    wasmJs {\n"
    "+        browser()\n"
    "+    }\n"
    "+\n",
)

# 5. seekTo smuggled into Resource.kt → DENY.
expect_deny(
    "seekTo smuggled into allowed source file",
    "diff --git a/domain/src/commonMain/kotlin/com/maxrave/domain/utils/Resource.kt b/domain/src/commonMain/kotlin/com/maxrave/domain/utils/Resource.kt\n"
    "--- a/domain/src/commonMain/kotlin/com/maxrave/domain/utils/Resource.kt\n"
    "+++ b/domain/src/commonMain/kotlin/com/maxrave/domain/utils/Resource.kt\n"
    "@@ -1,2 +1,3 @@\n"
    "+    player.seekTo(0)\n",
    "token in ALLOWED",
)

# 6. Corrupt patch → parse failure.
rc, out = run_with_patch("random garbage\nnot a diff\n")
assert rc == 1, f"corrupt: expected rc=1, got {rc}"
print("  PASS corrupt patch (parse failure)")

# 7. An allowed file's hunk touching MediaPlayerHandler → DENY.
expect_deny(
    "handler reference in allowed file",
    "diff --git a/data/src/commonMain/kotlin/com/maxrave/data/di/DatabaseModule.kt b/data/src/commonMain/kotlin/com/maxrave/data/di/DatabaseModule.kt\n"
    "--- a/data/src/commonMain/kotlin/com/maxrave/data/di/DatabaseModule.kt\n"
    "+++ b/data/src/commonMain/kotlin/com/maxrave/data/di/DatabaseModule.kt\n"
    "@@ -1,2 +1,3 @@\n"
    "+    single<MediaPlayerHandler> { TODO() }\n",
    "token in ALLOWED",
)

print("check_core_patch test battery: ALL PASS")
