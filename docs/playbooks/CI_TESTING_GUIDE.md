# CI/CD Testing & Chaos Suite Workflow

This document outlines how the **Unified CI Pipeline** (the 3-tier
hub-and-spoke DAG in `.github/workflows/ci.yml`) and its companion lanes
execute automated tests across Rust, native C++, Kotlin, Swift, and the
Supabase backend — and where to locate the aggregated test logs.
**Read this before attempting to reproduce a test failure locally.**

> TRUTH-02 (2026-11): this guide previously described the retired
> monolithic workflow (deleted 2026-10-27, `git log` has the history).
> Every table below is verified against the live workflow files.

## How the Unified Pipeline Works

**Trigger:** every pull request to `main`, every merge-queue entry, every
push to `main`, plus a `workflow_dispatch` escape hatch (GitHub
occasionally drops pull_request events; a manual dispatch run evaluates
the same matrix and satisfies the same required check).

### Tier 1 — Fast-Fail Preflight (ubuntu, ~2 min)

`preflight` (job name: "Tier 1: Fast-Fail & Path Filter"):

1. **gitleaks secret scan** — blocking, at t=0, full commit range
   (SEC-05).
2. **JNI panic-boundary convention gate** — every `Java_*` export in
   `rust/crates/streamify-ffi/src/jni_bridge*.rs` must wrap its body in
   `catch_unwind` (`scripts/jni_panic_boundary_check.py`).
3. **Docs-vs-reality lint** — workflow badges, release-tag links,
   relative doc links, stale-workflow claims, and checked-ledger
   file claims are grepped against the tree
   (`scripts/docs_ledger_check.py`, TRUTH-02).
4. **dorny/paths-filter impact analysis** — decides which Tier-2 spokes
   run (core / android / desktop / ios / supabase / packaging); on
   non-PR events the full matrix always runs (fail-safe: a queue entry
   must never under-test).

### Tier 2 — Domain Spokes (parallel, reusable workflows)

| Spoke | Workflow | What runs |
|---|---|---|
| **Core Engine & C++ DSP** | `_ci-engine.yml` | Rust core `cargo nextest` matrix (2 split shards); C++ ASan+UBSan on `native-dsp`, `native-simd-physics`, `native-telemetry-storage`; build-once native fan-out: Android NDK staticlibs (arm64-v8a + x86_64), Linux + Windows desktop cdylibs |
| **Rust clippy** | (inline in `ci.yml`) | whole 7-crate workspace, `-D warnings` |
| **Android Matrix** | `_ci-android.yml` | JVM unit suite (54 classes) + `assembleDebug` universal 2-ABI APK consuming the NDK artifacts |
| **Desktop Matrix (Linux, Windows)** | `_ci-desktop.yml` | Compose Multiplatform UI smoke suite (`:desktop:test` + `:shared:desktopTest`, engine-free FakeEnginePort) + JNA engine smoke suite on the real cdylib; `package_installers` mode adds jpackage + the <250 MiB `verifyDesktopSizeBudget` gate (.deb / .msi) |
| **Apple Unified** | `_ci-ios.yml` | Builds `StreamifyCore.xcframework` (content-addressed cache), **stages it into `iosApp/Frameworks/` so the real engine lane links** (IOS-02), runs the full iOS simulator XCTest suite including the 28 real-lane UniFFI contract tests, the macOS SPM compile gate (`swift build --build-tests`), the macOS desktop cdylib + JNA smoke suite, and (packaging mode) the macOS DMG |
| **Supabase RLS hardening** | (inline in `ci.yml`) | `supabase/sql/tests/rls_test.sql` — 11 RLS assertions against a real Postgres + pgvector container (SEC-10) |

Spokes are skipped (never missing) when their subtree is untouched — a
skipped spoke reports `skipped`, which the gate folds as pass.

### Tier 3 — The One Required Check

`ci-gate` (**"CI Gate (Blocking)"**) is the ONLY required status check
in branch protection: it passes on success or skipped, fails loudly on
failure/cancelled. One required check means zero
"Expected — Waiting for status" deadlocks, no matter how the spoke set
changes.

### Companion lanes

* **CodeQL** (`codeql.yml`) — advisory only (c-cpp, java-kotlin): source
  extraction, no build, can never deadlock the merge queue.
* **Nightly Deep Matrix** (`nightly-deep-matrix.yml`, schedule +
  dispatch only): deep Rust nextest profile (includes the 32-peer mesh
  chaos suite), C++20 LibFuzzer crash hunt (900 s, cached corpus), and
  the TEST-01 connected-Android lane — one API-34 x86_64 emulator on a
  KVM-enabled ubuntu runner (this replaced the three never-bootable
  macOS emulator legs, TRUTH-01c). A fuzz crash IS a memory-safety bug:
  the fuzz job fails the run.
* **Resolver Client Canary** (`resolver-canary.yml`, daily 06:30 UTC):
  probes the live client ladder. Rotations land as **reviewed pull
  requests** from the evergreen `bot/fleet-rotation` branch (GOV-05) —
  the canary never pushes to `main` directly.
* **build-ref.yml** — reference build with the `--nm` JNI parity mode on
  built artifacts; `release.yml` — `v*`-tag releases from the protected
  `release` environment (GOV-05).

## Where are the Test Logs?

The nightly `aggregate-and-publish-logs` job consolidates the deep-matrix
shard logs and emulator results into a dedicated orphan branch:

* **Branch:** `testing-log`
* **Historical Run Log:** `runs/test-run-{RUN_NUMBER}.log`
* **Latest Aggregated Log:** `latest.log`

PR-time failures (the common case) are read directly off the failed
job's log on the Actions run page — no aggregation branch involved.

## Instructions for AI Agents / Developers

If a test run fails in CI, **do not attempt to run slow full-suite
emulators or extensive fuzzers locally first**. Follow these steps:

1. **Read the failing job's log on GitHub** (the Actions run page).
   Assertion-level failure messages are the primary debugging surface —
   the lanes deliberately do not run `-quiet`.
2. **For nightly-lane failures, fetch the testing logs branch:**
   ```bash
   git fetch origin testing-log
   git checkout origin/testing-log
   cat latest.log
   ```
3. **Inspect a specific shard** (nightly lanes, e.g. the mesh chaos
   suite or the emulator run):
   ```bash
   grep -A 30 "SHARD: <shard-name>" latest.log
   ```
4. **Switch back, fix, and push:**
   ```bash
   git checkout main   # or your feature branch
   ```
   Apply the targeted fix in the relevant Kotlin, Rust, C++, or Swift
   module, commit, and push. CI re-evaluates.

By following this workflow, you save compute cycles, protect device
battery/thermals, and diagnose failures with exact ASan/UBSan traces
directly from CI.
