/**
 * features/streamify/smart-sync/smart-sync-engine.ts — W2-B: the 500 MB
 * OPFS predictive pre-fetcher (N+1 / N+2) with LRU eviction + circuit
 * breaker (see front-end/decisions/waves/W2B-credits-smartsync-discover.md).
 *
 * DESIGN RULINGS (the task's non-negotiables, enforced here):
 *
 * - POLITE CONSUMER: the engine never touches the resolver tree or
 *   relayBase — it calls the P4-A DownloadManager's startDownload with
 *   source 'smart-sync', riding the SAME pipeline the manual downloads
 *   ride (resolveOnline → fetch → XChaCha20 seal → OPFS).
 *
 * - SEQUENTIAL, POST-BUFFER: one pre-fetch at a time (concurrency 1). The
 *   RUNTIME HOOK (use-smart-sync.ts) fires sync() only after the active
 *   track has been playing cleanly for SMART_SYNC_CLEAN_PLAY_MS — the
 *   engine itself re-checks the transport so a late call can never
 *   compete with playback bandwidth.
 *
 * - IMMEDIATE CIRCUIT BREAKER: any 403 / 429 / bot-gate / solver error on
 *   a pre-fetch trips a SMART_SYNC_BREAKER_MS cooldown and HALTS the whole
 *   pass (N+2 is never attempted after N+1 burns) — background jobs can
 *   never burn the Apps Script daily URLFetch quota or trigger upstream
 *   rate limits. Quota (OPFS) and abort failures do NOT trip the breaker
 *   (they are not solver answers); a plain network failure neither.
 *
 * - METERED / SAVE-DATA: sync() is skipped while
 *   navigator.connection.saveData is true UNLESS the user explicitly
 *   opted in (the settings toggle's save-data override).
 *
 * - THE 500 MB CEILING (524,288,000 bytes) applies to source
 *   'smart-sync' storage ONLY. Eviction is LRU by
 *   lastPlayedAt ?? completedAt ?? createdAt ascending and NEVER touches
 *   manual downloads — the manifest's source tag is the hard boundary
 *   (P4-A sidecars without the field read as 'manual'). The engine also
 *   refuses to push browser origin storage into critical pressure
 *   (navigator.storage.estimate() usage/quota >= 0.9).
 *
 * ALL TIME/STORAGE DEPENDENCIES ARE INJECTED — the vitest lane pins every
 * rule without a network, an OPFS, or a clock.
 */

import type {
    DownloadManager,
    DownloadOutcome,
    DownloadSpec,
} from '/@/renderer/api/streamify/downloads/download-manager';

import { sourceOfMeta } from '/@/renderer/api/streamify/downloads/meta';
import {
    getStreamifySmartSyncState,
    setStreamifySmartSyncState,
} from '/@/renderer/store/streamify-smart-sync.store';
import { logger } from '/@/renderer/utils/logger';

// ---------------------------------------------------------------------------
// The locked constants
// ---------------------------------------------------------------------------

/** 500 MB — the hard smart-sync ceiling (bytes). */
export const SMART_SYNC_CAP_BYTES = 524_288_000;
/** The circuit-breaker cooldown (ms). */
export const SMART_SYNC_BREAKER_MS = 15 * 60 * 1000;
/** How long the active track must play cleanly before the first pre-fetch. */
export const SMART_SYNC_CLEAN_PLAY_MS = 5_000;
/** The predictive lookahead: N+1 and N+2. */
export const SMART_SYNC_LOOKAHEAD = 2;
/** Origin-storage pressure guard (navigator.storage.estimate()). */
export const SMART_SYNC_STORAGE_PRESSURE = 0.9;
/** Fallback incoming-size estimate when duration is unknown (bytes). */
const DEFAULT_INCOMING_ESTIMATE_BYTES = 6 * 1024 * 1024;
/** Bytes/sec the codec ladder tops out around (160 kbps opus + container). */
const ESTIMATED_BYTES_PER_SEC = 26 * 1024;

// ---------------------------------------------------------------------------
// The engine
// ---------------------------------------------------------------------------

export interface SmartSyncCandidate {
    spec: DownloadSpec;
}

export interface SmartSyncDeps {
    clock?: () => number;
    /** navigator.connection.saveData — absent in node/tests. */
    connectionSaveData?: () => boolean;
    /** The transport gate (the engine double-checks the hook). */
    isPlaying?: () => boolean;
    manager: Pick<DownloadManager, 'listEntries' | 'remove' | 'startDownload' | 'touchPlayed'>;
    /** The queue's next tracks (queue-source's reader in production). */
    readQueue?: () => { currentVideoId: null | string; upcoming: DownloadSpec[] };
    /** Settings snapshot (the store's read in production). */
    settings?: () => { enabled: boolean; saveDataOverride: boolean };
    /** navigator.storage.estimate — absent in node/tests. */
    storageEstimate?: () => null | Promise<null | { quota: number; usage: number }>;
}

