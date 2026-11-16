# P1A — Feishin auth + session (Engineer A)

**Status: COMPLETE** — 2026-11-16. Fork PR [streamify-feishin#1](https://github.com/meraxes89/streamify-feishin/pull/1) (merged as `540ee30e`), vendored here with the F1 single-commit mechanics. CI green on every push (`Test` = upstream lint + commitlint, `Web CI` = install → lint → build:web → artifact).

## Scope (as kicked off)

1. `/streamify-auth` route + app-outlet gate — our own module, thin pre-approved wrapper.
2. Supabase Google OAuth → synthetic server entry persisted through feishin's own server store; the P0 stub controller backs it.
3. Session refresh / 401 handling wired into the controller's request layer; signed-in state surfaced for Engineer B (library browsing, P1B).

## The moving parts (all new directories, ours)

| Piece | Role |
|---|---|
| `src/renderer/api/streamify/streamify-supabase.ts` | supabase-js client singleton (PKCE, persisted sessions, auto-refresh, URL redirect detection), the synthetic `ServerType.STREAMIFY` entry upsert into the auth store (stable id `streamify`, credential = live access token, kept fresh by the auth-state listener), clean sign-out (SDK sign-out + entry removal + hash navigation). |
| `src/renderer/api/streamify/streamify-request.ts` | the authenticated request lane: Bearer + apikey headers, **one** refresh-and-retry on 401, then clean sign-out back to `/streamify-auth`. `StreamifySessionExpiredError` is the typed signal. |
| `src/renderer/api/streamify/streamify-controller.ts` | auth trio real (`getUserInfo` via SDK; `getServerInfo` = GoTrue `/auth/v1/user` probe through the request lane; `authenticate` points stock form attempts at the auth page). Remaining stubs crash-proof (below). |
| `src/renderer/features/streamify-auth/` | the auth route (Google button, session restore, own-server escape hatch) + `use-streamify-auth-gate` (all gate logic; the outlet carries a 3-line wrapper). |

## Gate truth table

Only active when `settings.js` carries `STREAMIFY_SUPABASE_URL` + `STREAMIFY_SUPABASE_ANON_KEY` and `STREAMIFY_AUTH ≠ false`. Electron and stock deployments are never touched (short-circuit to PASS, zero side effects).

| State | Result |
|---|---|
| current server is STREAMIFY, session live | PASS (stock `useServerAuthenticated` network-validates via `getUserInfo`) |
| current server is STREAMIFY, session dead | controller signs out cleanly (real 401 path) → gate REDIRECT to auth |
| current server is another type | PASS — never interfere, even mid-session in hybrid lists |
| no current server, live session | upsert + select the synthetic entry (covers the OAuth redirect boot), then PASS |
| no current server, no session | REDIRECT to `/streamify-auth` (the auth page's "use your own server" link walks stock users into the untouched picker/login in one click; the gate never mounts on those routes) |

## Design decisions worth remembering

- **Sign-out removes the synthetic entry.** Cleanest signed-out semantics; re-sign-in recreates it (stable id makes the upsert idempotent). Server-list membership while signed in is exactly like a Navidrome entry (verified in the stock server list and command palette).
- **Never steal a deliberate stock selection.** The upsert only auto-selects when nothing is selected or the streamify entry itself is; a hybrid user stays on their Navidrome/Jellyfin pick across silent refreshes.
- **Stub semantics are crash-proof.** Promise-typed stubs reject asynchronously (react-query surfaces honest error states); the three sync-typed URL builders (`getImageUrl`, `getImageRequest`, `getDownloadUrl`) return empty values. Verification caught the hard way that `getImageUrl` is called synchronously from render paths (player-bar artwork while a queue from another server is active) and a synchronous throw blanks the whole app.
- **`getUserInfo` distinguishes auth rejections from network failures.** Clear 401/403/JWT-session rejections trigger the clean sign-out; transient network errors rethrow untouched so the stock hook routes them to the no-network page — no sign-outs on flaky connections.
- **Runtime knobs, never in-tree secrets.** `settings.js.template` + one Dockerfile `ENV` line (nginx envsubst only substitutes *defined* variables; without the defaults, literal `${STREAMIFY_…}` garbage leaks into settings.js — counterfactual verified). The anon key is public-by-design and arrives via deployment config only.

## Verification (CI-built bundle at `http://localhost:9180`, real Supabase project, throwaway Navidrome)

| Acceptance | Evidence |
|---|---|
| Fresh visit → auth route | `#/streamify-auth` rendered, both zero-server and hybrid states |
| Google login | Button → real Supabase PKCE authorize (`code_challenge_method=s256`) → real accounts.google.com sign-in page. **The consent click-through needs a human Google account** — everything before and after is machine-verified. |
| Entry persisted, in-app | Entry survives reloads, listed in stock server list + command palette, no selection stealing |
| Reload → still signed in | Stock persistence + queue continuity verified (reload mid-play resumed at the next track) |
| Revoked session → clean bounce | Seeded SDK storage (real user id, fake tokens): real GoTrue **403** on `/auth/v1/user` → refresh rejected → SDK storage **cleared**, entry **removed**, bounce to `#/streamify-auth`, no crash, no unauthenticated calls after |
| Stock Navidrome path | Add → login → browse → album (artwork renders) → **play** through the dual react-player engine — verified twice, including on the final bundle |
| Knobs off | Unconfigured → pure stock; `STREAMIFY_AUTH=false` → pure stock |
| Docker mechanics | envsubst semantics verified for all three states (deployment-set / ENV-defaults / counterfactual) |

**Honest limits:** a *valid* session (silent refresh happy path, post-consent entry selection) cannot exist headlessly — the project's email lane requires confirmation, anonymous sign-ins are disabled, and the Google consent needs a real account. The revoked-session path exercises the same refresh/sign-out machinery against real 401/403s. The 401 refresh-and-retry lane itself is code-verified + typechecked; its runtime happy path rides on `getServerInfo`'s probe once a real session exists. A test user `streamify.p1.verify@gmail.com` exists in the project (email-unconfirmed, created during the lane probing).

## For Engineer B (P1B — library browsing)

- Import from `src/renderer/api/streamify/streamify-request.ts`: `streamifyRequest<T>(path, init)` gives you the authenticated lane (Bearer + apikey, one refresh-and-retry, clean sign-out on dead sessions). Point it at the engine facade endpoints; never roll your own fetch in the controller.
- Signed-in state: `getStreamifySession()` (session or null), `STREAMIFY_SERVER_ID` (stable entry id), and the auth store's synthetic entry (serverId = `streamify`).
- Make endpoints real one at a time; keep the crash-proof stub semantics for whatever is still stubbed. The `StreamifyNotImplementedError` rejection is the honest "not yet" signal.
- `getServerInfo` version is `0.1.0` — bump when the surface grows.
