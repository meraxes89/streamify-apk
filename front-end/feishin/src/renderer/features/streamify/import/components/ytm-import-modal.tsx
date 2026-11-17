/**
 * features/streamify/import/components/ytm-import-modal.tsx — the 1-Click
 * importer modal (W1-A).
 *
 * Opened from the playlist list header (the one upstream-file seam this
 * wave carries — PATCHES.md A26). UI components live entirely inside
 * features/streamify/import/ (the nested-namespace rule); every
 * user-facing string is en.json-only (streamifyImport.*).
 */

import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';

import { t } from 'i18next';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useYtmImport } from '../hooks/use-ytm-import';
import { parseYtmInput } from '../url-parser';

import { useCurrentServer } from '/@/renderer/store';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { closeAllModals, openModal } from '/@/shared/components/modal/modal';
import { ModalButton } from '/@/shared/components/modal/model-shared';
import { Progress } from '/@/shared/components/progress/progress';
import { Spinner } from '/@/shared/components/spinner/spinner';
import { Stack } from '/@/shared/components/stack/stack';
import { TextInput } from '/@/shared/components/text-input/text-input';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';
import { ServerType } from '/@/shared/types/types';

export const openYtmImportModal = () => {
    openModal({
        children: <YtmImportModal />,
        size: 'md',
        title: t('streamifyImport.title'),
    });
};

const YtmImportModal = () => {
    const { t } = useTranslation();
    const server = useCurrentServer() as null | ServerListItemWithCredential;
    // W3-A: a deep-link / share-target entry seeds the input (the media
    // session bridge writes the session key before opening this modal).
    const [input, setInput] = useState(() => {
        try {
            const prefill = window.sessionStorage.getItem('streamify.import.prefill');
            if (prefill !== null) window.sessionStorage.removeItem('streamify.import.prefill');
            return prefill ?? '';
        } catch {
            return '';
        }
    });
    const importer = useYtmImport();

    const parsed = useMemo(() => parseYtmInput(input), [input]);
    const isStreamify = server !== null && server.type === ServerType.STREAMIFY;
    const canStart = parsed.ok && !importer.running && isStreamify;

    const handleStart = () => {
        if (!parsed.ok) return;
        importer.start({
            onSettled: (summary) => {
                if (summary === null) {
                    toast.error({
                        message: t('streamifyImport.toastFailed'),
                    });
                    return;
                }
                if ((importer.progress?.phase ?? 'done') === 'cancelled') {
                    toast.info({
                        message: t('streamifyImport.toastCancelled', {
                            count: summary.importedCount,
                        }),
                    });
                    return;
                }
                toast.success({
                    message: t('streamifyImport.toastDone', {
                        count: summary.importedCount,
                    }),
                });
            },
            server,
            serverId: server?.id,
            target: parsed.target,
        });
    };

    const progress = importer.progress;
    const total = progress?.totalExpected ?? null;
    const pct =
        total !== null && total > 0
            ? Math.min(100, Math.round(((progress?.importedCount ?? 0) / total) * 100))
            : null;

    return (
        <Stack gap="md">
            <TextInput
                disabled={importer.running}
                onChange={(e) => setInput(e.target.value)}
                placeholder={t('streamifyImport.inputPlaceholder')}
                value={input}
            />
            {input !== '' && !parsed.ok && (
                <Text $block-size="sm" color="var(--text-secondary)">
                    {t('streamifyImport.invalidInput')}
                </Text>
            )}
            {parsed.ok && (
                <Text $block-size="sm" color="var(--text-secondary)">
                    {t(
                        parsed.target.kind === 'album'
                            ? 'streamifyImport.detectedAlbum'
                            : 'streamifyImport.detectedPlaylist',
                        { id: parsed.target.listId },
                    )}
                </Text>
            )}
            {progress && progress.phase !== 'resolving' && (
                <Stack gap="xs">
                    <Group justify="space-between">
                        <Group gap="xs">
                            {progress.phase === 'importing' && <Spinner size={14} />}
                            <Text $block-size="sm">
                                {progress.phase === 'cancelled'
                                    ? t('streamifyImport.progressCancelled', {
                                          count: progress.importedCount,
                                      })
                                    : t('streamifyImport.progress', {
                                          count: progress.importedCount,
                                          total: total ?? t('streamifyImport.progressUnknownTotal'),
                                      })}
                            </Text>
                        </Group>
                        {pct !== null && <Text $block-size="sm">{pct}%</Text>}
                    </Group>
                    {pct !== null && <Progress value={pct} />}
                </Stack>
            )}
            {progress?.error !== null && progress?.error !== undefined && (
                <Text $block-size="sm" color="var(--danger-color)">
                    {progress.error}
                </Text>
            )}
            <Group gap="xs" justify="flex-end">
                {importer.running ? (
                    <Button color="red" onClick={importer.cancel} variant="subtle">
                        {t('common.cancel')}
                    </Button>
                ) : (
                    <Button onClick={() => closeAllModals()} variant="subtle">
                        {t('common.close')}
                    </Button>
                )}
                <ModalButton disabled={!canStart} onClick={handleStart} variant="filled">
                    {importer.running
                        ? t('streamifyImport.importing')
                        : t('streamifyImport.action')}
                </ModalButton>
            </Group>
        </Stack>
    );
};
