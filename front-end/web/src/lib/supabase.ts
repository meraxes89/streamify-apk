/**
 * lib/supabase.ts — W2.2: the web data layer (auth + library).
 *
 * HOUSE STYLE: raw `fetch` against the PostgREST/auth surfaces — NO SDK,
 * exactly like the canary's postResults.ts (the repo deliberately carries
 * zero supabase-js dependencies; RLS is the fence, the anon key is public
 * by design and NEVER committed — it arrives via VITE_SUPABASE_ANON_KEY,
 * baked by CI from the repo secret).
 *
 * AUTH: email+password (enabled by default on Supabase projects; the
 * Android app's Google id_token flow needs a web origin registered in
 * the Google console — out of the sandbox's reach, so the email lane is
 * the web default). Sessions persist in localStorage; 401s trigger the
 * refresh-token flow once, then replay.
 *
 * LIBRARY: mirrors the Android clients' exact calls (SupabaseTracksClient
 * / SupabaseStatsClient): likes two-way (user_likes + tracks upsert with
 * the SAME `trk_<|abs(javaHashCode(title_artist)|>` id derivation — a web
 * like and an Android like land on the same row), play events for stats.
 */

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

interface SupabaseEnv {
  url: string;
  anonKey: string;
}

let envCache: SupabaseEnv | null = null;

function supabaseEnv(): SupabaseEnv | null {
  if (envCache === null) {
    const url = (import.meta.env.VITE_SUPABASE_URL as string | undefined)?.replace(/\/+$/, '');
    const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
    if (url !== undefined && url !== '' && anonKey !== undefined && anonKey !== '') {
      envCache = { url, anonKey };
    }
  }
  return envCache;
}

/** Whether the build carries Supabase credentials (forks/CI-secret builds). */
export function supabaseConfigured(): boolean {
  return supabaseEnv() !== null;
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

export interface SupabaseUser {
  id: string;
  email: string | null;
}

export interface SupabaseSession {
  accessToken: string;
  refreshToken: string;
  expiresAt: number; // epoch seconds
  user: SupabaseUser;
}

const SESSION_KEY = 'streamify.supabase.session';

function persistSession(session: SupabaseSession | null): void {
  try {
    if (session === null) {
      localStorage.removeItem(SESSION_KEY);
    } else {
      localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    }
  } catch {
    /* private mode etc. — sessions become memory-only */
  }
}

function readPersistedSession(): SupabaseSession | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (raw === null) return null;
    const parsed = JSON.parse(raw) as SupabaseSession;
    if (typeof parsed.accessToken !== 'string') return null;
    return parsed;
  } catch {
    return null;
  }
}

// Memory cache of the live session (module singleton).
let sessionCache: SupabaseSession | null = null;
let refreshInFlight: Promise<SupabaseSession | null> | null = null;

interface AuthResponse {
  access_token?: string;
  refresh_token?: string;
  expires_in?: number;
  user?: { id?: string; email?: string };
  error?: string;
  error_description?: string;
  msg?: string;
}

async function authCall(path: string, body: unknown): Promise<AuthResponse> {
  const env = supabaseEnv();
  if (env === null) throw new Error('Supabase not configured');
  const res = await fetch(`${env.url}/auth/v1/${path}`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      apikey: env.anonKey,
      authorization: `Bearer ${env.anonKey}`,
    },
    body: JSON.stringify(body),
  });
  return (await res.json()) as AuthResponse;
}

function toSession(res: AuthResponse): SupabaseSession | null {
  if (res.access_token === undefined || res.refresh_token === undefined) return null;
  if (res.user?.id === undefined) return null;
  return {
    accessToken: res.access_token,
    refreshToken: res.refresh_token,
    expiresAt: Math.floor(Date.now() / 1000) + (res.expires_in ?? 3600),
    user: { id: res.user.id, email: res.user.email ?? null },
  };
}

export interface AuthOutcome {
  session: SupabaseSession | null;
  /** "Check your email" when the project requires email confirmation. */
  needsConfirmation: boolean;
  error: string | null;
}

