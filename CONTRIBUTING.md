# Contributing to Streamify

Thanks for your interest in contributing. Streamify is a free music streaming platform shipping premium-grade features to everyone — see [PHILOSOPHY.md](PHILOSOPHY.md) for why, and this document for how.

Reading time: 5 minutes. First PR checklist at the bottom.

## Before you start

1. Read [PHILOSOPHY.md](PHILOSOPHY.md) — every technical decision should trace back to it.
2. Read [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) — the system map and dependency rules.
3. Read [docs/playbooks/CODEBASE_GUIDE.md](docs/playbooks/CODEBASE_GUIDE.md) — folder-by-folder encyclopedia of the app module.
4. Skim [docs/playbooks/ADD_A_FEATURE.md](docs/playbooks/ADD_A_FEATURE.md) — playbooks for common changes.
5. Engine-specific manuals live in `docs/architecture/engines/01_…md` through `docs/architecture/engines/12_…md`. Touch an engine, read its manual.
6. Follow the [Code of Conduct](CODE_OF_CONDUCT.md). Blunt about code, kind about people.

**Security issues are not issues.** Report vulnerabilities privately per [SECURITY.md](SECURITY.md).

## Project layout (orientation)

```
app/src/main/java/com/streamify/app/   Kotlin + Compose client (data · media · jam · radio · di · ui · viewmodel · util)
native/                               C++20 SIMD DSP + JNI trampolines (ARM NEON)
rust/                                 Rust core engines (CRDT, mesh, tagging, clocks)
supabase/                             Optional backend: schemas, RPCs, RLS
docs/                                 Engine manuals + onboarding guides (source of truth)
.github/workflows/                    CI: ci.yml (hub, Tier 1–3 gate), _ci-engine/_ci-android/_ci-desktop/_ci-ios (Tier-2 spokes), nightly-deep-matrix, resolver-canary, release, build-ref, codeql (advisory)
```

Three languages, one product. The **boundaries matter more than the interiors**: JNI exports must match `external fun` declarations 1:1; Jam wire bytes must be bit-identical between the Kotlin (`app/src/main/java/com/streamify/app/jam/JamOpWire.kt`) and Rust (`rust/crates/streamify-jam/src/jam_crdt.rs`) stacks. If you change an interface, change both sides and their tests in the same PR.

## Development environment

Requirements: JDK 17, Android SDK (minSdk 26 / targetSdk 34), NDK r26+, Rust 1.93+ (`aarch64-linux-android`, `armv7-linux-androideabi` targets), Gradle wrapper.

```bash
git clone https://github.com/meraxes89/streamify-apk.git && cd streamify-apk

# Secrets are never hardcoded. Create local.properties:
#   SUPABASE_URL=https://<project>.supabase.co
#   SUPABASE_ANON_KEY=<anon key>
# (values come from your own Supabase project; see
#  docs/architecture/engines/07_database_storage.md and
#  docs/playbooks/BACKEND_DB_SYNC.md for the live-DB sync runbook)

./gradlew assembleDebug          # builds APK with native + rust toolchain hooks
cargo test -p streamify-core     # Rust unit + integration suites
./gradlew testDebugUnitTest      # Kotlin unit tests (483+)
```

Run the emulator or a physical device (API 26+). For DSP latency work, a physical ARM device is required — NEON paths don't exist under x86 emulation.

## Workflow

1. **Pick or file an issue.** Anything without a tracking issue should get one first. Label accordingly (`bug`, `engine:*`, `audit-*`).
2. **Branch from `main`:** `<type>/<issue-number>-<slug>` (e.g., `fix/45-jni-parity-check`). Types: `fix`, `feat`, `perf`, `refactor`, `docs`, `chore`.
3. **Small, single-purpose PRs.** Our house style, established by the audit-22 series: one finding → one titled PR → measurement gate if it claims a perf effect. "Perf" PRs without a before/after benchmark will be parked, not merged.
4. **Tests are part of the change.** New logic ships with tests. Changed interfaces ship with parity/differential tests on both sides of the boundary.
5. **Docs update in the same PR.** Folder README, engine manual, `CHANGELOG.md` entry. Documentation is part of the definition of done.
6. **CI green → review → squash-merge.** Maintainers protect `main`; expect at least one review on non-trivial changes.

### Commit message convention

We use conventional commits with scope:

```
fix(jam): reconcile Merkle root mismatch on late-join peers (#47)
perf(dsp): reduce limiter branch misses on snapdragon 8-series (#48)
docs(native): document jni_bridge_dsp.cc compile-target inclusion
```

Subject ≤ 72 chars, imperative mood, issue reference in parentheses.

### Pull request template

GitHub auto-fills `.github/PULL_REQUEST_TEMPLATE.md`. Fill every section; "N/A" is acceptable where honest, empty is not.

## What we review hard

- **Native boundary changes** (`NativeBridge.kt`, `native/jni/*`, `rust/src/jni_bridge*`): symbol parity, ABI stability, error handling across FFI (no panic-across-JNI; no unguarded `unwrap()` in production hot paths).
- **Consensus & wire formats:** byte-exactness, golden-vector fixtures, election-safety reasoning.
- **Supabase migrations:** RLS policies reviewed like code; anon-key scoping verified.
- **Hot-path allocations:** RAII only in C++; zero GC-triggering allocations in Rust audio paths.

## What we won't merge

- Features behind paywalls or ads (contradicts PHILOSOPHY.md §non-negotiables 1–2).
- Changes that weaken CI gating (e.g., adding `continue-on-error` to test jobs).
- Bulk reformatting mixed with behavior changes — split them.
- New dependencies without a documented rationale in the PR description.

## Getting help

- Open a `question`-labeled issue, or ask in the thread of the relevant engine's manual.
- Tag maintainers only when a PR has had zero response for 5+ business days.

## First-PR checklist

- [ ] Branch name follows `<type>/<issue>-<slug>`
- [ ] Builds locally (`assembleDebug` + relevant test suites pass)
- [ ] Tests added/updated for changed behavior and changed boundaries
- [ ] Docs updated (folder README / engine manual / CHANGELOG)
- [ ] No secrets, keystores, or tokens committed
- [ ] PR template filled completely
- [ ] One purpose per PR

Welcome aboard. 🎧
