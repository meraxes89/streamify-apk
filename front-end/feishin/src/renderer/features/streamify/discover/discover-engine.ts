/**
 * features/streamify/discover/discover-engine.ts — W2-B: the
 * playTally-seeded Discover Mix generator.
 *
 * THE ALGORITHM (front-end/decisions/waves/W2B-credits-smartsync-discover.md):
 *
 * 1. SEEDS — the user's top-played tracks, ranked STRICTLY from the
 *    playTally ledger (#191/#192), whose counts are produced only by the
 *    canonical min(half-duration, 4 min) actual-played-time rule
 *    (front-end/decisions/p2/P2B-playback-spine.md — this engine never
 *    counts a skip, never re-derives play state). Top DISCOVER_MAX_SEEDS
 *    seeds with a resolvable snapshot videoId drive the crawl.
 *
 * 2. COLD START — fewer than DISCOVER_MIN_SEEDS resolvable seeds (a new
 *    user) falls back cleanly to the regional provider recommendations
 *    (getExplore, else getHomeFeed) — no personalization, honest
 *    attribution.
 *
 * 3. CANDIDATES — provider.getUpNext(seed) + provider.getRelated(seed)
 *    across the seeds (the Edge KV 12 h TTL class), deduplicated by
 *    videoId AND by (title, artist) signature, interleaved round-robin
 *    across seeds so one radio cannot dominate the mix.
 *
 * 4. FILTERS — anything the user played inside the recent-history window
 *    (the snapshot's lastPlayedAt + playEvents timestamps) is excluded,
 *    along with the seeds themselves.
 *
 * Pure + injected (tally, snapshot, meta source, clock) — the vitest lane
 * pins every rule without a network.
 */

import type { LibrarySnapshot } from '/@/renderer/api/streamify/library';
import type {
    MetaShelfItem,
    MetaSongRef,
    W2bMetaSource,
} from '/@/renderer/api/streamify/meta-bridge';
import type { PlayTally } from '/@/renderer/api/streamify/playback/play-tally';

// ---------------------------------------------------------------------------
// The locked constants
// ---------------------------------------------------------------------------

/** How many top tracks seed the crawl. */
export const DISCOVER_MAX_SEEDS = 3;
/** Below this many resolvable seeds the mix falls back to Explore/Home. */
export const DISCOVER_MIN_SEEDS = 3;
/** The bounded result size (the shelf's page). */
export const DISCOVER_TARGET = 25;
/** The recent-history exclusion window (played inside → filtered out). */
export const DISCOVER_RECENT_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// The DTOs
// ---------------------------------------------------------------------------

export interface DiscoverDeps {
    /** Injectable clock (tests). */
    now?: () => number;
    /** The live snapshot (null when the library has not loaded). */
    snapshot: LibrarySnapshot | null;
    /** The W1 provider seam (features never touch the provider module). */
    source: W2bMetaSource;
    /** The playTally ledger (read by the caller — readTally in production). */
    tally: PlayTally;
}

export interface DiscoverMix {
    generatedAtMs: number;
    /** Seed attribution ("Because you listened to…"); empty on cold start. */
    seeds: DiscoverSeed[];
    /** Where the tracks came from (the attribution subtitle's honest form). */
    source: 'explore' | 'home' | 'tally';
    tracks: DiscoverTrack[];
}

export interface DiscoverSeed {
    artist: null | string;
    title: string;
    videoId: string;
}

export interface DiscoverTrack {
    artist: null | string;
    durationMs: null | number;
    title: string;
    videoId: string;
}

// ---------------------------------------------------------------------------
// Helpers (pure, exported for the test lane)
// ---------------------------------------------------------------------------

/**
 * Rank the tally's topTracks into playable seeds (count desc, then the
 * ledger's insertion order for stability). Only tracks the snapshot can
 * resolve to a videoId are seeds.
 */
export function discoverSeeds(
    snapshot: LibrarySnapshot | null,
    tally: PlayTally,
    max = DISCOVER_MAX_SEEDS,
): DiscoverSeed[] {
    if (snapshot === null) return [];
    const ranked = Object.entries(tally.topTracks).sort((a, b) => b[1].count - a[1].count);
    const seeds: DiscoverSeed[] = [];
    for (const [trackId] of ranked) {
        if (seeds.length >= max) break;
        const row = snapshot.songById.get(trackId);
        if (row === undefined) continue;
        if (row.streamUrl === null || row.streamUrl === '') continue;
        seeds.push({
            artist: row.artist ?? null,
            title: row.title,
            videoId: row.streamUrl,
        });
    }
    return seeds;
}

/** The (title, artist) signature — the cross-tier dedup key (W1-B's rule). */
export function discoverSig(title: null | string, artist: null | string): string {
    return `${(title ?? '').trim().toLowerCase()}|${(artist ?? '').trim().toLowerCase()}`;
}

/**
 * Generate one Discover Mix. Resolves null when nothing honest could be
 * built (no seeds AND both cold-start feeds missed) — the shelf then keeps
 * its empty state; it never fabricates tracks.
 */
