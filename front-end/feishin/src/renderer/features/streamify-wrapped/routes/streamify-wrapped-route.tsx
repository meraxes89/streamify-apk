/**
 * features/streamify-wrapped/routes/streamify-wrapped-route.tsx — P4-A:
 * the Wrapped & listening stats view.
 *
 * Window selector (7d / 30d / YTD / All-Time) over the listening ledger
 * (playTally verified listens + the #191 fire-path reconciliation).
 * Metrics: total listening time, verified play count, top tracks, top
 * artists, top genres (honest empty state until the cloud schema grows a
 * genre column), discovery-vs-repeat, and the hour-of-day histogram.
 * Empty/new-account states render as zero states — never NaN, never a
 * broken chart.
 *
 * W3-B extends the same route with the listening-rhythm card (peak
 * hour/weekday + streaks), the Top Discovery card (Discover Mix / Automix
 * graduates) and the client-side PNG share card — all fed by
 * features/streamify/wrapped/wrapped-engine.ts, the same ledger, the same
 * invariant (min(half-duration, 4 min); skips never count).
 */

import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
    WRAPPED_WINDOWS,
    type WrappedStats,
    type WrappedWindow,
} from '/@/renderer/api/streamify/wrapped/aggregates';
import { PageHeader } from '/@/renderer/components/page-header/page-header';
import { AnimatedPage } from '/@/renderer/features/shared/components/animated-page';
import { PageErrorBoundary } from '/@/renderer/features/shared/components/page-error-boundary';
import { WrappedDiscoveryCard } from '/@/renderer/features/streamify/wrapped/components/wrapped-discovery-card';
import { WrappedRhythmCard } from '/@/renderer/features/streamify/wrapped/components/wrapped-rhythm-card';
import { WrappedShareCardButton } from '/@/renderer/features/streamify/wrapped/components/wrapped-share-card';
import {
    formatWrappedShareCardData,
    getWrappedExtended,
} from '/@/renderer/features/streamify/wrapped/wrapped-engine';
import { Badge } from '/@/shared/components/badge/badge';
import { Group } from '/@/shared/components/group/group';
import { Paper } from '/@/shared/components/paper/paper';
import { SegmentedControl } from '/@/shared/components/segmented-control/segmented-control';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

