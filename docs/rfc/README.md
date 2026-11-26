# RFCs — proposals, not decisions

An RFC records a proposal before commitment: the problem, constraints,
options, cost, migration path and open questions. An RFC may be rejected.
An accepted RFC is followed by a new [ADR](../adr/README.md), which is
immutable. Keep rejected RFCs — the "why not" is the valuable part.

| ID | Title | Status | Decision | Owner |
|---|---|---|---|---|
| [RFC-001](RFC-001-sovereign-gpu-offload.md) | Sovereign direct GPU offload | Draft | — | Core engineering |
| [RFC-002](RFC-002-backend-cloud-graph.md) | Backend cloud graph / V4 schema + contract v3 | Draft for review | [ADR-008](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md) (partial) | Backend architecture |
| [RFC-003](RFC-003-kmp-spatial-navigation.md) | KMP spatial navigation, predictive back & shared elements | Draft | — | Core engineering |

## Rules

- File an RFC when a change spans multiple [subsystems](../SYSTEMS.md), has
  materially different approaches, or needs explicit rejection on the record.
- Do **not** RFC a bug fix, a spark, or a decision already made — sparks go to
  [IDEA_LOG.md](../IDEA_LOG.md), decisions go to an ADR.
- Filenames are `RFC-NNN-short-title.md`; numbers are never reused.
- Status vocabulary: `Draft` → `In Review` → `Accepted` / `Rejected` /
  `Superseded by RFC-NNN`.
- New RFCs must be added to this table in the same PR.

Former paths `docs/BACKEND_ARCHITECTURE_RFC.md` and
`docs/architecture/GPU_DIRECT_DRIVER_OFFLOAD_RFC.md` are retired; both RFCs now
live only in this directory.

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
