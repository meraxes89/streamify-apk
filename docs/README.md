# `docs/` — Engineering Documentation & Architecture Hub

Welcome to the Streamify engineering documentation portal. This directory is organized into three distinct tiers:

```
docs/
├── architecture/   # System blueprints, master plans, invariants & 12 engine deep-dives
├── playbooks/      # Developer workflows, codebase tour & CI testing manuals
└── archive/        # Historical lineage mergers, post-mortems & superseded logs
```

---

## 🏛️ 1. Architecture & System Design (`docs/architecture/`)

Start here for system maps, core invariants, and subsystem deep-dives:

| Document | Purpose / Scope |
|---|---|
| [`architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) | **System Map** — High-level architecture, module dependencies, and boundaries. |
| [`architecture/A_TIER_PLAN_26_10.MD`](architecture/A_TIER_PLAN_26_10.MD) | **Master Plan** — The A+ Tier overhaul roadmap (Security, Amputation, UniFFI). |
| [`architecture/A_Checkbox.md`](architecture/A_Checkbox.md) | **Consensus Ledger** — Approved architectural decisions & task tracking checklist. |
| [`architecture/JAM_ENGINE_SPEC.md`](architecture/JAM_ENGINE_SPEC.md) | P2P CRDT Jam engine architecture, Merkle state sync, and wire protocols. |
| [`architecture/rust_integration_deep_dive.md`](architecture/rust_integration_deep_dive.md) | Rust core engine, JNI boundary semantics, and memory safety analysis. |

### Subsystem Engine Deep-Dives (`docs/architecture/engines/`)
1. [`01_stream_resolution.md`](architecture/engines/01_stream_resolution.md) — Multi-tier streaming pipeline, candidate deduplication, and resolution fallback.
2. [`02_dsp_acoustic.md`](architecture/engines/02_dsp_acoustic.md) — Float PCM mastering, LUFS normalization, and true-peak limiting.
3. [`03_lyrics_alignment.md`](architecture/engines/03_lyrics_alignment.md) — Neural lyrics synchronization, phonetic drift correction, and token matching.
4. [`04_neural_continuum.md`](architecture/engines/04_neural_continuum.md) — Predictive pre-caching, circadian transition curves, and offline pre-fetching.
5. [`05_mesh_airdrop.md`](architecture/engines/05_mesh_airdrop.md) — Wi-Fi Direct & BLE zero-configuration transport mesh.
6. [`06_jam_distributed.md`](architecture/engines/06_jam_distributed.md) — 32-node democratic voting CRDT, LWW registers, and vector clocks.
7. [`07_database_storage.md`](architecture/engines/07_database_storage.md) — Path-keyed SQLite connection pools and WAL-mode transaction lanes.
8. [`08_ingestion_scraper.md`](architecture/engines/08_ingestion_scraper.md) — Track metadata scraping, ISRC matching, and fuzzy resolution.
9. [`09_search_graph.md`](architecture/engines/09_search_graph.md) — Local search index, multi-tier ranking, and fuzzy token scoring.
10. [`10_telemetry_profiler.md`](architecture/engines/10_telemetry_profiler.md) — Real-time performance monitors, frame pacing counters, and battery diagnostics.
11. [`11_playback_ui.md`](architecture/engines/11_playback_ui.md) — 120Hz liquid sheet morphing, predictive back, and haptic feedback.
12. [`12_server_security.md`](architecture/engines/12_server_security.md) — Security boundaries, token rotation, and supply chain attestation.

---

## 🛠️ 2. Developer Playbooks & Reference (`docs/playbooks/`)

Practical how-to guides for daily development and test workflows:

| Playbook | Purpose / Scope |
|---|---|
| [`playbooks/ADD_A_FEATURE.md`](playbooks/ADD_A_FEATURE.md) | Step-by-step cookbook for adding new features without breaking ABI or JNI boundaries. |
| [`playbooks/CODEBASE_GUIDE.md`](playbooks/CODEBASE_GUIDE.md) | File-by-file and folder-by-folder codebase tour with LOC counts. |
| [`playbooks/CI_TESTING_GUIDE.md`](playbooks/CI_TESTING_GUIDE.md) | How the Extreme Test Matrix works, shard topologies, and log inspection. |
| [`playbooks/TODO.md`](playbooks/TODO.md) | Authoring tracker for ongoing documentation and spec updates. |

---

## 📦 3. Archive & Historical Logs (`docs/archive/`)

Historical records preserved for lineage and audit trails:
- [`archive/MASTER-STREAM-RESOLUTION.md`](archive/MASTER-STREAM-RESOLUTION.md) — Pre-v1 unified stream resolution specification (194KB).
- [`archive/PR-jamv3-streamify-lineage-merge.md`](archive/PR-jamv3-streamify-lineage-merge.md) — Lineage merge documentation for Jam v3.
- [`archive/RELEASE-adblog-merge.md`](archive/RELEASE-adblog-merge.md) — ADB debug log merge records.
- [`archive/why_we_removed_python.md`](archive/why_we_removed_python.md) — Historical rationale on migrating off embedded Python runtime.
- [`archive/GOALS_v1.md`](archive/GOALS_v1.md) — Original product roadmap goals.
- [`archive/DIAGNOSTIC-v1.0.1.md`](archive/DIAGNOSTIC-v1.0.1.md) — Historical diagnostic run.