export interface SmartSyncEngine {
    /** The breaker probe (the runtime hook's gate). */
    breakerOpen(now?: number): boolean;
    /** Remove every smart-sync entry (the settings button). */
    breakerState(): { openUntilMs: number; reason: null | string };
    clearCache(): Promise<{ entries: number; freedBytes: number }>;
    stampPlayed(videoId: string): Promise<void>;
    sync(): Promise<SmartSyncPass>;
    usage(): Promise<{ entries: number; usedBytes: number }>;
}

export interface SmartSyncPass {
    /** Why the pass stopped doing work (the runtime hook logs it). */
    halted: 'breaker-open' | 'disabled' | 'done' | 'not-playing' | 'pressure' | 'save-data';
    /** videoIds this pass actually pre-fetched. */
    prefetched: string[];
}

interface SmartEntry {
    bytes: number;
    completedAt: null | string;
    lastPlayedAt: null | string;
    videoId: string;
}

export function createSmartSyncEngine(deps: SmartSyncDeps): SmartSyncEngine {
    const {
        clock = Date.now,
        connectionSaveData = () =>
            typeof navigator !== 'undefined' &&
            (navigator as { connection?: { saveData?: boolean } }).connection?.saveData === true,
        isPlaying = () => true,
        manager,
        readQueue,
        settings = () => ({ enabled: true, saveDataOverride: false }),
        storageEstimate = () => null,
    } = deps;

    let syncing = false;
    const queueReader = readQueue;

    // -----------------------------------------------------------------
    // Store plumbing (the settings meter + breaker surface)
    // -----------------------------------------------------------------

    function publishUsage(usedBytes: number, entries: number): void {
        setStreamifySmartSyncState({ usage: { entries, usedBytes } });
    }

    function tripBreaker(reason: string): void {
        const openUntilMs = clock() + SMART_SYNC_BREAKER_MS;
        setStreamifySmartSyncState({ breaker: { openUntilMs, reason } });
        logger.warn(`streamify smart-sync: circuit breaker open (15 min) — ${reason}`);
    }

    async function smartEntries(): Promise<SmartEntry[]> {
        const all = await manager.listEntries();
        return all
            .filter((meta) => meta.status === 'complete' && sourceOfMeta(meta) === 'smart-sync')
            .map((meta) => ({
                bytes: meta.ciphertextBytes,
                completedAt: meta.completedAt,
                lastPlayedAt: meta.lastPlayedAt ?? null,
                videoId: meta.videoId,
            }));
    }

    // -----------------------------------------------------------------
    // LRU eviction (smart-sync entries only — manual is untouchable)
    // -----------------------------------------------------------------

    /** Evict LRU smart-sync entries until `needed` bytes fit under the cap.
     * Returns the still-needed shortfall (0 = it fits). */
    async function evictForBudget(needed: number): Promise<{ evicted: string[] }> {
        const entries = await smartEntries();
        let used = entries.reduce((sum, e) => sum + e.bytes, 0);
        if (used + needed <= SMART_SYNC_CAP_BYTES) return { evicted: [] };
        const byLru = [...entries].sort((a, b) => lruKeyOf(a) - lruKeyOf(b));
        const evicted: string[] = [];
        for (const candidate of byLru) {
            if (used + needed <= SMART_SYNC_CAP_BYTES) break;
            await manager.remove(candidate.videoId);
            used -= candidate.bytes;
            evicted.push(candidate.videoId);
        }
        publishUsage(used, Math.max(0, entries.length - evicted.length));
        return { evicted };
    }

    // -----------------------------------------------------------------
    // The pass
    // -----------------------------------------------------------------

    async function sync(): Promise<SmartSyncPass> {
        if (syncing) return { halted: 'done', prefetched: [] };
        const { breaker } = getStreamifySmartSyncState();
        if (breaker.openUntilMs > clock()) return { halted: 'breaker-open', prefetched: [] };

        const { enabled, saveDataOverride } = settings();
        if (!enabled) return { halted: 'disabled', prefetched: [] };
        if (connectionSaveData() && !saveDataOverride)
            return { halted: 'save-data', prefetched: [] };
        if (!isPlaying()) return { halted: 'not-playing', prefetched: [] };

        const estimate = await storageEstimate();
        if (
            estimate !== null &&
            estimate.quota > 0 &&
            estimate.usage / estimate.quota >= SMART_SYNC_STORAGE_PRESSURE
        ) {
            return { halted: 'pressure', prefetched: [] };
        }

        const look = queueReader?.();
        if (look === undefined || look.upcoming.length === 0) {
            return { halted: 'done', prefetched: [] };
        }

        syncing = true;
        const prefetched: string[] = [];
        try {
            // Already-cached tracks (ANY source — smart-sync or manual)
            // are skipped up front: no re-download, no pointless LRU
            // eviction to make budget room for bytes that exist.
            const cached = new Set(
                (await manager.listEntries())
                    .filter((meta) => meta.status === 'complete')
                    .map((meta) => meta.videoId),
            );
            for (const spec of look.upcoming.slice(0, SMART_SYNC_LOOKAHEAD)) {
                if (cached.has(spec.videoId)) continue;
                // Budget BEFORE the bytes hit OPFS: evict LRU smart-sync
                // entries until the estimate fits under the cap.
                await evictForBudget(estimateIncomingBytes(spec));
                const outcome = await manager.startDownload(spec);
                if (outcome.ok) {
                    if (!('alreadyActive' in outcome)) prefetched.push(spec.videoId);
                    continue;
                }
                if (isBreakerTrip(outcome)) {
                    tripBreaker(outcome.reason);
                    break; // halt the pass — N+2 is never attempted
                }
                // Non-tripping failure (quota / network / abort): stop the
                // pass quietly — no retry storm, no breaker.
                break;
            }
        } finally {
            syncing = false;
            setStreamifySmartSyncState({ lastSyncAtMs: clock() });
            await refreshUsage().catch(() => undefined);
        }
        return { halted: 'done', prefetched };
    }

    async function refreshUsage(): Promise<void> {
        const entries = await smartEntries();
        publishUsage(
            entries.reduce((sum, e) => sum + e.bytes, 0),
            entries.length,
        );
    }

    // -----------------------------------------------------------------
    // The maintenance surface
    // -----------------------------------------------------------------

    async function usage(): Promise<{ entries: number; usedBytes: number }> {
        const entries = await smartEntries();
        return {
            entries: entries.length,
            usedBytes: entries.reduce((sum, e) => sum + e.bytes, 0),
        };
    }

    async function clearCache(): Promise<{ entries: number; freedBytes: number }> {
        const entries = await smartEntries();
        let freedBytes = 0;
        for (const e of entries) {
            await manager.remove(e.videoId);
            freedBytes += e.bytes;
        }
        publishUsage(0, 0);
        return { entries: entries.length, freedBytes };
    }

    async function stampPlayed(videoId: string): Promise<void> {
        // The LRU input: only smart-sync entries matter (manual entries
        // never evict); touchPlayed no-ops on everything else, quietly.
        await manager.touchPlayed(videoId);
    }

    return {
        breakerOpen: (now) => getStreamifySmartSyncState().breaker.openUntilMs > (now ?? clock()),
        breakerState: () => getStreamifySmartSyncState().breaker,
        clearCache,
        stampPlayed,
        sync,
        usage,
    };
}

