/**
 * features/streamify-jam/hooks/use-streamify-jam-controller.ts - mounts
 * the Jam session controller singleton (P4B).
 *
 * The controller outlives the drawer: closing the UI must not tear down a
 * live session, so the singleton is keyed by the signed-in user and created
 * lazily on first mount (the player-bar trigger mounts this hook whenever a
 * Streamify session is active). Real dependencies: the shared supabase-js
 * client (streamify-supabase.ts), the player-store bridge, and the stable
 * per-install device id.
 */

import { useEffect } from 'react';

import { JamLiveTransport } from '/@/renderer/api/streamify/jam-live/jam-live-transport';
import { createJamPlayerBridge } from '/@/renderer/api/streamify/jam-live/jam-player-bridge';
import {
    getStreamifyJamDeviceId,
    StreamifyJamSessionController,
} from '/@/renderer/api/streamify/jam-live/jam-session-controller';
import {
    getStreamifySupabase,
    isStreamifyAuthEnabled,
} from '/@/renderer/api/streamify/streamify-supabase';
import { useAuthStore } from '/@/renderer/store';
import { logger } from '/@/renderer/utils/logger';

let controller: null | StreamifyJamSessionController = null;

let controllerUserId: null | string = null;

/** The controller for the signed-in user (null when unconfigured). */
export const getStreamifyJamController = (): null | StreamifyJamSessionController => {
    if (!isStreamifyAuthEnabled()) return null;
    const supabase = getStreamifySupabase();
    if (!supabase) return null;

    const server = useAuthStore.getState().currentServer;
    const userId = server?.type === 'streamify' ? (server.userId ?? null) : null;
    const displayName = server?.username ?? null;

    if (controller && controllerUserId === userId) {
        return controller;
    }
    if (controller) {
        // User switched accounts: drop the stale session cleanly.
        void controller.leave().catch(() => undefined);
        controller = null;
    }

    const deviceId = getStreamifyJamDeviceId();
    controller = new StreamifyJamSessionController({
        bridge: createJamPlayerBridge(),
        deviceId,
        transportFactory: (code, events) =>
            new JamLiveTransport({
                client: supabase,
                code,
                deviceId,
                events,
                presenceMeta: { name: displayName, userId },
            }),
        userId,
    });
    controllerUserId = userId;
    logger.debug('Streamify jam controller mounted', { userId: userId ?? 'anonymous' });
    return controller;
};

/** React mount point: returns the singleton (stable for the user). */
export const useStreamifyJamController = (): null | StreamifyJamSessionController => {
    useEffect(() => {
        // The singleton is created on first read; nothing to clean up here
        // (leaving a session is an explicit user action via the drawer).
    }, []);
    return getStreamifyJamController();
};
