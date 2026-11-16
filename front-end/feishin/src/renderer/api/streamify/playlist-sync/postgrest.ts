/**
 * streamify/playlist-sync/postgrest.ts - the authenticated PostgREST lane
 * for the playlist sync layer (P3A).
 *
 * House style carried over from cloud.ts: raw fetch against PostgREST, no
 * SDK, anon key public by design (RLS is the fence), 401 -> one refresh ->
 * replay (the refresh mutates the session object in place, exactly like
 * the cloud.ts lane, so the caller's session stays current). This module
 * adds the write verbs the P1 read lanes never needed: POST (insert,
 * idempotent via Prefer resolution=ignore-duplicates), PATCH (including
 * the primary-key rewrite a reorder materializes), DELETE (the element
 * block range), all with Prefer: return=minimal so writes stay cheap.
 */

import { StreamifySession } from '../session';
import { elementBlockRange } from './element-id';

// ---------------------------------------------------------------------------
// The request lane
// ---------------------------------------------------------------------------

export interface PgPlaylistRow {
    cover_url: null | string;
    created_at: string;
    description: null | string;
    id: string;
    is_public: boolean | null;
    name: string;
    updated_at: string;
}

export interface PgRequestInit {
    body?: unknown;
    headers?: Record<string, string>;
    method: string;
    signal?: AbortSignal;
}

export interface PgTrackRow {
    added_at: null | string;
    added_by: null | string;
    id: string;
    playlist_id: string;
    position: number;
    track_id: string;
}

interface AuthTokenResponse {
    access_token?: string;
    expires_in?: number;
    refresh_token?: string;
}

/** The user's playlist rows (the RLS-fenced own-list query). */
export async function fetchPlaylistRows(
    session: StreamifySession,
    signal?: AbortSignal,
): Promise<PgPlaylistRow[]> {
    if (session.accessToken === '' || session.userId === '') {
        return [];
    }
    const res = await pgRestCall(
        session,
        'playlists?user_id=eq.' +
            session.userId +
            '&order=created_at.asc&select=id,name,description,cover_url,is_public,created_at,updated_at',
        { method: 'GET', signal },
    );
    if (!res.ok) {
        throw new Error(`Streamify playlist load failed (${res.status})`);
    }
    const rows = (await res.json()) as Array<Record<string, unknown>>;
    return rows.map((row) => ({
        cover_url: row.cover_url == null ? null : String(row.cover_url),
        created_at: String(row.created_at ?? ''),
        description: row.description == null ? null : String(row.description),
        id: String(row.id ?? ''),
        is_public: row.is_public == null ? null : row.is_public === true,
        name: String(row.name ?? ''),
        updated_at: String(row.updated_at ?? ''),
    }));
}

// ---------------------------------------------------------------------------
// Row shapes (the wire columns, nothing more)
// ---------------------------------------------------------------------------

/** playlist_tracks rows for a set of playlists (every column the CRDT
 * mapping reads - the id IS the packed (add_op_id, frac_bits) pair). */
export async function fetchTrackRows(
    session: StreamifySession,
    playlistIds: string[],
    signal?: AbortSignal,
): Promise<PgTrackRow[]> {
    if (session.accessToken === '' || playlistIds.length === 0) {
        return [];
    }
    const ids = playlistIds.join(',');
    const res = await pgRestCall(
        session,
        'playlist_tracks?playlist_id=in.(' +
            ids +
            ')&order=position.asc&select=id,playlist_id,track_id,position,added_by,added_at',
        { method: 'GET', signal },
    );
    if (!res.ok) {
        throw new Error(`Streamify playlist track load failed (${res.status})`);
    }
    const rows = (await res.json()) as Array<Record<string, unknown>>;
    return rows.map((row) => ({
        added_at: row.added_at == null ? null : String(row.added_at),
        added_by: row.added_by == null ? null : String(row.added_by),
        id: String(row.id ?? ''),
        playlist_id: String(row.playlist_id ?? ''),
        position: Number(row.position ?? 0) || 0,
        track_id: String(row.track_id ?? ''),
    }));
}

