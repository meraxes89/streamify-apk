/**
 * features/streamify-jam/components/jam-drawer.tsx - the collaborative
 * listening session drawer (P4B).
 *
 * Two surfaces in one drawer (tabs): the Jam room (join/create, live
 * participants, the collaborative queue, host/follower sync state) and the
 * Streamify equalizer. The drawer is fully self-contained: it renders from
 * the jam store and drives the session controller - no upstream layout
 * file carries it (the player-bar trigger in right-controls.tsx is the
 * only two-line hook, logged in PATCHES.md).
 */

import type { StreamifyJamSessionController } from '/@/renderer/api/streamify/jam-live/jam-session-controller';

import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { buildJamInviteLink } from '/@/renderer/api/streamify/jam-live/jam-live-protocol';
import { StreamifyEqPanel } from '/@/renderer/features/streamify-eq/components/streamify-eq-panel';
import { JamQueueList } from '/@/renderer/features/streamify-jam/components/jam-queue-list';
import { useStreamifyJamStore } from '/@/renderer/store/streamify-jam.store';
import { ActionIcon } from '/@/shared/components/action-icon/action-icon';
import { Badge } from '/@/shared/components/badge/badge';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Spinner } from '/@/shared/components/spinner/spinner';
import { Stack } from '/@/shared/components/stack/stack';
import { Tabs } from '/@/shared/components/tabs/tabs';
import { TextInput } from '/@/shared/components/text-input/text-input';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';

