# Engineering documentation

This directory is the authority for how Streamify is built and why. Start at
the [subsystem index](SYSTEMS.md) — it lists all 23 subsystems (S01–S23) with
their owner, boundaries, sources, specs, and decisions.

## The contract

There are exactly four authoritative document types. A document's directory
is its type; do not put an RFC in `architecture/` or an ADR in `archive/`.

| Type | Question it answers | Location | Lifecycle |
|---|---|---|---|
| **System page** | What is this subsystem, what does it own? | [systems/](systems/) | Updated with code in the same PR |
| **ADR** | Why was this choice made, what was rejected? | [adr/](adr/) | Immutable; superseded by a new ADR |
| **RFC** | What is proposed, and what are the options? | [rfc/](rfc/) | Draft → In Review → Accepted / Rejected |
| **Playbook** | How do I do X here? | [playbooks/](playbooks/) | Updated when the workflow changes |

Everything else is supporting material:

- [architecture/](architecture/) — the master map and 13 numbered engine deep-dives (L2 specs)
- [archive/](archive/) — retired planning docs, **never authoritative**
- [IDEA_LOG.md](IDEA_LOG.md) — append-only intake ledger for daily ideas; one line per spark
- `docs/nano/` — external audit artifacts when present (excluded from the integrity gate by design)

`README.md` at the repo root and the directory READMEs are navigational;
subsystem truth lives in `systems/` and `adr/`.

## Which document do I update?

| You changed… | Update… |
|---|---|
| Behaviour of a subsystem | its `Sxx` page + `doc-verified-against` pin, same PR |
| Dependency, schema, API contract, irreversible choice | **new ADR**; never edit an accepted one |
| A proposal that needs review before commitment | **new RFC**; if accepted, follow with a new ADR |
| A build/test/release workflow | the relevant playbook + CI doc |
| User-facing shipped behaviour | append to `CHANGELOG.md` (never rewrite) |
| A new idea, not yet committed | one row in `IDEA_LOG.md` |
| Superseded architecture | status flip + `HISTORICAL` banner + superseding ADR/RFC |

## Drift protocol

Every authoritative document carries a pin:

```
<!-- doc-verified-against: <40-char commit sha> -->
```

`scripts/verify_docs.py` checks links, required system-page frontmatter,
index coverage, source paths, and pin ancestry. `scripts/docs_ledger_check.py`
checks workflow/release references and denylists present-tense claims that
describe retired code. Both run in the Tier-1 preflight of
`.github/workflows/ci.yml`.

When code covered by a pinned doc changes, re-verify the claims and move the
pin **in the same commit**. A pin older than `origin/main` is reported as stale;
the report is explicit, not silent. If commit order cannot be determined
(divergent branches, shallow clone), say "can't tell" — never call it current.

## Architecture map

| Document | Scope |
|---|---|
| [SYSTEMS.md](SYSTEMS.md) | Canonical index of all 23 subsystems |
| [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | Master layer map; includes historical notes on the retired native core |
| [gpu/](gpu/README.md) | GPU engine capability tiers, phased plan, CI spec |
| [JAM_ENGINE_SPEC.md](architecture/JAM_ENGINE_SPEC.md) | CRDT jam engine, Merkle sync, wire protocols |
| [CRYPTO_SPEC.md](architecture/CRYPTO_SPEC.md) | ChaCha20-Poly1305 vault and cache cipher |
| [acoustic_embedding_contract_v1.md](architecture/acoustic_embedding_contract_v1.md) | Acoustic embedding contract |
| [engines/](architecture/engines/) | 13 numbered engine deep-dives (L2). Several were written against the retired native core — check the tree before trusting a claim. |

> **HISTORICAL:** the `rust/` workspace and `native/` C++20 DSP layer were
> removed (commit `50b937f7` on this lineage). Descriptions of them in
> `ARCHITECTURE.md`, ADR-001/002, and engine spec 02 are lineage records, not
> current state. Current audio truth is in [S08](systems/S08-dsp-and-acoustics.md)
> and [ADR-009](adr/ADR-009-kotlin-only-audio-pipeline.md).

## Decision records and proposals

- [ADR index](adr/README.md) — accepted decisions, MADR/Nygard format, immutable
- [RFC index](rfc/README.md) — proposals with explicit status, including rejected ones
- [IDEA_LOG.md](IDEA_LOG.md) — the intake funnel: spark → explored → committed

## Playbooks

| Playbook | Scope |
|---|---|
| [ADD_A_FEATURE.md](playbooks/ADD_A_FEATURE.md) | Adding features without breaking contracts; starts with subsystem ID |
| [CI_TESTING_GUIDE.md](playbooks/CI_TESTING_GUIDE.md) | Hub-and-spoke CI DAG, shards, log inspection |
| [CODEBASE_GUIDE.md](playbooks/CODEBASE_GUIDE.md) | Folder-by-folder codebase tour |
| [BACKEND_DB_SYNC.md](playbooks/BACKEND_DB_SYNC.md) | Supabase ↔ repo reconciliation runbook |

## Archive

`archive/` preserves lineage and audit material; nothing in it is
authoritative. Retired roadmaps, status ledgers, external reviews, and the
pre-multiplatform Rust analysis live there.

<!-- doc-verified-against: 1d387b7ed4d5f3d4cb13f5f2681eb2bcbf1bb05e -->
