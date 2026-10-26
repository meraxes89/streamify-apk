# Streamify A+ Tier Execution Ledger & Checkbox Tracker
**Tracking Document:** `A_Checkbox.md`  
**Governing Plan:** [`docs/A_TIER_PLAN_26_10.MD`](file:///data/data/com.termux/files/home/streamify-apk/docs/A_TIER_PLAN_26_10.MD)  
**Last Updated:** October 26, 2026

This ledger records the official consensus decisions, status of execution items, and active tracking for the A+ tier repository overhaul.

---

## 🏛️ Ledger of Architectural Consensus

| Ref ID | Architectural Decision | Status | Consensus Note |
|---|---|---|---|
| **DEC-01** | **UniFFI with `.udl` for Control Plane** | **APPROVED** | Centralized, reviewable `.udl` contract acts as the sole ABI source of truth. Scaffolding checksums replace manual parity checks on migrated APIs. |
| **DEC-02** | **Hard Isolation for Real-Time Audio Path** | **APPROVED** | 10–20ms audio frame render loop strictly bypasses UniFFI and GC memory. Uses non-allocating, zero-copy native execution (AAudio/Oboe direct or single pinned JNI symbol). |
| **DEC-03** | **Data-Driven C++ DSP Retention** | **APPROVED** | C++ NEON kernels will not be rewritten without empirical proof. Rust ports must prove bit-exactness/tolerance and $\le 5\%$ latency variance on ARM reference hardware. |
| **DEC-04** | **Canary-First Delivery Sequence** | **APPROVED** | Toolchain and `.so` packaging validated on pure domains before stateful mesh/CRDT systems are migrated. |
| **DEC-05** | **Zero-Tolerance for Ghost Bridges** | **APPROVED** | All 7 orphaned C++ bridge files confirmed dead and slated for amputation. CI reverse parity check enforced. |

---

## 📋 Execution Ledger & Task Checklist

### 🚨 Blocking Issue #0: Critical Security & Supply-Chain Hardening
- [ ] **SEC-01**: Rotate compromised Supabase `service_role` key in `app/build.gradle.kts:25`; replace with unprivileged anon key.
- [ ] **SEC-02**: Migrate administrative RPC operations (`set_user_admin_role`, `terminate_jam_session_admin`) to secure Supabase Edge Functions.
- [ ] **SEC-03**: Strip plaintext `ZHIPU_KEYS[]` array from `native/jni/jni_bridge.cc:771–777` and proxy through backend.
- [ ] **SEC-04**: Disable silent debug-keystore fallback in release builds (`build.gradle.kts:69–84`) and pin updater certificate signature.
- [ ] **SEC-05**: Add `gitleaks` step to GitHub Actions PR and push validation workflows.

### 🧹 Phase 0: Dead-Code Amputation & Reverse Parity Enforcement
- [ ] **AMP-01**: Delete `native/jni/jni_bridge_native_dsp_engine.cc` (~450 LOC).
- [ ] **AMP-02**: Delete `native/jni/jni_bridge_harmonic_engine.cc` (~380 LOC).
- [ ] **AMP-03**: Delete `native/jni/jni_bridge_media_palette.cc` (~290 LOC).
- [ ] **AMP-04**: Delete `native/jni/jni_bridge_audio_remuxer.cc` (~260 LOC).
- [ ] **AMP-05**: Delete `native/jni/jni_bridge_agsl_glow.cc` (~210 LOC).
- [ ] **AMP-06**: Delete `native/src/jni_bridge_frame_palette.cpp` (~200 LOC).
- [ ] **AMP-07**: Delete `native/src/jni_bridge_audio_sinks.cpp` (~180 LOC).
- [ ] **AMP-08**: Clean up CMake targets in `native/CMakeLists.txt` associated with deleted bridges.
- [ ] **AMP-09**: Implement reverse parity check in `scripts/jni_parity_check.py` to fail CI if any exported native symbol has no active Kotlin caller.
- [ ] **AMP-10**: Consolidate dual-owner audio processing path into a single native runtime owner.

### 🛡️ Repository Foundation & Rulesets
- [ ] **GOV-01**: Configure GitHub Rulesets on `main` branch (linear history, required passing CI checks, require branches up-to-date, block direct push).
- [ ] **GOV-02**: Configure update grouping and rate limits in `.github/dependabot.yml`.
- [ ] **GOV-03**: Close failing Dependabot PRs (#56, #60, #62, #64, #68) pending grouped scheduled updates.
- [ ] **GOV-04**: Move high-concurrency 32-peer mesh chaos tests (`test_mesh_gossip_32peers.rs`) from PR-blocking gate to dedicated nightly workflow (`0 0 * * *`).

### 🚀 Phase 1: UniFFI Control Plane Migration
- [ ] **UFFI-01**: Initialize `rust/src/streamify.udl` and wire `uniffi_bindgen` into Gradle build pipeline (`cargo-ndk`).
- [ ] **UFFI-02**: Canary rollout: Migrate resolver plumbing and pure candidate hashing functions to UniFFI.
- [ ] **UFFI-03**: Migrate Jam session control-plane operations (election, CRDT op apply, Merkle reconciliation) to UniFFI.
- [ ] **UFFI-04**: Delete duplicate Kotlin CRDT implementation, validating against `fixtures/jam_golden_vectors.json`.
