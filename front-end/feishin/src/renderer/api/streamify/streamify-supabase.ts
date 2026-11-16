import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';
/**
 * streamify-supabase.ts — the Streamify session backbone (P1).
 *
 * The Streamify server type is backed by our Supabase project (the same one
 * the flagship app and the ws-w web client use). This module owns:
 *
 *   - runtime configuration (settings.js knobs — deployment-provided, never
 *     committed: STREAMIFY_SUPABASE_URL / STREAMIFY_SUPABASE_ANON_KEY, with
 *     STREAMIFY_AUTH as an explicit opt-out),
 *   - the single lazily-created supabase-js client (PKCE + persisted sessions
 *     + auto refresh + URL redirect detection, matching the ws-w client
 *     semantics the project already uses),
 *   - the synthetic server entry: the live Supabase session is mirrored into
 *     feishin's own auth store as a `ServerType.STREAMIFY` server entry with
 *     a stable id, so it persists across reloads and sits in the server list
 *     exactly like a Navidrome/Jellyfin entry. The P0 stub controller is the
 *     backing controller for that entry.
 *   - clean sign-out: SDK sign-out + entry removal + hash navigation back to
 *     the /streamify-auth route.
 *
 * Nothing here touches upstream files; see PATCHES.md at the fork root.
 */
import type { Session } from '@supabase/supabase-js';

import { createClient, type SupabaseClient } from '@supabase/supabase-js';

import { STREAMIFY_SERVER_ID } from './streamify-server-id';

import { AppRoute } from '/@/renderer/router/routes';
import { useAuthStore } from '/@/renderer/store';
import { logger } from '/@/renderer/utils/logger';
import { ServerType } from '/@/shared/types/types';

// Re-exported for the existing import sites (P1/P2); the definition lives
// in the lightweight module so dependency-light lanes can import the id
// without the store/router/DOM-Purify chain.
export { STREAMIFY_SERVER_ID };

const STREAMIFY_SERVER_NAME = 'Streamify';

export interface StreamifySupabaseConfig {
    anonKey: string;
    url: string;
}

/**
 * Runtime configuration from settings.js (web deployments). The anon key is
 * public by design — RLS is the fence — and arrives via deployment
 * configuration, never through the tree.
 */
export const getStreamifySupabaseConfig = (): null | StreamifySupabaseConfig => {
    const url = window.STREAMIFY_SUPABASE_URL?.trim().replace(/\/+$/, '');
    const anonKey = window.STREAMIFY_SUPABASE_ANON_KEY?.trim();

    if (!url || !anonKey) return null;

    return { anonKey, url };
};

/**
 * Whether the Streamify auth flow is active for this deployment. Requires a
 * Supabase configuration; `STREAMIFY_AUTH=false` is an explicit opt-out for
 * hybrid deployments that want pure stock behavior.
 */
export const isStreamifyAuthEnabled = (): boolean => {
    if (!getStreamifySupabaseConfig()) return false;

    const flag = window.STREAMIFY_AUTH;
    return flag !== false && flag !== 'false';
};

let client: null | SupabaseClient = null;
let listenersBound = false;

/** Navigate to the streamify auth route (HashRouter-compatible). */
export const navigateToStreamifyAuth = () => {
    const target = `#${AppRoute.STREAMIFY_AUTH}`;

    if (window.location.hash !== target) {
        window.location.hash = target;
    }
};

/** Build the synthetic server entry for a live Supabase session. */
export const buildStreamifyServerEntry = (session: Session): ServerListItemWithCredential => {
    const config = getStreamifySupabaseConfig();
    const user = session.user;

    return {
        credential: session.access_token,
        id: STREAMIFY_SERVER_ID,
        isAdmin: false,
        name: STREAMIFY_SERVER_NAME,
        savePassword: false,
        type: ServerType.STREAMIFY,
        url: config?.url ?? '',
        userId: user.id,
        username: user.email ?? user.id,
    };
};

