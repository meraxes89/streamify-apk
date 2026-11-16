/**
 * features/streamify-downloads/routes/streamify-downloads-route.tsx —
 * P4-A: the downloads manager view.
 *
 * Boots the download manager on mount (orphan sweep + storage estimate +
 * the wasm health probe into the store), then renders: the OPFS storage
 * meter, the active queue (progress + cancel), the completed offline
 * entries (badges, provenance, delete), and the quota/cipher notes.
 */

import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import { DownloadQueue } from '../components/download-queue';
import { DownloadedList } from '../components/downloaded-list';
import { StorageMeter } from '../components/storage-meter';

import { bootDownloads } from '/@/renderer/api/streamify/downloads/instance';
import { PageHeader } from '/@/renderer/components/page-header/page-header';
import { AnimatedPage } from '/@/renderer/features/shared/components/animated-page';
import { PageErrorBoundary } from '/@/renderer/features/shared/components/page-error-boundary';
import { useStreamifyDownloadsStoreBase } from '/@/renderer/store/streamify-downloads.store';
import { Group } from '/@/shared/components/group/group';
import { Paper } from '/@/shared/components/paper/paper';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

const StreamifyDownloadsRoute = () => {
    const { t } = useTranslation();
    const wasm = useStreamifyDownloadsStoreBase((s) => s.wasm);

    useEffect(() => {
        void bootDownloads();
    }, []);

    return (
        <AnimatedPage>
            <PageHeader>
                <TextTitle order={3}>{t('streamifyDownloads.pageTitle')}</TextTitle>
            </PageHeader>
            <PageErrorBoundary>
                <Stack gap="md" p="md">
                    <Paper p="md" shadow="none" withBorder>
                        <Stack gap="xs">
                            <Text fw={600} size="md">
                                {t('streamifyDownloads.sectionStorage')}
                            </Text>
                            <StorageMeter />
                            {wasm.status === 'error' ? (
                                <Text c="var(--theme-colors-error)" size="sm">
                                    {t('streamifyDownloads.wasmError', {
                                        message: wasm.error ?? '',
                                    })}
                                </Text>
                            ) : (
                                <Text c="dimmed" size="sm">
                                    {t('streamifyDownloads.wasmSelfTest', {
                                        result:
                                            wasm.selfTest === 0
                                                ? t('common.ok')
                                                : t('streamifyDownloads.wasmSelfTestFailed'),
                                    })}
                                </Text>
                            )}
                        </Stack>
                    </Paper>
                    <Paper p="md" shadow="none" withBorder>
                        <Stack gap="xs">
                            <Text fw={600} size="md">
                                {t('streamifyDownloads.sectionQueue')}
                            </Text>
                            <DownloadQueue />
                        </Stack>
                    </Paper>
                    <Paper p="md" shadow="none" withBorder>
                        <Stack gap="xs">
                            <Group justify="space-between">
                                <Text fw={600} size="md">
                                    {t('streamifyDownloads.sectionEntries')}
                                </Text>
                            </Group>
                            <DownloadedList />
                        </Stack>
                    </Paper>
                </Stack>
            </PageErrorBoundary>
        </AnimatedPage>
    );
};

export default StreamifyDownloadsRoute;
