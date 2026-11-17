/**
 * features/streamify/media-session/components/streamify-media-session-bridge.tsx
 * — the W3-A mount point: ONE element, zero UI (the A14 seam shape).
 *
 * Renders null; mounts useStreamifyMediaSession() (the OS lock-screen
 * position/chapter bridge + the deep-link entry). Rendered from
 * right-controls.tsx beside the Connect button — the player bar's mount
 * IS the post-auth boot timing the deep-link contract needs.
 */

import { useStreamifyMediaSession } from '/@/renderer/features/streamify/media-session/use-streamify-media-session';

export const StreamifyMediaSessionBridge = () => {
    useStreamifyMediaSession();
    return null;
};
