# RFCs — proposals, not decisions

An RFC records a proposal before commitment: the problem, constraints,
options, cost, migration path and open questions. An RFC may be rejected.
An accepted RFC is followed by a new [ADR](../adr/README.md), which is
immutable. Keep rejected RFCs — the "why not" is the valuable part.

| ID | Title | Status | Decision | Owner |
|---|---|---|---|---|
| [RFC-001](RFC-001-sovereign-gpu-offload.md) | Sovereign direct GPU offload | Proposal (not accepted) | — | Core engineering |
| [RFC-002](RFC-002-backend-cloud-graph.md) | Backend cloud graph / V4 schema + contract v3 | Draft for review | [ADR-008](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md) (partial) | Backend architecture |

## Rules

- File an RFC when a change spans multiple [subsystems](../SYSTEMS.md), has
  materially different approaches, or needs explicit rejection on the record.
- Do **not** RFC a bug fix, a spark, or a decision already made — sparks go to
  [IDEA_LOG.md](../IDEA_LOG.md), decisions go to an ADR.
- Filenames are `RFC-NNN-short-title.md`; numbers are never reused.
- Status vocabulary: `Draft` → `In Review` → `Accepted` / `Rejected` /
  `Superseded by RFC-NNN`.
- New RFCs must be added to this table in the same PR.

Historical paths `docs/BACKEND_ARCHITECTURE_RFC.md` and
`docs/architecture/GPU_DIRECT_DRIVER_OFFLOAD_RFC.md` are pointer stubs only.

<!-- doc-verified-against: 1d387b7ed4d5f3d4cb13f5f2681eb2bcbf1bb05e -->
