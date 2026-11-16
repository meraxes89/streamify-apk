/**
 * use-streamify-auth-gate.ts — the thin P1 auth gate around the app outlet.
 *
 * Semantics (only active when the deployment carries a Supabase configuration
 * and has not explicitly opted out via STREAMIFY_AUTH=false — stock and
 * Electron deployments are never touched):
 *
 *   current server is STREAMIFY   → session live: pass; dead: redirect
 *   current server is other type  → pass (never interfere with stock users)
 *   no current server, live session → ensure the synthetic entry exists and
 *                                     is selected (covers the OAuth redirect
 *                                     boot), then pass
 *   no current server, no session → redirect to /streamify-auth — the auth
 *                                     page's "use your own server" link walks
 *                                     stock users into the untouched picker
 *                                     and login flow in one click (the gate
 *                                     itself never mounts on those routes)
 *
 * The session check is the SDK's storage restore (fast, and it consumes any
 * OAuth redirect code) — network validation stays where upstream put it, in
 * useServerAuthenticated via the controller's getUserInfo. A dead selected
 * session is bounced there (controller signs out) or by the auth route; the
 * gate's REDIRECT only ever sends users to /streamify-auth.
 */
import { useEffect, useState } from 'react';

import {
    getStreamifySession,
    isStreamifyAuthEnabled,
    upsertStreamifyServerEntry,
} from '/@/renderer/api/streamify/streamify-supabase';
import { useAuthStore } from '/@/renderer/store';
import { logger } from '/@/renderer/utils/logger';
import { ServerType } from '/@/shared/types/types';

export enum StreamifyGateState {
    /** Session check in progress — hold rendering briefly. */
    CHECKING = 'checking',
    /** Stock outlet rendering (also the answer for every disabled deployment). */
    PASS = 'pass',
    /** Redirect to /streamify-auth. */
    REDIRECT = 'redirect',
}

export const useStreamifyAuthGate = (): StreamifyGateState => {
    const [state, setState] = useState<StreamifyGateState>(() =>
        isStreamifyAuthEnabled() ? StreamifyGateState.CHECKING : StreamifyGateState.PASS,
    );

    const currentServerType = useAuthStore((s) => s.currentServer?.type ?? null);

    useEffect(() => {
        if (!isStreamifyAuthEnabled()) {
            setState(StreamifyGateState.PASS);
            return;
        }

        let cancelled = false;

        const evaluate = async () => {
            setState(StreamifyGateState.CHECKING);

            const session = await getStreamifySession();

            if (cancelled) return;

            if (currentServerType === ServerType.STREAMIFY) {
                setState(session ? StreamifyGateState.PASS : StreamifyGateState.REDIRECT);
                return;
            }

            if (currentServerType) {
                // A stock server is deliberately selected — never interfere.
                setState(StreamifyGateState.PASS);
                return;
            }

            if (session) {
                // Live session without a selected streamify entry: the OAuth
                // redirect boot or a restored install. Ensure the synthetic
                // entry exists and is selected; the next pass resolves it.
                upsertStreamifyServerEntry(session);
                return;
            }

            // Unauthenticated on a Streamify deployment: the auth page is
            // the entry point (its "use your own server" link walks stock
            // users into the untouched picker/login flow in one click).
            setState(StreamifyGateState.REDIRECT);
        };

        evaluate().catch((error) => {
            logger.error('Streamify auth gate failed — falling back to stock behavior', {
                action: 'streamify_gate_failed',
                error: error instanceof Error ? error.message : String(error),
            });

            if (!cancelled) setState(StreamifyGateState.PASS);
        });

        return () => {
            cancelled = true;
        };
    }, [currentServerType]);

    return state;
};
