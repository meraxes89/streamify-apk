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

- Verification pins are checked by `verify_docs.py` (both ancestry and `git cat-file -e <pin>:<doc>`); keep pins in lockstep whenever a doc or its owned subsystem changes.

## Refinement backlog

- Move pins forward as each doc's area is re-verified; do not bulk-bump without review.

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
