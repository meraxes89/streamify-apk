import {
    getStreamUrl,
    invalidateStreamUrl,
    type LadderRung,
    probeStreamUrl,
    recentResolveLog,
    type StreamUrlResolution,
} from './resolver/audio/get-stream-url';
import { relayBaseCandidate } from './resolver/lib/relayBase';

/**
 * api/streamify/playback/stream-source.ts — the engine-side consumption
 * layer (P2-A app stream).
 *
 * House rule (the P2-A kickoff): ZERO resolution logic here. This module
 * imports `getStreamUrl` from the vendored resolver mirror like any other
 * consumer — resolution behavior is fixed in `front-end/web` (the resolver
 * stream) and vendored here byte-identically (CI-enforced by
 * scripts/verify_feishin_resolver.py). What lives HERE is engine wiring:
 *
 *   - resolveStreamifyTrack: the controller seam's back end (feishin song id
 *     → snapshot row → videoId → getStreamUrl)
 *   - onStreamifySourceError: the error claim hook (wired by the thin
 *     upstream seam in web-player.tsx / web-player-engine.tsx). Probe the
 *     failing URL: 403/410 → stale → invalidate + re-resolve (deferred when
 *     mid-crossfade — the contract's "never mid-crossfade" rule; the
 *     cached-ladder walk covers the window). Healthy URL → decode error →
 *     walk the cached ladder (no pipeline hit — the contract's "retries hit
 *     the cache" rule).
 *   - applyStreamifySeek: the seek-strategy hook — range-capable URLs seek
 *     in place (the stock path); non-range URLs reload from zero + silent
 *     fast-forward (muted, high-rate), so the user never sees a restart.
 *
 * Contract: front-end/decisions/p2/P2A-contract-getstream-url.md.
 */
