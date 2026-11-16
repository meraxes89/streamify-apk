/**
 * features/streamify-downloads/components/downloaded-list.tsx — P4-A: the
 * completed offline entries — offline-ready badges, integrity status,
 * rung/codec provenance, sizes, and delete/clear actions.
 */

import { useTranslation } from 'react-i18next';

import { formatBytes } from './storage-meter';

import { getDownloadManager } from '/@/renderer/api/streamify/downloads/instance';
import { useStreamifyDownloadsStoreBase } from '/@/renderer/store/streamify-downloads.store';
import { ActionIcon } from '/@/shared/components/action-icon/action-icon';
import { Badge } from '/@/shared/components/badge/badge';
import { Group } from '/@/shared/components/group/group';
import { Icon } from '/@/shared/components/icon/icon';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';

export const DownloadedList = () => {
    const { t } = useTranslation();
    const entries = useStreamifyDownloadsStoreBase((s) => s.entries);
    const entryList = Object.values(entries);

    if (entryList.length === 0) {
        return (
            <Text c="dimmed" size="sm">
                {t('streamifyDownloads.entriesEmpty')}
            </Text>
        );
    }

    const onDelete = async (videoId: string) => {
        await getDownloadManager()?.remove(videoId);
        toast.success({ message: t('streamifyDownloads.toastRemoved') });
    };

    return (
        <Stack gap="md">
            {entryList.map((entry) => {
                const corrupt = entry.integrity === 'corrupt';
                return (
                    <Group justify="space-between" key={entry.videoId} wrap="nowrap">
                        <Group gap="xs" wrap="nowrap">
                            <Text lineClamp={1} size="sm">
                                {entry.title} — {entry.artist}
                            </Text>
                            {corrupt ? (
                                <Badge color="var(--theme-colors-error)" size="sm" variant="light">
                                    {t('streamifyDownloads.badgeCorrupt')}
                                </Badge>
                            ) : (
                                <Badge
                                    color="var(--theme-colors-success)"
                                    size="sm"
                                    variant="light"
                                >
                                    {t('streamifyDownloads.badgeOfflineReady')}
                                </Badge>
                            )}
                        </Group>
                        <Group gap="xs" wrap="nowrap">
                            <Text c="dimmed" size="sm">
                                {formatBytes(entry.ciphertextBytes)}
                            </Text>
                            <Text c="dimmed" size="sm" title={entry.mimeType}>
                                {entry.rung ?? t('streamifyDownloads.rungUnknown')}
                            </Text>
                            <ActionIcon
                                aria-label={t('streamifyDownloads.actionDelete')}
                                onClick={() => onDelete(entry.videoId)}
                                size="sm"
                            >
                                <Icon icon="delete" />
                            </ActionIcon>
                        </Group>
                    </Group>
                );
            })}
        </Stack>
    );
};
