/**
 * lib/supabase.ts — W2.2: the web data layer (auth + library).
 *
 * HOUSE STYLE: raw `fetch` against the PostgREST/auth surfaces — NO SDK,
 * exactly like the canary's postResults.ts (the repo deliberately carries
 * zero supabase-js dependencies; RLS is the fence, the anon key is public
 * by design and NEVER committed — it arrives via VITE_SUPABASE_ANON_KEY,
 * baked by CI from the repo secret).
 *
 * AUTH: Google OAuth (P3a) — the Android One-Tap lane's web lowering:
 * full-page redirect through /auth/v1/authorize using the PKCE flow
 * (S256 code challenge; tokens arrive via the code exchange, never the
 * URL), with the legacy implicit fragment lane kept as a fallback for
 * insecure contexts / private mode. The redirect back is consumed
 * pre-render (see SUPABASE_CLIENT_OPTIONS + consumeOAuthRedirect below).
 * Sessions persist in localStorage; 401s trigger the refresh-token flow
 * once, then replay.
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

async function authCall(
  path: string,
  body: unknown,
  init?: { signal?: AbortSignal },
): Promise<AuthResponse> {
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
    signal: init?.signal,
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

// ---------------------------------------------------------------------------
// Google OAuth (P3a) — the Android app's One-Tap lane, web lowering.
//
// Same raw-fetch house style: NO SDK. The client honors the supabase-js
// createClient semantics below — the SDK equivalent of
// `createClient(url, key, { auth: { detectSessionInUrl: true,
// flowType: 'pkce' } })`, implemented directly on fetch:
//
//   - detectSessionInUrl: consumeOAuthRedirect() (main.tsx, BEFORE the
//     router mounts) parses BOTH redirect shapes Supabase returns —
//     the PKCE authorization code in the query string (`?code=…`) and
//     the legacy implicit tokens in the fragment (`#access_token=…`) —
//     then scrubs the URL so nothing lingers in the address bar.
//   - flowType 'pkce': signInWithGoogle() sends an S256 code challenge;
//     the tokens then arrive via the POST exchange response (which
//     carries the full user object), never through the URL.
//   - persistSession / autoRefreshToken: localStorage persistence plus
//     the 401 → refresh → replay lane in restCall() (pre-existing).
// ---------------------------------------------------------------------------

/** The supabase-js client options this raw-fetch client implements. */
const SUPABASE_CLIENT_OPTIONS = {
  auth: {
    detectSessionInUrl: true,
    flowType: 'pkce',
    persistSession: true,
    autoRefreshToken: true,
  },
} as const;

/** The URL Supabase should redirect back to (this page, query/hash-free). */
function oauthRedirectTo(): string {
  return `${window.location.origin}${window.location.pathname}`;
}

// ---- PKCE (RFC 7636) — verifier + S256 challenge --------------------

const PKCE_VERIFIER_KEY = 'streamify.pkce.verifier';

function base64UrlFromBytes(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** A 43-char base64url verifier (32 random bytes — RFC 7636 §4.1). */
function newPkceVerifier(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64UrlFromBytes(bytes);
}

/** The S256 challenge: base64url(SHA-256(verifier)). */
async function pkceChallengeS256(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64UrlFromBytes(new Uint8Array(digest));
}

/**
 * Begin Google sign-in: full-page navigation to the Supabase authorize
 * endpoint (the supabase-js `signInWithOAuth({provider:'google'})`
 * equivalent, minus the SDK). PKCE by default: the verifier is stored
 * client-side and the challenge rides the authorize URL, so the session
 * tokens come back through the code exchange — never the URL. Falls
 * back to the implicit fragment flow when WebCrypto/storage are
 * unavailable. Returns false when the build carries no Supabase
 * credentials.
 */
export async function signInWithGoogle(): Promise<boolean> {
  const env = supabaseEnv();
  if (env === null) return false;
  const params = new URLSearchParams({
    provider: 'google',
    redirect_to: oauthRedirectTo(),
    apikey: env.anonKey,
  });
  if (SUPABASE_CLIENT_OPTIONS.auth.flowType === 'pkce' && window.isSecureContext) {
    const verifier = newPkceVerifier();
    let stored = false;
    try {
      localStorage.setItem(PKCE_VERIFIER_KEY, verifier);
      stored = localStorage.getItem(PKCE_VERIFIER_KEY) === verifier;
    } catch {
      stored = false; // private mode etc. — implicit flow still works
    }
    if (stored) {
      try {
        params.set('code_challenge', await pkceChallengeS256(verifier));
        params.set('code_challenge_method', 's256');
      } catch {
        /* crypto.subtle unavailable — keep the implicit flow */
      }
    }
  }
  window.location.assign(`${env.url}/auth/v1/authorize?${params.toString()}`);
  return true;
}

let pendingOAuthSession: SupabaseSession | null = null;
let pendingOAuthError: string | null = null;
let oauthExchangeInFlight: Promise<void> | null = null;

/**
 * Decode the user identity (sub / email claims) straight out of a
 * Supabase access-token JWT. The implicit fragment no longer carries a
 * `user` param on current GoTrue — the token itself is the payload of
 * record (this is what un-breaks the "no user payload" redirect).
 */
function userFromAccessToken(token: string): { id?: string; email?: string } {
  const parts = token.split('.');
  if (parts.length !== 3) return {};
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
    const payload = JSON.parse(
      new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))),
    ) as { sub?: unknown; email?: unknown };
    if (typeof payload.sub === 'string' && payload.sub !== '') {
      return {
        id: payload.sub,
        email: typeof payload.email === 'string' ? payload.email : undefined,
      };
    }
  } catch {
    /* not a decodable JWT — caller reports the honest error */
  }
  return {};
}

