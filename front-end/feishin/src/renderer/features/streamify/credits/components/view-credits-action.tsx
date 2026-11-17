/**
 * features/streamify/credits/components/view-credits-action.tsx — W2-B:
 * the "View Credits / Liner Notes" context-menu action.
 *
 * Lives in the streamify/credits feature tree (the fork's new-code-only
 * policy); the upstream song context menu imports it with a one-line diff
 * (PATCHES.md A19 extension). Self-hides on non-Streamify servers — the
 * StreamifyDownloadAction pattern, verbatim.
 */

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { useStreamifyCreditsStoreBase } from '/@/renderer/features/streamify/credits/credits-store';
import { videoIdOfSongId } from '/@/renderer/features/streamify/credits/song-video-id';
import { useCurrentServer } from '/@/renderer/store';
import { ContextMenu } from '/@/shared/components/context-menu/context-menu';
import { toast } from '/@/shared/components/toast/toast';
import { Song } from '/@/shared/types/domain-types';
import { ServerType } from '/@/shared/types/types';

export interface ViewCreditsActionProps {
    /** The selection (credits are per-track: the first resolvable song opens). */
    songs: Song[];
}

export const ViewCreditsAction = ({ songs }: ViewCreditsActionProps) => {
    const { t } = useTranslation();
    const server = useCurrentServer();

    const enabled = useMemo(() => server?.type === ServerType.STREAMIFY, [server?.type]);
    if (!enabled || songs.length === 0) return null;

    const onSelect = () => {
        for (const song of songs) {
            const videoId = videoIdOfSongId(song.id);
            if (videoId !== null) {
                useStreamifyCreditsStoreBase.getState().open({
                    artist: song.artistName ?? null,
                    title: song.name,
                    videoId,
                });
                return;
            }
        }
        toast.warn({ message: t('streamifyCredits.toastNoStreamTrack') });
    };

    return (
        <ContextMenu.Item leftIcon="list" onSelect={onSelect}>
            {t('streamifyCredits.actionViewCredits')}
        </ContextMenu.Item>
    );
};
