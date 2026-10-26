# CI/CD Testing & Chaos Suite Workflow

This document outlines how the GitHub Actions Extreme Test Matrix & Chaos Suite executes automated tests across native C++, Rust, and Kotlin subsystems, and where to locate the aggregated test logs. **Read this before attempting to reproduce a test failure locally.**

## Where are the Test Logs?

When the GitHub Actions workflow (`.github/workflows/extreme-test-matrix.yml`) finishes running the test suite, it **automatically consolidates all shard logs, LibFuzzer runs, and emulator results into a dedicated orphan branch** in this repository.

*   **Branch:** `testing-log`
*   **Historical Run Log:** `runs/test-run-{RUN_NUMBER}.log` (e.g., `runs/test-run-12.log`)
*   **Latest Aggregated Log:** `latest.log`

## How the Extreme Testing Pipeline Works

1.  **Trigger:** A push or pull request to `main`/`develop`, or the nightly cron schedule (`0 0 * * *`), activates the workflow.
2.  **Gatekeeper Build (`build-and-compile`):** Compiles host native C++20 and executes `./gradlew assembleDebug`. Upon success, artifacts and GitHub Releases (`streamify.apk`) are published immediately.
3.  **Parallel Multi-Shard Matrix (`test-matrix`):** Executes 8 isolated verification targets concurrently:
    *   `native-dsp`: KissFFT 2048 STFT, Krumhansl Key, Ellis BPM, and EBU R128 LUFS under AddressSanitizer (ASan) and UndefinedBehaviorSanitizer (UBSan).
    *   `native-simd-physics`: 128-D ARM NEON VectorStore and RK4 AirDrop ODE Solver.
    *   `native-telemetry-storage`: Lock-Free SPSC Ring Buffer, SHA-256 Proof-of-Compute, and SQLite WAL.
    *   `rust-core-engine`: Zero-Copy SIMD JSON, Lofty Metadata, Lyric Precompiler, and PTP Sync via Cargo.
    *   `playback-media3-timeline`: Sliding 2-Track JIT Timeline, Gapless playback, and Equal-Power Crossfade.
    *   `network-resolver-chaos`: 3-Tier Stream Resolver Race, HTTP 429 adaptive backoff, and CDN failover.
    *   `database-outbox-sync`: Conflict-free Fractional Indexing, LWW CRDTs, and PGRST204 Auto-Healer.
    *   `byzantine-jam-ptp`: 2-Peer Byzantine Consensus, MAD Lyric Drift, and PTP PLL Clock synchronizer.
4.  **Native LibFuzzer Hunting (`native-deep-fuzz`):** Continuous fuzzing with cached seed corpus (`native/fuzz/corpus`) to detect buffer overruns and crashes in audio demuxing.
5.  **ARM64 Hardware-Accelerated Emulators (`emulator-matrix`):** Headless Media3 and Compose UI tests running on macOS Apple Silicon virtualization across API 26 (MinSDK), API 30, and API 34 (TargetSDK).
6.  **Log Aggregation & Storage (`aggregate-and-publish-logs`):**
    *   Downloads all shard logs (`log-*`), concatenates them into `combined-test.log`, and commits them to the `testing-log` branch.
    *   If any shard fails or is cancelled, extracts the exact failure stack traces and posts a detailed comment on the triggering commit via GitHub CLI (`gh api`).

## Instructions for AI Agents / Developers

If a test run fails in CI, **do not attempt to run slow full-suite emulators or extensive fuzzers locally first**. Follow these steps to inspect the exact failure traces:

1.  **Fetch the Testing Logs Branch:**
    ```bash
    git fetch origin testing-log
    git checkout origin/testing-log
    ```
2.  **View the Latest Test Run:**
    Read the consolidated log or view the latest run file:
    ```bash
    cat latest.log
    ```
3.  **Inspect a Specific Shard:**
    Filter for the specific failing shard (e.g., `native-dsp` or `network-resolver-chaos`):
    ```bash
    grep -A 30 "SHARD: native-dsp" latest.log
    ```
4.  **Switch Back, Fix, and Push:**
    ```bash
    git checkout main
    ```
    Apply the targeted fix in the relevant Kotlin, C++, or Python module, commit, and push.

By following this workflow, you save compute cycles, protect device battery/thermals, and diagnose failures with exact ASan/UBSan traces directly from CI.
