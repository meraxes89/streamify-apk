# P2-A contract — `getStreamUrl` + seek/offset semantics

**Status: POSTED (week one) — v1, 2026-11-17.** Owner: Engineer A (P2 driver).
Consumers: the dual react-player engine (the feishin fork's web player), any
future engine. Implementation: `front-end/web/src/audio/get-stream-url.ts`
(the resolver stream). This note is the half-page contract the engine side
builds against; changes land as new versions of this file, never silently.

## The entry point

`getStreamUrl(trackId, { relayBase, … }) → Promise<StreamUrlResolution>`. One
object, everything the engine needs:

```
url           the live rung's URL (ladder[0] for progressive; the manifest for
              hls; the serverAbrStreamingUrl for sabr — NOT element-consumable)
protocol      'progressive' | 'hls' | 'sabr'
ladder[]      ranked rungs: rank/itag/url/mimeType/audioOnly/codec/bitrate
codecs        the live rung's codec descriptor
resolvedAt / expiresAt   epoch ms (wire expiresInSeconds when present, ~6 h;
              documented 6 h default when the wire is silent; expirySource
              says which)
durationMs    drives the seek bar before the player reports its own
rangeSupport  probed (1-byte Range request) — selects the seek strategy
contentLength / title / artist / rung / isSabr
```

It wraps `resolveAudioStream` (the link solver). Consumers never import the
pipeline directly; the solver's own exports are frozen and fixture-locked.

## The ladder

Ranked by the W1.2-derived element order: **audio-only opus/webm → m4a (aac)
→ muxed video itag (last resort)**. `ladder[0]` is live — the pipeline's own
conservative element pick (AAC-first) is re-ranked beneath it, so a browser
that cannot decode rung 0 (Safari vs webm/opus) walks down at runtime instead
of failing. On decode/buffer error the engine walks DOWN in place — same
track, same offset, from the cached resolution. **A track never fails on its
first rung**; failure is declared only when the ladder is exhausted. HLS wins
are a single-manifest rung (no walk exists). SABR wins carry no element URL
at all — react-player cannot parse SABR; that is the honest unplayable state
and it is instrumented (below).

## Cache & re-resolve

The last resolution is cached per track. **Seeks and retries (ladder walks,
health probes) hit the cache, never the pipeline.** Exactly two things
invalidate: expiry (`expiresAt` passing) and a reported 403/410 on the live
URL (`invalidateStreamUrl(trackId, reason)` → next `getStreamUrl` re-resolves).
Re-resolve happens at the next natural boundary — **never mid-crossfade**: an
in-flight crossfade finishes on the dying resolution, and an open media
request outlives its own URL expiry (the URL dies for NEW requests, not for
the stream already serving).

## Seek

- **Range-capable URL** (`rangeSupport: true`): in-place seek on the SAME
  URL — the element issues Range requests; no reload, no re-resolve.
- **Non-range URL** (`rangeSupport: false`): reload from zero + silent
  fast-forward (muted, high-rate playback to the target, then restore).
  The user never sees a track restart.
- **`rangeSupport: null`** (HLS, or the probe itself failed): HLS seeks in
  place via its manifest; on progressive, null is read as false
  (conservative).
- Offsets are absolute track positions. Crossfade scheduling is driven by
  the active slot's remaining time (`durationMs − position`), never the wall
  clock.

## Preload / crossfade (the dual engine)

Engine A owns the current track; Engine B holds the next. The idle slot
resolves its URL as soon as the queue advances it — always ≥ the crossfade
window, typically minutes early, never later than ~T−15 s — which is safe
for the ~6 h URL lifetime. Engine B's URL is promoted only at crossfade
completion. `durationMs` drives the seek bar until the player reports its
own duration.

## Instrumentation (the SABR gate)

Every resolve logs protocol + format (progressive/hls/sabr, rung, itag,
codec, ladder depth, rangeSupport, expiry). The daily corpus run
(`resolver-canary.yml → sabr-corpus`, the W1.2 canonical IDs) reports the
SABR hit rate. **The custom engine proceeds only if that rate makes playback
genuinely unusable — a checkpoint decision with the lead after one week of
data.** Until then it does not exist; nobody builds it speculatively.