/** The incoming-bytes estimate for budgeting (duration × ladder ceiling). */
export function estimateIncomingBytes(spec: DownloadSpec): number {
    if (spec.durationSec === null || spec.durationSec <= 0) {
        return DEFAULT_INCOMING_ESTIMATE_BYTES;
    }
    return Math.round(spec.durationSec * ESTIMATED_BYTES_PER_SEC * 1.1);
}

/** Classify one download outcome against the breaker rules. */
export function isBreakerTrip(outcome: DownloadOutcome): boolean {
    if (outcome.ok) return false;
    // 403 / 429 anywhere: the bot-gate / rate-limit answers.
    if (outcome.status === 403 || outcome.status === 429) return true;
    // A resolve-stage failure means the solver ladder itself answered with
    // an error — the "solver error" class the task names.
    if (outcome.stage === 'resolve') return true;
    // Bot-gate vocabulary in the failure text (belt-and-braces: the
    // resolver's own error strings are not stable API).
    return /bot[- ]?gate|po[- ]?token|sign.?in to confirm|confirm you.?re not a bot/i.test(
        outcome.reason,
    );
}

/** The LRU sort key: least-recently-played, else least-recently-cached. */
export function lruKeyOf(entry: {
    completedAt: null | string;
    lastPlayedAt: null | string;
}): number {
    const played = entry.lastPlayedAt !== null ? Date.parse(entry.lastPlayedAt) : Number.NaN;
    if (Number.isFinite(played)) return played;
    const completed = entry.completedAt !== null ? Date.parse(entry.completedAt) : Number.NaN;
    return Number.isFinite(completed) ? completed : 0;
}
