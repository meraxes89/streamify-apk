/**
 * streamify/engine.ts - THE BRIDGE (the fork-side lowering of the
 * front-end/web/src/lib/engine.ts bridge contract, per F2: "the feishin
 * controller will use the same bridge module, moved/re-exported as needed
 * in new directories only").
 *
 * House rule: every streamify data access the controller makes goes
 * through this module - the controller never imports the cloud lanes, the
 * session codec or the snapshot model directly. One seam, one place to
 * audit, one place for the wasm engine to slot into when its facade grows
 * (see decisions/p1/facade-gaps.md for what P1 reads needed that the
 * facade does not expose yet).
 *
 * What the bridge owns in P1:
 *   - session resolution (the credential codec + the dev fallback)
 *   - the library snapshot: load once per (server, credential), memoize,
 *     dedupe concurrent loads, reload on credential change
 *   - the typed read surface the controller maps onto feishin endpoints
 *
 * Reads are local-first: after the snapshot loads, no endpoint touches the
 * network again (pagination, sorting, search, derivations all run against
 * memory). The load itself uses ONLY the cloud lanes the shipping web app
 * already issues at sign-in - zero new Supabase round-trips.
 */

import { LibrarySnapshot, loadLibrarySnapshot } from './library';
import { resolveStreamifySessionAsync, streamifySessionStableKey } from './session';

import { ServerListItemWithCredential } from '/@/shared/types/domain-types';

interface SnapshotMemo {
    key: string;
    promise: Promise<LibrarySnapshot>;
}

let snapshotMemo: null | SnapshotMemo = null;

function memoKey(server: ServerListItemWithCredential): string {
    return `${server.id}|${server.credential}`;
}

/**
 * The loaded snapshot for the current session - the id-based lookups
 * (covers, details) read it synchronously; null before the first load
 * completes.
 */
let loadedSnapshot: LibrarySnapshot | null = null;

/** The synchronous snapshot (id-based lookups); null before the first load. */
export function currentStreamifyLibrary(): LibrarySnapshot | null {
    return loadedSnapshot;
}

/**
 * Resolve the session and load (or reuse) the library snapshot for it.
 * Concurrent callers share one load; a session-identity change (account
 * switch) invalidates. The key is REFRESH-STABLE (user identity, not the
 * access token) since P3A: silent token refreshes no longer force full
 * snapshot reloads, and the resolver now prefers the live supabase-js
 * session for signed-in users (the P1A raw-token credential gap - see
 * session.ts).
 */
export async function getStreamifyLibrary(
    server: null | ServerListItemWithCredential,
    signal?: AbortSignal,
): Promise<LibrarySnapshot> {
    if (server === null) {
        // The controller glue throws before a null server ever reaches an
        // endpoint (apiRouteError); the type merely allows it. Defensive
        // honest empty, same as the signed-out case.
        return emptySnapshot();
    }
    const session = await resolveStreamifySessionAsync(server.credential);
    if (session === null) {
        // Signed out (no credential, no live session, no settings.js dev
        // config): an honest, EMPTY, ready snapshot - the sign-in empty
        // states render from the session, never from fake data.
        return emptySnapshot();
    }
    const key = `${memoKey(server)}|${streamifySessionStableKey(session)}`;
    if (snapshotMemo !== null && snapshotMemo.key === key) {
        return snapshotMemo.promise;
    }
    const promise = loadLibrarySnapshot(session, key, signal)
        .then((snapshot) => {
            loadedSnapshot = snapshot;
            return snapshot;
        })
        .catch((err: unknown) => {
            // A failed load must not poison later attempts.
            if (snapshotMemo !== null && snapshotMemo.promise === promise) {
                snapshotMemo = null;
            }
            throw err;
        });
    snapshotMemo = { key, promise };
    return promise;
}

let emptySnapshotCache: LibrarySnapshot | null = null;

/** Drop the memoized snapshot so the next read reloads it (P3A: playlist
 * mutations add/remove tracks the snapshot's song index must learn; the
 * reload is lazy - only the next read pays for it). */
export function invalidateStreamifyLibraryMemo(): void {
    snapshotMemo = null;
}

function emptySnapshot(): LibrarySnapshot {
    if (emptySnapshotCache === null) {
        emptySnapshotCache = {
            albumById: new Map(),
            albums: [],
            artistById: new Map(),
            artists: [],
            likedIds: [],
            loadedAt: new Date().toISOString(),
            playEvents: [],
            playlistById: new Map(),
            playlists: [],
            sessionKey: 'signed-out',
            songById: new Map(),
            songs: [],
        };
    }
    return emptySnapshotCache;
}