/**
 * Mirror the live session into the auth store as the synthetic server entry.
 *
 * The entry is only auto-selected when no other server is currently selected,
 * or when the streamify entry itself is — a user who deliberately switched to
 * their own Navidrome/Jellyfin server keeps that selection.
 */
export const upsertStreamifyServerEntry = (session: Session) => {
    const entry = buildStreamifyServerEntry(session);
    const store = useAuthStore.getState();
    const existing = store.actions.getServer(entry.id);

    if (existing) {
        store.actions.updateServer(entry.id, {
            credential: entry.credential,
            userId: entry.userId,
            username: entry.username,
        });
    } else {
        store.actions.addServer(entry);
    }

    const currentServer = useAuthStore.getState().currentServer;
    const shouldSelect = !currentServer || currentServer.id === STREAMIFY_SERVER_ID;

    if (shouldSelect) {
        const server = useAuthStore.getState().actions.getServer(entry.id);

        if (server) {
            useAuthStore.getState().actions.setCurrentServer(server);
        }
    }
};

/** Remove the synthetic server entry (no-op when absent). */
export const removeStreamifyServerEntry = () => {
    const store = useAuthStore.getState();

    if (store.actions.getServer(STREAMIFY_SERVER_ID)) {
        store.actions.deleteServer(STREAMIFY_SERVER_ID);
    }
};

/** Full clean sign-out: SDK sign-out, entry removal, and return to auth. */
export const signOutStreamify = async (): Promise<void> => {
    removeStreamifyServerEntry();

    if (client) {
        try {
            await client.auth.signOut({ scope: 'local' });
        } catch (error) {
            logger.warn('Streamify sign-out failed', {
                action: 'streamify_signout_failed',
                error: error instanceof Error ? error.message : String(error),
            });
        }
    }

    navigateToStreamifyAuth();
};

/**
 * Defensive cleanup of OAuth redirect parameters. supabase-js scrubs the
 * authorization code after the PKCE exchange; this keeps the URL clean even
 * if the SDK leaves an `error=`/`code=` behind (and never fires when there
 * is nothing to scrub).
 */
const scrubOAuthParams = () => {
    const search = window.location.search;

    if (!search || !/[?&](code|error|error_code|error_description)=/.test(search)) return;

    window.history.replaceState(
        window.history.state,
        '',
        window.location.pathname + window.location.hash,
    );
};

const bindAuthStateListeners = (supabase: SupabaseClient) => {
    if (listenersBound) return;

    listenersBound = true;

    supabase.auth.onAuthStateChange((event, session) => {
        logger.debug('Streamify auth state changed', { event });

        if (session) {
            // Keep the synthetic entry in sync with the live session (sign-in,
            // silent refresh, profile update, restore on boot).
            upsertStreamifyServerEntry(session);
        }

        if (event === 'SIGNED_OUT') {
            removeStreamifyServerEntry();
            navigateToStreamifyAuth();
        }
    });
};

/**
 * The single supabase-js client for the Streamify server type. Returns null
 * when the deployment carries no Supabase configuration (stock behavior).
 */
export const getStreamifySupabase = (): null | SupabaseClient => {
    const config = getStreamifySupabaseConfig();

    if (!config) return null;

    if (!client) {
        client = createClient(config.url, config.anonKey, {
            auth: {
                autoRefreshToken: true,
                detectSessionInUrl: true,
                flowType: 'pkce',
                persistSession: true,
            },
        });

        bindAuthStateListeners(client);
    }

    return client;
};

/**
 * The current Supabase session (restored from storage, consuming any OAuth
 * redirect when present). Resolves null when unconfigured or signed out.
 */
export const getStreamifySession = async (): Promise<null | Session> => {
    const supabase = getStreamifySupabase();

    if (!supabase) return null;

    try {
        const { data } = await supabase.auth.getSession();
        scrubOAuthParams();
        return data.session;
    } catch (error) {
        logger.warn('Streamify session restore failed', {
            action: 'streamify_session_restore_failed',
            error: error instanceof Error ? error.message : String(error),
        });

        return null;
    }
};
