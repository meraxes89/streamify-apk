/**
 * features/streamify/credits/components/song-credits-modal.tsx — W2-B: the
 * Liner Notes drawer (the credits surface).
 *
 * Grouped contributor roles (Performed by / Written by / Produced by /
 * Source… — whatever sections YTM's structured description carries), the
 * copyright/label line inside those groups, and the HONEST empty state
 * when YTM has no credits shelf for the track. Never fabricated: every
 * role group rendered here came from a provider payload that passed the
 * bridge's shape validation.
 */

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { type MetaCredits } from '/@/renderer/api/streamify/meta-bridge';
import { useStreamifyCreditsStoreBase } from '/@/renderer/features/streamify/credits/credits-store';
import { useSongCredits } from '/@/renderer/features/streamify/credits/use-song-credits';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Modal } from '/@/shared/components/modal/modal';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

type RoleGroup = MetaCredits['roleGroups'][number];

const RoleGroupBlock = ({ group }: { group: RoleGroup }) => (
    <Stack gap="xs" key={group.title || 'roles'}>
        <TextTitle order={6}>{group.title}</TextTitle>
        <Stack gap={0}>
            {group.credits.map((credit) => (
                <Group justify="apart" key={`${group.title}-${credit.name}`}>
                    <Text size="sm" weight={600}>
                        {credit.name}
                    </Text>
                    {credit.subtitle !== null && (
                        <Text isMuted size="xs">
                            {credit.subtitle}
                        </Text>
                    )}
                </Group>
            ))}
        </Stack>
    </Stack>
);

export const SongCreditsModal = () => {
    const { t } = useTranslation();
    const request = useStreamifyCreditsStoreBase((state) => state.request);
    const close = useStreamifyCreditsStoreBase((state) => state.close);
    const { refetch, state } = useSongCredits(request?.videoId ?? null);

    const opened = request !== null;

    const subtitle = useMemo(
        () => (request !== null && request.artist !== null ? request.artist : ''),
        [request],
    );

    return (
        <Modal
            handlers={{
                close: () => close(),
                open: () => undefined,
                toggle: () => close(),
            }}
            opened={opened}
            size="lg"
            title={t('streamifyCredits.modalTitle')}
        >
            {request !== null && (
                <Stack gap="lg">
                    <Stack gap={0}>
                        <Text size="lg" weight={700}>
                            {request.title}
                        </Text>
                        {subtitle !== '' && (
                            <Text isMuted size="sm">
                                {subtitle}
                            </Text>
                        )}
                    </Stack>

                    {state.kind === 'loading' && (
                        <Text isMuted size="sm">
                            {t('streamifyCredits.loading')}
                        </Text>
                    )}

                    {state.kind === 'error' && (
                        <Stack gap="sm">
                            <Text size="sm">{t('streamifyCredits.errorTitle')}</Text>
                            <Button onClick={refetch} size="xs" variant="subtle">
                                {t('streamifyCredits.retry')}
                            </Button>
                        </Stack>
                    )}

                    {state.kind === 'empty' && (
                        <Stack gap={4}>
                            <Text size="sm">{t('streamifyCredits.emptyTitle')}</Text>
                            <Text isMuted size="xs">
                                {t('streamifyCredits.emptyDescription')}
                            </Text>
                        </Stack>
                    )}

                    {state.kind === 'loaded' && (
                        <Stack gap="md">
                            {state.credits.roleGroups.map((group) => (
                                <RoleGroupBlock group={group} key={group.title || 'roles'} />
                            ))}
                        </Stack>
                    )}
                </Stack>
            )}
        </Modal>
    );
};
