/**
 * api/streamify/favorites.ts — P3-B: the favorites (likes) subsystem for
 * the Streamify controller.
 *
 * THE MODEL: a favorite IS a cloud like (user_likes ⋈ tracks — the exact
 * tables the snapshot loads), so create/deleteFavorite mirror the web
 * app's likeTrack/unlikeTrack call-for-call (tracks upsert
 * merge-duplicates → user_likes insert ignore-duplicates; delete removes
 * the edge, the shared catalog row stays). Album / album-artist /
 * artist favorites have NO cloud backing (P1's honest-empty ruling) —
 * the controller no-ops them with a debug log rather than faking state.
 *
 * LIVE SNAPSHOT UPDATES: the snapshot is memoized per session, so a
 * favorite that only wrote to the cloud would leave every list reading
 * STALE liked flags until the next sign-in. applyFavoriteToSnapshot
 * flips the rows and reorders likedIds in place — after the TanStack
 * invalidation refetches, getSongList / getFavoriteSongs / filterSongs
 * answer with the user's action already reflected.
 *
 * OFFLINE RESILIENCE: a favorite is the user's opinion, not a cache —
 * losing it to a flap is unacceptable. When the cloud write cannot go
 * through (offline / 5xx), the local state stands and the op lands in a
 * bounded journal (same policy as the scrobble outbox: 200-entry cap,
 * 5-attempt poison drop, any success resets the attempt counters).
 * The journal drains on the next favorites call, the next snapshot
 * load, and every create/delete attempt. A dead session (401 after
 * refresh) is NOT journalable — it rethrows so the auth seam's
 * clean-sign-out bounce owns it.
 */

import type { LibrarySnapshot, SongRow } from './library';
import type { StreamifySession } from './session';

import { likeItem, likeTrack, restCall, unlikeItem, unlikeTrack } from './cloud';

import { logger } from '/@/renderer/utils/logger';

// ---------------------------------------------------------------------------
// The live snapshot update
// ---------------------------------------------------------------------------

export interface FavoriteOp {
    attempts: number;
    /** Upsert facts for the like (the unlike needs none). */
    facts?: {
        album: null | string;
        artist: string;
        coverUrl: null | string;
        durationSec: null | number;
        title: string;
        videoId: null | string;
    };
    /** W1-B: album/artist favorites ride the same journal (undefined = track). */
    itemType?: 'album' | 'artist';
    op: 'like' | 'unlike';
    /** The cloud track id (feishin song id) — or the alb_/art_ item id. */
    trackId: string;
}

// ---------------------------------------------------------------------------
// The offline journal (bounded, poison-dropping, flap-resetting)
// ---------------------------------------------------------------------------

/**
 * Flip the liked state of the given song ids on the live snapshot:
 * rows flip `liked`, and `likedIds` (most-recent-first, the like-recency
 * order getFavoriteSongs preserves) gains the id at the front or loses it.
 */
export function applyFavoriteToSnapshot(
    snapshot: LibrarySnapshot,
    songIds: string[],
    favorite: boolean,
): void {
    for (const id of songIds) {
        const row = snapshot.songById.get(id);
        if (row === undefined) continue;
        row.liked = favorite;
    }
    const idSet = new Set(songIds);
    const remaining = snapshot.likedIds.filter((id) => !idSet.has(id));
    snapshot.likedIds =
        favorite && songIds.length > 0
            ? [...songIds.filter((id) => snapshot.songById.has(id)), ...remaining]
            : remaining;
}

/** Journal ceiling — oldest ops drop past it (bounded storage). */
export const JOURNAL_CAP = 200;

/** Poison protection: drop a journaled op after this many failed sends. */
export const MAX_JOURNAL_ATTEMPTS = 5;

export interface FavoriteJournalStore {
    load(): FavoriteOp[];
    save(ops: FavoriteOp[]): void;
}

const JOURNAL_KEY = 'streamify.feishin.favorites.journal.v1';

export interface FavoriteOutcome {
    /** The op landed in the offline journal for a later drain. */
    journaled: boolean;
    /** The op reached the cloud (or was a no-op type). */
    synced: boolean;
}

export class FavoriteJournal {
    get size(): number {
        return this.ops.length;
    }
    private ops: FavoriteOp[];

    private readonly store: FavoriteJournalStore;

    constructor(store?: FavoriteJournalStore) {
        this.store = store ?? localStorageJournalStore();
        this.ops = this.store.load();
    }

