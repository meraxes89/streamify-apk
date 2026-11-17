/**
 * api/streamify/catalog/automix.ts — the endless Automix radio engine
 * (W1-B): yt.music.getUpNext continuation pages through the
 * MetadataProvider, filtered against the user's recently-played ledger.
 *
 * THE EXCLUSION RULE (the task's ruling): the radio filters out recently
 * played track IDs from the playTally ledger (#191/#192). Two honest
 * inputs, both already maintained by the playback spine:
 *   - the playTally's topTracks keys (the local ledger of counted plays —
 *     track ids, mapped to videoIds through the snapshot);
 *   - the snapshot's playEvents (most-recent-first) inside a bounded
 *     recency window (default 3 h) — the tally carries no timestamps, the
 *     events do.
 * Excluded items match by videoId OR by (title, artist) signature, so
 * covers/alternate versions of a just-played track also rotate out.
 *
 * PAGES: bounded (AUTOMIX_MAX_PAGES, default 5) — the "endless" contract
 * is the continuation wiring, not an unbounded loop; the caller asks for
 * a count and the engine pages until it has enough or the feed ends.
 * Every miss (null) resolves an honest empty — callers keep their
 * snapshot fallbacks.
 */

import type { LibrarySnapshot } from '../library';
import type { MetadataProvider, YtmSongRef } from '../metadata/provider';

import { readTally, type TallyStorage } from '../playback/play-tally';
import { ORIGIN_AUTOMIX, rememberOrigins } from '../wrapped/origin-hints';
import { songSig } from './ytm-mappers';

export const AUTOMIX_MAX_PAGES = 5;

/** The recency window for the play-events exclusion (ms). */
export const RECENT_WINDOW_MS = 3 * 60 * 60 * 1000;

export interface AutomixExclusions {
    sigs: Set<string>;
    videoIds: Set<string>;
}

export interface AutomixOptions {
    /** Extra ids/sigs to exclude (the seed track, already-queued items). */
    extra?: AutomixExclusions;
    maxPages?: number;
    provider?: MetadataProvider;
    signal?: AbortSignal;
}

/**
 * Run the Up Next radio for a seed video: page the continuations until
 * `count` non-excluded, non-duplicate songs are collected (or the feed
 * ends / the page cap hits — both honest stops).
 */
export async function automixRadio(
    provider: MetadataProvider,
    seedVideoId: string,
    count: number,
    opts: AutomixOptions = {},
): Promise<YtmSongRef[]> {
    if (seedVideoId === '' || count <= 0) return [];
    const maxPages = opts.maxPages ?? AUTOMIX_MAX_PAGES;
    const seenVideoIds = new Set<string>([seedVideoId, ...(opts.extra?.videoIds ?? [])]);
    const sigs = new Set(opts.extra?.sigs ?? []);
    const out: YtmSongRef[] = [];

    let continuation: string | undefined;
    for (let page = 0; page < maxPages && out.length < count; page += 1) {
        const upNext = await provider.getUpNext(seedVideoId, continuation);
        if (upNext === null) break; // honest miss / feed end
        for (const song of upNext.songs) {
            if (out.length >= count) break;
            if (song.videoId === '' || seenVideoIds.has(song.videoId)) continue;
            const sig = songSig(song.title, song.artist);
            if (sigs.has(sig)) continue;
            seenVideoIds.add(song.videoId);
            sigs.add(sig);
            out.push(song);
        }
        continuation = upNext.continuation ?? undefined;
        if (continuation === undefined) break;
    }
    // W3-B: the Automix lane claims the discovery origin for every track it
    // surfaced (first-writer-wins) — the Wrapped "Top Discovery" stat reads
    // it back off the listening ledger when these tracks cross the play
    // threshold. Mood radio rides the same claim (it calls automixRadio).
    if (out.length > 0) {
        rememberOrigins(
            out.map((song) => song.videoId),
            ORIGIN_AUTOMIX,
        );
    }
    return out;
}

/**
 * Pick the Automix seed for an unfiltered random list: the user's most
 * tallied track (the strongest taste signal the ledger has), else a
 * deterministic-ish random snapshot song. null when the library is empty.
 */
export function automixSeed(snapshot: LibrarySnapshot, tallyStorage?: TallyStorage): null | string {
    const tally = readTally(tallyStorage);
    let bestId: null | string = null;
    let bestCount = -1;
    for (const [trackId, stats] of Object.entries(tally.topTracks)) {
        const row = snapshot.songById.get(trackId);
        if (row === undefined || row.streamUrl === null || row.streamUrl === '') continue;
        if (stats.count > bestCount) {
            bestCount = stats.count;
            bestId = row.streamUrl;
        }
    }
    if (bestId !== null) return bestId;
    const playable = snapshot.songs.filter((row) => row.streamUrl !== null && row.streamUrl !== '');
    if (playable.length === 0) return null;
    return (playable[Math.floor(Math.random() * playable.length)]?.streamUrl as string) ?? null;
}

/** Does a candidate hit the exclusion set? (videoId or title+artist sig) */
export function isExcluded(ref: YtmSongRef, exclusions: AutomixExclusions): boolean {
    if (exclusions.videoIds.has(ref.videoId)) return true;
    return exclusions.sigs.has(songSig(ref.title, ref.artist));
}

/**
 * The recently-played exclusion set: playTally topTracks + playEvents
 * inside the recency window (both keyed to videoIds/sigs via snapshot).
 */
export function recentExclusions(
    snapshot: LibrarySnapshot,
    opts: { now?: number; tallyStorage?: TallyStorage; windowMs?: number } = {},
): AutomixExclusions {
    const now = opts.now ?? Date.now();
    const windowMs = opts.windowMs ?? RECENT_WINDOW_MS;
    const videoIds = new Set<string>();
    const sigs = new Set<string>();
    const addTrackId = (trackId: string): void => {
        const row = snapshot.songById.get(trackId);
        if (row === undefined) return;
        if (row.streamUrl !== null && row.streamUrl !== '') videoIds.add(row.streamUrl);
        sigs.add(songSig(row.title, row.artist));
    };

    // 1. The playTally ledger (the task's named source).
    const tally = readTally(opts.tallyStorage);
    for (const trackId of Object.keys(tally.topTracks)) addTrackId(trackId);

    // 2. Cloud play events inside the recency window (timestamps exist here).
    for (const event of snapshot.playEvents) {
        const at = Date.parse(event.created_at);
        if (Number.isFinite(at) && now - at <= windowMs) {
            addTrackId(event.track_sig);
        }
    }
    return { sigs, videoIds };
}
