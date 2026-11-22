# Feishin adoption audit — P0 extract

Status: P0 code-audit + verification record (2026-11-15).
Scope: tag `v1.17.0` of `jeffvli/feishin`, as forked to
`meraxes89/streamify-feishin` (head `d12f99f6` at audit time).
Note: the kickoff referenced this audit; it was not in the repo when P0
started, so P0 produced this extract from the actual v1.17.0 code plus the
end-to-end verification run. Future phases append their own sections.

## 1. Integration seam

- Server types are abstracted behind `InternalControllerEndpoint`
  (`src/shared/types/domain-types.ts`, lines 1720–1857). The renderer calls
  `api.controller[serverType].<endpoint>(…)`; the UI hides per-type
  affordances when endpoints are absent/optional. A registered stub
  controller therefore renders a selectable-but-inert server type — the
  established pattern we rely on.
- `ServerType` is declared TWICE upstream: the API-side enum
  (`domain-types.ts`) and a UI-side enum (`src/shared/types/types.ts`). Both
  must carry new members or `typecheck:web` fails on the exhaustive records.
- Exhaustive `Record<ServerType, …>` sites (must be extended when the enum
  grows): `add-server-form.tsx` `SERVER_TYPES`, `login-route.tsx`
  `SERVER_ICONS`/`SERVER_NAMES`. Sort-dropdown records are `Partial` and need
  nothing.
- `toServerType` (`types.ts`) whitelists the settings.js-provided
  `SERVER_TYPE`; unknown values silently fall back to Navidrome.

## 2. Endpoint inventory (the controller surface)

`InternalControllerEndpoint` = **69 methods total: 53 required + 16
optional**. The P0 stub implements all 69, each throwing
`StreamifyNotImplementedError('<endpoint>')`.

Required (53): addToPlaylist, authenticate, createFavorite,
createInternetRadioStation, createPlaylist, deleteFavorite,
deleteInternetRadioStation, deletePlaylist, getAlbumArtistDetail,
getAlbumArtistList, getAlbumArtistListCount, getAlbumDetail, getAlbumList,
getAlbumListCount, getAlbumRadio, getArtistList, getArtistListCount,
getArtistRadio, getDownloadUrl, getFavoriteSongs, getFolder, getGenreList,
getImageRequest, getImageUrl, getInternetRadioStations, getMusicFolderList,
getPlaylistDetail, getPlaylistList, getPlaylistListCount, getPlaylistSongIds,
getPlaylistSongList, getPlayQueue, getRandomSongList, getRoles,
getScanStatus, getServerInfo, getSimilarSongs, getSongDetail, getSongList,
getSongListCount, getStreamUrl, getTopSongs, getUserInfo, refreshItems,
removeFromPlaylist, replacePlaylist, savePlayQueue, scrobble, search,
setPlaylistSongs, startLibraryScan, updateInternetRadioStation,
updatePlaylist.

Optional (16): deleteArtistImage, deleteInternetRadioStationImage,
deletePlaylistImage, getAlbumArtistInfo, getAlbumInfo, getLyrics,
getStructuredLyrics, getTagList, getUserList, jukeboxControl,
movePlaylistItem, setRating, shareItem, uploadArtistImage,
uploadInternetRadioStationImage, uploadPlaylistImage.

P1 priority (auth + library first): authenticate, getUserInfo, getServerInfo,
getMusicFolderList, the album/artist/song list+count set, getAlbumDetail,
getSongDetail, getGenreList, getRandomSongList, search.

## 3. Build system facts (v1.17.0)

- Web target: `pnpm run build:web` = `vite build --config web.vite.config.ts`
  → `out/web` static export; base `./` (subpath-safe); hash router; PWA
  service worker (workbox, scope `/assets/`).
- `packageManager: pnpm@11.5.2` (corepack); `.npmrc` `legacy-peer-deps=true`;
  no engines field. Node 24 used for the P0 verification build.
- `postinstall` runs `install-electron && electron-builder install-app-deps`;
  `ELECTRON_SKIP_BINARY_DOWNLOAD=1` keeps it web-only-friendly.
- Default Node heap OOMs on the web build in ≤4 GB environments; upstream's
  electron build already pins `--max-old-space-size=4096`. On small boxes,
  `--sourcemap false --minify false` (CLI-only, no config change) fits.
- Lint gate: `pnpm run lint` = `typecheck:node` + `typecheck:web` + eslint
  `--max-warnings=0` (4-space indent via prettier; perfectionist
  natural-order imports/objects) + stylelint. commitlint (conventional) runs
  on PR ranges and pushes.
