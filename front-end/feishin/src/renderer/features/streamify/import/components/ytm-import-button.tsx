/**
 * features/streamify/import/components/ytm-import-button.tsx — the playlist
 * list header trigger (W1-A).
 *
 * The ONE upstream-file seam this wave carries (PATCHES.md A26): a single
 * <StreamifyYtmImportButton /> line in playlist-list-header.tsx. This
 * component lives in the fork's tree and self-hides on non-Streamify
 * servers — the StreamifyDownloadAction pattern, verbatim.
 */

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { openYtmImportModal } from './ytm-import-modal';

import { useCurrentServer } from '/@/renderer/store';
import { ActionIcon } from '/@/shared/components/action-icon/action-icon';
import { ServerType } from '/@/shared/types/types';

export const StreamifyYtmImportButton = () => {
    const { t } = useTranslation();
    const server = useCurrentServer();

    const enabled = useMemo(() => server?.type === ServerType.STREAMIFY, [server?.type]);
    if (!enabled) return null;

    return (
        <ActionIcon
            aria-label={t('streamifyImport.action')}
            icon="playlist"
            iconProps={{ size: 'lg' }}
            onClick={openYtmImportModal}
            size="sm"
            tooltip={{
                label: t('streamifyImport.buttonTooltip'),
                openDelay: 0,
            }}
            variant="subtle"
        />
    );
};
