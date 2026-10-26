# Diagnostic Report: Stream Resolution & Playback Architecture (v1.0.1)

**Date**: August 25, 2026  
**Target Environment**: Linux / Android / Termux  
**Comparison Baseline**: Build 157 (`6ffa6c4`) vs. Flagship HEAD (`303f425` / `308b948`)  

---

## 1. Executive Summary & Core Philosophy

Streamify is built around a **zero-token / single-login philosophy**: users authenticate only with the Supabase registry for account data synchronization, with **zero requirement for Google account authentication, OAuth, or SAPISID extraction**.

In recent commits leading up to `303f425`, streaming resolution broke completely, causing every track in the queue to fail and skip indefinitely. Meanwhile, **Build 157 (`6ffa6c4`)** continued to resolve and play streams reliably on the same network and device.

This document details the exact technical root causes of this disparity and the architectural changes needed to ensure permanent, reliable playback.

---

## 2. Forensic Comparison: Build 157 (`6ffa6c4`) vs. HEAD

| Dimension | Build 157 (`6ffa6c4`) | Regressed HEAD (`303f425`) |
| :--- | :--- | :--- |
| **Progressive Container Fallback (`formats[]`)** | **Enabled without mime restriction**: Added itags 18 (360p) & 22 (720p) carrying stereo AAC audio. | **Blocked by strict mime gate**: `mime.startsWith("audio/")` discarded all `formats[]` entries ($0$ candidates). |
| **Negative Result Cache** | **None**: Failed lookups never poisoned future resolution attempts. | **Poisoned 10-min lockout**: Marked video IDs as "walled", fast-failing subsequent user clicks. |
| **Circuit Breaker** | **None**: Temporary HTTP errors never disabled client routes or songs. | **1-hour hard trip**: Marked video IDs as definitively dead on transient player failures. |
| **Playback Error Recovery** | **Direct re-resolution**: Re-resolved the current track and re-prepared ExoPlayer in place. | **Cascading backoff loop**: Delayed queue advance (`registerResolutionFailure`) causing rapid multi-song skips. |
| **Client Target Fleet** | `ANDROID 21.26.364`, `ANDROID_VR 1.60.19`, `IOS 21.26.4`. | Stale 19.x clients (HTTP 400) or mismatched fake clients. |

---

## 3. Deep Dive: YouTube SABR & The Mime-Gate Regression

### 3.1 What YouTube Returns for Mobile App Clients
When sending `/youtubei/v1/player` requests with Android/iOS client fingerprints without account sessions:
1. YouTube returns `playabilityStatus.status = "OK"`.
2. YouTube returns `streamingData.adaptiveFormats` (19+ formats).
3. **The SABR Protocol Enforcement**: YouTube omits the individual `url` and `signatureCipher` properties from pure audio adaptive formats, instead providing `streamingData.serverAbrStreamingUrl`.
4. YouTube returns the progressive `streamingData.formats` array containing itag 18 (360p MP4) and itag 22 (720p MP4) with direct CDN URLs.

### 3.2 The Mime Gate Bug in HEAD
In Build 157 (`6ffa6c4`), `parsePlayerResponse` processed both adaptive and standard formats:

```kotlin
// Build 157 (6ffa6c4) - WORKING
if (candidateFormats.isEmpty()) {
    val formats = streamingData.optJSONArray("formats")
    if (formats != null) {
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val streamUrl = extractUrlFromFormat(f)
            if (streamUrl.isNotBlank()) {
                f.put("extractedUrl", streamUrl)
                candidateFormats.add(f) // Accepts itag 18 / 22 regardless of "video/mp4" mime
            }
        }
    }
}
```

In subsequent commits, `kotlinParseVerdict` introduced a strict audio-only filter:

```kotlin
// Regressed HEAD - BROKEN
val mime = f.optString("mimeType", "")
val isAudio = mime.startsWith("audio/") // FALSE for "video/mp4; codecs=..."
val streamUrl = extractUrlFromFormat(f)

if (isAudio && streamUrl.isNotBlank()) {
    candidateFormats.add(...)
}
```

Because muxed progressive formats have `mimeType: "video/mp4"`, `isAudio` evaluated to `false`. **All progressive formats were discarded**, leaving `candidateFormats` completely empty ($0$ formats) whenever adaptive audio URLs were omitted by SABR.

### 3.3 Why ExoPlayer Plays Muxed Containers Flawlessly
ExoPlayer's audio render pipeline extracts the audio track (stereo AAC-LC at 96–192 kbps) directly from MP4 containers (itags 18 and 22). It does not require video rendering surfaces, resulting in high-fidelity, low-overhead audio playback.

---

## 4. Poison Caches & The Auto-Advance Failure Loop

### 4.1 `NegativeResultCache` and `StreamifyCircuitBreaker`
When the primary client race failed due to the mime gate:
1. `NegativeResultCache.markWalled(videoId)` locked out the track for 10 minutes.
2. `StreamifyCircuitBreaker.tripHard(videoId)` marked the track dead for 1 hour.
3. If the user tapped the track again or Tier 2 search tried alternate candidates, the negative cache short-circuited resolution, preventing retries.

### 4.2 Cascading Queue Skips in `PlayerViewModel`
In `PlayerViewModel.kt`, failed resolution triggered:
```kotlin
private suspend fun registerResolutionFailure() {
    consecutiveResolutionFailures++
    autoAdvanceBackoffMs = if (autoAdvanceBackoffMs == 0L) 500L else (autoAdvanceBackoffMs * 2).coerceAtMost(30_000L)
    // Delayed launch calling advanceQueue(isUserSkip = false)
}
```
This created an unstoppable cascade where ExoPlayer threw an error on track $N$, triggering an auto-skip to $N+1$, which was also blocked by the negative cache, immediately auto-skipping to $N+2$, until the user's entire playlist was consumed.

---

## 5. Architectural Corrective Actions

To ensure bulletproof playback while preserving the zero-token architecture:

1. **Restore Full Container Fallback**: Ensure `streamingData.formats` (itags 18/22) is fully admitted whenever `adaptiveFormats` yields zero direct URLs.
2. **Neutralize Destructive Poison Caches**: Ensure `NegativeResultCache` and `StreamifyCircuitBreaker` operate as non-blocking no-ops so transient network errors never result in persistent track lockouts.
3. **Deterministic Playback & Graceful Error Handling**: Replace the cascading auto-advance backoff loop in `PlayerViewModel` with a clean error notification, allowing the user to retry without destroying queue state.
4. **Preserve Single-Login Integrity**: Maintain clean, unauthenticated client targets (`ANDROID 21.26.364`, `ANDROID_VR 1.60.19`, `IOS 21.26.4`) without requiring Google/YouTube user credentials.
