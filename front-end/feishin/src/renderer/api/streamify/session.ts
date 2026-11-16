/**
 * streamify/session.ts - the session seam between the controller reads and
 * Engineer A's auth flow (P1 kickoff: "agree on the request interface on day
 * one, then build against a mock session").
 *
 * THE CONTRACT (the part A's authenticate must honor):
 *
 *   `authenticate(url, body)` returns feishin's AuthenticationResponse with
 *   `credential` set to `JSON.stringify(StreamifyCredentialPayload)` (the
 *   codec below). Every read method then receives that same string on
 *   `apiClientProps.server.credential` and decodes it here. The payload
 *   carries everything a read needs - no hidden module state, no second
 *   auth store: feishin's server store stays the single source of truth,
 *   exactly like the Navidrome credential (token) and Subsonic credential
 *   (token+salt query string) before it.
 *
 * THE MOCK (unblocks reads ahead of A's flow):
 *
 *   When the credential is absent or not a Streamify payload, the provider
 *   falls back to deployment variables from settings.js:
 *
 *     window.STREAMIFY_SUPABASE_URL      - the Supabase project URL
 *     window.STREAMIFY_SUPABASE_ANON_KEY - the public anon key (RLS is the
 *                                          fence; the key is public by
 *                                          design, same as front-end/web)
 *     window.STREAMIFY_DEV_ACCESS_TOKEN  - OPTIONAL dev-only access token
 *                                          (generate one out-of-band for
 *                                          local development; empty in
 *                                          real deployments)
 *
 *   With URL+ANON_KEY set and no token, reads resolve an anonymous session:
 *   the library snapshot loads empty (RLS returns nothing) and every list
 *   renders its honest empty state. With a dev token, a developer gets the
 *   full read surface today - the "mock session" the kickoff asked for.
 *
 * No upstream file is touched for any of this: the window declarations live
 * in this module (declare global), the settings.js variables are added to
 * settings.js.template (pre-approved touch, PATCHES.md).
 */

/**
 * The credential payload A's authenticate produces. Versioned so the shape
 * can evolve without guessing: `v: 1` is the P1 shape.
 */
export interface StreamifyCredentialPayload {
    accessToken: string;
    anonKey: string;
    email: null | string;
    expiresAt: number;
    isAdmin: boolean;
    refreshToken: string;
    supabaseUrl: string;
    userId: string;
    v: 1;
}

export interface StreamifySession {
    /** The user's access token (empty string = anonymous). */
    accessToken: string;
    /** The public anon key (RLS is the fence). */
    anonKey: string;
    email: null | string;
    /** Access token expiry, epoch seconds (0 = unknown). */
    expiresAt: number;
    isAdmin: boolean;
    /** The refresh token ('' when anonymous / not retained). */
    refreshToken: string;
    /** Where this session came from - surfaced in logs, nothing else. */
    source: 'credential' | 'dev';
    /** The Supabase project URL (no trailing slash). */
    supabaseUrl: string;
    userId: string;
}

/** Decode a credential string; null when it is not a v1 payload. */
export function decodeStreamifyCredential(
    raw: null | string | undefined,
): null | StreamifyCredentialPayload {
    if (raw === null || raw === undefined || raw === '') {
        return null;
    }
    try {
        const parsed = JSON.parse(raw) as Partial<StreamifyCredentialPayload>;
        if (parsed.v !== 1 || typeof parsed.supabaseUrl !== 'string' || parsed.supabaseUrl === '') {
            return null;
        }
        return {
            accessToken: typeof parsed.accessToken === 'string' ? parsed.accessToken : '',
            anonKey: typeof parsed.anonKey === 'string' ? parsed.anonKey : '',
            email: typeof parsed.email === 'string' ? parsed.email : null,
            expiresAt: typeof parsed.expiresAt === 'number' ? parsed.expiresAt : 0,
            isAdmin: parsed.isAdmin === true,
            refreshToken: typeof parsed.refreshToken === 'string' ? parsed.refreshToken : '',
            supabaseUrl: parsed.supabaseUrl,
            userId: typeof parsed.userId === 'string' ? parsed.userId : '',
            v: 1,
        };
    } catch {
        return null;
    }
}

/** Encode a credential payload (A's side; kept here so both ends share it). */
export function encodeStreamifyCredential(payload: StreamifyCredentialPayload): string {
    return JSON.stringify(payload);
}

declare global {
    interface Window {
        STREAMIFY_DEV_ACCESS_TOKEN?: string;
        STREAMIFY_SUPABASE_ANON_KEY?: string;
        STREAMIFY_SUPABASE_URL?: string;
    }
}

/**
 * Resolve the session a set of reads should run under.
 *
 * 1. The server's credential (A's authenticate output) - the production lane.
 * 2. The settings.js dev fallback - the mock session (dev token optional).
 * 3. null - signed out: every read returns its honest empty result.
 */
