/**
 * features/streamify-jam/components/jam-queue-list.tsx - the live
 * collaborative queue (P4B): ordered tracks with upvotes, per-item
 * remove/move, and the add-current-track affordance.
 *
 * Every mutation goes through the session controller (local-first CRDT +
 * journal); the list itself renders the store's ordered view with a scroll
 * region (max-h + custom scrollbar per the fork's list conventions).
 */

import type { StreamifyJamSessionController } from '/@/renderer/api/streamify/jam-live/jam-session-controller';

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { usePlayerSong } from '/@/renderer/store/player.store';
import { useStreamifyJamStore } from '/@/renderer/store/streamify-jam.store';
import { formatDurationStringShort } from '/@/renderer/utils/format';
import { ActionIcon } from '/@/shared/components/action-icon/action-icon';
import { Badge } from '/@/shared/components/badge/badge';
import { Group } from '/@/shared/components/group/group';
import { ScrollArea } from '/@/shared/components/scroll-area/scroll-area';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';
import { ServerType } from '/@/shared/types/types';

const formatArtists = (artists: Array<{ name: string }> | undefined): string =>
    (artists ?? []).map((artist) => artist.name).join(', ');

export const JamQueueList = ({
    controller,
}: {
    controller: null | StreamifyJamSessionController;
}) => {
    const { t } = useTranslation();
    const queue = useStreamifyJamStore((state) => state.queue);
    const status = useStreamifyJamStore((state) => state.status);
    const currentSong = usePlayerSong();

    const live = status === 'live' && controller !== null;

    const canAddCurrent = useMemo(
        () => currentSong?._serverType === ServerType.STREAMIFY,
        [currentSong],
    );

    const move = (addOpId: string, delta: number) => {
        const index = queue.findIndex((item) => item.addOpId === addOpId);
        if (index === -1) return;
        controller?.moveTrack(addOpId, index + delta);
    };

    return (
        <Stack gap="xs">
            <Group justify="apart">
                <Text isMuted size="sm">
                    {t('streamify.jam.queueCount', { count: queue.length })}
                </Text>
                <Group gap={4} wrap="nowrap">
                    <Text isMuted size="xs" span>
                        {t('streamify.jam.votingHint')}
                    </Text>
                    <ActionIcon
                        disabled={!canAddCurrent || !controller}
                        icon="plus"
                        onClick={() => {
                            if (currentSong) {
                                const { _contextPlaylistId, _uniqueId, ...payload } = currentSong;
                                void _contextPlaylistId;
                                void _uniqueId;
                                controller?.addTrack(payload);
                            }
                        }}
                        size="sm"
                        tooltip={{ label: t('streamify.jam.addCurrent'), openDelay: 0 }}
                        variant="subtle"
                    />
                </Group>
            </Group>
            <ScrollArea style={{ height: 280 }}>
                <Stack gap={0}>
                    {queue.length === 0 ? (
                        <Text isMuted p="1rem" size="sm">
                            {t('streamify.jam.queueEmpty')}
                        </Text>
                    ) : (
                        queue.map((item, index) => (
                            <Group
                                justify="apart"
                                key={item.addOpId}
                                p="0.4rem 0.5rem"
                                style={{
                                    borderTop:
                                        index === 0
                                            ? undefined
                                            : '1px solid var(--theme-colors-surface)',
                                }}
                                wrap="nowrap"
                            >
                                <Stack gap={0} style={{ overflow: 'hidden' }}>
                                    <Text lineClamp={1} size="sm">
                                        {item.track?.name ?? t('streamify.jam.trackPending')}
                                    </Text>
                                    <Text isMuted lineClamp={1} size="xs">
                                        {item.track
                                            ? `${formatArtists(item.track.artists)}${
                                                  item.track.duration
                                                      ? ` · ${formatDurationStringShort(
                                                            item.track.duration,
                                                        )}`
                                                      : ''
                                              }`
                                            : `cad:${item.cadId.slice(0, 10)}`}
                                    </Text>
                                </Stack>
                                <Group gap={4} wrap="nowrap">
                                    <Badge size="sm" variant="outline">
                                        {item.upvotes > 0
                                            ? t('streamify.jam.upvotes', {
                                                  count: item.upvotes,
                                              })
                                            : t('streamify.jam.noUpvotes')}
                                    </Badge>
                                    <ActionIcon
                                        disabled={!live}
                                        icon="arrowUp"
                                        onClick={() => controller?.upvoteTrack(item.addOpId)}
                                        size="compact-md"
                                        tooltip={{
                                            label: item.voted
                                                ? t('streamify.jam.upvoted')
                                                : t('streamify.jam.upvote'),
                                            openDelay: 0,
                                        }}
                                        variant={item.voted ? 'filled' : 'subtle'}
                                    />
                                    <ActionIcon
                                        disabled={index === 0}
                                        icon="arrowUpS"
                                        onClick={() => move(item.addOpId, -1)}
                                        size="compact-md"
                                        tooltip={{ label: t('streamify.jam.moveUp'), openDelay: 0 }}
                                        variant="subtle"
                                    />
                                    <ActionIcon
                                        disabled={index === queue.length - 1}
                                        icon="arrowDownS"
                                        onClick={() => move(item.addOpId, 1)}
                                        size="compact-md"
                                        tooltip={{
                                            label: t('streamify.jam.moveDown'),
                                            openDelay: 0,
                                        }}
                                        variant="subtle"
                                    />
                                    <ActionIcon
                                        icon="x"
                                        onClick={() => controller?.removeTrack(item.addOpId)}
                                        size="compact-md"
                                        tooltip={{ label: t('streamify.jam.remove'), openDelay: 0 }}
                                        variant="subtle"
                                    />
                                </Group>
                            </Group>
                        ))
                    )}
                </Stack>
            </ScrollArea>
        </Stack>
    );
};
