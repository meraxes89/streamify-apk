# Idea log — intake funnel

Daily ideas are normal in an early-stage hobby project. This file is the
**only** place an uncommitted idea belongs. It is append-only: never rewrite,
reorder or delete a row. A dated correction row is fine.

## The funnel

| Stage | Trigger | Cost | Where it goes next |
|---|---|---|---|
| `spark` | "What if we…?" | one row, no prose | usually nowhere — that is fine |
| `explored` | a branch, prototype or week of work | one row + best commit SHA | RFC if it needs review |
| `accepted` | committed as the way forward | row links to RFC / ADR / Sxx | implementation |
| `declined` | dropped after exploration | row + one concrete reason + SHA | nowhere; keep the "why not" |

Decline is a status, not deletion. The reason is the valuable part: it stops
the same idea being re-decided in three months.

## Format

Append rows to the table below with the next sequential `I-NNN` (never reuse
an ID). If an idea becomes a subsystem, record both IDs (`I-042 → S08`).

```markdown
| I-001 | 2026-11-25 | Idea in a few words | spark | — | — |
| I-002 | 2026-11-18 | Idea worked on for a week | explored → declined | One concrete, factual reason | RFC-001, 7f3a1c2 |
```

Rules:

- `spark` rows are one line. A same-day rejection still gets a line.
- `explored → declined` rows get exactly one sentence of *why* and the commit
  SHA of the best prototype. Git keeps the object reachable through the SHA,
  so delete the branch — do not keep twenty dead branches.
- Do not write an RFC for a declined idea.
- If the idea is accepted and expensive to reverse, write an RFC; once
  accepted, follow with a new ADR; then update the affected Sxx page.
- Review this file monthly (10 minutes). Check that every `declined` row has a
  reason and SHA. Never collapse old rows.

## Ledger

| ID | Date | Idea | Status | Why / next | Evidence |
|---|---|---|---|---|---|

<!-- doc-verified-against: ee2590133b8ccf610640278dca3b3374139573b4 -->
