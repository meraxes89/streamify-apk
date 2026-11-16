/**
 * features/streamify-eq/utils/attach-source.ts - the attach-time CORS
 * guardrail (P4B).
 *
 * web-player.tsx calls this instead of `source.connect(gains[slot])`
 * (a two-line thin seam, logged in PATCHES.md): while the watchdog has a
 * hard bypass latched, NEW media-element sources route straight to
 * context.destination, so a fresh track never enters a graph we already
 * declared unhealthy. Everything else is byte-identical to upstream.
 */

import type { WebAudio } from '/@/shared/types/types';

import { useStreamifyEqStoreBase } from '/@/renderer/store/streamify-eq.store';

export const connectStreamifySource = (
    webAudio: WebAudio,
    source: AudioNode,
    slot: 0 | 1,
): void => {
    const { bypassed } = useStreamifyEqStoreBase.getState();
    if (bypassed) {
        source.connect(webAudio.context.destination);
        return;
    }
    source.connect(webAudio.gains[slot]);
};
