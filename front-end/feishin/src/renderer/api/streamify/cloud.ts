/**
 * streamify/cloud.ts - the cloud data lanes behind the library snapshot.
 *
 * These mirror front-end/web/src/lib/supabase.ts call-for-call: the SAME
 * PostgREST queries the shipping web app issues at sign-in (likes, playlist
 * membership, tracks by id, play events). The P1 rule is "reads are
 * local-first, no NEW Supabase round-trips": every query in this file
 * already exists in the web app's library load path, and the controller
 * resolves all pagination/sorting/search against the in-memory snapshot
 * these lanes populate - a browse session issues zero additional requests.
 *
 * House style carried over verbatim: raw fetch against PostgREST, no SDK,
 * anon key public by design (RLS is the fence), 401 -> one refresh -> replay
 * (the refresh lane exists so a long-lived browse session survives token
 * expiry; a failed refresh throws with a 401-flavored message so
 * use-server-authenticated routes the user back through A's authenticate).
 */

import { StreamifySession } from './session';

// ---------------------------------------------------------------------------
// Row shapes (the wire columns, nothing more)
// ---------------------------------------------------------------------------

export interface CloudPlaylistRow {
    cover_url: null | string;
    created_at: string;
    description: null | string;
    id: string;
    name: string;
    updated_at: string;
}

/** The tracks table row as PostgREST delivers it (see supabase.ts toCloudTrack). */
export interface CloudTrackRow {
    album: null | string;
    artist: string;
    cover_url: null | string;
    duration_sec: null | number;
    id: string;
    stream_url: null | string;
    title: string;
}

export interface PlayEventRow {
    artist: string;
    /** Included so lastPlayedAt is a real timestamp (see decisions/p1). */
    created_at: string;
    duration_played_sec: number;
    hour_of_day: number;
    title: string;
    track_sig: string;
}

export interface PlaylistTrackRow {
    playlist_id: string;
    position: number;
    track_id: string;
}

// ---------------------------------------------------------------------------
// The fetch lane
// ---------------------------------------------------------------------------

interface AuthTokenResponse {
    access_token?: string;
    expires_in?: number;
    refresh_token?: string;
}

/** playlist_tracks rows for a set of playlists (position ascending). */
export async function fetchAllPlaylistTracks(
    session: StreamifySession,
    playlistIds: string[],
    signal?: AbortSignal,
): Promise<PlaylistTrackRow[]> {
    if (session.accessToken === '' || playlistIds.length === 0) {
        return [];
    }
    const ids = playlistIds.join(',');
    const res = await restCall(
        session,
        `playlist_tracks?playlist_id=in.(${ids})&order=position.asc&select=playlist_id,track_id,position`,
        { method: 'GET', signal },
    );
    if (!res.ok) {
        return [];
    }
    const rows = (await res.json()) as Array<{
        playlist_id?: unknown;
        position?: unknown;
        track_id?: unknown;
    }>;
    return rows.map((row) => ({
        playlist_id: String(row.playlist_id ?? ''),
        position: typeof row.position === 'number' ? row.position : 0,
        track_id: String(row.track_id ?? ''),
    }));
}

/**
 * user_likes joined against tracks, like order preserved (most recent
 * first). Anonymous / signed-out sessions get an honest empty list.
 */
export async function fetchLikes(
    session: StreamifySession,
    signal?: AbortSignal,
): Promise<CloudTrackRow[]> {
    if (session.accessToken === '') {
        return [];
    }
    const likesRes = await restCall(
        session,
        `user_likes?user_id=eq.${session.userId}&select=track_id&order=created_at.desc`,
        { method: 'GET', signal },
    );
    if (!likesRes.ok) {
        return [];
    }
    const likes = (await likesRes.json()) as Array<{ track_id: string }>;
    if (likes.length === 0) {
        return [];
    }
    const ids = likes.map((l) => l.track_id).join(',');
    const tracksRes = await restCall(session, `tracks?id=in.(${ids})&select=*`, {
        method: 'GET',
        signal,
    });
    if (!tracksRes.ok) {
        return [];
    }
    const rows = (await tracksRes.json()) as Array<Record<string, unknown>>;
    const byId = new Map(rows.map((row) => [String(row.id), toTrackRow(row)]));
    const out: CloudTrackRow[] = [];
    for (const like of likes) {
        const row = byId.get(like.track_id);
        if (row !== undefined) {
            out.push(row);
        }
    }
    return out;
}

// ---------------------------------------------------------------------------
// The library lanes (each one exists verbatim in the web app's load path)
// ---------------------------------------------------------------------------