/** Implicit branch: tokens (and best-effort identity) from the fragment. */
function consumeImplicitFragment(fragment: URLSearchParams): void {
  const accessToken = fragment.get('access_token');
  const refreshToken = fragment.get('refresh_token');
  if (accessToken === null || refreshToken === null) {
    pendingOAuthError = 'OAuth redirect carried no tokens';
    return;
  }
  // Identity: the `user` param when GoTrue sends it, else decoded from
  // the access-token JWT (sub/email claims).
  let user: { id?: string; email?: string } | undefined;
  const rawUser = fragment.get('user');
  if (rawUser !== null) {
    try {
      user = JSON.parse(rawUser) as { id?: string; email?: string };
    } catch {
      user = undefined;
    }
  }
  if (user?.id === undefined) user = userFromAccessToken(accessToken);
  if (user?.id === undefined) {
    pendingOAuthError = 'OAuth redirect carried no user payload';
    return;
  }
  const expiresIn = Number(fragment.get('expires_in') ?? '3600');
  const session: SupabaseSession = {
    accessToken,
    refreshToken,
    expiresAt:
      Math.floor(Date.now() / 1000) + (Number.isFinite(expiresIn) && expiresIn > 0 ? expiresIn : 3600),
    user: { id: user.id, email: user.email ?? null },
  };
  sessionCache = session;
  persistSession(session);
  pendingOAuthSession = session;
}

/** PKCE branch: exchange the one-time `?code=` for the session (POST). */
async function exchangePkceCode(code: string): Promise<void> {
  const env = supabaseEnv();
  let verifier: string | null = null;
  try {
    verifier = localStorage.getItem(PKCE_VERIFIER_KEY);
    if (verifier !== null) localStorage.removeItem(PKCE_VERIFIER_KEY); // one-time
  } catch {
    verifier = null;
  }
  if (env === null || verifier === null) {
    pendingOAuthError =
      'OAuth code could not be exchanged (missing PKCE verifier) — please sign in again.';
    return;
  }
  // The exchange response carries the full session + user object. A 10 s
  // abort guard keeps a hung network call from wedging the app boot.
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 10_000);
  try {
    const res = await authCall(
      'token?grant_type=pkce',
      { auth_code: code, code_verifier: verifier },
      { signal: controller.signal },
    );
    const session = toSession(res);
    if (session === null) {
      pendingOAuthError =
        res.error_description ??
        res.msg ??
        res.error ??
        'OAuth code exchange failed — please sign in again.';
      return;
    }
    sessionCache = session;
    persistSession(session);
    pendingOAuthSession = session;
  } catch (err) {
    pendingOAuthError = `OAuth code exchange failed: ${errText(err)}`;
  } finally {
    window.clearTimeout(timer);
  }
}

/**
 * Consume an OAuth redirect (if any) — the detectSessionInUrl lane.
 *
 * Runs synchronously BEFORE the router mounts (main.tsx). Handles BOTH
 * redirect shapes: PKCE `?code=…` in window.location.search (the flow
 * signInWithGoogle now initiates) and implicit `#access_token=…` in
 * window.location.hash (the legacy/fallback flow). The URL is scrubbed
 * with replaceState FIRST — synchronously, so the hash router never
 * sees the payload as a route and the tokens never linger in the
 * address bar or history. The PKCE code exchange then runs in the
 * background; await via awaitOAuthExchange() (sessionStore.init does).
 * Safe to call on every load (no-ops when neither shape is present).
 */
export function consumeOAuthRedirect(): void {
  if (!SUPABASE_CLIENT_OPTIONS.auth.detectSessionInUrl) return;

  const search = window.location.search.startsWith('?')
    ? new URLSearchParams(window.location.search.slice(1))
    : new URLSearchParams();
  const fragment =
    window.location.hash.length >= 2
      ? new URLSearchParams(window.location.hash.slice(1))
      : null;

  const code = search.get('code'); // PKCE: ?code=…
  const accessToken = fragment?.get('access_token') ?? null; // implicit: #access_token=…
  const errorParam =
    (fragment?.get('error_description') ?? fragment?.get('error')) ??
    (search.get('error_description') ?? search.get('error'));

  if (code === null && accessToken === null && errorParam === null) {
    return; // a normal load / a hash-router route — not OAuth
  }

  // Scrub FIRST (query AND fragment, replaceState — no history entry).
  window.history.replaceState(null, '', window.location.pathname);

  if (errorParam !== null) {
    pendingOAuthError = errorParam;
    return;
  }
  if (accessToken !== null) {
    consumeImplicitFragment(fragment as URLSearchParams);
    return;
  }
  oauthExchangeInFlight = exchangePkceCode(code as string);
}

/**
 * The in-flight PKCE code exchange kicked off by consumeOAuthRedirect
 * (resolves immediately when no exchange is running). sessionStore.init
 * awaits this so the session is settled before the UI reads it.
 */
export function awaitOAuthExchange(): Promise<void> {
  return oauthExchangeInFlight ?? Promise.resolve();
}

/** The session parsed from an OAuth redirect (one-shot, for the store). */
export function takePendingOAuthSession(): SupabaseSession | null {
  const s = pendingOAuthSession;
  pendingOAuthSession = null;
  return s;
}

/** The OAuth error from a failed redirect (one-shot, for the store). */
export function takePendingOAuthError(): string | null {
  const e = pendingOAuthError;
  pendingOAuthError = null;
  return e;
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
