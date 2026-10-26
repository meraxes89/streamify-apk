# Streamify A+ Tier Engineering & Architectural Master Plan
**Target Horizon:** October 26, 2026  
**Document ID:** `A_TIER_PLAN_26_10.MD`  
**Authors:** Senior Staff Systems Architect & Lead Maintainers  
**Status:** Approved for Execution (Consensus from RFC Discussion #83)

---

## 1. Executive Summary & Purpose

Streamify has built class-leading client systems: 120Hz liquid sheet physics, local P2P CRDT jam consensus, custom K-weighted audiophile DSP, and SIMD frame palette extraction. However, the codebase has suffered under a **Tri-lingual Frankenstein Architecture** (Kotlin + C++ + Rust) characterized by:
- Over 120 hand-rolled `Java_*` JNI exports vulnerable to uncaught panics and JVM process abortion (`SIGSEGV`).
- 7 dead C++ JNI bridge files (~7,000 LOC of compiled dead weight) completely disconnected from the Android app.
- Critical security vulnerabilities including committed `service_role` database administrator keys and plaintext third-party AI keys.
- CI instability caused by high-concurrency 32-peer mesh simulations racing on resource-constrained runners alongside uncoordinated Dependabot upgrade storms.

This plan details the surgical path to transform Streamify from a **B- liability** into a **hardened, maintainable, high-velocity A+ tier mobile systems repository**.

---

## 2. Core Architectural Principles (The Invariants)

1. **The Contract-First Law**: No raw, hand-rolled JNI control-plane symbols will ever be introduced again. All future native boundaries must be defined in a centralized, reviewable, diffable `.udl` contract using **UniFFI**.
2. **Hard Real-Time Audio Isolation**: The 10–20ms audio buffer render loop is strictly non-allocating, zero-copy, and lock-free. Real-time audio DSP bypasses UniFFI and GC-managed memory entirely, communicating directly with AAudio/Oboe NDK or a single pinned native frame hook.
3. **Data-Driven DSP Retention**: C++ NEON kernels will not be rewritten out of dogma. Existing DSP implementations stay as leaf libraries unless a candidate Rust implementation matches bit-exact output and demonstrates $\le 5\%$ latency variance on mid-tier ARM reference devices.
4. **Bi-directional Parity Enforcement**: CI must enforce both forward parity (`Kotlin -> Native`) and reverse parity (`Native -> Kotlin`). No uncalled native symbols may exist in shipped binaries.

---

## 3. Execution Roadmap

```mermaid
flowchart TD
    subgraph S0["Blocking Issue #0: Security Lockdown"]
        A1["Rotate Supabase service_role Key"] --> A2["Extract ZhipuAI Keys to Backend"]
        A2 --> A3["Pin In-App Updater Signatures"]
        A3 --> A4["Integrate Gitleaks into CI"]
    end

    subgraph S1["Phase 0: Dead-Code Amputation & Reverse Parity"]
        B1["Delete 7 Orphaned C++ Bridge Files"] --> B2["Implement Reverse JNI Parity Gate"]
        B2 --> B3["Consolidate Dual-Owner Audio Path"]
    end

    subgraph S2["Foundation: CI & Governance"]
        C1["GitHub Rulesets & Required Checks on main"] --> C2["Dependabot Update Grouping & Throttling"]
        C2 --> C3["Split Fast PR Gates vs Nightly Stress Lane"]
    end

    subgraph S3["Phase 1: UniFFI Adoption"]
        D1["Define streamify.udl Contract"] --> D2["Canary Slice on Resolver & Config Domain"]
        D2 --> D3["Migrate Jam Control-Plane CRDT & Delete Kotlin Duplicates"]
    end

    S0 --> S1 --> S2 --> S3
```

---

### Step 0: Blocking Security & Supply-Chain Hardening
* **Supabase Admin Key Remediation**: Rotate the exposed key at `app/build.gradle.kts:25` (`role: service_role`). Rebind client traffic strictly to an unprivileged anonymous key and route all administrative operations (`set_user_admin_role`, `terminate_jam_session_admin`) through authenticated Supabase Edge Functions.
* **C++ Secret Extraction**: Remove plaintext `ZHIPU_KEYS[]` from `native/jni/jni_bridge.cc:771-777` and proxy requests through a secure gateway.
* **In-App Updater Signature Pinning**: Disable debug-keystore release fallback in `build.gradle.kts:69-84` and enforce SHA-256 cert pinning on downloaded updates.
* **Gitleaks CI Gate**: Install automated static secret detection on all pull request and push workflows.

---

### Step 1: Phase 0 — Dead-Code Amputation & Reverse Parity
* **Amputate 7 Ghost Bridges**:
  - `native/jni/jni_bridge_native_dsp_engine.cc`
  - `native/jni/jni_bridge_harmonic_engine.cc`
  - `native/jni/jni_bridge_media_palette.cc`
  - `native/jni/jni_bridge_audio_remuxer.cc`
  - `native/jni/jni_bridge_agsl_glow.cc`
  - `native/src/jni_bridge_frame_palette.cpp`
  - `native/src/jni_bridge_audio_sinks.cpp`
* **Implement Reverse Parity CI Check**: Extend `scripts/jni_parity_check.py` to inspect all exported `Java_*` symbols and ensure they map to an active Kotlin `external fun` declaration in the application tree.
* **Single-Owner Audio Thread**: Unify the dual-library audio processing path where C++ and Rust currently interleave calls on the same real-time audio thread.

---

### Step 2: Foundation & Repository Governance
* **GitHub Rulesets on `main`**: Enable GitHub rulesets enforcing linear commit history, branch up-to-date requirements, and mandatory green status checks (`compile-check`, `rust-lint`, `build-and-compile`, and parity checks). Prohibit direct pushes to `main`.
* **Dependabot Grouping**: Refactor `.github/dependabot.yml` to group minor dependency updates and throttle automated PR creation.
* **Test Decoupling**: Separate deterministic fast unit tests (< 2 minutes) for PR verification from high-concurrency 32-node mesh chaos simulations, which are relocated to a dedicated nightly matrix (`0 0 * * *`).

#### GOV-01 Ruleset Configuration for `main` (beta-phase-1)

The `main` branch is protected by a single repository ruleset — `main-branch-protection` — targeting `branch`, scoped to `~DEFAULT_BRANCH`, enforced **active** (not evaluate). The blocking CI gate referenced below is the `CI gate (blocking)` check run produced by the `ci-gate` job of the Extreme Test Matrix workflow, which folds the blocking set (rust-lint, build-debug-apk, test shards, native deep fuzz, CodeQL, gitleaks) into one stable check name.

| Rule | Setting | Rationale |
|---|---|---|
| Require a pull request before merging | **Required** (0 approving reviews minimum; house style still expects 1 review on non-trivial changes) | Zero direct pushes to `main`: every change is reviewable, CI-verified, and revertible as an atomic PR. |
| Require linear history | **Required** | No merge commits on `main`; every commit is individually bisectable, which the DSP regression and JNI parity workflows depend on. |
| Require branches to be up to date before merging | **Required** (`strict_required_status_checks_policy`) | PRs must be rebased on the latest `main` so the blocking CI gate always validates the exact tree that lands, never a stale ancestor. |
| Require status checks to pass | **Required**: `CI gate (blocking)` | Mandatory passing CI gate — the merge button is disabled while any blocking job (including the SEC-05 gitleaks secret scan) is red or pending. |
| Block force pushes | **Required** | Rewriting published history would invalidate gitleaks baselines, audit trails, and bisection anchors. |
| Restrict deletions | **Required** | `main` is the release/rollback anchor; it must never be deleted. |

**Creation runbook (repo admin):** Settings → Branches → Rulesets → New ruleset → the table above; or via API:

```bash
curl -X POST \
  -H "Authorization: token $ADMIN_PAT" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/meraxes89/streamify-apk/rulesets \
  -d '{
    "name": "main-branch-protection",
    "target": "branch",
    "enforcement": "active",
    "conditions": {"ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}},
    "rules": [
      {"type": "deletion"},
      {"type": "non_fast_forward"},
      {"type": "required_linear_history"},
      {"type": "pull_request", "parameters": {"required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false, "require_code_owner_review": false,
        "require_last_push_approval": false, "required_review_thread_resolution": false}},
      {"type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [{"context": "CI gate (blocking)", "integration_id": 15368}]}}
    ]
  }'
```

`non_fast_forward` enforces the no-force-push guarantee and, together with `pull_request`, yields the zero-direct-push policy: any push that is not a fast-forward merge of a green PR is rejected by the server. Bypass list is intentionally empty — admins are subject to the same gate as everyone else. The payload above is the exact REST request used to create the active `main-branch-protection` ruleset (integration id 15368 is the GitHub Actions app that reports the `CI gate (blocking)` check run).

**GOV-02 execution status (beta-phase-1):** `.github/dependabot.yml` now throttles `open-pull-requests-limit: 3` across all three ecosystems (gradle, cargo, github-actions), with minor/patch bumps arriving as weekly grouped batches; semver-major bumps remain excluded from grouping (dedicated upgrade spikes, per the P0 containment directive).

**GOV-03 execution status (beta-phase-1):** The stale ungrouped Dependabot PRs (#56, #60, #62, #64, #68) are closed pending the grouped weekly schedule; their updates re-materialize inside the grouped batches if still applicable.

---

### Step 3: Phase 1 — UniFFI Control Plane Migration
* **Interface Specification**: Establish `rust/src/streamify.udl` as the canonical boundary contract for all domain entities, errors, and asynchronous callbacks.
* **Canary Slice**: Pilot UniFFI compilation, packaging, and R8 rule validation on pure functions (resolver plumbing, deterministic candidate hashing).
* **CRDT Jam Migration**: Migrate Jam session state reconciliation and voting ops to UniFFI, allowing complete retirement of redundant Kotlin duplicate implementations using the established golden vector suite (`fixtures/jam_golden_vectors.json`).
