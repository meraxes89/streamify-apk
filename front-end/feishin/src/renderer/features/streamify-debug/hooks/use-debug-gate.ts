/**
 * features/streamify-debug/hooks/use-debug-gate.ts — P4-A: the reactive
 * /debug gate. Binds the pure ruling (debug-gate-ruling.ts) to the live
 * session + URL; the route redirects to / when denied.
 */

import { useMemo } from 'react';
import { useSearchParams } from 'react-router';

import { debugGateAllowed, type DebugGateInputs } from './debug-gate-ruling';

import { resolveStreamifySession } from '/@/renderer/api/streamify/session';
import { useAuthStore } from '/@/renderer/store';
import { getStreamifyDebugState } from '/@/renderer/store/streamify-debug.store';

export type { DebugGateInputs } from './debug-gate-ruling';
export { debugGateAllowed } from './debug-gate-ruling';

export function useDebugGate(): {
    allowed: boolean;
    via: 'dev' | 'developer-mode' | 'flag' | null;
} {
    const [searchParams] = useSearchParams();
    const server = useAuthStore((state) => state.currentServer);

    return useMemo(() => {
        const session =
            server?.credential !== undefined ? resolveStreamifySession(server.credential) : null;
        const inputs: DebugGateInputs = {
            dev: import.meta.env.DEV,
            developerMode: getStreamifyDebugState().developerMode,
            isAdmin: session === null ? false : session.isAdmin,
            urlFlag: searchParams.get('debug') === '1',
        };
        if (!debugGateAllowed(inputs)) return { allowed: false, via: null };
        if (inputs.dev) return { allowed: true, via: 'dev' };
        if (inputs.developerMode) return { allowed: true, via: 'developer-mode' };
        return { allowed: true, via: 'flag' };
    }, [searchParams, server]);
}