import { usePlayerStoreBase } from '/@/renderer/store';
import { useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { logger } from '/@/renderer/utils/logger';
import { PlayerStatus, PlayerStyle } from '/@/shared/types/types';

export {
    getStreamUrl,
    invalidateStreamUrl,
    peekStreamUrl,
    probeStreamUrl,
} from './resolver/audio/get-stream-url';
export type {
    GetStreamUrlOptions,
    ProbeOutcome,
    ResolveLogRecord,
    StreamUrlResolution,
    StreamUrlRung,
} from './resolver/audio/get-stream-url';
export { relayBaseCandidate } from './resolver/lib/relayBase';

// ---------------------------------------------------------------------------
// The honest failure states
// ---------------------------------------------------------------------------

interface PlaybackSession {
    cursor: number;
    resolution: StreamUrlResolution;
    videoId: string;
}

/** The track has no video mapping in the library snapshot. */
export class StreamifyNoVideoError extends Error {
    constructor(songId: string) {
        super(`streamify: song ${songId} has no stream video mapping in the library snapshot`);
        this.name = 'StreamifyNoVideoError';
    }
}

// ---------------------------------------------------------------------------
// Playback sessions (per-video ladder state + the URL reverse index)
// ---------------------------------------------------------------------------

/** A SABR-only resolve: react-player cannot parse SABR (the P2-A gate). */
export class StreamifySabrError extends Error {
    constructor(videoId: string) {
        super(
            `streamify: ${videoId} resolved SABR-only — the element engine cannot parse SABR ` +
                '(see the P2-A contract; the daily corpus instrumentation counts the hit)',
        );
        this.name = 'StreamifySabrError';
    }
}

const sessions = new Map<string, PlaybackSession>();
const urlIndex = new Map<string, string>();

export interface SourceErrorShape {
    code?: number;
    label: string;
}

/**
 * The rung that last resolved a video (the scrobble bridge attaches this
 * to play events — "which lane served the listen"). Reads the resolver's
 * bounded log ring backwards; null when this video has no resolve record
 * in the ring (never played, or rotated out past 50 resolves).
 */
export function lastRungForVideo(videoId: string): LadderRung | null {
    const log = recentResolveLog();
    for (let i = log.length - 1; i >= 0; i -= 1) {
        if (log[i].trackId === videoId) return log[i].rung;
    }
    return null;
}

// ---------------------------------------------------------------------------
// The controller seam's back end
// ---------------------------------------------------------------------------

/**
 * Claim a media-element error for the Streamify layer. SYNC decision, async
 * recovery: returns true when the failing source belongs to a Streamify
 * session (the stock retry/skip logic stands down); false lets the stock
 * path run. The probe classifies the failure:
 *   - 403/410 → stale URL → invalidate + re-resolve (deferred when
 *     mid-crossfade: the cached-ladder walk covers the window instead —
 *     the pipeline is never hit mid-crossfade, per the contract).
 *   - healthy → decode error → walk the cached ladder (never the pipeline).
 *   - ladder exhausted → honest failure, logged; the user's skip stands.
 */
export function onStreamifySourceError(err: SourceErrorShape, audio: HTMLMediaElement): boolean {
    const url = audio.currentSrc || audio.src;
    const session = sessionByUrl(url);
    if (session === null) {
        return false; // not ours — the stock path owns it
    }
    void recoverFromError(session, url, audio, err);
    return true;
}

/** Resolve a video and register its playback session (ladder + URL index). */
export async function resolveStreamifyTrack(videoId: string): Promise<StreamUrlResolution> {
    const resolution = await getStreamUrl(videoId, { relayBase: relayBaseCandidate() });
    indexResolution(resolution);
    if (resolution.protocol === 'sabr') {
        // Honest state: surfaced to the caller; the instrumentation already
        // counted the hit inside get-stream-url.
        throw new StreamifySabrError(videoId);
    }
    return resolution;
}

// ---------------------------------------------------------------------------
// The mid-crossfade guard (the contract: never re-resolve mid-crossfade)
// ---------------------------------------------------------------------------

function inCrossfadeWindow(session: PlaybackSession): boolean {
    try {
        const player = usePlayerStoreBase.getState().player;
        if (player.transitionType !== PlayerStyle.CROSSFADE) return false;
        if (player.status !== PlayerStatus.PLAYING) return false;
        if (session.resolution.durationMs === null || session.resolution.durationMs <= 0) {
            return false;
        }
        const position = useTimestampStoreBase.getState().timestamp;
        const durationSec = session.resolution.durationMs / 1000;
        return durationSec - position <= player.crossfadeDuration;
    } catch {
        return false;
    }
}

// ---------------------------------------------------------------------------
// Source swaps (element-level, position-preserving)
// ---------------------------------------------------------------------------

function indexResolution(resolution: StreamUrlResolution): void {
    sessions.set(resolution.trackId, { cursor: 0, resolution, videoId: resolution.trackId });
    const urls = [resolution.url, ...resolution.ladder.map((r) => r.url)];
    for (const url of urls) {
        if (url !== '' && !urlIndex.has(url)) {
            urlIndex.set(url, resolution.trackId);
        }
    }
}

// ---------------------------------------------------------------------------
// The error claim hook (the thin upstream seam calls this)
// ---------------------------------------------------------------------------

async function recoverFromError(
    session: PlaybackSession,
    failedUrl: string,
    audio: HTMLMediaElement,
    err: SourceErrorShape,
): Promise<void> {
    const at = audio.currentTime;
    const probe = await probeStreamUrl(failedUrl);
    const stale = !probe.ok && (probe.status === 403 || probe.status === 410);

    if (stale) {
        if (inCrossfadeWindow(session)) {
            // The contract: no re-resolve mid-crossfade. The cache entry
            // dies now (the next natural boundary re-resolves); the cached
            // ladder walk recovers the audible window.
            invalidateStreamUrl(session.videoId, 'stale mid-crossfade (re-resolve deferred)');
            logger.warn('streamify: stale URL mid-crossfade — walking the cached ladder', {
                videoId: session.videoId,
            });
            walkCachedLadder(session, audio, at);
            return;
        }
        invalidateStreamUrl(session.videoId, `element ${String(probe.status)} (${err.label})`);
        try {
            const fresh = await getStreamUrl(session.videoId, {
                forceRefresh: true,
                relayBase: relayBaseCandidate(),
            });
            indexResolution(fresh);
            if (fresh.protocol === 'sabr') {
                throw new StreamifySabrError(session.videoId);
            }
            swapSource(audio, fresh.url, at, session.videoId);
            return;
        } catch (resolveErr) {
            logger.warn('streamify: re-resolve refused — falling back to the cached ladder', {
                resolveErr,
            });
            walkCachedLadder(session, audio, at);
            return;
        }
    }

    // Healthy URL (or inconclusive probe): a decode/codec error — the walk.
    walkCachedLadder(session, audio, at);
}

function sessionByUrl(url: string): null | PlaybackSession {
    const videoId = urlIndex.get(url);
    if (videoId === undefined) return null;
    return sessions.get(videoId) ?? null;
}

function swapSource(
    audio: HTMLMediaElement,
    url: string,
    atSeconds: number,
    videoId: string,
): void {
    try {
        audio.src = url;
        audio.load();
        if (Number.isFinite(atSeconds) && atSeconds > 0) {
            // HAVE_NOTHING: the element defers this to its default playback
            // start position when metadata lands.
            audio.currentTime = atSeconds;
        }
        void audio.play().catch(() => undefined);
        logger.debug(`streamify: swapped ${videoId} to a fresh source at ${atSeconds.toFixed(2)}s`);
    } catch (err) {
        logger.error('streamify: source swap failed', { err });
    }
}

function walkCachedLadder(session: PlaybackSession, audio: HTMLMediaElement, at: number): void {
    const next = session.resolution.ladder[session.cursor + 1];
    if (next === undefined) {
        logger.error('streamify: ladder exhausted — honest failure, the skip stands', {
            cursor: session.cursor,
            depth: session.resolution.ladder.length,
            videoId: session.videoId,
        });
        return;
    }
    session.cursor += 1;
    logger.warn('streamify: decode error — walking the cached ladder (no pipeline hit)', {
        from: session.resolution.ladder[session.cursor - 1]?.itag,
        to: next.itag,
        videoId: session.videoId,
    });
    swapSource(audio, next.url, at, session.videoId);
}

// ---------------------------------------------------------------------------
// The seek-strategy hook (the thin upstream seam calls this)
// ---------------------------------------------------------------------------

const FASTFORWARD_RATE = 16; // Chrome's cap; the silent fast-forward rate.

/**
 * Claim a seek for the Streamify layer. SYNC decision: true only when the
 * active source is a Streamify progressive URL whose probe answered
 * non-range — the reload-from-zero + silent-fast-forward strategy (the
 * posted contract). Everything else (range-capable, HLS, other servers)
 * returns false and the stock in-place seek runs.
 */
export function applyStreamifySeek(audio: HTMLMediaElement, targetSeconds: number): boolean {
    if (!Number.isFinite(targetSeconds) || targetSeconds < 1) return false;
    const url = audio.currentSrc || audio.src;
    const session = sessionByUrl(url);
    if (session === null) return false;
    if (session.resolution.protocol !== 'progressive') return false; // HLS: the stock path (manifest-driven)
    if (session.resolution.rangeSupport !== false) return false; // true/null: the stock in-place seek
    void reloadAndFastForward(session, audio, targetSeconds);
    return true;
}

function onceEvent(target: HTMLMediaElement, name: string): Promise<void> {
    return new Promise((resolve) => {
        const done = () => {
            target.removeEventListener(name, done);
            resolve();
        };
        target.addEventListener(name, done);
    });
}

async function reloadAndFastForward(
    session: PlaybackSession,
    audio: HTMLMediaElement,
    targetSeconds: number,
): Promise<void> {
    const url = session.resolution.ladder[session.cursor]?.url ?? session.resolution.url;
    if (url === '') return;
    const wasMuted = audio.muted;
    const wasRate = audio.playbackRate;
    try {
        // Silent: muted while we race to the target — the user never sees a
        // restart (the posted contract's non-range seek strategy).
        const metaReady = onceEvent(audio, 'loadedmetadata');
        audio.muted = true;
        audio.src = url;
        audio.load();
        await metaReady;

        const duration =
            Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : targetSeconds;
        const target = Math.min(Math.max(targetSeconds, 0), Math.max(duration - 0.05, 0));
        if (audio.currentTime >= target) {
            audio.muted = wasMuted;
            return;
        }

        audio.playbackRate = FASTFORWARD_RATE;
        await audio.play().catch(() => undefined);
        await untilTimeUpdate(audio, () => audio.currentTime >= target || audio.ended);
        audio.currentTime = target;
    } catch (err) {
        logger.error('streamify: non-range seek (reload + fast-forward) failed', { err });
    } finally {
        audio.playbackRate = wasRate;
        audio.muted = wasMuted;
    }
}

function untilTimeUpdate(target: HTMLMediaElement, predicate: () => boolean): Promise<void> {
    return new Promise((resolve) => {
        const check = () => {
            if (predicate()) {
                target.removeEventListener('timeupdate', check);
                resolve();
            }
        };
        target.addEventListener('timeupdate', check);
        check();
    });
}
