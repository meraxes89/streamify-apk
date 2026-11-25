---
id: S23
title: CI, release & docs
tier: engineering
status: active
owner: tooling
sources: [.github/workflows/, scripts/, docs/]
tests: []
---

# S23 — CI, release & docs

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Eleven GitHub workflows (Tier-1 preflight, platform shards, CodeQL, nightly matrix, release), release scripts, doc-integrity gates and the version manifest.

## Does not own

- Product behaviour — this subsystem enforces how changes are verified and shipped.

## Current truth

- Doc integrity is part of Tier-1 preflight: `docs_ledger_check.py` and `verify_docs.py` both run there.

## Interfaces

- PR → preflight → impact-filtered shards → release artifacts → update manifest.

## Decisions and specs

- Spec: [CI_TESTING_GUIDE.md](../playbooks/CI_TESTING_GUIDE.md)

## Known limits / debt

- 17 pinned docs are currently stale relative to `origin/main`; the gate reports them without failing.

## Refinement backlog

- Move pins forward as each doc's area is re-verified; do not bulk-bump without review.

<!-- doc-verified-against: ee2590133b8ccf610640278dca3b3374139573b4 -->
