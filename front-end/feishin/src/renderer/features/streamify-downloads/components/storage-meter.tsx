/**
 * features/streamify-downloads/components/storage-meter.tsx — P4-A: the
 * OPFS usage meter (quota bar + byte counters).
 */

import { useTranslation } from 'react-i18next';

import { useStreamifyDownloadsStoreBase } from '/@/renderer/store/streamify-downloads.store';
import { Group } from '/@/shared/components/group/group';
import { Progress } from '/@/shared/components/progress/progress';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';

export function formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GiB`;
}

export const StorageMeter = () => {
    const { t } = useTranslation();
    const storage = useStreamifyDownloadsStoreBase((s) => s.storage);

    if (storage === null || storage.quota <= 0) {
        return (
            <Text c="dimmed" size="sm">
                {t('streamifyDownloads.storageUnavailable')}
            </Text>
        );
    }
    const pct = Math.min(100, Math.round((storage.usage / storage.quota) * 100));
    return (
        <Stack gap="xs">
            <Group justify="space-between">
                <Text size="sm">
                    {t('streamifyDownloads.storageUsage', {
                        total: formatBytes(storage.quota),
                        used: formatBytes(storage.usage),
                    })}
                </Text>
                <Text c={pct > 90 ? 'var(--theme-colors-error)' : 'dimmed'} size="sm">
                    {pct}%
                </Text>
            </Group>
            <Progress
                color={pct > 90 ? 'var(--theme-colors-error)' : 'var(--theme-colors-primary)'}
                size="sm"
                value={pct}
                w="100%"
            />
        </Stack>
    );
};