/** Email+password sign-up (the default-enabled Supabase lane). */
export async function signUp(email: string, password: string): Promise<AuthOutcome> {
  try {
    const res = await authCall('signup', { email, password });
    if (res.access_token !== undefined) {
      const session = toSession(res);
      if (session !== null) {
        sessionCache = session;
        persistSession(session);
        return { session, needsConfirmation: false, error: null };
      }
    }
    // No session: either confirmation is required, or an error came back.
    if (res.msg !== undefined || res.error === undefined) {
      return { session: null, needsConfirmation: true, error: null };
    }
    return { session: null, needsConfirmation: false, error: res.error_description ?? res.error };
  } catch (err) {
    return { session: null, needsConfirmation: false, error: errText(err) };
  }
}

/** Email+password sign-in. */
export async function signInWithPassword(email: string, password: string): Promise<AuthOutcome> {
  try {
    const res = await authCall('token?grant_type=password', { email, password });
    const session = toSession(res);
    if (session === null) {
      return {
        session: null,
        needsConfirmation: false,
        error: res.error_description ?? res.error ?? 'invalid credentials',
      };
    }
    sessionCache = session;
    persistSession(session);
    return { session, needsConfirmation: false, error: null };
  } catch (err) {
    return { session: null, needsConfirmation: false, error: errText(err) };
  }
}

