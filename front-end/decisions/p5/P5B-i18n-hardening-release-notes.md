# P5-B — i18n hardening (39-locale catalog guard), dead-reference cleanup & the WS-W cutover release notes

- **Status:** implemented (branch `ws-w/p5b-i18n-cleanup-release`, rebased onto merged #192 + #193)
- **Scope:** `front-end/feishin` (scripts/check-i18n.mjs, i18next-parser.config.js, en.json, eq-curve aria-label, two dead-comment fixes) + `.github/workflows/feishin-ci.yml` (the new gate step) + planning-doc cutover notes (`front-end/WS-W-MASTER-PLAN-v3.md`, `download/feishin-adoption-audit.md`) + this ADR
- **Zero-overlap contract (Engineer A owns `ws-w/p5a-cutover-legacy-archive`):** no touches to `Dockerfile`, `docker-compose.yaml`, `relayBase.ts` placeholder *filtering* (the comment-only file-count fix here is orthogonal), the CanaryConsole `/debug` mount, the `front-end/web → legacy/front-end-web` move, `web-build.yml` / `web-deploy.yml` / `resolver-canary.yml`, or `front-end/feishin/PATCHES.md`.

---

## 1. The 39-locale en.json-only policy (the decision)

`front-end/feishin/src/i18n/locales/` carries **39 catalogs**: `en.json` plus 38 Weblate-managed files. Upstream's translation pipeline is a live bot — Weblate opens PRs against those 38 files on a regular cadence.

**Decision: every Streamify translation key lives only in `en.json`.** Rationale:

- i18next initializes with `fallbackLng: 'en'` (`src/i18n/i18n.ts`), so any key missing from a user's catalog falls back to English *cleanly, per key*. A German user sees German for the 79% of upstream keys their catalog translates and English for Streamify keys — exactly the behavior a partially-translated catalog already delivers everywhere else.
- Backfilling English strings into the 38 files converts **1 carried file into 39 monthly merge-conflict surfaces** for zero UX gain: Weblate's bot does not know which keys are "ours" and will happily re-translate or clobber them, and every future Streamify key would need 39-file commits to stay honest.
- The policy is mechanically enforced now (§2): any Streamify key appearing in a non-English catalog fails CI, so a well-meaning backfill PR cannot land quietly.

Current posture (the check's parity report, non-destructive): upstream coverage across the 38 catalogs ranges from 0.4% (bg, 6 keys) to 90.7% (ca/es/pl/uk, 1515 keys); all 38 carry **zero** Streamify keys — 175/175 live in `en.json` alone, and `en.json` itself has **0 empty Streamify values** (the only empty value in any catalog's Streamify-relevant surface is upstream's own `player.repeat_other`, untouched).

## 2. The check-i18n.mjs CI gate

`front-end/feishin/scripts/check-i18n.mjs` (node, zero deps) runs in **Feishin Web CI between lint and tests** (`.github/workflows/feishin-ci.yml`). It **fails** the build on:

1. any of the 39 `src/i18n/locales/*.json` failing to parse as UTF-8 JSON;
2. an empty string value on a Streamify key (en.json today, any file ever);
3. a Streamify key present in a non-English catalog (the §1 policy, enforced);
4. a `t('…')` reference in `src/renderer/features/streamify-*` or `src/renderer/api/streamify/` (tests excluded) that is not backed by `en.json` — including **dynamic template keys**: `` t(`streamify.jam.error.${code}`) `` must at least resolve to a populated key namespace (the static prefix must match ≥1 en.json key).

And it **reports, non-destructively** (never fails on Weblate's business): per-catalog key counts, parity %, extra CLDR plural forms (`_zero/_two/_few/_many` — English has none of these), locale-appropriate `_one` omissions (the no-plural set: bg, id, ja, ko, lv, ro, th, tl, zh-Hans, zh-Hant — they legitimately carry no `_one` forms, which also covers non-plural oddities like `player.repeat_one` whose `_other` sibling exists), stale extra keys (3 exist today: `common.setting` in de/fr/ru, `entity.play` in ru — Weblate's to prune), and untranslated counts with Streamify's en-only keys excluded from the expectation.

## 3. The P1–P4 audit (what the gate walked into)

Full audit of every Streamify UI surface (auth, downloads, wrapped, debug, jam, eq, settings) plus `api/streamify/`:

- **162 unique static `t()` keys** referenced across the surfaces — all 162 resolve in `en.json`; zero missing, zero empty.
- **3 dynamic key families** (jam error codes ×4, relay health status ×3, wrapped windows ×4) — all namespaces populated.
- **1 fix landed:** `eq-curve.tsx` shipped a hardcoded `aria-label="EQ frequency response"` (screen-reader-visible English). Now `t('streamify.eq.chartAriaLabel')` + the en.json key. This PR is the i18n-hardening PR; it holds itself to the bar it enforces.
- **2 deliberate non-fixes, documented:** the relay URL example placeholder (`https://my-relay.example.workers.dev`) follows upstream's technical-placeholder convention (`placeholder="48000"`, `placeholder="mp3, opus, flac"` in upstream settings); `StreamifyNotImplementedError('endpoint')` messages are developer/log-facing diagnostics behind the UI's per-server-type gating, not user copy.
- Toasts, error prefixes, badges, section titles, and the `/debug` panel were verified `t()`-backed (the panel's depth/flush/rung values are numbers and enum-ish strings by construction — pinned by the §6 guard).

## 4. Tooling repair: i18next-parser.config.js

The vendored config pointed extraction at `src/renderer/i18n/locales/$LOCALE.json` — a tree that does not exist (upstream moved catalogs to `src/i18n/locales/` and never updated the parser config). Fixed to `./locales/$LOCALE.json` (config-relative resolution, per i18next-parser v8+ semantics — the config's own `../renderer/**` input globs confirm the resolution base). `pnpm run i18next` now writes to the live catalogs.

## 5. Dead-reference cleanup (code comments + planning docs)

- `api/streamify/playback/stream-source.ts` — the house-rule comment cited the deleted `scripts/verify_feishin_resolver.py` and hardcoded `front-end/web` as "the resolver stream". Now points at the canonical mirror (`playback/resolver/`) and the live enforcement (`scripts/sync_feishin_resolver.py --check`, which CI runs on every `front-end/**` PR).
- `api/streamify/playback/resolver/lib/relayBase.ts` — the file-count comment said the sync check guards "the other **nine**" vendored modules; the mirror carries **ten** (botguard ×3, audio ×5, lib ×2 besides relayBase). Comment now matches reality. (Comment-only change; Engineer A's placeholder-filtering work in the same file is untouched.)
- `front-end/WS-W-MASTER-PLAN-v3.md` — added the **P5 cutover notice** (the greenfield `front-end/web` UI strategy was superseded by the Feishin adoption; `front-end/feishin` is canonical; the greenfield tree archives at `legacy/front-end-web` in P5-A, its resolver stream still feeding the byte-identical mirror), annotated the §5 monorepo tree (feishin/ canonical, web/ archived), and marked the §9.3 filename-mirroring table as the historical record it now is.
- `download/feishin-adoption-audit.md` — the P0 record stays verbatim; §5's "both untouched" CI note now carries the P5 posture, and a new §6 addendum records the cutover state and points here.

## 6. Observability guard tests (production diagnostics, leak-free)

New `api/streamify/__tests__/observability-guard.test.ts` (network-free, 9 tests) locks the production diagnostic hooks:

- **Scrobble outbox:** `pendingScrobbles()` and the *persisted localStorage row* round-trip JSON with a **pinned field set** (`album, artist, attempts, durationMs, firedAt, playedMs, rung, thresholdMs, title, trackId`) — no field can ever smuggle a URL or token onto a diagnostic surface; `lastFlushAt` is null before the first report and a finite epoch ms after; the rung attaches at fire time without carrying the URL; the `sessionForQueue()` queue-persistence projection round-trips with its own pinned field set.
- **Resolver rung telemetry:** a real `getStreamUrl` pass (pipeline mocked one level under, probe injected) produces a `ResolveLogRecord` whose field set is pinned and whose serialization contains **no URL, no auth token, no signed-stream material** — while the resolution it describes verifiably carries the signed googlevideo URL (the fixture is realistic poison; the record never sees it).
- **/debug health read model:** the route's exact assembly (scrobble depth + flush ts, resolver slice, ledger counts, CRDT outbox depth + persist ts, realtime binding) round-trips as one JSON blob, leak-free, with the CRDT store's Maps/Sets deliberately excluded (the route reads primitives only).
- The leak scanner self-tests: it must catch the poison URL and a Bearer header, and must not flag a plain rung string.

---

# WS-W Cutover Release Notes (P0 → P5)

**The WebSocket-Write stream is cut over.** `front-end/feishin` — the vendored `jeffvli/feishin` v1.17.0 fork — is the canonical Streamify web front-end. The greenfield `front-end/web` app that WS-W's master plan specified is archived untouched at `legacy/front-end-web` (P5-A); its resolver stream remains the byte-identity source for the fork's mirror. What follows is the complete delivery record.

## Architecture (what ships)

- **Base:** upstream feishin v1.17.0, vendored whole (GPL-3.0 preserved); every upstream file touched is logged in `front-end/feishin/PATCHES.md` (39 numbered patches). The stock Navidrome/Jellyfin/OpenSubsonic flows stay intact — the Streamify server type is additive (`ServerType.STREAMIFY` on both enums, radio entry on the add-server form).
- **Session:** Supabase Google OAuth (PKCE), synthetic server entry bridged into feishin's credential store, session restore, sign-out (P1).
- **Library:** local-first snapshot bridge — 24 read endpoints land in an in-memory `LibrarySnapshot` (songs/albums/artists/playlists/genres + play stats), so browse/search/sorts run offline-first against one consistent image (P1), with favorites/curation on top (P3-B: liked state, smart filtering, `FAVORITED` polarity fixed at the SQL boundary).
- **Playback:** the dual react-player engine (src1↔src2 crossfade slots) wired to a vendored, **byte-identical mirror** of the resolver stack (10 modules under `playback/resolver/`, CI-enforced by `sync_feishin_resolver.py --check` on every `front-end/**` PR) — the same ladder/deobf/expiry semantics the web app and the Android engine share; zero-egress offline bypass for downloaded tracks; the seek-strategy and stale-URL walk contract from P2-A (never re-resolve mid-crossfade; retries hit the cached ladder).
- **Persistence spine:** queue state machine with localStorage persistence (restore ids/order/cursor/offset), gapless scheduler, crossfade-aware transitions (P2-B).
- **Scrobble:** actual-played-time accounting from feishin's sparse report stream → threshold ruling (min(half, 4 min)) → tally + live snapshot bump → durable outbox → `recordPlayEventStrict` cloud drain; the wrapped listening ledger rides the same crossing (P2-B + P3-B + P4-A).
- **Jam (listening rooms):** the P3A playlist CRDT stack (48-byte op rows, hybrid-logical-clock op ids, fractional indexing, golden-vector-pinned codec) + the P4B live room: host/follower playback deltas over Supabase Realtime broadcast, epoch fencing, drift correction (2 s hard / 750 ms soft), room codes + invite links, host lease heartbeat, upvotes, the Leave/Desync escape hatch.
- **EQ:** 10-band ISO biquad cascade + preamp through Web Audio on both crossfade slots, feeding feishin's built-in visualizer; CORS-safe graph bypass (audio never mutes); 6 built-in presets + custom presets; brand theming (OLED-dark + brand red, additive Mantine preset — upstream themes untouched).
- **Downloads (P4-A):** OPFS at-rest storage in the `STREAMIFY_DL_V1` container — XChaCha20-Poly1305 per-chunk AEAD with chunk-index AAD, wasm cipher (sha256-pinned artifact, vitest-enforced), quarantine on tamper, offline serve bypassing the resolver entirely.
- **Wrapped (P4-A):** time-windowed listening ledger (7d/30d/YTD/all), top artists/tracks, discovery rate, hour-of-day histogram, local+cloud no-double-count merge.
- **/debug (P4-A + P5-A):** the gated diagnostics page — resolver rungs, scrobble outbox depth/flush, CRDT sync state, ledger counts, OPFS quota, wasm self-test — behind the pure ruling (dev mode, persisted developer grant, or `?debug=1` for authenticated admins), with the CanaryConsole mounted by P5-A.
- **Settings (P4B):** codec ceiling (Opus/AAC/Auto), crossfade 0–12 s, gapless, `STREAMIFY_RELAY_BASE` override + health check, Jam permission defaults, scrobble toggle, Google account status/refresh/sign-out.
- **i18n (P5-B):** the 39-locale en.json-only policy + CI gate (this document, §1–§4).

## Endpoint coverage (the controller surface)

**52 of 69** `InternalControllerEndpoint` methods are implemented; **17 remain honest stubs** that throw `StreamifyNotImplementedError` with the endpoint name (the UI gates their affordances away per feishin's absent-endpoint pattern):

- **Auth/session/server:** authenticate, getUserInfo, getServerInfo, getRoles, getScanStatus, startLibraryScan, refreshItems
- **Library reads:** getMusicFolderList, getSongList/Count, getAlbumList/Count, getAlbumDetail, getAlbumInfo, getArtistList/Count, getAlbumArtistList/Count, getAlbumArtistDetail, getAlbumArtistInfo, getGenreList, getRandomSongList, getSimilarSongs, getTopSongs, getAlbumRadio, getArtistRadio, search, getInternetRadioStations
- **Playlists (CRDT-backed):** getPlaylistList/Count, getPlaylistDetail, getPlaylistSongList/Ids, createPlaylist, updatePlaylist, deletePlaylist, setPlaylistSongs, addToPlaylist, removeFromPlaylist, movePlaylistItem, replacePlaylist
- **Playback:** getStreamUrl, getDownloadUrl, savePlayQueue, getPlayQueue, scrobble
- **Favorites:** createFavorite, deleteFavorite, getFavoriteSongs
- **Images:** getImageUrl, getImageRequest
- **Stubbed (17):** getFolder, getLyrics, getStructuredLyrics, getTagList, getUserList, jukeboxControl, setRating, shareItem, createInternetRadioStation, updateInternetRadioStation, deleteInternetRadioStation, deleteInternetRadioStationImage, uploadInternetRadioStationImage, uploadArtistImage, deleteArtistImage, uploadPlaylistImage, deletePlaylistImage

## Phase ledger (PRs)

| Phase | PR | Delivered |
|---|---|---|
| P0 fork base | #181 | vendored v1.17.0, STREAMIFY registration, 69 stubs, golden-path verification vs a live Navidrome |
| P1-A auth | #182 | `/streamify-auth`, Supabase Google OAuth, synthetic server entry |
| P1-B library | #184 | 24 read endpoints, snapshot bridge, local-first browse/search |
| P2-A resolver | #185, #186 | `getStreamUrl` single entry point + link-solver lock; dual react-player engine; vendored mirror, CI byte-identity |
| P2-B spine | #187 | queue state machine + persistence, gapless scheduler, scrobble outbox |
| P3-A jam CRDT | #190 | full playlist endpoint surface, dual-replica convergence, offline op-log |
| P3-B favorites | #191 | favorites/curation, playback persistence, scrobble bridge (actual played time) |
| P4-A downloads | #192 | OPFS + wasm chunk-cipher downloads, wrapped stats, /debug gate |
| P4-B jam/EQ | #193 | jam live-queue UI, EQ DSP + visualizer, brand theming, settings |
| P5-A cutover | (Engineer A) | Dockerfile/compose relay packaging, CanaryConsole in /debug, `front-end/web → legacy/front-end-web`, workflow path-gating, PATCHES.md consolidation |
| P5-B hardening | **this PR** | 39-locale i18n gate, dead-reference cleanup, observability guards, this cutover record |

## Verification results

- **Vitest:** 313 network-free tests across 31 files, 100% pass — the resolver walk/stale/seek locks, queue persistence round-trips, scrobble threshold/outbox/poison-drop battery, CRDT convergence (both delivery orders, duplication, offline op-log), jam live protocol/wire/CAD/frac golden vectors, downloads cipher round-trip/tamper/quarantine/OPFS lifecycle, wrapped aggregation/ledger merge, the /debug gate ruling, and (new) the observability leak guards.
- **Feishin Web CI** (`feishin-ci.yml`): install → lint (typecheck node+web, eslint --max-warnings=0, stylelint) → **i18n check** → tests → `build:web`. Green on this branch.
- **Resolver-mirror sync:** `sync_feishin_resolver.py --check` green on every `front-end/**` PR (web-build.yml lane).
- **In-browser verifications** (per-phase ADRs): P0 Navidrome golden path (add server → browse → play → auto-advance, 206 media responses); P1 Streamify auth + library browse; P2 engine activation (real stream resolution, crossfade); P4 downloads/wrapped/jam/EQ exercised against the built app.
- **Localization:** `node scripts/check-i18n.mjs` — 39 catalogs parse, 175 Streamify keys en.json-only and non-empty, all surface `t()` references resolve; the 38 Weblate catalogs untouched by this PR (`git diff --stat` shows zero edits outside `en.json`).

## Known honest gaps (carried, not hidden)

- Lyrics/ratings/sharing/radio-station management/jukebox remain stubbed (the 17 above).
- Licensed-content playback stays behind the same measured walls as every Streamify surface (datacenter bot-gating; the PO-free ladder covers standard content — E2/E3).
- Jam queue-mutation permissions are enforced host-side, not server-side (the journal is append-only by design; server-side enforcement needs SQL, deliberately out of scope).
- The Kalman-PLL playback sync refinement remains deferred (threshold-based drift correction ships); documented in the P4B ADR.
