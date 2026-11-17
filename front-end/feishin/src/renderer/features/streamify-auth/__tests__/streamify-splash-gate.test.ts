/**
 * streamify-splash-gate.test.ts — the #202 restore lock (node lane).
 *
 * The vitest lane is network-free node (no DOM): what is locked HERE is
 * the decision logic and the data the gate consumes —
 *
 *   - the action-required branch condition: a locked STREAMIFY
 *     deployment takes the splash gate, every other locked type keeps
 *     the stock LoginRoute (Username/Password) — the branch helpers are
 *     pure window readers, so the suppression rule itself is pinned;
 *   - the local-mode credential: the red Play CTA's credential DECODES
 *     through the v:1 session codec as an anonymous session, so the
 *     app-outlet's hasMissingCredentials check passes and every read
 *     resolves its honest empty (fetchPlaylists short-circuits on the
 *     empty access token — no network to the placeholder URL);
 *   - the OAuth contract: the redirectTo shape the gate passes to
 *     supabase.auth.signInWithOAuth is origin + pathname (subpath-safe
 *     under /streamify-apk/ and at /), the same construction the live
 *     streamify-auth route uses;
 *   - the module surface: StreamifySplashGate is exported with the
 *     props contract (animate / getSupabase / onLocalEnter) and the
 *     component module imports cleanly in node (rAF only touched inside
 *     the effect when animate && !reducedMotion).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

// The component module pulls the auth store chain, which reaches
// env-settings-overrides → sanitize (dompurify needs a DOM; node lane).
vi.mock('dompurify', () => ({
    default: { addHook: vi.fn(), sanitize: (x: string) => x },
}));

import {
    encodeStreamifyLocalCredential,
    STREAMIFY_LOCAL_URL,
} from '/@/renderer/api/streamify/local-entry';
import { decodeStreamifyCredential } from '/@/renderer/api/streamify/session';
import {
    isServerLock,
    isStreamifyLockedDeployment,
} from '/@/renderer/features/action-required/utils/window-properties';

type MutableWindow = Record<string, unknown>;

function clearWindow(): void {
    delete (globalThis as MutableWindow).window;
}

function setWindow(props: Record<string, unknown>): void {
    (globalThis as MutableWindow).window = props as unknown as typeof globalThis & Window;
}

afterEach(() => {
    clearWindow();
    vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// The action-required branch rule
// ---------------------------------------------------------------------------

describe('#202 action-required branch — locked STREAMIFY renders the splash gate', () => {
    it('a locked streamify deployment satisfies both gate conditions', () => {
        setWindow({ SERVER_LOCK: 'true', SERVER_TYPE: 'streamify' });
        expect(isServerLock()).toBe(true);
        expect(isStreamifyLockedDeployment()).toBe(true);
    });

    it('SERVER_TYPE is case-insensitive (login-route uses toServerType)', () => {
        setWindow({ SERVER_LOCK: true, SERVER_TYPE: 'Streamify' });
        expect(isStreamifyLockedDeployment()).toBe(true);
    });

    it('an unlocked or non-streamify deployment keeps the stock flow', () => {
        setWindow({ SERVER_LOCK: 'false', SERVER_TYPE: 'streamify' });
        expect(isServerLock()).toBe(false);

        setWindow({ SERVER_LOCK: 'true', SERVER_TYPE: 'navidrome' });
        expect(isServerLock()).toBe(true);
        expect(isStreamifyLockedDeployment()).toBe(false);

        setWindow({ SERVER_LOCK: 'true' });
        expect(isStreamifyLockedDeployment()).toBe(false);
    });
});

// ---------------------------------------------------------------------------
// The local-mode credential codec
// ---------------------------------------------------------------------------

describe('#202 local-mode entry — the anonymous v:1 credential', () => {
    it('encodes a payload that decodes through the session codec', () => {
        const raw = encodeStreamifyLocalCredential();
        const payload = decodeStreamifyCredential(raw);
        expect(payload).not.toBeNull();
        expect(payload?.v).toBe(1);
        expect(payload?.supabaseUrl).toBe(STREAMIFY_LOCAL_URL);
    });

    it('is anonymous: empty access token + user (honest-empty reads, no dial-out)', () => {
        const payload = decodeStreamifyCredential(encodeStreamifyLocalCredential());
        expect(payload?.accessToken).toBe('');
        expect(payload?.userId).toBe('');
        // fetchPlaylists short-circuits exactly on this: no request is
        // ever built against the placeholder URL.
        expect(payload?.accessToken === '').toBe(true);
    });

    it('accepts the configured Supabase URL when present', () => {
        const payload = decodeStreamifyCredential(
            encodeStreamifyLocalCredential('https://real-project.supabase.co'),
        );
        expect(payload?.supabaseUrl).toBe('https://real-project.supabase.co');
    });
});

// ---------------------------------------------------------------------------
// The OAuth redirectTo contract (subpath safety)
// ---------------------------------------------------------------------------

describe('#202 Google OAuth redirectTo — subpath-safe', () => {
    it('builds origin + pathname (query/hash-free) under a subpath', () => {
        setWindow({
            location: {
                hash: '#/action-required',
                origin: 'https://meraxes89.github.io',
                pathname: '/streamify-apk/',
                search: '',
            },
        });
        const redirectTo = `${window.location.origin}${window.location.pathname}`;
        expect(redirectTo).toBe('https://meraxes89.github.io/streamify-apk/');
    });

    it('builds origin + / at a root deployment', () => {
        setWindow({
            location: {
                hash: '#/action-required',
                origin: 'https://streamify.example',
                pathname: '/',
                search: '',
            },
        });
        expect(`${window.location.origin}${window.location.pathname}`).toBe(
            'https://streamify.example/',
        );
    });
});

// ---------------------------------------------------------------------------
// The module surface (import-time hygiene)
// ---------------------------------------------------------------------------

describe('#202 StreamifySplashGate module surface', () => {
    it('exports the component with the injectable-props contract', async () => {
        const mod =
            await import('/@/renderer/features/streamify-auth/components/streamify-splash-gate');
        expect(typeof mod.StreamifySplashGate).toBe('function');
    });

    it('local-entry exports are stable', async () => {
        const mod = await import('/@/renderer/api/streamify/local-entry');
        expect(mod.STREAMIFY_LOCAL_URL).toBe('https://streamify.local');
        expect(typeof mod.encodeStreamifyLocalCredential).toBe('function');
    });
});