function formatDuration(totalSeconds: number): string {
    if (totalSeconds < 60) return `${Math.round(totalSeconds)}s`;
    const minutes = Math.floor(totalSeconds / 60);
    if (minutes < 60) return `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    const rem = minutes % 60;
    return rem > 0 ? `${hours}h ${rem}m` : `${hours}h`;
}

const StatCard = ({ label, value }: { label: string; value: string }) => (
    <Paper p="md" shadow="none" withBorder>
        <Stack gap={4}>
            <Text c="dimmed" size="sm">
                {label}
            </Text>
            <Text fw={700} size="xl">
                {value}
            </Text>
        </Stack>
    </Paper>
);

const HourHistogram = ({ stats, t }: { stats: WrappedStats; t: (k: string) => string }) => {
    const max = Math.max(1, ...stats.byHour);
    return (
        <Stack gap="xs">
            <Group align="flex-end" gap={4} h={120} wrap="nowrap">
                {stats.byHour.map((count, hour) => (
                    <Stack
                        gap={2}
                        h="100%"
                        key={hour}
                        style={{ alignItems: 'center', flex: 1, justifyContent: 'flex-end' }}
                    >
                        <div
                            style={{
                                backgroundColor: 'var(--theme-colors-primary)',
                                borderRadius: 2,
                                height: `${(count / max) * 100}%`,
                                minHeight: count > 0 ? 2 : 0,
                                width: '70%',
                            }}
                            title={`${hour}:00 — ${count}`}
                        />
                        {hour % 6 === 0 ? (
                            <Text c="dimmed" size="xs">
                                {hour}
                            </Text>
                        ) : null}
                    </Stack>
                ))}
            </Group>
            <Text c="dimmed" size="xs">
                {t('streamifyWrapped.hourHint')}
            </Text>
        </Stack>
    );
};

const StreamifyWrappedRoute = () => {
    const { t } = useTranslation();
    const [window, setWindow] = useState<WrappedWindow>('30d');

    // W3-B: the extended snapshot (stats + rhythm + streak + graduation)
    // over the same ledger getWrappedStats seeds and reads.
    const extended = useMemo(() => getWrappedExtended(window), [window]);
    const stats: WrappedStats = extended.stats;

    const empty = stats.count === 0;

    const shareData = useMemo(
        () =>
            formatWrappedShareCardData({
                minutesWord: t('streamifyWrapped.shareMinutes'),
                playsTemplate: (count) => t('streamifyWrapped.sharePlays', { count }),
                stats,
                streak: extended.streak,
                streakTemplate: (days) => t('streamifyWrapped.shareStreak', { count: days }),
                windowLabel: t(`streamifyWrapped.window_${window}`),
            }),
        [extended.streak, stats, t, window],
    );

    return (
        <AnimatedPage>
            <PageHeader>
                <TextTitle order={3}>{t('streamifyWrapped.pageTitle')}</TextTitle>
            </PageHeader>
            <PageErrorBoundary>
                <Stack gap="md" p="md">
                    <Group justify="space-between" wrap="nowrap">
                        <SegmentedControl
                            data={WRAPPED_WINDOWS.map((w) => ({
                                label: t(`streamifyWrapped.window_${w}`),
                                value: w,
                            }))}
                            onChange={(v) => setWindow(v as WrappedWindow)}
                            value={window}
                        />
                        {stats.firstAt !== null ? (
                            <Text c="dimmed" size="sm">
                                {t('streamifyWrapped.since', {
                                    date: new Date(stats.firstAt).toLocaleDateString(),
                                })}
                            </Text>
                        ) : null}
                    </Group>
                    {!empty ? <WrappedShareCardButton data={shareData} /> : null}

                    {empty ? (
                        <Paper p="xl" shadow="none" withBorder>
                            <Stack align="center" gap="xs">
                                <Text fw={600} size="md">
                                    {t('streamifyWrapped.emptyTitle')}
                                </Text>
                                <Text c="dimmed" size="sm">
                                    {t('streamifyWrapped.emptyBody')}
                                </Text>
                            </Stack>
                        </Paper>
                    ) : (
                        <>
                            <Group gap="md" grow>
                                <StatCard
                                    label={t('streamifyWrapped.metricListeningTime')}
                                    value={formatDuration(stats.totalSeconds)}
                                />
                                <StatCard
                                    label={t('streamifyWrapped.metricVerifiedPlays')}
                                    value={String(stats.count)}
                                />
                                <StatCard
                                    label={t('streamifyWrapped.metricDiscovery')}
                                    value={
                                        stats.discoveryRatio === null
                                            ? '—'
                                            : `${Math.round(stats.discoveryRatio * 100)}%`
                                    }
                                />
                            </Group>

                            <Paper p="md" shadow="none" withBorder>
                                <Stack gap="xs">
                                    <Text fw={600} size="md">
                                        {t('streamifyWrapped.sectionHours')}
                                    </Text>
                                    <HourHistogram stats={stats} t={t} />
                                </Stack>
                            </Paper>

                            <WrappedRhythmCard rhythm={extended.rhythm} streak={extended.streak} />

                            <WrappedDiscoveryCard discovery={extended.discovery} />

                            <Group gap="md" grow>
                                <Paper p="md" shadow="none" withBorder>
                                    <Stack gap="xs">
                                        <Text fw={600} size="md">
                                            {t('streamifyWrapped.sectionTopTracks')}
                                        </Text>
                                        {stats.topTracks.length === 0 ? (
                                            <Text c="dimmed" size="sm">
                                                {t('streamifyWrapped.emptyList')}
                                            </Text>
                                        ) : (
                                            stats.topTracks.map((track, i) => (
                                                <Group justify="space-between" key={track.id}>
                                                    <Group gap="xs" wrap="nowrap">
                                                        <Text c="dimmed" size="sm">
                                                            {i + 1}.
                                                        </Text>
                                                        <Text lineClamp={1} size="sm">
                                                            {track.title} — {track.artist}
                                                        </Text>
                                                    </Group>
                                                    <Badge size="sm" variant="outline">
                                                        ×{track.count}
                                                    </Badge>
                                                </Group>
                                            ))
                                        )}
                                    </Stack>
                                </Paper>
                                <Paper p="md" shadow="none" withBorder>
                                    <Stack gap="xs">
                                        <Text fw={600} size="md">
                                            {t('streamifyWrapped.sectionTopArtists')}
                                        </Text>
                                        {stats.topArtists.length === 0 ? (
                                            <Text c="dimmed" size="sm">
                                                {t('streamifyWrapped.emptyList')}
                                            </Text>
                                        ) : (
                                            stats.topArtists.map((artist, i) => (
                                                <Group justify="space-between" key={artist.name}>
                                                    <Group gap="xs" wrap="nowrap">
                                                        <Text c="dimmed" size="sm">
                                                            {i + 1}.
                                                        </Text>
                                                        <Text lineClamp={1} size="sm">
                                                            {artist.name}
                                                        </Text>
                                                    </Group>
                                                    <Text c="dimmed" size="sm">
                                                        ×{artist.count} · {artist.tracks}{' '}
                                                        {t('streamifyWrapped.tracksWord')}
                                                    </Text>
                                                </Group>
                                            ))
                                        )}
                                    </Stack>
                                </Paper>
                            </Group>

                            <Paper p="md" shadow="none" withBorder>
                                <Stack gap="xs">
                                    <Text fw={600} size="md">
                                        {t('streamifyWrapped.sectionTopGenres')}
                                    </Text>
                                    {stats.topGenres.length === 0 ? (
                                        <Text c="dimmed" size="sm">
                                            {t('streamifyWrapped.genresPending')}
                                        </Text>
                                    ) : (
                                        stats.topGenres.map((genre) => (
                                            <Group justify="space-between" key={genre.name}>
                                                <Text size="sm">{genre.name}</Text>
                                                <Text c="dimmed" size="sm">
                                                    ×{genre.count}
                                                </Text>
                                            </Group>
                                        ))
                                    )}
                                </Stack>
                            </Paper>
                        </>
                    )}
                </Stack>
            </PageErrorBoundary>
        </AnimatedPage>
    );
};

export default StreamifyWrappedRoute;
