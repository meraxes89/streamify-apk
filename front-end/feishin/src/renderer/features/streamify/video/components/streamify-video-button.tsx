/**
 * features/streamify/video/components/streamify-video-button.tsx — the
 * player-bar W2-A seam (the P4B StreamifyJamButton precedent).
 *
 * One upstream hook point (right-controls.tsx renders
 * `<StreamifyVideoButton />`, two lines, logged in PATCHES.md) and
 * everything else lives in features/streamify/video/:
 *
 *   - mounts the toggle controller (useStreamifyVideoMode),
 *   - renders the toggle (hidden when the current session is not Streamify
 *     or no track plays; disabled + tooltip when the counterpart/stream
 *     resolution failed — audio never interrupts),
 *   - mounts the stable video overlay (dock + fullscreen surfaces; null
 *     unless video mode is live).
 *
 * The icon pair rides react-icons directly (LuVideo/LuMusic2 — the
 * window-controls/sidebar-icon precedent) so the upstream AppIcon map
 * stays untouched.
 */

import { useTranslation } from 'react-i18next';
import { LuMusic2, LuVideo } from 'react-icons/lu';

import { StreamifyVideoOverlay } from '/@/renderer/features/streamify/video/components/streamify-video-overlay';
import { useStreamifyVideoMode } from '/@/renderer/features/streamify/video/hooks/use-video-mode';
import { useStreamifyVideoStore } from '/@/renderer/features/streamify/video/streamify-video.store';
import { useAuthStore, usePlayerSong } from '/@/renderer/store';
import { Tooltip } from '/@/shared/components/tooltip/tooltip';
import { ServerType } from '/@/shared/types/types';

export const StreamifyVideoButton = () => {
    const { t } = useTranslation();
    const { toggle } = useStreamifyVideoMode();

    const isStreamify = useAuthStore((state) => state.currentServer?.type) === ServerType.STREAMIFY;
    const currentSong = usePlayerSong();
    const mode = useStreamifyVideoStore((state) => state.mode);
    const switching = useStreamifyVideoStore((state) => state.switching);
    const unavailable = useStreamifyVideoStore((state) => state.unavailable);

    if (!isStreamify || !currentSong) {
        return null;
    }

    const inVideoMode = mode === 'video';
    const disabled = !inVideoMode && unavailable;
    const tooltip = unavailable
        ? t('streamify.video.noVideoAvailable')
        : inVideoMode
          ? t('streamify.video.switchToAudio')
          : t('streamify.video.switchToVideo');

    return (
        <>
            <Tooltip label={tooltip} openDelay={0}>
                <button
                    aria-label={tooltip}
                    disabled={disabled}
                    onClick={(event) => {
                        event.stopPropagation();
                        toggle();
                    }}
                    style={{
                        alignItems: 'center',
                        background: 'transparent',
                        border: 'none',
                        color: inVideoMode ? 'var(--theme-colors-primary)' : 'inherit',
                        cursor: disabled ? 'not-allowed' : 'pointer',
                        display: 'inline-flex',
                        height: '32px',
                        justifyContent: 'center',
                        opacity: disabled ? 0.45 : switching ? 0.7 : 1,
                        width: '32px',
                    }}
                    type="button"
                >
                    {inVideoMode ? <LuMusic2 size={18} /> : <LuVideo size={18} />}
                </button>
            </Tooltip>
            <StreamifyVideoOverlay />
        </>
    );
};
