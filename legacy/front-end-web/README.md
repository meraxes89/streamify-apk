# streamify-web — ARCHIVED (P5-A cutover)

> **This tree is read-only history.** It moved to `legacy/front-end-web` in
> the P5-A cutover (2026-11) when the feishin fork became the sole
> production web front-end. Nothing here builds, lints, or deploys: the
> tree is outside every active workspace, CI lane and code scanner, and it
> must never be edited — archaeology only. The live application is
> `front-end/feishin` (Docker: `front-end/feishin/Dockerfile` +
> `docker-compose.yaml`); the resolver stack it vendored is now the
> canonical source at
> `front-end/feishin/src/renderer/api/streamify/playback/resolver/`, and the
> residential canary console is mounted inside the gated `/debug` route.
> See `front-end/decisions/p5/P5A-cutover-legacy-archive-upstream-ritual.md`
> for the cutover record.

---

## Historical README (as of the archive)

Vite + React 18 + TypeScript (strict) front-end for the Streamify web port
**P0 proof phase**. This page proves the whole chain in a browser:

- **G0.2** — the Rust wasm engine answers a ping (the `engine: pong` chip),
- **G0.3** — YouTube Music search returns ≥ 5 tracks for “Blinding Lights
  The Weeknd”,
- **G0.4** — every track plays 10 seconds of audio through the CORS relay
  (auto-pause, then a `PASS: 10s audio played` badge).

Deployed at <https://meraxes89.github.io/streamify-apk/> (repo path under the
`meraxes89` owner — hence the Vite `base: '/streamify-apk/'`). The residential
canary probe lives at `/streamify-apk/canary.html`.

## Layout

- `index.html` / `canary.html` — the two Vite entry points (one deploy).
- `src/lib/engine.ts` — **the bridge contract** to the wasm engine. All engine
  calls go through this module; nothing else may import `src/wasm/*`.
- `src/wasm/` — generated territory: wasm-pack output lands here; a committed
  stub keeps the build green until then. See `src/wasm/README.md`.
- `src/components/` — presentational, props-driven components
  (`SearchPanel`, `StatusCard`, `TrackRow`).

## Local dev

```sh
cd front-end
bun install
npm run dev -w web   # http://localhost:5173/streamify-apk/
```

The YouTube Music relay is expected at `http://localhost:8787` — start it with
`wrangler dev` inside `front-end/relay` (that workspace is built by another
agent).

## Build

```sh
cd front-end
npm run build -w web   # tsc --noEmit && vite build → web/dist/
```

The build stays green against the committed wasm stub: when the facade is
missing, the engine status card shows
`wasm module not built — wasm-pack output missing from src/wasm`.

## Deploy

Pushes to `main` or a `ws-w/*` proof branch touching `front-end/**` run the
`Web Deploy` workflow (`.github/workflows/web-deploy.yml`) which builds
`web/dist/` (wasm facade + vite) and publishes it to GitHub Pages. Since the
WS-W live wiring (2026-11-12) that workflow bakes the live env into the
bundle — see *Relay / env* below.

## Relay / env

Production defaults since the live wiring (2026-11-12, `W0.6`):

- Default relay base: `https://streamify-relay.zephyr4289.workers.dev` — the
  **deployed** Cloudflare Worker, baked at build time by `web-deploy.yml` via
  `VITE_RELAY_BASE_URL` (a public constant, committed in the workflow).
- The canary page (`/streamify-apk/canary.html`) additionally gets
  `VITE_SUPABASE_URL` (public constant) + `VITE_SUPABASE_ANON_KEY` (repo
  secret `SUPABASE_ANON_KEY` — JWT-shaped, never committed) for the
  "Post results to Supabase" leg.
- `?relay=https://your-relay.example` (query param) overrides everything —
  handy for pointing the deployed page at another relay.
- In dev (`npm run dev -w web`) the default remains `http://localhost:8787`
  unless `VITE_RELAY_BASE_URL` is exported.

## Regenerating the wasm facade

```sh
cd front-end
wasm-pack build crates/streamify-wasm --target web --out-dir ../web/src/wasm
```

(Exact commands are wired by the lead in W0.4 — see `src/wasm/README.md`.)