    /**
     * Drain the journal. Per-op failures keep the op (attempts++);
     * MAX_JOURNAL_ATTEMPTS drops it; any success resets the counters.
     * A dead session aborts the drain by rethrowing (the caller decides
     * whether that is fatal — for the controller it is).
     */
    async drain(session: StreamifySession): Promise<number> {
        if (this.ops.length === 0) return 0;
        const batch = this.ops.map((op) => ({ ...op }));
        const remaining: FavoriteOp[] = [];
        let applied = 0;
        let anySuccess = false;
        for (const op of batch) {
            let ok = false;
            try {
                ok = await applyOp(session, op);
            } catch (err) {
                if (isAuthRejection(err)) throw err;
                ok = false;
            }
            if (ok) {
                applied += 1;
                anySuccess = true;
            } else {
                const next = { ...op, attempts: op.attempts + 1 };
                if (next.attempts < MAX_JOURNAL_ATTEMPTS) remaining.push(next);
                // else: poison-dropped — bounded loss, logged below
            }
        }
        this.ops = remaining;
        if (anySuccess) {
            for (const op of this.ops) op.attempts = 0;
        }
        this.persist();
        if (applied > 0) {
            logger.debug(`Streamify favorites journal drained (${applied} op(s) applied)`, {
                action: 'streamify_favorites_journal_drained',
                applied,
            });
        }
        return applied;
    }

    pending(): FavoriteOp[] {
        return this.ops.map((op) => ({ ...op }));
    }

    push(op: Omit<FavoriteOp, 'attempts'>): void {
        // Coalesce: a like and its undo cancel; a repeat of the same op
        // stays a single entry (idempotent server-side anyway).
        const opposite: FavoriteOp['op'] = op.op === 'like' ? 'unlike' : 'like';
        const sameItem = (entry: FavoriteOp): boolean =>
            entry.trackId === op.trackId && entry.itemType === op.itemType;
        const flippedIndex = this.ops.findIndex(
            (entry) => sameItem(entry) && entry.op === opposite,
        );
        if (flippedIndex >= 0) {
            this.ops.splice(flippedIndex, 1);
        } else if (!this.ops.some((entry) => sameItem(entry) && entry.op === op.op)) {
            this.ops.push({ ...op, attempts: 0 });
            if (this.ops.length > JOURNAL_CAP) {
                this.ops = this.ops.slice(this.ops.length - JOURNAL_CAP);
            }
        }
        this.persist();
    }

    private persist(): void {
        this.store.save(this.ops);
    }
}

/**
 * Flip the liked state of an album/artist on the live snapshot: the
 * likedAlbumIds / likedArtistIds sets are the single source the list
 * filters and normalizeAlbum/normalizeAlbumArtist read.
 */
export function applyItemFavoriteToSnapshot(
    snapshot: LibrarySnapshot,
    type: 'album' | 'artist',
    itemIds: string[],
    favorite: boolean,
): void {
    const set = type === 'album' ? snapshot.likedAlbumIds : snapshot.likedArtistIds;
    for (const id of itemIds) {
        if (favorite) set.add(id);
        else set.delete(id);
    }
}

/**
 * Apply a favorite mutation: live snapshot update ALWAYS, cloud write
 * best-effort, journal on network failure. A dead session rethrows
 * (unauthorized) so the stock error path and the auth seam keep their
 * upstream behavior.
 */
export async function mutateFavorite(
    session: null | StreamifySession,
    snapshot: LibrarySnapshot,
    songIds: string[],
    favorite: boolean,
    journal: FavoriteJournal,
): Promise<FavoriteOutcome> {
    applyFavoriteToSnapshot(snapshot, songIds, favorite);

    if (session === null || session.accessToken === '' || songIds.length === 0) {
        // Signed-out / anonymous: the opinion still journals — it drains
        // when a session exists. (The snapshot is empty in this state, so
        // the live update is a no-op; kept for shape symmetry.)
        for (const trackId of songIds) {
            journal.push({
                facts: songFacts(snapshot.songById.get(trackId)),
                op: favorite ? 'like' : 'unlike',
                trackId,
            });
        }
        return { journaled: true, synced: false };
    }

    // Opportunistic drain first (yesterday's offline opinion goes out
    // before today's rides in behind it).
    try {
        await journal.drain(session);
    } catch (err) {
        if (isAuthRejection(err)) throw err;
        /* offline — today's op joins the journal below */
    }

    let synced = true;
    for (const trackId of songIds) {
        const facts = songFacts(snapshot.songById.get(trackId));
        if (facts === undefined) {
            // Unknown row (not in the snapshot — e.g. a queue song from a
            // stale queue restore): the id still names a catalog row (the
            // ids are the catalog's own), so journal the edge-only op and
            // let the drain insert the user_likes edge without fabricating
            // catalog metadata.
            synced = false;
            journal.push({ op: favorite ? 'like' : 'unlike', trackId });
            continue;
        }
        try {
            const ok = favorite
                ? await likeTrack(session, trackId, facts)
                : await unlikeTrack(session, trackId);
            if (!ok) {
                synced = false;
                journal.push({ facts, op: favorite ? 'like' : 'unlike', trackId });
            }
        } catch (err) {
            if (isAuthRejection(err)) throw err;
            synced = false;
            journal.push({ facts, op: favorite ? 'like' : 'unlike', trackId });
        }
    }
    return { journaled: !synced, synced };
}

