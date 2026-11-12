# WS-W front-end — the Streamify web port (P0 proof phase)

Platform #5: the same sovereign engine, compiled to WebAssembly and served
from GitHub Pages at $0. This workspace is deliberately SEPARATE from
`../rust` (the frozen engine workspace) so the wasm target graph never
touches the engine's native-only build matrix (tokio rt-multi-thread,
reqwest rustls, rusqlite bundled, JNI).

## Layout

```
front-end/
  Cargo.toml                  wasm workspace root (members: crates/*)
  crates/streamify-wasm/      wasm-bindgen facade (ping / set_relay /
                              search_music / get_stream) over the vendored
                              engine parser (src/innertube.rs)
  web/                        Vite + React + TS app (deploys to /streamify-apk/)
    src/wasm.ts               the ONLY module that talks to the wasm pkg
    src/mse.ts                bounded-range MSE bridge player
    src/pages/Canary.tsx      W0.1b residential canary (hash route #/canary)
  relay/                      Cloudflare Worker: minimal CORS relay (P0 scope;
                              hardening is P4 / W4.1)
  canary/                     probe matrix + Supabase posting (consumed by web/)
  decisions/                  W0.2 spike evidence + W0.5 R-C spend decision
```

## The chain (proven in P0)

```
TypeScript ──▶ wasm facade ──web-sys fetch──▶ relay /r?u=<target>
  ──▶ youtubei / googlevideo ──▶ response streamed back (CORS *)
  ──▶ engine InnertubeParser (vendored) ──▶ JSON-string bridge ──▶ TS
```

W0.1 landmines honored (each cost a day when hit blind): JSON-string bridge
(never serde Value/Map across the boundary), `JsFuture` everywhere (never
`spawn_local` around async externs), no `SystemTime::now()` on wasm32
(traps; use `js_sys::Date::now()`).

## Build & test

```bash
cd front-end
npm ci
npm run lint              # tsc -b web
npm run build             # vite build (bundles the wasm pkg)

# wasm side
cargo check --manifest-path Cargo.toml --target wasm32-unknown-unknown
wasm-pack build crates/streamify-wasm --target web
cd crates/streamify-wasm && cargo test --target wasm32-unknown-unknown  # CI: headless Chrome
```

CI lanes: `web-build.yml` (PRs + main) and `web-deploy.yml` (main → GitHub
Pages). Both are additive; the frozen engine lanes are untouched.

## Runtime env (build-time via VITE_*)

| Variable | Where | What |
|---|---|---|
| `VITE_RELAY_BASE_URL` | deploy workflow var | deployed worker URL (REQUIRED for Pages; localhost falls back to wrangler dev on :8787) |
| `VITE_SUPABASE_URL` | deploy workflow var | canary telemetry target (PO provides) |
| `VITE_SUPABASE_ANON_KEY` | deploy workflow var | INSERT-only key for `canary_results` |

See `web/.env.example`.

## Deploy runbook (PO, one-time)

1. `cd front-end/relay && npx wrangler deploy` (needs Cloudflare creds) →
   note the workers.dev URL.
2. Repo Settings → Variables → `VITE_RELAY_BASE_URL` = that URL.
3. Repo Settings → Pages → Source: GitHub Actions.
4. Merge the P0 PR → `web-deploy.yml` publishes to
   https://meraxes89.github.io/streamify-apk/ .
5. Apply `canary/migrations/001_canary_results.sql` in Supabase, set the
   two Supabase vars, recruit ≥5 residential testers for W0.1b.

## Stable test hooks (CI / browser smoke)

`#ping-result` (pong), `#result-count` (+`data-count`),
`#audio-state` (`data-state/-peak/-time/-client/-ciphered/-itag/-mode`),
`#proof-result` (`data-status/-http/-bytes/-peak/-time`),
`#canary-run` / `#canary-table` / `#canary-summary` / `#canary-rc` /
`#canary-post-status`.

## Decisions of record

- `decisions/W0.2-spike-rustypipe-wasm32.md` — rustypipe 0.11.4 is KILLED
  for wasm32 (rquickjs-sys is unconditional; no feature excludes QuickJS).
  The facade rides the engine's own pure parser, vendored verbatim with a
  provenance header. P1 engine change request: lift `json.rs` into a shared
  transport-free crate.
- `decisions/W0.5-rc-spend.md` — NO-SPEND on the "zero-relay-bytes" R-C
  path in P2/P3; measured evidence inside. Reopen criteria are explicit.
