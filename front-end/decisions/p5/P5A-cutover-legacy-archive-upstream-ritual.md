# P5A — Docker cutover, CanaryConsole in /debug, the legacy web archive & the upstream tag-merge ritual

**Status:** COMPLETE. Branch `ws-w/p5a-cutover-legacy-archive`, PR against
`main` (rebased after #192/#193). CI: Feishin Web CI (lint + typecheck +
tests + build:web), Unified CI preflight (now carrying the canonical
resolver integrity check), CI gate (blocking); wasm/wasm-tests/relay stay
green in Web Build.

## What landed

1. **The two [P0] Docker/runtime cutover bugs + the production compose.**
2. **The CanaryConsole mounted inside the gated `/debug` route** (the
   retired web-app canary page's only live consumer).
3. **`front-end/web` archived to `legacy/front-end-web`** — full history
   preserved, every active lane decoupled; the feishin resolver mirror
   promoted to the canonical source of truth.
4. **PATCHES.md rebuilt as the per-file conflict map** and this record
   documents the two-level, fork-first upstream merge ritual.

---

## A. The Docker & production runtime contract

### The Dockerfile:28 quote bug (P0)

The P2-A append (#186) dropped the closing quote on
`STREAMIFY_SUPABASE_ANON_KEY`:

```dockerfile
# broken (2026-11-12 .. P5-A):
ENV STREAMIFY_AUTH="" STREAMIFY_SUPABASE_URL="" STREAMIFY_SUPABASE_ANON_KEY=" STREAMIFY_RELAY_BASE=""
```

One envsubst token, not five variables: nginx's `envsubst` never
substituted any of them, and the literal `${STREAMIFY_SUPABASE_ANON_KEY}`
style placeholders leaked into `settings.js`. The bug hid because
`session.ts`'s window reads already filter `${STREAMIFY_…}` placeholders as
unset — the app degraded to its anonymous/dev fallbacks instead of failing
loudly. The P5 audit caught it via PATCHES.md patch 20 vs the actual file.

**Fixed line (P5-A):**

```dockerfile
ENV STREAMIFY_AUTH="" STREAMIFY_SUPABASE_URL="" STREAMIFY_SUPABASE_ANON_KEY="" STREAMIFY_RELAY_BASE="" STREAMIFY_DEV_ACCESS_TOKEN=""
```

`STREAMIFY_DEV_ACCESS_TOKEN` is added because `settings.js.template` has
referenced it since P1B (#184) while the ENV never defined it — same
envsubst-masking bug class, caught in the same audit.

### The relayBase.ts placeholder guard (P0)

`relayBaseCandidate()` now filters **every** source (the
`window.STREAMIFY_RELAY_BASE` knob, the `?relay=` param, the build env
`VITE_RELAY_BASE_URL`) through `isUnexpandedPlaceholder()` — a value that
starts with `${` and ends with `}` reads as unset, never as a live relay
URL. This generalizes session.ts's `${STREAMIFY_…}` filter to the relay
knob: a relay base can never start with `${`, so the rejection has no
false positives. A deployment that leaves the variable unset now gets the
designed degraded mode (relay off → direct lane only), not a POST to a URL
whose host is the literal `${STREAMIFY_RELAY_BASE}`.

### docker-compose.yaml — the production contract

Production runs THIS fork via Docker; the compose no longer boots stock
`ghcr.io/jeffvli/feishin:latest`:

| knob | value | why |
|---|---|---|
| `build.context` / `image` | local `Dockerfile` / `streamify-feishin:latest` | the image always matches the checked-out tree |
| `SERVER_TYPE` / `SERVER_NAME` | `streamify` / `streamify` | the fork's own registration |
| `SERVER_LOCK` | `true` | locked to the Streamify type; username/password are the only toggles |
| `ANALYTICS_DISABLED` | `true` | no Umami |
| `STREAMIFY_AUTH` | `${STREAMIFY_AUTH:-true}` | `'false'` is the explicit opt-out |
| `STREAMIFY_SUPABASE_URL` / `_ANON_KEY` | host pass-through | the Supabase project + public anon key (RLS is the fence) |
| `STREAMIFY_RELAY_BASE` | host pass-through | the Cloudflare worker relay; empty = direct-lane only |
| `STREAMIFY_DEV_ACCESS_TOKEN` | host pass-through | dev-only; empty in real deployments |

`SERVER_URL`/`REMOTE_URL` stay empty: the Streamify flow reads its Supabase
endpoint from `STREAMIFY_SUPABASE_URL` (settings.js), not the stock server
URL knob. Secrets live in deployment configuration, never in the tree.

---

## B. CanaryConsole inside `/debug`

The residential canary console (`front-end/canary`, W0.1b — the 10-client ×
2-video probe matrix + the R-C conditions, posting to Supabase
`canary_results`, anon INSERT) loses its retired web-app page
(`canary.html`) with the archive. Its live consumer is now the `/debug`
diagnostics route from #192, where it **inherits the access gate for
free**: DEV mode, the persisted developer-mode grant, or `?debug=1` +
authenticated admin; everyone else is redirected to `/`. The pure ruling
stays in `features/streamify-debug/hooks/debug-gate-ruling.ts` (directly
unit-tested); the route keeps the canary mount on the far side of the
`if (!gate.allowed) return <Navigate replace to="/" />` early return
(locked by test).

**The wiring** (`features/streamify-debug/components/canary-panel.tsx`):

- `supabase` — the ACTIVE session's Supabase URL + anon key
  (`resolveStreamifySession` over the synthetic server entry's credential),
  so "Post results to Supabase" targets the project this session is signed
  into, not a build-time constant. Without a session the console degrades
  to its copy-rows-JSON fallback.
- `defaultRelayBase` — `relayBaseCandidate()`, i.e. the relay production
  playback actually rides, as the editable input's starting value.
- The console's `auth.store` import is DIRECT (not the store barrel): the
  barrel drags the player store's radio/logger chain, which touches
  `localStorage` at module scope — fine in a browser, fatal for the
  network-free node test lane.

**Resolution contract** (no `legacy/front-end-web/canary.html`, no package
link): `@streamify/canary` aliases to `front-end/canary/src` in
`web.vite.config.ts` + `vitest.config.ts` (+ `resolve.dedupe
['react','react-dom']` — the source sits outside the feishin root, and
without dedupe vite resolves a phantom `<root>/react` module for it), and
in `tsconfig.web.json` paths (`@streamify/canary/*`, plus `react` /
`react/jsx-runtime` pinned to feishin's `@types/react` so the
outside-the-root sources typecheck under the feishin program with **no
front-end npm install in CI** — Feishin CI only runs pnpm inside
`front-end/feishin`). ESLint does not lint the canary tree (outside its
cwd); the feishin web build bundles it with its own CSS.

The canary package gained an optional host-injection seam
(`CanaryConsoleProps` + the `postResults(report, override?)` param, with
the same trailing-slash normalization `supabaseEnv()` applies) —
backward-compatible; the archived page was its only other consumer.

---

## C. The legacy archive & the canonical resolver

### Layout

```
legacy/
  front-end-web/     # git mv of front-end/web — full history, read-only
```

`legacy/front-end-web` is archaeology: a banner README marks it archived,
and it is excluded from every active lane — the `front-end` npm workspaces
(now `relay` + `canary` only), Feishin CI, Web Build, CodeQL
(`paths-ignore: legacy/**`), every linter and TypeScript project. Nothing
may import from it (locked by test in the corpus-runner integrity suite).

### The resolver mirror is now canonical

`front-end/feishin/src/renderer/api/streamify/playback/resolver/` (10
vendored files + the fork-authored `lib/relayBase.ts`) is the **single
source of truth** for resolution behavior. The two-stream rule ("resolution
fixes land in front-end/web ONLY") is retired with the second stream —
fixes now land in the feishin tree directly.

What replaced each retired guardrail:

| retired | replacement |
|---|---|
| `scripts/sync_feishin_resolver.py` (+ `--check`) | `scripts/check_feishin_resolver.py` — self-contained existence / provenance-header / import-integrity / contract check, wired into `ci.yml` preflight (every event, ~1 s) |
| `web-build.yml` job `resolver-mirror-sync` | the preflight integrity check above (Web Build keeps only wasm / wasm-tests / relay) |
| `front-end/web` unit suites (resolver fixture lock, playback spine) | the feishin fork's own suites (`front-end/feishin` `__tests__/`, 223+ network-free tests) + the live daily corpus |
| `web-deploy.yml` (GitHub Pages) | Docker (`front-end/feishin/Dockerfile` + `docker-compose.yaml`) |
| `front-end/web` corpus runner (`npm run test:corpus -w web`) | `front-end/feishin/scripts/sabr-corpus.test.ts` + `vitest.corpus.config.ts` + `pnpm run test:corpus` — same corpus (the canary/g3b canonical IDs), same relay, same report shape, same 06:30 UTC cadence (`resolver-canary.yml` now installs with pnpm exactly like Feishin CI) |

The integrity check enforces, on the canonical tree: all 11 files present;
each opens with a provenance header naming its own path; relative imports
resolve inside the tree; bare imports limited to
`youtubei.js`/`googlevideo`/`bgutils-js`; no absolute `/@/` aliases (the
corpus runner must stay consumable standalone); no `lib/engine` revival
(the retired wasm-bridge seam); the two `relayBase` rewrites in place; and
the `getStreamUrl`/`recentResolveLog` export contract the engine + corpus
consume.

---

## D. PATCHES.md & the two-level upstream merge ritual

### The per-file conflict map

`front-end/feishin/PATCHES.md` was rewritten from the append-only
per-patch history into the authoritative **per-file** map: one entry per
upstream file we carry changes in (section A — what we carry, phase
attribution, conflict surface), the fork-owned directory inventory
(section B), the CI record (section C), and the ritual summary (section D).
The three historical discrepancies fixed by the consolidation (patch 3a's
P0-vs-P1/P1B phasing, patch 20's clean-append claim vs the broken
Dockerfile quote, and #190's unrecorded fork-owned additions) are recorded
in the file's preamble, not papered over.

### The ritual (fork-first, two levels)

**Baselines:** upstream `v1.17.0` = `5c169aca8d55` (the P0 fork point);
`development` = `e36c5a95`; **`v1.18.0` is not tagged yet** — when it
lands, run this ritual against it.

**Level 1 — the fork repo (`meraxes89/streamify-feishin`), where upstream
merges happen:**

```bash
git fetch upstream --tags
git merge v<next-tag>          # three-way merge on the fork's main; NEVER rebase
# resolve conflicts using PATCHES.md section A as the per-file map
pnpm install && pnpm run lint && pnpm run test && pnpm run build:web   # must be green
# land the merge on the fork's main (its own CI gates it)
```

Why merge-only at this level: the fork carries ~30 upstream files with
minimal seams; three-way merge keeps upstream's history and produces the
exact per-file conflict surfaces PATCHES.md documents. A rebase would
rewrite the vendored commits and destroy the audit trail.

**Level 2 — this monorepo (`streamify-apk`), one squash commit per tag:**

```bash
git fetch <fork-remote>            # the fork's post-merge main
git read-tree --prefix=front-end/feishin/ -u <fork-merge-commit>~
# ...or equivalently: rm -rf front-end/feishin && git read-tree --prefix=front-end/feishin/ -u <fork-merge-commit>
# verify: pnpm run lint && pnpm run test in front-end/feishin
# update PATCHES.md (the per-file map) in the SAME commit
```

The vendored tree advances as a **single-commit vendor replace** — no
interleaved upstream history in this repo, so monorepo PR reviews see
"bump vendored feishin to v1.18.0 (fork merge `<sha>`)" and the per-file
diff is exactly the fork's merge diff. Never cherry-pick trains; never
merge upstream directly here (this repo has no `upstream` remote by
design).

---

## Verification

Network-free vitest locks added in the fork (24 files, 223+ tests total):

- `relay-base.test.ts` (5) — the placeholder guard: rejects
  `${STREAMIFY_RELAY_BASE}` from the window knob / `?relay=` / build env
  while accepting valid runtime URLs; never returns a placeholder.
- `canary-panel.test.ts` (6) — SSR smoke through the panel, the no-session
  degradation, the injection seam (behavioral: stubbed `fetch` proves the
  post targets the session's Supabase project + anon key), the legacy
  no-props shape, the pure gate ruling, and the route source keeping the
  canary mount on the gated side of the redirect.
- `sabr-corpus-runner.test.ts` (3) — the runner imports the canonical
  `get-stream-url` (never the archive), the canonical module graph loads
  under plain node, and the live lane stays out of the default test lane.

Local: `pnpm run lint` clean, `pnpm run test` 223/223 (no vite build in
the 4 GB sandbox — Feishin CI runs `build:web`). CI: all active workflows
must be green on the PR; **retired checks to drop from Branch
Protection:** `resolver-mirror-sync`, `web` (Web Build), and the `Web
Deploy` / `deploy` (Pages) checks.
