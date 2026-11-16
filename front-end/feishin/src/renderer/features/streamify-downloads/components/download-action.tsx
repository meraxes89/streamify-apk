/**
 * features/streamify-downloads/components/download-action.tsx — P4-A:
 * the context-menu action that queues OPFS downloads.
 *
 * Lives in the streamify-downloads feature tree (the fork's new-code-only
 * policy); the upstream menu files import it with a one-line diff each
 * (PATCHES.md). Self-hides on non-Streamify servers — the SetRatingAction
 * pattern, verbatim.
 */

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { queueSongDownload } from '/@/renderer/api/streamify/downloads/instance';
import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';
import { useCurrentServer } from '/@/renderer/store';
import { ContextMenu } from '/@/shared/components/context-menu/context-menu';
import { toast } from '/@/shared/components/toast/toast';
import { LibraryItem } from '/@/shared/types/domain-types';
import { ServerType } from '/@/shared/types/types';

export interface StreamifyDownloadActionProps {
    ids: string[];
    itemType: LibraryItem.ALBUM | LibraryItem.PLAYLIST | LibraryItem.SONG;
}

/** Expand the selection to song ids via the live snapshot. */
function expandToSongIds(itemType: StreamifyDownloadActionProps['itemType'], ids: string[]) {
    if (itemType === LibraryItem.SONG) return ids;
    const snapshot = currentStreamifyLibrary();
    if (snapshot === null) return [];
    const out: string[] = [];
    for (const id of ids) {
        if (itemType === LibraryItem.ALBUM) {
            const album = snapshot.albumById.get(id);
            if (album !== undefined) out.push(...album.songIds);
        } else {
            const playlist = snapshot.playlistById.get(id);
            if (playlist !== undefined) out.push(...playlist.songIds);
        }
    }
    return out;
}

export const StreamifyDownloadAction = ({ ids, itemType }: StreamifyDownloadActionProps) => {
    const { t } = useTranslation();
    const server = useCurrentServer();

    const enabled = useMemo(() => server?.type === ServerType.STREAMIFY, [server?.type]);
    if (!enabled || ids.length === 0) return null;

    const onSelect = () => {
        const songIds = expandToSongIds(itemType, ids);
        if (songIds.length === 0) {
            toast.warn({ message: t('streamifyDownloads.toastNothingToQueue') });
            return;
        }
        for (const songId of songIds) {
            void queueSongDownload(songId);
        }
        toast.success({
            message: t('streamifyDownloads.toastQueued', { count: songIds.length }),
        });
    };

    return (
        <ContextMenu.Item leftIcon="download" onSelect={onSelect}>
            {itemType === LibraryItem.SONG
                ? t('streamifyDownloads.actionDownload')
                : t('streamifyDownloads.actionDownloadCollection')}
        </ContextMenu.Item>
    );
};
