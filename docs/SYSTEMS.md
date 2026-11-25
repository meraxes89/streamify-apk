# Streamify subsystem index (S01–S23)

This is the single entry point for understanding the codebase. Every subsystem
has a stable ID, one page in [systems/](systems/), and an owner. IDs are never
renumbered or reused; names may change. Use the ID in PR titles (`[S08]`),
commit trailers, ADRs, and the changelog.

Read order:

1. [docs/README.md](README.md) — the documentation contract (types, lifecycle, drift rules)
2. This index — find your subsystem
3. Its `Sxx` page — boundaries, current truth, known limits, backlog
4. [ADRs](adr/README.md) — why the current shape was chosen
5. [RFCs](rfc/README.md) — proposals, accepted or rejected
6. [IDEA_LOG.md](IDEA_LOG.md) — where uncommitted ideas go before they become any of the above

## Conventions

- `status`: `planned` → `active` → `mature` → `deprecated` → `removed`.
  A removed subsystem keeps its page with the removal commit and successor.
- `tier`: `shell` (deployable app), `client` (library inside the apps),
  `feature` (product domain), `backend` (server/data), `engineering` (tooling).
- Every system page carries `<!-- doc-verified-against: <sha> -->`.
  Changing a subsystem's behaviour requires updating its page and pin
  **in the same PR**.
- "Current truth" describes the checked-out tree. Historical claims are
  labelled `HISTORICAL` with the commit that retired them, never silently
  rewritten.

## Index

