# Architecture Decision Records

This directory records the decisions that shaped Streamify's architecture.
Each ADR follows the MADR template with Nygard-style context: the forces in
play at decision time, the alternatives considered and rejected, and the
consequences accepted. ADRs are immutable once accepted; superseding a
decision requires a new ADR that links back.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-sovereign-rust-cpp-core-separation.md) | Sovereign Rust/C++ core separation | Accepted |
| [ADR-002](ADR-002-lock-free-spsc-ringbuffer.md) | Lock-free SPSC ringbuffer over the JVM heap | Accepted |
| [ADR-003](ADR-003-hybrid-sqlite-supabase-sync.md) | Hybrid SQLite WAL + Supabase sync | Accepted |
| [ADR-004](ADR-004-ioc-streamify-modules.md) | Inversion of control via streamify-modules | Accepted |
| [ADR-005](ADR-005-vivo-package-identity-and-oauth-resolution.md) | Vivo package identity, Web OAuth callback, and resilient update resolution | Accepted |
| [ADR-006](ADR-006-unified-hybrid-sync-v2.md) | Unified hybrid sync architecture v2 & schema alignment | Accepted |
| [ADR-007](ADR-007-user-graph-alignment-and-cross-device-continuity.md) | User graph alignment and cross-device continuity | Accepted |
| [ADR-008](ADR-008-server-contract-v4-and-sync-dataplane-hardening.md) | Server Contract v4 and sync data-plane hardening | Accepted |
| [ADR-009](ADR-009-kotlin-only-audio-pipeline.md) | Kotlin-only audio pipeline | Accepted (supersedes ADR-001/002 for shipped audio) |
| [ADR-010](ADR-010-sovereign-gpu-engine.md) | Sovereign GPU Engine capability matrix over Skia (Apple thermal/ProMotion/TBDR) | Accepted |

## Format

```
# ADR-NNNN: <short title in the infinitive or noun form>
* Status: Proposed | Accepted | Superseded by ADR-NNNN
* Date: YYYY-MM-DD
* Verified against: <commit> (see staleness protocol below)

## Context       -- forces, constraints, measured evidence, alternatives rejected
## Decision      -- the change, with the concrete mechanisms chosen
## Consequences  -- positive, negative, and required follow-up work
## Compliance    -- how the decision is enforced in CI or code review
```

## Staleness protocol

Every authoritative document (each ADR, each directory README,
`docs/architecture/ARCHITECTURE.md`) carries an HTML comment pin:

```
<!-- doc-verified-against: <40-char commit sha> -->
```

`scripts/verify_docs.py` enforces two rules with
`git merge-base --is-ancestor`:

1. A pin that is not an ancestor of `origin/main` is an error: the document
   references a commit that does not exist in the integration history.
2. A pin older than the tip of `origin/main` is reported as stale: code has
   advanced past the verification point and the document must be re-verified
   (and its pin moved) before or with the next change to the area it covers.

When you change code covered by a pinned document, re-verify the document's
claims against the new tree and update the pin in the same commit.

---
<!-- doc-verified-against: 5286b8486a114eb58d1ce8355baefb6b98602555 -->
