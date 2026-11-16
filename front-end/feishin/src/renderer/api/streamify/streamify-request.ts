/**
 * streamify-request.ts — the authenticated request lane for Streamify
 * endpoints (P1).
 *
 * Every Streamify controller endpoint that talks to a backend goes through
 * here instead of raw fetch/axios:
 *
 *   - requests carry the live Supabase access token (Bearer) plus the anon
 *     key, exactly like the ws-w client's restCall lane;
 *   - a 401 triggers exactly ONE refresh-and-retry (supabase.auth.refreshSession
 *     → replay the request with the fresh token);
 *   - a second 401 (or a failed refresh) is a dead session: clean sign-out
 *     (SDK sign-out + synthetic entry removal) and a bounce back to the
 *     /streamify-auth route. No crash, no unauthenticated library calls.
 *
 * P1 uses this lane against the Supabase auth surface itself (GoTrue's
 * /auth/v1/user is the token-validated server-info probe); later phases point
 * it at the Streamify engine facade endpoints with the same contract.
 */
import type { Session } from '@supabase/supabase-js';

import {
    getStreamifySession,
    getStreamifySupabase,
    getStreamifySupabaseConfig,
    signOutStreamify,
} from '/@/renderer/api/streamify/streamify-supabase';
import { logger } from '/@/renderer/utils/logger';

export interface StreamifyRequestInit {
    body?: unknown;
    headers?: Record<string, string>;
    method?: string;
    signal?: AbortSignal;
}

/** Thrown when the session is dead and the clean sign-out bounce has begun. */
export class StreamifySessionExpiredError extends Error {
    constructor(message = 'Streamify session expired — signing out') {
        super(message);
        this.name = 'StreamifySessionExpiredError';
    }
}

const UNCONFIGURED_ERROR =
    'Streamify is not configured for this deployment (missing STREAMIFY_SUPABASE_URL / STREAMIFY_SUPABASE_ANON_KEY in settings.js)';

const performRequest = async (
    url: string,
    session: Session,
    anonKey: string,
    init: StreamifyRequestInit,
): Promise<Response> =>
    fetch(url, {
        body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
        headers: {
            apikey: anonKey,
            authorization: `Bearer ${session.access_token}`,
            'content-type': 'application/json',
            ...(init.headers ?? {}),
        },
        method: init.method ?? 'GET',
        signal: init.signal,
    });

const rejectSession = async (reason: string): Promise<never> => {
    logger.warn('Streamify session rejected — signing out', {
        action: 'streamify_session_rejected',
        reason,
    });

    await signOutStreamify();

    throw new StreamifySessionExpiredError();
};

/**
 * Perform an authenticated request against the Streamify backend
 * (Supabase project base URL + path). On 401: one refresh-and-retry,
 * then a clean sign-out back to /streamify-auth.
 */
export const streamifyRequest = async <T>(
    path: string,
    init: StreamifyRequestInit = {},
): Promise<T> => {
    const config = getStreamifySupabaseConfig();

    if (!config) {
        throw new Error(UNCONFIGURED_ERROR);
    }

    const url = `${config.url}${path}`;

    let session = await getStreamifySession();

    if (!session) {
        return rejectSession('no active session');
    }

    let response = await performRequest(url, session, config.anonKey, init);

    if (response.status === 401) {
        const supabase = getStreamifySupabase();

        if (!supabase) {
            throw new Error(UNCONFIGURED_ERROR);
        }

        logger.debug('Streamify request got 401 — refreshing session and retrying once', {
            action: 'streamify_401_refresh_retry',
            path,
        });

        const { data, error } = await supabase.auth.refreshSession();

        if (error || !data.session) {
            return rejectSession(`session refresh failed: ${error?.message ?? 'no session'}`);
        }

        session = data.session;

        response = await performRequest(url, session, config.anonKey, init);

        if (response.status === 401) {
            return rejectSession('retry after refresh still rejected');
        }
    }

    if (!response.ok) {
        throw new Error(`Streamify request failed (${response.status} ${response.statusText})`);
    }

    return (await response.json()) as T;
};
