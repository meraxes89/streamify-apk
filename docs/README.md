# Engineering documentation

This directory holds the authoritative systems documentation. Every
authoritative document carries a `doc-verified-against` pin (the commit whose
tree it was verified against). `scripts/verify_docs.py` checks each pin with
`git merge-base --is-ancestor` against `origin/main` and fails when a pin is
not an ancestor (bogus pin) or when main has advanced past it (stale document).

## Structure

```
docs/
├── README.md            This index
├── architecture/        System map, engine deep-dives, technical plans
│   ├── ARCHITECTURE.md  Master layer map (authoritative)
│   └── engines/         13 subsystem deep-dives
├── adr/                 Architecture Decision Records (MADR/Nygard format)
├── playbooks/           Developer workflows and runbooks
└── archive/             Retired planning docs, status ledgers, merger logs
```

## Architecture

| Document | Scope |
|---|---|
| [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | Master map: Compose UI -> Koin -> Rust core -> C++20 ringbuffer -> platform HALs; hybrid SQLite/Supabase sync; measured invariants |
| [gpu/](gpu/README.md) | Sovereign GPU Engine: Skia/HWUI pipeline, 4-tier capability matrix, 2-engineer phased plan, and CI spec |
| [JAM_ENGINE_SPEC.md](architecture/JAM_ENGINE_SPEC.md) | CRDT jam engine, Merkle state sync, wire protocols |
| [CRYPTO_SPEC.md](architecture/CRYPTO_SPEC.md) | ChaCha20-Poly1305 vault and cache cipher (SEC-06) |
| [acoustic_embedding_contract_v1.md](architecture/acoustic_embedding_contract_v1.md) | 128-dim acoustic embedding contract (Rust + UDL) |
| [MULTIPLATFORM_PLAN_27_10.MD](architecture/MULTIPLATFORM_PLAN_27_10.MD) | Multiplatform master plan: sovereign core law, dual UniFFI codegen, DacPresentationClock invariant |
| [A_TIER_PLAN_26_10.MD](architecture/A_TIER_PLAN_26_10.MD) | A+ tier plan: contract-first UniFFI, RT audio isolation, DSP retention, parity invariants |
| [engines/](architecture/engines/) | 13 numbered subsystem deep-dives (stream resolution, DSP, lyrics alignment, jam, storage, CI security, playback dataplane) |

## Architecture Decision Records

Decisions are recorded in [adr/](adr/) using the MADR template with Nygard
context sections. Current records:

- [ADR-001: Sovereign Rust/C++ core separation](adr/ADR-001-sovereign-rust-cpp-core-separation.md)
- [ADR-002: Lock-free SPSC ringbuffer over the JVM heap](adr/ADR-002-lock-free-spsc-ringbuffer.md)
- [ADR-003: Hybrid SQLite WAL + Supabase sync](adr/ADR-003-hybrid-sqlite-supabase-sync.md)
- [ADR-004: Inversion of control via streamify-modules](adr/ADR-004-ioc-streamify-modules.md)

## Playbooks

| Playbook | Scope |
|---|---|
| [ADD_A_FEATURE.md](playbooks/ADD_A_FEATURE.md) | Adding features without breaking ABI or JNI boundaries |
| [CI_TESTING_GUIDE.md](playbooks/CI_TESTING_GUIDE.md) | Hub-and-spoke CI DAG, shards, log inspection |
| [CODEBASE_GUIDE.md](playbooks/CODEBASE_GUIDE.md) | Folder-by-folder codebase tour |
| [BACKEND_DB_SYNC.md](playbooks/BACKEND_DB_SYNC.md) | Supabase to repo reconciliation runbook |
| [ANDROID_RUST_AUDIO_CUTOVER.md](playbooks/ANDROID_RUST_AUDIO_CUTOVER.md) | Android to Rust audio path cutover |
| [gate4_fluidity_runbook.md](playbooks/gate4_fluidity_runbook.md) | Fluidity gate runbook |

## Archive

`archive/` preserves records for lineage and audit; nothing in it is
authoritative. In November 2026 the phased-roadmap and status-ledger documents
were retired there: `ROADMAP_PHASES.md`, `AHEAD.md`, `BEHIND.md`,
`A_Checkbox.md`, `MULTIPLATFORM_CHECKBOX.MD`, `SWIFT_MIGRATION_PLAN.MD`, the
external review narratives (`resp5_architect_review.md`,
`resp6_ci_review.md`), the Feishin adoption audit, the pre-multiplatform Rust
integration analysis (`rust_integration_deep_dive.md`), and the legacy
detailed manual (`README_DETAILED.MD`). Historical merger logs and build logs
were already archived there. Current architecture truth lives in
[architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) and the ADRs.

---
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