export function resolveStreamifySession(
    credential: null | string | undefined,
): null | StreamifySession {
    const payload = decodeStreamifyCredential(credential);
    if (payload !== null) {
        return {
            accessToken: payload.accessToken,
            anonKey: payload.anonKey,
            email: payload.email,
            expiresAt: payload.expiresAt,
            isAdmin: payload.isAdmin,
            refreshToken: payload.refreshToken,
            source: 'credential',
            supabaseUrl: payload.supabaseUrl.replace(/\/+$/, ''),
            userId: payload.userId,
        };
    }
    return devSession();
}

/**
 * Resolve the session a read/write should run under - the async form P3A
 * adds (P1B's sync resolver stays untouched for compatibility).
 *
 * Resolution order:
 *   1. The credential payload codec (the P1 contract lane - authenticate
 *      outputs JSON.stringify(StreamifyCredentialPayload)).
 *   2. The LIVE supabase-js session. P1A's synthetic server entry stores
 *      the raw access token as the credential (not a JSON payload), so
 *      signed-in users only resolve through the SDK client - without this
 *      lane they silently fell to the anonymous dev session and every
 *      authenticated read came back empty (the P1A/P1B integration gap;
 *      see decisions/p3/P3A-jam-playlists.md).
 *   3. The settings.js dev fallback (the P1 mock session).
 *   4. null - fully unconfigured: honest empty results.
 */
export async function resolveStreamifySessionAsync(
    credential: null | string | undefined,
): Promise<null | StreamifySession> {
    const payload = decodeStreamifyCredential(credential);
    if (payload !== null) {
        return {
            accessToken: payload.accessToken,
            anonKey: payload.anonKey,
            email: payload.email,
            expiresAt: payload.expiresAt,
            isAdmin: payload.isAdmin,
            refreshToken: payload.refreshToken,
            source: 'credential',
            supabaseUrl: payload.supabaseUrl.replace(/\/+$/, ''),
            userId: payload.userId,
        };
    }
    const live = await liveSupabaseSession();
    if (live !== null) {
        return live;
    }
    return devSession();
}

/** A stable fingerprint for snapshot memoization (session identity). */
export function streamifySessionKey(session: StreamifySession): string {
    return `${session.supabaseUrl}|${session.userId}|${session.accessToken}`;
}

/**
 * A REFRESH-STABLE fingerprint (identity, not token): the memo key for
 * long-lived loads whose data depends on the user, not the access token.
 * P1B's key included the raw token, so every silent token refresh (hourly)
 * invalidated the snapshot memo and forced a full reload; the stable key
 * keeps the memo warm across refreshes while still separating accounts.
 */
export function streamifySessionStableKey(session: StreamifySession): string {
    return `${session.supabaseUrl}|${session.userId}`;
}

function devSession(): null | StreamifySession {
    const supabaseUrl = readWindow('STREAMIFY_SUPABASE_URL').replace(/\/+$/, '');
    const anonKey = readWindow('STREAMIFY_SUPABASE_ANON_KEY');
    if (supabaseUrl === '' || anonKey === '') {
        return null;
    }
    const accessToken = readWindow('STREAMIFY_DEV_ACCESS_TOKEN');
    return {
        accessToken,
        anonKey,
        email: null,
        expiresAt: 0,
        isAdmin: false,
        refreshToken: '',
        source: 'dev',
        supabaseUrl,
        userId: '',
    };
}

/**
 * The live supabase-js session as a StreamifySession. The import is
 * dynamic so this module stays importable in the network-free test lane
 * (node env, no window); any failure resolves null - the dev fallback (or
 * null) answers honestly.
 */
async function liveSupabaseSession(): Promise<null | StreamifySession> {
    try {
        const supabaseModule = await import('./streamify-supabase');
        const config = supabaseModule.getStreamifySupabaseConfig();
        const supabase = supabaseModule.getStreamifySupabase();
        if (!config || !supabase) {
            return null;
        }
        const { data } = await supabase.auth.getSession();
        const session = data.session;
        if (!session || !session.user) {
            return null;
        }
        return {
            accessToken: session.access_token,
            anonKey: config.anonKey,
            email: session.user.email ?? null,
            expiresAt: session.expires_at ?? 0,
            isAdmin: false,
            refreshToken: session.refresh_token ?? '',
            source: 'credential',
            supabaseUrl: config.url,
            userId: session.user.id,
        };
    } catch {
        return null;
    }
}

function readWindow(
    name: 'STREAMIFY_DEV_ACCESS_TOKEN' | 'STREAMIFY_SUPABASE_ANON_KEY' | 'STREAMIFY_SUPABASE_URL',
): string {
    // Electron preload + web settings.js both land plain strings on window.
    // Deployments that never set a variable leave the literal `${...}`
    // placeholder behind (nginx envsubst only substitutes defined vars -
    // same reason upstream's env-settings parser filters them), so those
    // read as unset here too. The try/catch keeps the network-free test
    // lane (node env, no window) honest: unset, not fatal.
    let value: unknown;
    try {
        value = (window as Window)[name];
    } catch {
        return '';
    }
    if (typeof value !== 'string') {
        return '';
    }
    const trimmed = value.trim();
    if (trimmed.startsWith('${STREAMIFY_') && trimmed.endsWith('}')) {
        return '';
    }
    return trimmed;
}