/** The refresh flow (once-per-401; single-flight). */
async function refreshSession(): Promise<SupabaseSession | null> {
  if (refreshInFlight !== null) return refreshInFlight;
  const current = sessionCache ?? readPersistedSession();
  if (current === null) return null;
  refreshInFlight = (async () => {
    try {
      const res = await authCall('token?grant_type=refresh_token', {
        refresh_token: current.refreshToken,
      });
      const session = toSession(res);
      if (session === null) {
        // Dead refresh token — clear.
        sessionCache = null;
        persistSession(null);
        return null;
      }
      sessionCache = session;
      persistSession(session);
      return session;
    } catch {
      return null;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

/** The live session: memory → persisted → refresh-on-expiry. */
export async function getSession(): Promise<SupabaseSession | null> {
  if (sessionCache !== null) {
    if (sessionCache.expiresAt > Date.now() / 1000 + 60) return sessionCache;
    return refreshSession();
  }
  const persisted = readPersistedSession();
  if (persisted === null) return null;
  sessionCache = persisted;
  if (persisted.expiresAt > Date.now() / 1000 + 60) return persisted;
  return refreshSession();
}

export async function signOut(): Promise<void> {
  const current = sessionCache ?? readPersistedSession();
  if (current !== null) {
    // Best-effort revoke (anon-allowed).
    void authCall('logout', { refresh_token: current.refreshToken }).catch(() => undefined);
  }
  sessionCache = null;
  persistSession(null);
}

// ---------------------------------------------------------------------------
// PostgREST helper (authed, 401 → refresh → replay once)
// ---------------------------------------------------------------------------

async function restCall(
  path: string,
  init: { method: string; body?: unknown; prefer?: string; signal?: AbortSignal },
): Promise<Response> {
  const env = supabaseEnv();
  if (env === null) throw new Error('Supabase not configured');
  let session = await getSession();
  if (session === null) throw new Error('not signed in');
  const doFetch = (): Promise<Response> =>
    fetch(`${env.url}/rest/v1/${path}`, {
      method: init.method,
      headers: {
        apikey: env.anonKey,
        authorization: `Bearer ${session!.accessToken}`,
        'content-type': 'application/json',
        ...(init.prefer !== undefined ? { prefer: init.prefer } : {}),
      },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      signal: init.signal,
    });
  let res = await doFetch();
  if (res.status === 401) {
    const refreshed = await refreshSession();
    if (refreshed !== null) {
      session = refreshed;
      res = await doFetch();
    }
  }
  return res;
}

// ---------------------------------------------------------------------------
// Library — likes (mirrors SupabaseTracksClient, id-compatible)
// ---------------------------------------------------------------------------

export interface CloudTrack {
  id: string;
  title: string;
  artist: string;
  album: string | null;
  durationSec: number | null;
  coverUrl: string | null;
  streamUrl: string | null;
}

/** Java String.hashCode — the Android app's cloud-id derivation (must match). */
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i += 1) {
    h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  }
  return h;
}

/** `trk_<|abs(hash)>` — byte-compatible with the Android upsert. */
export function cloudTrackId(title: string, artist: string): string {
  const sig = `${title.trim().toLowerCase()}_${artist.trim().toLowerCase()}`;
  return `trk_${Math.abs(javaHash(sig))}`;
}

/** Pull the user's liked tracks (user_likes ⋈ tracks). */
export async function fetchLikes(signal?: AbortSignal): Promise<CloudTrack[]> {
  const session = await getSession();
  if (session === null) return [];
  const likesRes = await restCall(
    `user_likes?user_id=eq.${session.user.id}&select=track_id&order=created_at.desc`,
    { method: 'GET', signal },
  );
  if (!likesRes.ok) return [];
  const likes = (await likesRes.json()) as Array<{ track_id: string }>;
  if (likes.length === 0) return [];
  const ids = likes.map((l) => l.track_id).join(',');
  const tracksRes = await restCall(`tracks?id=in.(${ids})&select=*`, { method: 'GET', signal });
  if (!tracksRes.ok) return [];
  const rows = (await tracksRes.json()) as Array<Record<string, unknown>>;
  const byId = new Map(rows.map((r) => [String(r.id), r]));
  // Preserve like order (most recent first).
  const out: CloudTrack[] = [];
  for (const like of likes) {
    const row = byId.get(like.track_id);
    if (row === undefined) continue;
    out.push({
      id: String(row.id),
      title: String(row.title ?? ''),
      artist: String(row.artist ?? ''),
      album: row.album === null || row.album === undefined ? null : String(row.album),
      durationSec:
        row.duration_sec === null || row.duration_sec === undefined
          ? null
          : Number(row.duration_sec),
      coverUrl: row.cover_url === null || row.cover_url === undefined ? null : String(row.cover_url),
      streamUrl:
        row.stream_url === null || row.stream_url === undefined ? null : String(row.stream_url),
    });
  }
  return out;
}

/** Like a track: upsert the tracks row (id-merge) + insert user_likes. */
export async function likeTrack(track: {
  title: string;
  artist: string;
  album?: string | null;
  durationSec?: number | null;
  coverUrl?: string | null;
  videoId?: string | null;
}): Promise<boolean> {
  const id = cloudTrackId(track.title, track.artist);
  const session = await getSession();
  if (session === null) return false;
  // tracks upsert (merge-duplicates, same posture as the Android client).
  const upsert = await restCall('tracks', {
    method: 'POST',
    prefer: 'resolution=merge-duplicates',
    body: {
      id,
      title: track.title,
      artist: track.artist,
      album: track.album ?? null,
      duration_sec: track.durationSec ?? null,
      cover_url: track.coverUrl ?? null,
      stream_url: track.videoId ?? null,
    },
  });
  if (!upsert.ok && upsert.status !== 409) return false;
  const like = await restCall('user_likes', {
    method: 'POST',
    prefer: 'resolution=ignore-duplicates',
    body: { user_id: session.user.id, track_id: id },
  });
  return like.ok || like.status === 409;
}

/** Remove a like (row stays in the shared catalog). */
export async function unlikeTrack(trackId: string): Promise<boolean> {
  const session = await getSession();
  if (session === null) return false;
  const res = await restCall(`user_likes?user_id=eq.${session.user.id}&track_id=eq.${trackId}`, {
    method: 'DELETE',
  });
  return res.ok;
}

// ---------------------------------------------------------------------------
// Stats — play events (mirrors SupabaseStatsClient's batch POST)
// ---------------------------------------------------------------------------

export async function recordPlayEvent(event: {
  trackSig: string;
  title: string;
  artist: string;
  durationPlayedSec: number;
  completionRatio: number;
}): Promise<void> {
  const session = await getSession();
  if (session === null) return;
  await restCall('user_play_events', {
    method: 'POST',
    prefer: 'return=minimal',
    body: {
      user_id: session.user.id,
      track_sig: event.trackSig,
      title: event.title,
      artist: event.artist,
      duration_played_sec: Math.round(event.durationPlayedSec),
      completion_ratio: Math.min(1, Math.max(0, event.completionRatio)),
      hour_of_day: new Date().getHours(),
    },
  }).catch(() => undefined);
}

function errText(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