/** The user's own play events (most recent first) - the top/similar data. */
export async function fetchPlayEvents(
    session: StreamifySession,
    limit: number,
    signal?: AbortSignal,
): Promise<PlayEventRow[]> {
    if (session.accessToken === '') {
        return [];
    }
    const res = await restCall(
        session,
        `user_play_events?user_id=eq.${session.userId}&select=track_sig,title,artist,duration_played_sec,hour_of_day,created_at&order=created_at.desc&limit=${limit}`,
        { method: 'GET', signal },
    );
    if (!res.ok) {
        return [];
    }
    const rows = (await res.json()) as Array<Record<string, unknown>>;
    return rows.map((row) => ({
        artist: String(row.artist ?? ''),
        created_at: String(row.created_at ?? ''),
        duration_played_sec: Number(row.duration_played_sec ?? 0) || 0,
        hour_of_day: Number(row.hour_of_day ?? 0) || 0,
        title: String(row.title ?? ''),
        track_sig: String(row.track_sig ?? ''),
    }));
}

/** The user's playlists (playlists table, RLS-fenced). */
export async function fetchPlaylists(
    session: StreamifySession,
    signal?: AbortSignal,
): Promise<CloudPlaylistRow[]> {
    if (session.accessToken === '' || session.userId === '') {
        return [];
    }
    const res = await restCall(
        session,
        `playlists?user_id=eq.${session.userId}&order=created_at.desc&select=*`,
        { method: 'GET', signal },
    );
    if (!res.ok) {
        return [];
    }
    const rows = (await res.json()) as Array<Record<string, unknown>>;
    return rows.map((row) => ({
        cover_url:
            row.cover_url === null || row.cover_url === undefined ? null : String(row.cover_url),
        created_at: String(row.created_at ?? ''),
        description:
            row.description === null || row.description === undefined
                ? null
                : String(row.description),
        id: String(row.id ?? ''),
        name: String(row.name ?? ''),
        updated_at: String(row.updated_at ?? ''),
    }));
}

/** Track rows by id (the playlist membership resolution lane). */
export async function fetchTracksByIds(
    session: StreamifySession,
    trackIds: string[],
    signal?: AbortSignal,
): Promise<CloudTrackRow[]> {
    if (session.accessToken === '' || trackIds.length === 0) {
        return [];
    }
    const ids = trackIds.join(',');
    const res = await restCall(session, `tracks?id=in.(${ids})&select=*`, {
        method: 'GET',
        signal,
    });
    if (!res.ok) {
        return [];
    }
    const rows = (await res.json()) as Array<Record<string, unknown>>;
    return rows.map((row) => toTrackRow(row));
}

/** One refresh attempt via the Supabase auth surface (anon-allowed). */
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
        return {
            accessToken: body.access_token,
            expiresAt: Math.floor(Date.now() / 1000) + (body.expires_in ?? 3600),
        };
    } catch {
        return null;
    }
}

async function restCall(
    session: StreamifySession,
    path: string,
    init: { method: string; signal?: AbortSignal },
): Promise<Response> {
    const doFetch = (): Promise<Response> =>
        fetch(`${session.supabaseUrl}/rest/v1/${path}`, {
            headers: {
                apikey: session.anonKey,
                authorization: `Bearer ${session.accessToken}`,
                'content-type': 'application/json',
            },
            method: init.method,
            signal: init.signal,
        });
    let res = await doFetch();
    if (res.status === 401 && session.refreshToken !== '') {
        const refreshed = await refreshSession(session);
        if (refreshed !== null) {
            session.accessToken = refreshed.accessToken;
            session.expiresAt = refreshed.expiresAt;
            res = await doFetch();
        }
    }
    if (res.status === 401) {
        // The message is what use-server-authenticated pattern-matches to
        // decide a re-authentication is needed ("unauthorized").
        throw new Error('Streamify session unauthorized (401)');
    }
    return res;
}

function toTrackRow(row: Record<string, unknown>): CloudTrackRow {
    return {
        album: row.album === null || row.album === undefined ? null : String(row.album),
        artist: String(row.artist ?? ''),
        cover_url:
            row.cover_url === null || row.cover_url === undefined ? null : String(row.cover_url),
        duration_sec:
            row.duration_sec === null || row.duration_sec === undefined
                ? null
                : Number(row.duration_sec),
        id: String(row.id ?? ''),
        stream_url:
            row.stream_url === null || row.stream_url === undefined ? null : String(row.stream_url),
        title: String(row.title ?? ''),
    };
}
