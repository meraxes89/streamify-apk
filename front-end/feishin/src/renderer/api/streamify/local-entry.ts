/**
 * api/streamify/local-entry.ts — the local-mode credential codec (#202).
 *
 * The splash gate's red Play CTA enters Streamify without Google: a
 * synthetic server entry whose credential is a VALID v:1
 * StreamifyCredentialPayload carrying an anonymous session (empty access
 * token). decodeStreamifyCredential accepts it (v === 1 + a non-empty
 * supabaseUrl), so the app-outlet's hasMissingCredentials check passes
 * and every read resolves through the P1 session contract honestly:
 * anonymous under a configured Supabase project (RLS returns nothing —
 * the honest empty states), a non-routable placeholder when the
 * deployment carries no Supabase configuration at all.
 */
import type { StreamifyCredentialPayload } from './session';

/** The non-routable identity key (mirrors settings.js SERVER_URL). */
export const STREAMIFY_LOCAL_URL = 'https://streamify.local';

/**
 * Build the local-mode credential JSON (the v:1 codec payload). The
 * anonymous session: empty token/user, never expires, source 'local'.
 */
export function encodeStreamifyLocalCredential(supabaseUrl: string = STREAMIFY_LOCAL_URL): string {
    const payload: StreamifyCredentialPayload = {
        accessToken: '',
        anonKey: '',
        email: null,
        expiresAt: 0,
        isAdmin: false,
        refreshToken: '',
        supabaseUrl,
        userId: '',
        v: 1,
    };
    return JSON.stringify(payload);
}