- Docker: `node:23-alpine` builder → `nginxinc/nginx-unprivileged` +
  `ng.conf.template` (envsubst `settings.js`, port 9180). No docker daemon in
  the P0 sandbox — replicated natively (static server + settings.js via the
  same substitution), see §4.

## 4. P0 verification record (sandbox, in-browser)

Stack: built `out/web` served on :8090 (static, settings.js generated like
the nginx entrypoint), throwaway Navidrome 0.64.2 on :4533 (binary, 5
generated MP3s across 2 artists/albums, admin user via `/auth/createAdmin`),
headless Chrome (agent-browser).

Golden path — all steps verified:

1. First run → add-server screen; Streamify radio present alongside
   Jellyfin/Navidrome/OpenSubsonic.
2. Add Navidrome (`http://localhost:4533`, admin) → ADD → authenticated
   (console: "Server authentication successful … serverType: navidrome"),
   app lands on `#/` (home).
3. Browse: `#/library/albums` renders both albums (Calibration — Pilot Tone
   2024; Vector Songs — Signal Forms 2025) from Navidrome (ND native `/api/*`
   + subsonic `/rest/*` requests, CORS `Access-Control-Allow-Origin: *`).
4. Album detail: 3 tracks with durations (Carrier Wave 0:45, Phase Drift
   0:40, Envelope Rise 0:50).
5. Play (album play control): queue populated, engine resolved the real
   stream URL (`/rest/stream.view?id=…`), Navidrome served **206 Partial
   Content** media responses; `<audio>` duration = 45s (real track);
   `currentTime` advanced continuously (0 → 26s+ across observations);
   **auto-advanced to track 2** (dur 40s) at track end; four stream requests
   observed across two distinct song ids.

Automation notes (environmental, not product defects): (a) sandbox network
rate-limits anonymous `api.github.com`, so the app's update-check dialog was
fed a mocked release response to render its real buttons (stock users hit the
live API); (b) headless autoplay policy required the final `audio.play()`
after the queue+stream were set via the UI — a real user's trusted click
carries that gesture natively; (c) the `umami.jeffvli.org` analytics script
hangs in the sandbox and was blocked (upstream analytics, not our code).

Streamify smoke test: `settings.js` with `SERVER_TYPE=streamify` → app boots
cleanly (normal init logs, zero page errors), add-server form renders with
**Streamify radio checked** — `toServerType` resolves our type. Exit bar met.

Stock flow regression: the Navidrome golden path above ran on the same build
carrying the registration diff — stock behavior intact.

## 5. CI status at audit time

- Fork `Test` (upstream: lint + commitlint): **success** on `d12f99f6`.
- Fork `Web CI` (ours: install → lint → build:web): **success** on `d12f99f6`.
- Monorepo `web-build.yml` triggers on `front-end/**` PRs; the vendored tree
  does not alter its inputs (gates compile `front-end/crates` + build
  `front-end/web`, both untouched). *(P5 note: `front-end/web` has since been
  archived to `legacy/front-end-web` and `front-end/feishin` is the canonical
  front-end — the workflow's path-gating and the resolver-mirror sync
  (`scripts/sync_feishin_resolver.py`) now guard that posture; see §6.)*

## 6. P5 cutover addendum (2026-11)

The audit above is the P0 record, kept verbatim. What changed by P5:

- **`front-end/feishin` is the canonical web front-end.** The vendored
  fork grew the full Streamify surface across P1–P4 (Google/Supabase auth
  + library snapshot, playback persistence/scrobble, favorites/curation,
  downloads + wrapped + `/debug`, jam live-queue + EQ + brand theming +
  settings). Feishin Web CI (`.github/workflows/feishin-ci.yml`) gates it:
  lint → i18n check → tests → `build:web`.
- **`front-end/web` is archived at `legacy/front-end-web`** (P5-A). Its
  resolver stream (`src/lib/`, `src/audio/`) remains the byte-identity
  source for the fork's `playback/resolver/` mirror — enforced on every
  `front-end/**` PR by `scripts/sync_feishin_resolver.py --check` in
  `web-build.yml`.
- **Localization policy**: 39 Weblate catalogs are carried untouched;
  Streamify keys live only in `src/i18n/locales/en.json` (English
  fallback). The `scripts/check-i18n.mjs` CI gate enforces it.
- The full cutover record — architecture, capabilities, endpoint
  coverage, verification results across P0–P5 — lives in
  `front-end/decisions/p5/P5B-i18n-hardening-release-notes.md`.
