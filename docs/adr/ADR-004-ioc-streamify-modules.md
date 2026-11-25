# ADR-004: Inversion of control via streamify-modules

* Status: Accepted
* Date: 2026-11-22
* Verified against: 8b858b1c (staleness protocol in docs/adr/README.md)

## Context

Streamify builds on an upstream lineage: the `core` submodule
(`maxrave-dev/core`) and large parts of the SimpMusic codebase evolve
independently. Feature work (HAL integration, SLYR lyrics, Supabase sync,
jam rooms, ffmpeg-free remuxing) must land without invasive edits to upstream
files, or every upstream merge becomes a conflict-resolution exercise.

## Decision

Downstream features live in `streamify-modules/` and attach through three
mechanisms, in loading order:

1. **Koin addition, not redefinition.** Upstream `loadAllModules(...)`
   initializes first, then `viewModelModule`, then
   `loadKoinModules(streamifyAllModules)` — observed in
   `androidApp/.../SimpMusicApplication.kt` and
   `composeApp/src/jvmMain/.../DesktopApp.kt`. The suite
   (`core-bridge`, `media-hal`, `lyrics-slyr`, `supabase`, `jam`) registers
   only new `com.streamify.*` types; upstream UI consumes them by injection
   or direct object access. A Koin caveat is documented in the code:
   modules added via `loadKoinModules` do not fire `createdAtStart`, so
   singletons must be explicitly injected to be constructed.
2. **Gradle dependency substitution.** The ffmpeg compat shim replaces the
   24.4 MB `io.github.maxrave-dev:ffmpeg-kit-audio` binary at build time
   (root `build.gradle.kts`), offering the same `com.arthenica.ffmpegkit`
   API implemented with zero-re-encode `MediaExtractor`/`MediaMuxer` remux.
3. **settings.gradle guarded includes.** The six modules are included only
   when the `streamify-modules/` directory exists, so the tree can be
   stripped for an upstream-pure build.

Per-platform behavior uses `expect/actual` (e.g. `mediaHalModule` registers
the Android Media3 processor chain on androidMain and the desktop HAL bridge
on jvmMain).

## Alternatives considered

- **Hard fork.** Rejected: upstream moves fast (SimpMusic), and every merge
  would re-litigate thousands of lines.
- **Upstream PRs for everything.** Rejected: downstream features are
  product-specific; upstream review latency would block releases.
- **Build variants/flavors.** Rejected: they scatter the same logic across
  source sets instead of centralizing it in modules.

## Consequences

**Positive.** Upstream merges stay mechanically simple; each extension is
testable as a module; the ffmpeg shim removes a 24.4 MB binary from the APK.

**Negative, stated honestly.** "Upstream immunity" is qualified, not
absolute: `core.patch` modifies the `core` submodule (a second divergence
channel that must be rebased), and `composeApp` itself contains streamify
code (`StreamifySyncEngine`, `StreamifyDefaultsInitializer`,
`StreamifyUpdateRepositoryImpl`), so the boundary is a convention under
review discipline, not a hard guarantee. The Kotlin `jam` module is a local
`StateFlow` room coordinator and does not yet exercise the Rust jam engine —
consolidating the two is open follow-up.

## Compliance

Module wiring is reviewable in `settings.gradle.kts` and the two
`startKoin` blocks; `scripts/check_feishin_resolver.py` guards upstream
vendored-file integrity; the guarded include keeps an upstream-pure build
constructible.

---
<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