const formatPosition = (positionMs: number): string => {
    const totalSeconds = Math.max(0, Math.floor(positionMs / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
};

export const JamDrawer = ({
    controller,
    onTabChange,
    tab,
}: {
    controller: null | StreamifyJamSessionController;
    onTabChange: (tab: 'eq' | 'jam') => void;
    tab: 'eq' | 'jam';
}) => {
    const { t } = useTranslation();
    const [joinCode, setJoinCode] = useState('');
    const [busy, setBusy] = useState(false);

    const status = useStreamifyJamStore((state) => state.status);
    const session = useStreamifyJamStore((state) => state.session);
    const isHost = useStreamifyJamStore((state) => state.sessionIsHost);
    const error = useStreamifyJamStore((state) => state.sessionError);
    const participants = useStreamifyJamStore((state) => state.participants);
    const hostPlayback = useStreamifyJamStore((state) => state.hostPlayback);
    const hostDemoted = useStreamifyJamStore((state) => state.hostDemoted);

    const inviteLink = session
        ? buildJamInviteLink(window.location.origin + window.location.pathname, session.roomCode)
        : null;

    const handleJoin = async () => {
        if (!controller) return;
        setBusy(true);
        const result = await controller.joinRoom(joinCode);
        setBusy(false);
        if (result) {
            toast.error({ message: t(`streamify.jam.error.${result.code}`) });
        } else {
            setJoinCode('');
        }
    };

    const handleCreate = async () => {
        if (!controller) return;
        setBusy(true);
        const result = await controller.createRoom();
        setBusy(false);
        if (result) {
            toast.error({ message: t(`streamify.jam.error.${result.code}`) });
        }
    };

    const handleLeave = async () => {
        await controller?.leave();
        toast.success({ message: t('streamify.jam.left') });
    };

    const copy = (value: string, label: string) => {
        void navigator.clipboard?.writeText(value).then(() => {
            toast.success({ message: label });
        });
    };

    const idleView = (
        <Stack gap="md">
            <TextTitle order={4}>{t('streamify.jam.title')}</TextTitle>
            <Text isMuted size="sm">
                {t('streamify.jam.intro')}
            </Text>
            <Stack gap="xs">
                <TextTitle order={5}>{t('streamify.jam.joinSection')}</TextTitle>
                <Group gap="xs">
                    <TextInput
                        maxLength={6}
                        onChange={(event) => setJoinCode(event.currentTarget.value.toUpperCase())}
                        placeholder={t('streamify.jam.codePlaceholder')}
                        value={joinCode}
                        w={140}
                    />
                    <Button
                        disabled={joinCode.trim().length !== 6 || busy}
                        loading={busy}
                        onClick={handleJoin}
                        size="sm"
                        variant="filled"
                    >
                        {t('streamify.jam.join')}
                    </Button>
                </Group>
            </Stack>
            <Divider />
            <Stack gap="xs">
                <TextTitle order={5}>{t('streamify.jam.createSection')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamify.jam.createIntro')}
                </Text>
                <Group gap="xs">
                    <Button loading={busy} onClick={handleCreate} size="sm" variant="outline">
                        {t('streamify.jam.create')}
                    </Button>
                </Group>
            </Stack>
        </Stack>
    );

    const liveView = (
        <Stack gap="md">
            <Group justify="apart" wrap="nowrap">
                <Group gap="xs" wrap="nowrap">
                    <TextTitle order={4}>{session?.roomCode}</TextTitle>
                    <Badge color={isHost ? 'primary' : 'gray'} variant="filled">
                        {isHost ? t('streamify.jam.hostBadge') : t('streamify.jam.followerBadge')}
                    </Badge>
                    {status === 'ended' ? (
                        <Badge color="red" variant="outline">
                            {t('streamify.jam.endedBadge')}
                        </Badge>
                    ) : null}
                </Group>
                <Group gap={4} wrap="nowrap">
                    {inviteLink ? (
                        <ActionIcon
                            icon="share"
                            onClick={() => copy(inviteLink, t('streamify.jam.linkCopied'))}
                            size="sm"
                            tooltip={{ label: t('streamify.jam.copyLink'), openDelay: 0 }}
                            variant="subtle"
                        />
                    ) : null}
                    <ActionIcon
                        icon="signOut"
                        onClick={handleLeave}
                        size="sm"
                        tooltip={{ label: t('streamify.jam.leave'), openDelay: 0 }}
                        variant="subtle"
                    />
                </Group>
            </Group>

            {isHost && hostDemoted ? (
                <Text c="var(--theme-colors-state-warning)" size="sm">
                    {t('streamify.jam.demoted')}
                </Text>
            ) : null}

            {!isHost && hostPlayback ? (
                <Stack gap={4}>
                    <TextTitle order={5}>{t('streamify.jam.hostPlayback')}</TextTitle>
                    <Group gap="xs">
                        <Badge color={hostPlayback.isPlaying ? 'green' : 'gray'} variant="outline">
                            {hostPlayback.isPlaying
                                ? t('streamify.jam.playing')
                                : t('streamify.jam.paused')}
                        </Badge>
                        <Text isMuted size="sm" span>
                            {formatPosition(hostPlayback.positionMs)}
                            {hostPlayback.trackName ? ` · ${hostPlayback.trackName}` : ''}
                        </Text>
                    </Group>
                    <Group gap="xs">
                        <Button
                            onClick={() => controller?.requestControl('skip')}
                            size="compact-sm"
                            variant="light"
                        >
                            {t('streamify.jam.suggestSkip')}
                        </Button>
                    </Group>
                </Stack>
            ) : null}

            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.jam.participants')}</TextTitle>
                <Stack gap={2}>
                    {participants.map((participant) => (
                        <Group justify="apart" key={participant.deviceId} wrap="nowrap">
                            <Text lineClamp={1} size="sm" span>
                                {participant.isSelf
                                    ? t('streamify.jam.you')
                                    : (participant.name ?? `${participant.deviceId.slice(0, 8)}`)}
                                {participant.isHost ? ` · ${t('streamify.jam.hostBadge')}` : ''}
                            </Text>
                            <Text isMuted size="xs" span>
                                {new Date(participant.lastSeenMs).toLocaleTimeString()}
                            </Text>
                        </Group>
                    ))}
                </Stack>
            </Stack>

            <Divider />
            <JamQueueList controller={controller} />
        </Stack>
    );

    return (
        <Tabs
            keepMounted={false}
            onChange={(value) => onTabChange((value as 'eq' | 'jam') ?? 'jam')}
            value={tab}
        >
            <Tabs.List>
                <Tabs.Tab value="jam">{t('streamify.jam.tab')}</Tabs.Tab>
                <Tabs.Tab value="eq">{t('streamify.eq.tab')}</Tabs.Tab>
            </Tabs.List>

            <Tabs.Panel px="sm" py="md" value="jam">
                {status === 'connecting' ? (
                    <Stack align="center" py="xl">
                        <Spinner container={false} />
                    </Stack>
                ) : status === 'idle' || status === 'error' ? (
                    <>
                        {status === 'error' && error ? (
                            <Text c="var(--theme-colors-state-error)" pb="sm" size="sm">
                                {t('streamify.jam.error.prefix', { error })}
                            </Text>
                        ) : null}
                        {idleView}
                    </>
                ) : (
                    liveView
                )}
            </Tabs.Panel>

            <Tabs.Panel px="sm" py="md" value="eq">
                <StreamifyEqPanel />
            </Tabs.Panel>
        </Tabs>
    );
};
