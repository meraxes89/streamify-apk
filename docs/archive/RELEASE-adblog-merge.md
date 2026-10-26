# Release Documentation: streamify-adblog → streamify-yt-spt → main

**Merge date**: 2026-08-26 · **Merge commit**: `86b59fa` · **Lineage**: `fb9e509 → 17af26b` (19 commits)

Compare views:
- https://github.com/zephyr4289/streamify-apk/compare/main...streamify-adblog
- https://github.com/zephyr4289/streamify-apk/compare/main...streamify-yt-spt

---

## PR 1 — feat(resolver): R-NP tier — NewPipe Extractor + BotGuard PO-token WebView
**Commit**: `56061ac`

Adds a resolution tier between the native client race and the alternate-upload
search that defeats SABR URL-stripping and licensed-content bot-walls:

- `newpipeextractor v0.26.4` (JitPack), lazy-init with an OkHttp-backed Downloader
- `PoTokenWebView` port: hidden WebView runs YouTube's BotGuard VM
  (`/api/jnn/v1/Create` → `runBotGuard` → `GenerateIT`), minting one streaming
  token per visitorData + per-video player tokens; expiry/recreate/single-retry
- `JavascriptUtil` ported on org.json + android.util.Base64 (no new plugins)
- `StreamifyPoTokenGenerator` implements NewPipe's `PoTokenProvider`
- `resolveViaNewPipe()` maps best audio (m4a > opus) to `ResolvedStream`;
  full `LadderTrace`/`ResolveGate` forensics

**Ladder**: `edge cache → R1 fleet race → R-NP → R2 search → exhaust`

## PR 2 — feat(fleet): release-free client & search adaptation
**Commits**: `f4597e1`, `942bdc7`

- `FleetConfig`: 2KB schema-guarded JSON from repo raw URL, 6h TTL, hardened
  ingestion (10KB cap, client-name whitelist, length caps, STS range, regex-
  validated search versions). Last-good kept on any failure; baked defaults
  are the final fallback
- Resolver: `SIGNATURE_TIMESTAMP` const → dynamic; `CLIENT/VIDEO_TARGETS` →
  builder functions merging remote-over-defaults
- `YouTubeMusicSearchApi`: WEB_REMIX/WEB versions now remote-tunable
- Self-healing loop: daily `resolver-canary` converts probe survivors into
  `fleet-config.json` and bot-commits it — apps adapt within TTL, no release

## PR 3 — feat(terminal): opt-in zero-GC diagnostic capture for all users
**Commits**: `86e04d6`, `3a8b1d2`

- **OFF (default)**: `append()` = volatile read + logcat forward. Zero alloc,
  zero locks, zero disk — touch/nav/HTTP/lifecycle logging is free
- **ON**: 4MB off-heap `DirectByteBuffer` frame-ring (`[len][ts][lvl][tag][msg]
  [magic]`, wrap-pad sentinels, torn-frame guard) — zero Java allocation per
  line; formatting + credential redaction at read time (precompiled regexes)
- **2h auto-shutoff**; disarm drains spool → wipes ring → releases buffer
- Terminal screen for **all users**: master capture switch, live tail, level
  chips + grep, Copy / Share / Download-to-Downloads
- OkHttp wire tracing follows the toggle via volatile interceptor level

## PR 4 — feat(dsp): re-wire the fused audio processor
**Commit**: `c9649c8`

Restores the `buildAudioSink` override deleted as collateral in `6a3d82a`:

```kotlin
DefaultAudioSink.Builder(context)
    .setEnableFloatOutput(true)
    .setAudioProcessors(arrayOf(streamifyProcessor))
    .build()
```

Online streams now pass through **loudnessDb pre-gain → LUFS normalization →
soft-knee limiter → Rust parametric EQ** instead of raw CDN audio. The Rust EQ
branch of `EqualizerManager` becomes reachable. `onDestroy` now frees the DSP
native handles (previously leaked two per process).

## PR 5 — fix(player): deterministic full-player layout
**Commits**: `77b5506` (prior art), `527e611`

- Hero artwork loses `weight()`: width-driven `aspectRatio` + hard `heightIn`
  cap — can never consume unbounded height and push seekbar/controls off-screen
- `chromeDimmed` floor 0.15 → 0.35 with 3s auto-undim (invisible-UI trap fixed)
- Explicit **Lyrics** button in the metadata row (was hidden long-press only)

## PR 6 — fix(ci): advisory gates + flake-proof status
**Commits**: `0948ccb`, `17af26b`, canary rework

- Gatekeeper probe: advisory (`continue-on-error`) — GitHub datacenter IPs are
  bot-walled by YouTube regardless of fingerprints; results go to step summary
- Test shards / LibFuzzer / emulator matrix: advisory so build+release status
  is never masked by shared-runner flakiness
- Admin release channel: `streamify-adblog` pushes publish
  **"Streamify Admin \<build\> (Dev Terminal)"** under `admin-build-*` tags

## PR 7 — fix(identity): hydration guard + playback error state
**Commit**: `0948ccb` (part)

- `hydrateTrack` weak-fuzzy matching can no longer merge tracks carrying
  different explicit videoIds (poisoned-pin protection)
- `PlayerState.lastError` set at all three failure points, cleared on success

## PR 8 — fix(search): burst-gated speculative prefetch
**Commit**: `942bdc7` (part)

Speculative pre-resolve was parallel `resolveStreamJit × top-3` per search
(~12 upstream calls/query with the client race). Now: single top-1 prefetch,
gated on a 500ms typing pause; cancelled instantly on track tap.

---

### Known follow-ups (parked)
- Road B: yt-dlp runtime tier (admin-gated) — pending R-NP miss-rate data
- Remote log upload to Supabase for one-tap remote debugging
- Canary probe: assert `url` presence, not just itag presence (SABR-era trap)
