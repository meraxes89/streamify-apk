/**
 * features/streamify/discover/components/discover-mix-shelf.tsx — W2-B:
 * the Home-route Discover Mix shelf.
 *
 * Seed attribution subtitles ("Because you listened to…"), a one-click
 * "Play Mix" (the whole mix, Play.NOW through the player context) and
 * "Refresh Mix" (regenerate from the current playTally state). Honest
 * states only: loading / empty / error — never fabricated tracks. The
 * shelf self-hides on non-Streamify servers (the home route's one-line
 * seam, PATCHES.md A26).
 */

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { usePlayer } from '/@/renderer/features/player/context/player-context';
import { useDiscoverMix } from '/@/renderer/features/streamify/discover/use-discover-mix';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { Play } from '/@/shared/types/types';

export const DiscoverMixShelf = () => {
    const { t } = useTranslation();
    const player = usePlayer();
    const { refresh, status } = useDiscoverMix();

    const attribution = useMemo(() => {
        if (status.kind !== 'ready') return null;
        if (status.mix.source !== 'tally' || status.mix.seeds.length === 0) {
            return t('streamifyDiscover.coldStartSubtitle');
        }
        const names = status.mix.seeds
            .slice(0, 2)
            .map((seed) => seed.title)
            .join(', ');
        return t('streamifyDiscover.becauseYouListened', { seeds: names });
    }, [status, t]);

    const playMix = () => {
        if (status.kind !== 'ready' || status.songs.length === 0) return;
        player.addToQueueByData(status.songs, Play.NOW);
    };

    const playTrack = (index: number) => {
        if (status.kind !== 'ready') return;
        const song = status.songs[index];
        if (song === undefined) return;
        player.addToQueueByData([song], Play.NOW);
    };

    if (status.kind === 'empty') {
        // The honest empty: no seeds, no cold-start feed, nothing to show.
        return null;
    }

    return (
        <Stack gap="sm">
            <Group justify="apart">
                <Stack gap={0}>
                    <TextTitle order={4}>{t('streamifyDiscover.shelfTitle')}</TextTitle>
                    {attribution !== null && (
                        <Text isMuted size="xs">
                            {attribution}
                        </Text>
                    )}
                </Stack>
                <Group gap="xs">
                    <Button
                        disabled={status.kind !== 'ready' || status.songs.length === 0}
                        onClick={playMix}
                        size="xs"
                        variant="default"
                    >
                        {t('streamifyDiscover.playMix')}
                    </Button>
                    <Button onClick={refresh} size="xs" variant="subtle">
                        {t('streamifyDiscover.refreshMix')}
                    </Button>
                </Group>
            </Group>

            {status.kind === 'loading' && (
                <Text isMuted size="sm">
                    {t('streamifyDiscover.loading')}
                </Text>
            )}

            {status.kind === 'error' && (
                <Stack gap="xs">
                    <Text size="sm">{t('streamifyDiscover.errorTitle')}</Text>
                    <Button onClick={refresh} size="xs" variant="subtle">
                        {t('streamifyDiscover.retry')}
                    </Button>
                </Stack>
            )}

            {status.kind === 'ready' && (
                <div
                    style={{
                        display: 'flex',
                        gap: '0.75rem',
                        overflowX: 'auto',
                        paddingBottom: '0.25rem',
                    }}
                >
                    {status.mix.tracks.slice(0, 12).map((track, index) => (
                        <button
                            key={track.videoId}
                            onClick={() => playTrack(index)}
                            style={{
                                background: 'var(--mantine-color-default)',
                                border: '1px solid var(--mantine-color-default-border)',
                                borderRadius: 'var(--mantine-radius-md)',
                                cursor: 'pointer',
                                flex: '0 0 auto',
                                padding: '0.6rem 0.9rem',
                                textAlign: 'left',
                                width: '13rem',
                            }}
                            type="button"
                        >
                            <Stack gap={0}>
                                <Text lineClamp={1} size="sm" weight={600}>
                                    {track.title}
                                </Text>
                                {track.artist !== null && (
                                    <Text isMuted lineClamp={1} size="xs">
                                        {track.artist}
                                    </Text>
                                )}
                            </Stack>
                        </button>
                    ))}
                </div>
            )}
        </Stack>
    );
};
