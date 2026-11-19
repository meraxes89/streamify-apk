# Streamify Skin — YouTube-Music-style mobile shell

A CSS + vanilla-JS layer that restyles Feishin's stock mobile layout into a
YTM-like player. **It lives entirely outside `front-end/feishin/`.**

## Why it exists

`front-end/feishin/` is vendored from `meraxes89/streamify-feishin`, which
merges upstream `jeffvli/feishin` release tags. Every edit inside that tree is
a merge conflict recorded in `front-end/feishin/PATCHES.md`. The mobile UI
problems (low-contrast subtle buttons, tiny hit areas, floating buttons,
desktop-first layout) cannot be fixed by a theme — they need structural
overrides. This skin delivers them without adding a single line to the
upstream divergence ledger.

## Files

| File | Purpose |
| --- | --- |
| `ytm.css` | Forced YTM-dark tokens, ≥44px tap targets, mini/full player, list/card styling, bottom nav styling. Scoped to `(max-width: 768px) and (pointer: coarse)`. |
| `ytm.js` | Injects the bottom tab bar, navigates via `location.hash` (Feishin uses `HashRouter`), hides the redundant floating search button, tracks the fullscreen overlay. |
| `loader.js` | Tiny bootstrap appended to generated `settings.js`. Injects the CSS `<link>` and `<script>` using settings.js's own URL as the base path. |
| `README.md` | This file. |

## How it is wired

`scripts/inject-skin.mjs <built-web-dir> [--repo-root <root>]`:

1. Replaces the marker-delimited loader block in the built `settings.js`.
2. Copies `ytm.css` / `ytm.js` into `<built-web-dir>/skin/`.
3. **Fails the build** if any critical `fs-*` CSS-module class vanished from
   Feishin's compiled CSS (upstream rename guard).

It runs in:

- `.github/workflows/feishin-pages.yml` — production Pages bundle.
- `.github/workflows/preview-pages.yml` — both the `prod/` and `preview/`
  trees before they are fused into one Pages artifact.
- Locally: `server/public/settings.js` and `server/public/skin/` are generated
  once by the same script. `server/index.js` additionally serves
  `front-end/skin/` directly at `/skin`, so **editing `ytm.css` shows up on the
  next reload with no rebuild** (HEAD disabled the service worker and added
  no-cache headers to index.html, so stale CSS is not a problem).

## Editing the skin

- Change visuals in `ytm.css`, behaviour in `ytm.js`. Both files are plain and
  framework-free — no React, no imports from Feishin internals.
- Vite compiles CSS modules to **stable** names (`fs-[file]-[class]`, e.g.
  `fs-mobile-playerbar-module-container`) thanks to `web.vite.config.ts`.
  Those are the hooks the skin targets. If you add a new selector, add its
  class to `REQUIRED_CLASSES` in `scripts/inject-skin.mjs` too.
- If you change `loader.js`, re-run the inject script (or let CI do it).
- Do **not** patch Feishin components to make the skin work. That defeats the
  isolation contract this layer exists to protect.

## Route map (for new tabs)

From `front-end/feishin/src/renderer/router/routes.ts` — HashRouter, so a tab
just assigns `location.hash`:

| Tab | Hash |
| --- | --- |
| Home | `#/` |
| Search | `#/search/song` (`SEARCH` needs an `itemType` param) |
| Library | `#/library/songs` |
| Downloads | `#/streamify-downloads` (fork route) |
| Settings | `#/streamify-settings` (fork route) |

## Known trade-offs

- Theme-token overrides use `!important` because Feishin writes
  `--theme-colors-primary` as an inline style on `<html>`. Keep the override
  list tight.
- The fullscreen player is an `AnimatePresence` overlay, not a route. `ytm.js`
  watches the DOM and sets `body.skin-player-open` so the nav hides cleanly.
- The floating **menu** button is deliberately kept (restyled as a top-left
  round button) — it is the only way into the mobile sidebar/playlists. Only
  the floating **search** button is retired, since the nav has a Search tab.
