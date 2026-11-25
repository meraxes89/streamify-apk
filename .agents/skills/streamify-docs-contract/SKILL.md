---
name: streamify-docs-contract
description: Enforces Streamify's documentation contract for any code change, feature, ADR, RFC or idea. Use when adding a feature, changing behaviour in any S01-S23 subsystem, deciding where to record a choice, writing or updating docs, onboarding to the repository, or when documentation may be stale. Routes the change to the correct document type and runs the Tier-1 documentation gates.
---

# Streamify documentation contract skill

This skill is the executable form of `docs/README.md`. When the two disagree,
the docs are the source of truth — update this skill in the same PR.

## Non-negotiable rules

1. **Read `docs/SYSTEMS.md` first.** Every subsystem has a stable ID `S01`–`S23`.
   Never renumber or reuse an ID; names may change, IDs do not.
2. **Directory equals type.** System page → `docs/systems/`; decision → `docs/adr/`;
   proposal → `docs/rfc/`; how-to → `docs/playbooks/`; history → `docs/archive/`.
   No RFC in `architecture/`, no ADR in `archive/`, no current-state claims in `archive/`.
3. **ADRs are immutable.** To change a decision, write a new ADR that says
   "Supersedes ADR-NNN". Never edit an accepted one. ADR numbers are never reused;
   the former `ADR-005-unified-hybrid-sync-v2.md` is now ADR-006.
4. **RFCs are proposals.** Status moves `Draft → In Review → Accepted | Rejected |
   Superseded`. An accepted RFC is followed by a new ADR. Keep rejected RFCs —
   the "why not" is the valuable part.
5. **`docs/IDEA_LOG.md` is append-only.** One line for a spark; one reason plus
   commit SHA for a declined experiment. Delete the dead branch, not the row.
   Never write a per-idea document.
6. **`CHANGELOG.md` is append-only.** Never rewrite or collapse earlier entries.
7. **The Rust/C++ DSP core was retired** (commit `50b937f7`, ADR-009). README and
   engine spec 02 contain historical prose. Never describe a native DSP core,
   LUFS engine, true-peak limiter or SPSC ringbuffer as current shipped code.
   Adding any native core requires a new RFC and ADR measuring the capability gained.
8. **Pins move only with verification.** `<!-- doc-verified-against: <sha> -->` is
   updated in the same commit as the code it describes. Never bulk-bump pins to
   silence a stale warning; 15 pre-existing stale pins are reported, not errors.

## Routing table

| The change is… | Write/update… |
|---|---|
| Behaviour of a subsystem | its `docs/systems/Sxx-*.md` page **and pin**, same PR |
| Dependency, schema, API contract, irreversible choice | new ADR; link the RFC if there was one |
| Proposal spanning subsystems or needing review | new RFC, added to `docs/rfc/README.md` table |
| Build/test/release workflow | the relevant playbook + CI doc |
| User-facing shipped behaviour | append to `CHANGELOG.md` |
| A new idea, not committed | one row in `docs/IDEA_LOG.md` |
| Superseded architecture | status flip + `HISTORICAL` banner + superseding ADR |

## Workflow for any code change

1. Find the subsystem ID in `docs/SYSTEMS.md`; open that page.
2. If this is an uncommitted idea, append the `IDEA_LOG.md` row first.
3. Decide from the routing table — do not write a document type that does not fit.
4. Change code, page and pin in the same commit. Add or update tests.
5. Run both gates from the repo root:
   - `python3 scripts/verify_docs.py`
   - `python3 scripts/docs_ledger_check.py`
6. In the PR: name the Sxx ID, state which document type you updated, and confirm
   that an irreversible choice has a new ADR.

## ADR recipe

File: `docs/adr/ADR-NNN-short-title.md`, next free number.

```
# ADR-NNN: <title>
* Status: Proposed | Accepted | Superseded by ADR-NNN
* Date: YYYY-MM-DD
* Verified against: <40-char sha>

## Context       forces, constraints, measured evidence, alternatives rejected
## Decision       the concrete mechanism chosen
## Alternatives Considered   each with pros, cons and why rejected
## Consequences   positive, negative, required follow-up
## Compliance     how CI/code review enforces it
```

Add the row to `docs/adr/README.md` in the same PR.

## RFC recipe

File: `docs/rfc/RFC-NNN-short-title.md`, next free number.

Include: problem, constraints, options with pros/cons, recommendation, cost,
migration path, test plan and open questions. Add the row (with a direct file
link) to `docs/rfc/README.md`, including status and the resulting ADR if accepted.

## Idea funnel recipe

Append one row to `docs/IDEA_LOG.md`:

```
| I-NNN | YYYY-MM-DD | Idea in a few words | spark | — | — |
| I-NNN | YYYY-MM-DD | Explored for a week, dropped | explored → declined | One factual reason | 7f3a1c2 |
```

Status values: `spark`, `explored`, `accepted`, `declined`. An accepted idea links
to its RFC / ADR / Sxx ID. A declined row needs exactly one reason and the best
prototype commit SHA.

## Drift check (when a doc may be stale)

Decide from version control, not confidence:

```
code=$(git log -1 --format=%H -- <code path>)
doc=$(git log -1 --format=%H -- <doc path>)
if [ "$code" = "$doc" ]; then echo "current: changed together"
elif git merge-base --is-ancestor "$doc" "$code"; then echo "stale: code moved after doc"
elif git merge-base --is-ancestor "$code" "$doc"; then echo "current: doc moved after code"
else echo "can't tell"; fi
```

Divergent history or a shallow clone means "can't tell". Report that; never
call the doc current.

## Junior path (10 minutes)

1. `docs/README.md` — the four types and the decision table.
2. `docs/SYSTEMS.md` — find your subsystem.
3. Its `Sxx` page — boundaries, current truth, backlog.
4. `docs/adr/README.md` — why the current shape exists.
5. `docs/playbooks/ADD_A_FEATURE.md` — step 0, then the recipe.

## Verification checklist

- [ ] Subsystem ID named in the PR and commit
- [ ] Correct document type used; no per-idea files
- [ ] Sxx page and pin updated in the same commit as code
- [ ] New ADR for irreversible choices; old ADRs untouched
- [ ] RFC added to its index with status; rejected RFCs kept
- [ ] `IDEA_LOG.md` / `CHANGELOG.md` appended, not rewritten
- [ ] No present-tense claims about the retired native DSP core
- [ ] Both documentation gates pass locally
- [ ] Unknown staleness reported as "can't tell", never "current"