// ---------------------------------------------------------------------------
// The controller-facing flow
// ---------------------------------------------------------------------------

/**
 * Apply an album/artist favorite mutation: live snapshot update ALWAYS,
 * cloud write (user_item_favorites) best-effort, journal on network
 * failure — the same contract as mutateFavorite for tracks. A dead
 * session rethrows (unauthorized) so the stock error path keeps its
 * upstream behavior.
 */
export async function mutateItemFavorite(
    session: null | StreamifySession,
    snapshot: LibrarySnapshot,
    type: 'album' | 'artist',
    itemIds: string[],
    favorite: boolean,
    journal: FavoriteJournal,
): Promise<FavoriteOutcome> {
    applyItemFavoriteToSnapshot(snapshot, type, itemIds, favorite);

    if (session === null || session.accessToken === '' || itemIds.length === 0) {
        for (const itemId of itemIds) {
            journal.push({ itemType: type, op: favorite ? 'like' : 'unlike', trackId: itemId });
        }
        return { journaled: true, synced: false };
    }

    // Opportunistic drain first (yesterday's offline opinion goes out
    // before today's rides in behind it).
    try {
        await journal.drain(session);
    } catch (err) {
        if (isAuthRejection(err)) throw err;
        /* offline — today's op joins the journal below */
    }

    let synced = true;
    for (const itemId of itemIds) {
        try {
            const ok = favorite
                ? await likeItem(session, type, itemId)
                : await unlikeItem(session, type, itemId);
            if (!ok) {
                synced = false;
                journal.push({ itemType: type, op: favorite ? 'like' : 'unlike', trackId: itemId });
            }
        } catch (err) {
            if (isAuthRejection(err)) throw err;
            synced = false;
            journal.push({ itemType: type, op: favorite ? 'like' : 'unlike', trackId: itemId });
        }
    }
    return { journaled: !synced, synced };
}

async function applyOp(session: StreamifySession, op: FavoriteOp): Promise<boolean> {
    if (op.itemType === 'album' || op.itemType === 'artist') {
        return op.op === 'like'
            ? likeItem(session, op.itemType, op.trackId)
            : unlikeItem(session, op.itemType, op.trackId);
    }
    if (op.op === 'unlike') return unlikeTrack(session, op.trackId);
    if (op.facts === undefined) {
        // Unknown row (liked while the snapshot could not resolve it): the
        // id came from the shared catalog, so the row exists server-side —
        // insert the edge only, never fabricate catalog metadata.
        const res = await restCall(session, 'user_likes', {
            body: { track_id: op.trackId, user_id: session.userId },
            method: 'POST',
            prefer: 'resolution=ignore-duplicates',
        });
        return res.ok || res.status === 409;
    }
    return likeTrack(session, op.trackId, op.facts);
}

function isAuthRejection(err: unknown): boolean {
    return err instanceof Error && err.message.includes('unauthorized');
}

// ---------------------------------------------------------------------------
// W1-B: the album/artist item-favorite flow (same shape as the track flow)
// ---------------------------------------------------------------------------

function localStorageJournalStore(): FavoriteJournalStore {
    return {
        load(): FavoriteOp[] {
            try {
                const raw = localStorage.getItem(JOURNAL_KEY);
                if (raw === null) return [];
                const parsed = JSON.parse(raw) as unknown;
                if (!Array.isArray(parsed)) return [];
                return parsed.filter(
                    (op): op is FavoriteOp =>
                        typeof op === 'object' &&
                        op !== null &&
                        (op as FavoriteOp).op !== undefined &&
                        typeof (op as FavoriteOp).trackId === 'string' &&
                        typeof (op as FavoriteOp).attempts === 'number',
                );
            } catch {
                return [];
            }
        },
        save(ops: FavoriteOp[]): void {
            try {
                localStorage.setItem(JOURNAL_KEY, JSON.stringify(ops));
            } catch {
                /* private mode — the journal is best-effort by contract */
            }
        },
    };
}

function songFacts(row: SongRow | undefined): FavoriteOp['facts'] {
    if (row === undefined) return undefined;
    return {
        album: row.album,
        artist: row.artist,
        coverUrl: row.coverUrl,
        durationSec: row.durationSec,
        title: row.title,
        videoId: row.streamUrl,
    };
}

// -------------------------------------------------------------------------
// Module wiring
// -------------------------------------------------------------------------

let journalInstance: FavoriteJournal | null = null;

export function getStreamifyFavoriteJournal(): FavoriteJournal {
    if (journalInstance === null) {
        journalInstance = new FavoriteJournal();
    }
    return journalInstance;
}

/** Test seam: drop the singleton (vitest isolation). */
export function resetStreamifyFavoriteJournal(): void {
    journalInstance = null;
}
