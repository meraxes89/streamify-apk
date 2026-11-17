/**
 * features/streamify/global-runtime.tsx — W2-B: the fork's global feature
 * mounts, rendered ONCE from app.tsx's AppShell (the PATCHES.md A27 seam).
 *
 * One import + one element buys the app every global Streamify surface:
 *   - SongCreditsModalHost — the Liner Notes drawer (opened from the song
 *     context menu anywhere in the app);
 *   - SmartSyncRuntime — the clean-play observer that drives the 500 MB
 *     predictive pre-fetcher.
 * Both self-gate on the Streamify server type (null output on stock
 * deployments) — this component itself renders nothing.
 */

import { SongCreditsModal } from '/@/renderer/features/streamify/credits/components/song-credits-modal';
import { SmartSyncRuntime } from '/@/renderer/features/streamify/smart-sync/components/smart-sync-runtime';

export const StreamifyGlobalRuntime = () => {
    return (
        <>
            <SongCreditsModal />
            <SmartSyncRuntime />
        </>
    );
};