export async function generateDiscoverMix(deps: DiscoverDeps): Promise<DiscoverMix | null> {
    const { snapshot, source, tally } = deps;
    const now = (deps.now ?? Date.now)();

    const seeds = discoverSeeds(snapshot, tally);
    if (seeds.length < DISCOVER_MIN_SEEDS) {
        // Cold start: regional recommendations, honestly attributed.
        const explore = await source.getExplore();
        if (explore !== null) {
            const tracks = tracksFromShelves(explore.shelves).slice(0, DISCOVER_TARGET);
            if (tracks.length > 0) {
                return { generatedAtMs: now, seeds: [], source: 'explore', tracks };
            }
        }
        const home = await source.getHomeFeed();
        if (home !== null) {
            const tracks = tracksFromShelves(home.shelves).slice(0, DISCOVER_TARGET);
            if (tracks.length > 0) {
                return { generatedAtMs: now, seeds: [], source: 'home', tracks };
            }
        }
        return null;
    }

    // Warm path: crawl Up Next + Related per seed, round-robin interleaved.
    const seedVideoIds = new Set(seeds.map((s) => s.videoId));
    const exclusions = recentPlayedExclusions(snapshot, now);
    const perSeed: DiscoverTrack[][] = [];
    for (const seed of seeds) {
        const [upNext, related] = await Promise.all([
            source.getUpNext(seed.videoId),
            source.getRelated(seed.videoId),
        ]);
        const candidates: DiscoverTrack[] = [];
        const seenVideoIds = new Set<string>([seed.videoId]);
        const seenSigs = new Set<string>();
        for (const ref of [...(upNext ?? []), ...(related ?? [])]) {
            if (seenVideoIds.has(ref.videoId)) continue;
            if (seedVideoIds.has(ref.videoId)) continue;
            if (exclusions.videoIds.has(ref.videoId)) continue;
            const sig = discoverSig(ref.title, ref.artist);
            if (seenSigs.has(sig) || exclusions.sigs.has(sig)) continue;
            seenVideoIds.add(ref.videoId);
            seenSigs.add(sig);
            candidates.push(toDiscoverTrack(ref));
        }
        perSeed.push(candidates);
    }

    // Interleave round-robin: seed 1's best, seed 2's best, seed 3's best,
    // then their second choices… diversity over radio depth. Per-seed
    // cursors advance past cross-seed duplicates (a track two radios both
    // suggest is taken exactly once, credited to the earlier seed).
    const tracks: DiscoverTrack[] = [];
    const takenVideoIds = new Set<string>();
    const takenSigs = new Set<string>();
    const cursors = perSeed.map(() => 0);
    let exhausted = false;
    while (tracks.length < DISCOVER_TARGET && !exhausted) {
        exhausted = true;
        for (let s = 0; s < perSeed.length && tracks.length < DISCOVER_TARGET; s += 1) {
            const candidates = perSeed[s];
            while (cursors[s] < candidates.length) {
                const candidate = candidates[cursors[s]];
                cursors[s] += 1;
                exhausted = false; // this seed still had candidates
                const sig = discoverSig(candidate.title, candidate.artist);
                if (takenVideoIds.has(candidate.videoId) || takenSigs.has(sig)) continue;
                takenVideoIds.add(candidate.videoId);
                takenSigs.add(sig);
                tracks.push(candidate);
                break; // one take per seed per round
            }
        }
    }

    if (tracks.length === 0) return null;
    return { generatedAtMs: now, seeds, source: 'tally', tracks };
}

/**
 * The recent-history exclusion set: videoIds the user played inside the
 * window (snapshot lastPlayedAt + playEvents timestamps — the tally carries
 * no per-track timestamps, the events do; both are inputs the playback
 * spine already maintains).
 */
export function recentPlayedExclusions(
    snapshot: LibrarySnapshot | null,
    now: number,
    windowMs = DISCOVER_RECENT_WINDOW_MS,
): { sigs: Set<string>; videoIds: Set<string> } {
    const videoIds = new Set<string>();
    const sigs = new Set<string>();
    if (snapshot === null) return { sigs, videoIds };
    const cutoff = now - windowMs;

    const addRow = (row: {
        artist: string;
        lastPlayedAt: null | string;
        streamUrl: null | string;
        title: string;
    }): void => {
        if (row.lastPlayedAt === null) return;
        const at = Date.parse(row.lastPlayedAt);
        if (!Number.isFinite(at) || at < cutoff) return;
        if (row.streamUrl !== null && row.streamUrl !== '') videoIds.add(row.streamUrl);
        sigs.add(discoverSig(row.title, row.artist));
    };
    for (const song of snapshot.songs) addRow(song);

    for (const event of snapshot.playEvents) {
        const at = Date.parse(event.created_at);
        if (!Number.isFinite(at) || at < cutoff) continue;
        const row = snapshot.songById.get(event.track_sig);
        if (row === undefined) continue;
        if (row.streamUrl !== null && row.streamUrl !== '') videoIds.add(row.streamUrl);
        sigs.add(discoverSig(row.title, row.artist));
    }
    return { sigs, videoIds };
}

function toDiscoverTrack(ref: MetaSongRef): DiscoverTrack {
    return {
        artist: ref.artist,
        durationMs: ref.durationMs,
        title: ref.title,
        videoId: ref.videoId,
    };
}

// ---------------------------------------------------------------------------
// The generator
// ---------------------------------------------------------------------------

function tracksFromShelves(
    shelves: Array<{ items: MetaShelfItem[]; title: string }>,
): DiscoverTrack[] {
    const out: DiscoverTrack[] = [];
    for (const shelf of shelves) {
        for (const item of shelf.items) {
            if (item.videoId === null) continue;
            out.push({
                artist: item.subtitle,
                durationMs: null,
                title: item.title,
                videoId: item.videoId,
            });
        }
    }
    return out;
}