| ID | Subsystem | Tier | Status | Owns | Primary sources | Spec | Decisions |
|---|---|---|---|---|---|---|---|
| S01 | Android shell | shell | active | Android app entry, manifest, services, widgets, signing | `androidApp/` | [11](architecture/engines/11_playback_ui.md) | — |
| S02 | Vivo shell | shell | active | Vivo store variant, package identity, OAuth callback | `vivoAndroidApp/` | — | [ADR-005](adr/ADR-005-vivo-package-identity-and-oauth-resolution.md) |
| S03 | Desktop shell | shell | active | Compose Desktop entry point, installers, Conveyor | `desktopApp/` | [MULTIPLATFORM_PLAN](architecture/MULTIPLATFORM_PLAN_27_10.MD) | — |
| S04 | Web/wasm shell | shell | active | Browser target, content-addressed bundle delivery | `composeApp/src/wasmJsMain`, `room-wasm-stub/` | — | — |
| S05 | iOS framework target | shell | active | iOS framework build, Apple platform bindings | `composeApp/src/iosMain` | [MULTIPLATFORM_PLAN](architecture/MULTIPLATFORM_PLAN_27_10.MD) | — |
| S06 | Playback engine | client | active | Media3 players, crossfade automaton, playback state | `core/media/media3/`, `core/data/.../mediaservice/` | [13](architecture/engines/13_playback_dataplane.md), [11](architecture/engines/11_playback_ui.md) | — |
| S07 | Desktop playback | client | active | libmpv via JNA, single-thread adapter, `af` graph | `core/media/media-jvm/` | [11](architecture/engines/11_playback_ui.md) | — |
| S08 | DSP & acoustics | client | active | EQ, delay, reverb, 8D imaging, AutoEq curves | `core/media/media3/.../audio/`, `core/domain/.../data/player/`, `core/service/autoEqService/` | [02](architecture/engines/02_dsp_acoustic.md) *(historical)* | [ADR-009](adr/ADR-009-kotlin-only-audio-pipeline.md) |
| S09 | Audio output & HAL | client | active | Device routing, cast, spatial capability, latency | `streamify-modules/media-hal/`, `cast/`, `core/media/media3/.../AudioOutputRouter.kt` | — | — |
| S10 | Local persistence | client | active | Room database, DAOs, offline-first reads | `core/data/` | [07](architecture/engines/07_database_storage.md) | [ADR-003](adr/ADR-003-hybrid-sqlite-supabase-sync.md) |
| S11 | Sync engine | client | active | LWW sync, outbox, device fleet, Wrapped upload | `streamify-modules/supabase/` | [07](architecture/engines/07_database_storage.md) | [ADR-006](adr/ADR-006-unified-hybrid-sync-v2.md), [ADR-007](adr/ADR-007-user-graph-alignment-and-cross-device-continuity.md), [ADR-008](adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md) |
| S12 | Stream resolution | feature | active | YouTube stream tiers, SABR, range gateway client | `core/service/kotlinYtmusicScraper/`, `server/src/slices/stream/` | [01](architecture/engines/01_stream_resolution.md) | — |
| S13 | Search & catalog | feature | active | Search, charts, playlists, catalog mapping | `server/src/slices/catalog/`, `core/service/spotify/` | [09](architecture/engines/09_search_graph.md) | — |
| S14 | Lyrics & alignment | feature | active | LRC fetch, bytecode timing, Bluetooth offset | `core/service/lyricsService/`, `streamify-modules/lyrics-slyr/` | [03](architecture/engines/03_lyrics_alignment.md) | — |
| S15 | Jam & mesh | feature | active | Listen-together CRDT, PTP clock, LAN broadcast | `streamify-modules/jam/`, `server/src/slices/room/` | [06](architecture/engines/06_jam_distributed.md), [JAM](architecture/JAM_ENGINE_SPEC.md) | — |
| S16 | Continuum radio | feature | planned | Recommendation/radio engine (spec only today) | `docs/architecture/engines/04_neural_continuum.md` | [04](architecture/engines/04_neural_continuum.md) | — |
| S17 | Ingestion & importers | feature | active | Spotify, Last.fm, Discord, Kizzy, listen-together import | `core/service/{spotify,lastfm,discord,kizzy,listenTogether}/` | [08](architecture/engines/08_ingestion_scraper.md) | — |
| S18 | Identity & trust | feature | active | Supabase auth, PKCE, crypto vault, attestation | `core/service/loginSync/`, `streamify-modules/supabase/.../Auth*`, `server/src/slices/auth/` | [CRYPTO](architecture/CRYPTO_SPEC.md), [12](architecture/engines/12_server_security.md) | [ADR-005](adr/ADR-005-vivo-package-identity-and-oauth-resolution.md) |
| S19 | Telemetry & Wrapped | feature | active | Fleet registration, listening stats, Wrapped cards | `streamify-modules/supabase/.../SupabaseWrappedSync.kt`, `composeApp/.../wrapped/` | [10](architecture/engines/10_telemetry_profiler.md) | — |
| S20 | Update & announcements | feature | active | Version resolution, signed bundles, popup governance | `composeApp/.../data/update/`, `composeApp/.../announcements/`, `server/src/platform/bundle-gate.mjs` | — | — |
| S21 | Server | backend | active | Fastify modular monolith: 6 vertical slices | `server/src/` | [12](architecture/engines/12_server_security.md) | [ADR-008](adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md) |
| S22 | Supabase | backend | active | Schema, RLS, RPCs, realtime, migrations | `supabase/` | [BACKEND RFC](rfc/RFC-002-backend-cloud-graph.md) | [ADR-003](adr/ADR-003-hybrid-sqlite-supabase-sync.md), [ADR-006](adr/ADR-006-unified-hybrid-sync-v2.md) |
| S23 | CI, release & docs | engineering | active | 11 workflows, release gates, doc integrity scripts | `.github/workflows/`, `scripts/`, `docs/` | — | — |
| S24 | Sovereign GPU engine | client | active | Skia/Metal runtime shaders, DRS, thermal/refresh governor | `streamify-modules/streamify-graphics/`, `docs/gpu/` | [ARCHITECTURE](gpu/ARCHITECTURE.md) | [ADR-010](adr/ADR-010-sovereign-gpu-engine.md) |

## Adding or retiring a subsystem

1. Append a row to `IDEA_LOG.md` first; do not create a page for a spark.
2. When the idea survives, allocate the next ID (`S25`), add the index row and
   a stub page **before** the code lands.
3. If the change is expensive to reverse, write an RFC; once accepted, write a
   new ADR that links back to it.
4. On removal, set `status: removed`, record the removal commit and successor,
   and keep the page. Never delete the page or renumber the ID.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
