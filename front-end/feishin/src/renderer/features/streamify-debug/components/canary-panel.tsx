/**
 * features/streamify-debug/components/canary-panel.tsx — P5-A: the
 * residential canary console (front-end/canary, W0.1b) mounted inside the
 * gated /debug diagnostics page.
 *
 * The console used to live on its own web-app page (canary.html — archived
 * to legacy/front-end-web). Since the cutover this is its only live mount,
 * and it inherits the /debug gate for free (use-debug-gate: DEV, the
 * persisted developer-mode grant, or ?debug=1 + authenticated admin —
 * standard users are redirected to / and can never reach this panel).
 *
 * Wiring: the console is host-agnostic (env reads), so this panel injects
 * the feishin-side live values:
 *   - supabase — the ACTIVE session's Supabase URL + anon key (the
 *     credential payload the synthetic server entry carries), so "Post
 *     results to Supabase" targets the project this very session is
 *     signed into instead of a build-time constant;
 *   - defaultRelayBase — the resolver's own relay knob
 *     (relayBaseCandidate()), i.e. the relay production playback actually
 *     rides, as the editable input's starting value.
 * Both are optional; without a session the console degrades to its
 * copy-rows-JSON fallback.
 */

import CanaryConsole from '@streamify/canary/ui/CanaryConsole';
import { useMemo } from 'react';

import { relayBaseCandidate } from '/@/renderer/api/streamify/playback/resolver/lib/relayBase';
import { resolveStreamifySession } from '/@/renderer/api/streamify/session';
// Direct module import (not the store barrel): the barrel drags the player
// store's radio/logger chain in, which touches localStorage at module
// scope — fine in a browser, fatal for the network-free node test lane.
import { useAuthStore } from '/@/renderer/store/auth.store';

export const StreamifyCanaryPanel = () => {
    const server = useAuthStore((state) => state.currentServer);

    const supabase = useMemo(() => {
        const session =
            server?.credential !== undefined ? resolveStreamifySession(server.credential) : null;
        if (session !== null && session.supabaseUrl !== '' && session.anonKey !== '') {
            return { anonKey: session.anonKey, url: session.supabaseUrl };
        }
        return undefined;
    }, [server]);

    // The relay the resolver itself rides (deployment knob → ?relay= →
    // build env → dev default). Read once per mount: the input stays
    // editable regardless of where the default came from.
    const defaultRelayBase = relayBaseCandidate();

    return <CanaryConsole defaultRelayBase={defaultRelayBase} supabase={supabase} />;
};
