# F0 — Adopt the Feishin fork as the Streamify web UI

Status: ACCEPTED (P0, 2026-11-15)
Owners: web UI track
Note: this decision doc was referenced by the P0 kickoff but was not present in
the repo when P0 started (checked `main`, all remote branches, and open PRs).
P0 materialized it from the kickoff constraints so the paper trail exists for
P1–P5. If the original author surfaces a conflicting version, reconcile here.

## Context

The hand-rolled Streamify web UI (front-end/web, the `ws-w/*` line) proved the
engine port but is not a sustainable player surface. Feishin (GPL-3.0, Mantine
9, ~143k TS/TSX, upstream ships a supported web build via Vite + nginx) gives
us a mature player: dual react-player engine, Navidrome/Subsonic/Jellyfin
controllers behind a per-server-type API abstraction, and per-server-type UI
gating. We fork it and teach it to speak Streamify.

## Decision

- Fork `jeffvli/feishin` → `meraxes89/streamify-feishin` (done in P0).
- Our trunk is `main`, started from **tag `v1.17.0`** — not upstream's
  `development` head. Upstream moves ~180 commits/month; release tags are
  monthly and stable. **Tag-only, merge-never-rebase** is the sync policy.
- The per-server-type controller pattern is the integration seam: a
  registered-but-stubbed controller hides its affordances via the UI's
  existing gating. That is upstream's established pattern, not a hack.
- One-time upstream diff (registration) lands in P0 so no later phase re-opens
  upstream files. Every divergence is ledgered in the fork's `PATCHES.md`.

## Binding constraints

1. The fork stays **GPL-3.0** with source available.
2. **Merge, never rebase; tags only** for upstream syncs.
3. Secrets stay in deployment configuration, never in the tree.
4. New Streamify code goes in **new directories only**. Never touch
   `features/login/`, `features/servers/`, `components/`, themes — with the
   sole exception of the frozen registration lines forced by exhaustive
   `Record<ServerType, …>` types (see PATCHES.md entries 5–6).
5. Conventional commits (commitlint runs on PR ranges and pushes in the
   fork's CI).

## Pre-approved future touch list

- `src/renderer/app/app-outlet.tsx` / `src/renderer/router/routes.ts` — P1
  auth gate for Streamify sessions.
- `package.json` — our dependencies.
- `settings.js.template` — Streamify deployment variables.

Anything else goes in new directories.

## Consequences

- The hand-rolled `front-end/web` remains the shipping surface until the
  feishin line replaces it (F2 tracks the cutover); both coexist under
  `front-end/` during the transition.
- Web CI for the fork is web-scoped (lint + `build:web`); the heavyweight
  matrix stays in the fork's upstream workflows untouched.
- Vendoring mechanics are owned by F1 (single-commit imports with subtree
  metadata).