/** One authed PostgREST call (Bearer + apikey). */
export async function pgRestCall(
    session: StreamifySession,
    path: string,
    init: PgRequestInit,
): Promise<Response> {
    const doFetch = (): Promise<Response> =>
        fetch(`${session.supabaseUrl}/rest/v1/${path}`, {
            body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
            headers: {
                apikey: session.anonKey,
                authorization: `Bearer ${session.accessToken}`,
                'content-type': 'application/json',
                ...(init.headers ?? {}),
            },
            method: init.method,
            signal: init.signal,
        });
    let res = await doFetch();
    if (res.status === 401 && session.refreshToken !== '') {
        const refreshed = await refreshSession(session);
        if (refreshed !== null) {
            res = await doFetch();
        }
    }
    return res;
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

/** Like pgRestCall but throws on any non-2xx after the refresh retry. */
export async function pgRestCallOrThrow(
    session: StreamifySession,
    path: string,
    init: PgRequestInit,
): Promise<Response> {
    const res = await pgRestCall(session, path, init);
    if (!res.ok) {
        throw new Error(
            `Streamify playlist write failed (${res.status} ${res.statusText}) ${path}`,
        );
    }
    return res;
}

async function refreshSession(
    session: StreamifySession,
): Promise<null | { accessToken: string; expiresAt: number }> {
    try {
        const res = await fetch(`${session.supabaseUrl}/auth/v1/token?grant_type=refresh_token`, {
            body: JSON.stringify({ refresh_token: session.refreshToken }),
            headers: {
                apikey: session.anonKey,
                authorization: `Bearer ${session.anonKey}`,
                'content-type': 'application/json',
            },
            method: 'POST',
        });
        if (!res.ok) {
            return null;
        }
        const body = (await res.json()) as AuthTokenResponse;
        if (body.access_token === undefined) {
            return null;
        }
        session.accessToken = body.access_token;
        session.expiresAt = Math.floor(Date.now() / 1000) + (body.expires_in ?? 3600);
        if (body.refresh_token !== undefined) {
            session.refreshToken = body.refresh_token;
        }
        return { accessToken: body.access_token, expiresAt: session.expiresAt };
    } catch {
        return null;
    }
}

// ---------------------------------------------------------------------------
// Writes (all idempotent or range-scoped)
// ---------------------------------------------------------------------------

const MINIMAL = { Prefer: 'return=minimal' } as const;

/**
 * Delete EVERY row of one element (the block range over the packed id):
 * id >= pack(addOpId, 0) AND id < pack(addOpId+1, 0). Big-endian packing
 * makes the Postgres bytewise uuid order equal the u64 numeric order, so
 * gte/lt implement the bounds exactly - the remove materializes at ANY
 * frac, concurrent reorders included.
 */
export async function deleteElementRows(
    session: StreamifySession,
    playlistId: string,
    addOpId: bigint,
): Promise<void> {
    const range = elementBlockRange(addOpId);
    await pgRestCallOrThrow(
        session,
        `playlist_tracks?playlist_id=eq.${playlistId}&id=gte.${range.gte}&id=lt.${range.lt}`,
        { headers: MINIMAL, method: 'DELETE' },
    );
}

/** Delete one playlist row (cascades its tracks - the delete tombstone). */
export async function deletePlaylistRow(session: StreamifySession, id: string): Promise<void> {
    await pgRestCallOrThrow(session, `playlists?id=eq.${id}`, {
        headers: MINIMAL,
        method: 'DELETE',
    });
}

/** Delete one element row by exact id (loser-row cleanup in a block). */
export async function deleteTrackRow(session: StreamifySession, rowId: string): Promise<void> {
    await pgRestCallOrThrow(session, `playlist_tracks?id=eq.${rowId}`, {
        headers: MINIMAL,
        method: 'DELETE',
    });
}

/** Insert playlist rows (idempotent on the primary key). */
export async function insertPlaylistRows(
    session: StreamifySession,
    rows: Array<{
        cover_url: null | string;
        created_at: string;
        description: null | string;
        id: string;
        is_public: boolean;
        name: string;
        updated_at: string;
        user_id: string;
    }>,
): Promise<void> {
    if (rows.length === 0) {
        return;
    }
    await pgRestCallOrThrow(session, 'playlists', {
        body: rows,
        headers: { Prefer: 'return=minimal,resolution=ignore-duplicates' },
        method: 'POST',
    });
}

/** Insert element rows (idempotent on the packed primary key). */
export async function insertTrackRows(
    session: StreamifySession,
    rows: Array<{
        added_at: string;
        added_by: string;
        id: string;
        playlist_id: string;
        position: number;
        track_id: string;
    }>,
): Promise<void> {
    if (rows.length === 0) {
        return;
    }
    await pgRestCallOrThrow(session, 'playlist_tracks', {
        body: rows,
        headers: { Prefer: 'return=minimal,resolution=ignore-duplicates' },
        method: 'POST',
    });
}

/** Patch one playlist row (metadata LWW repair; updated_at carries the
 * register clock so every reader can compare without extra columns). */
export async function patchPlaylistRow(
    session: StreamifySession,
    id: string,
    patch: {
        cover_url?: null | string;
        description?: null | string;
        is_public?: boolean;
        name?: string;
        updated_at?: string;
    },
): Promise<void> {
    await pgRestCallOrThrow(session, `playlists?id=eq.${id}`, {
        body: patch,
        headers: MINIMAL,
        method: 'PATCH',
    });
}

/**
 * Patch one element row. A reorder materializes as a PRIMARY-KEY rewrite
 * (the row id packs the frac, which just moved) plus the position cache
 * and the register clock (added_at) - one atomic UPDATE.
 */
export async function patchTrackRow(
    session: StreamifySession,
    rowId: string,
    patch: { added_at?: string; id?: string; position?: number },
): Promise<void> {
    await pgRestCallOrThrow(session, `playlist_tracks?id=eq.${rowId}`, {
        body: patch,
        headers: MINIMAL,
        method: 'PATCH',
    });
}
