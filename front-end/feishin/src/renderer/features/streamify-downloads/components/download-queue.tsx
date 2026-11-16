/**
 * features/streamify-downloads/components/download-queue.tsx — P4-A: the
 * active queue (live jobs with progress + cancel) and the per-track
 * sticky error notes.
 */

import { useTranslation } from 'react-i18next';

import { formatBytes } from './storage-meter';

import { getDownloadManager } from '/@/renderer/api/streamify/downloads/instance';
import { useStreamifyDownloadsStoreBase } from '/@/renderer/store/streamify-downloads.store';
import { ActionIcon } from '/@/shared/components/action-icon/action-icon';
import { Badge } from '/@/shared/components/badge/badge';
import { Group } from '/@/shared/components/group/group';
import { Icon } from '/@/shared/components/icon/icon';
import { Progress } from '/@/shared/components/progress/progress';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';

export const DownloadQueue = () => {
    const { t } = useTranslation();
    const jobs = useStreamifyDownloadsStoreBase((s) => s.jobs);
    const lastError = useStreamifyDownloadsStoreBase((s) => s.lastError);

    const jobList = Object.values(jobs);
    const errorEntries = Object.entries(lastError);

    if (jobList.length === 0 && errorEntries.length === 0) {
        return (
            <Text c="dimmed" size="sm">
                {t('streamifyDownloads.queueEmpty')}
            </Text>
        );
    }

    return (
        <Stack gap="md">
            {jobList.map((job) => {
                const pct =
                    job.totalBytes !== null && job.totalBytes > 0
                        ? Math.min(100, Math.round((job.receivedBytes / job.totalBytes) * 100))
                        : null;
                return (
                    <Stack gap={4} key={job.videoId}>
                        <Group justify="space-between">
                            <Group gap="xs">
                                <Text size="sm">{job.title}</Text>
                                <Badge size="sm" variant="outline">
                                    {t('streamifyDownloads.badgeDownloading')}
                                </Badge>
                            </Group>
                            <Group gap="xs">
                                {pct !== null ? (
                                    <Text c="dimmed" size="sm">
                                        {formatBytes(job.receivedBytes)} /{' '}
                                        {formatBytes(job.totalBytes ?? 0)}
                                    </Text>
                                ) : (
                                    <Text c="dimmed" size="sm">
                                        {formatBytes(job.receivedBytes)}
                                    </Text>
                                )}
                                <ActionIcon
                                    aria-label={t('streamifyDownloads.actionCancel')}
                                    onClick={() => getDownloadManager()?.cancel(job.videoId)}
                                    size="sm"
                                >
                                    <Icon icon="xCircle" />
                                </ActionIcon>
                            </Group>
                        </Group>
                        {pct !== null ? (
                            <Progress size="sm" value={pct} w="100%" />
                        ) : (
                            <Progress
                                color="var(--theme-colors-warning)"
                                size="sm"
                                value={100}
                                w="100%"
                            />
                        )}
                    </Stack>
                );
            })}
            {errorEntries.map(([videoId, message]) => (
                <Group gap="xs" key={videoId}>
                    <Icon color="error" icon="warn" size="sm" />
                    <Text c="dimmed" size="sm">
                        {message}
                    </Text>
                </Group>
            ))}
        </Stack>
    );
};
