/**
 * features/streamify/smart-sync/components/smart-sync-runtime.tsx — W2-B:
 * the Smart Sync engine's global mount (rendered by
 * StreamifyGlobalRuntime). A pure effect component: starts the clean-play
 * observer for Streamify servers, renders nothing.
 */

import { useSmartSync } from '/@/renderer/features/streamify/smart-sync/use-smart-sync';

export const SmartSyncRuntime = () => {
    useSmartSync();
    return null;
};
