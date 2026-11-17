/**
 * features/streamify/wrapped/components/wrapped-rhythm-card.tsx — W3-B: the
 * listening-rhythm card (day-of-week distribution + peak hour/weekday +
 * the streak stats), rendered inside the P4-A Wrapped route.
 */

import type {
    ListeningStreak,
    WrappedRhythm,
} from '/@/renderer/features/streamify/wrapped/wrapped-engine';

import { useTranslation } from 'react-i18next';

import { Badge } from '/@/shared/components/badge/badge';
import { Group } from '/@/shared/components/group/group';
import { Paper } from '/@/shared/components/paper/paper';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';

const WEEKDAY_FORMATTER = new Intl.DateTimeFormat(undefined, { weekday: 'short' });
const WEEKDAY_LABELS = Array.from(
    { length: 7 },
    (_, day) => WEEKDAY_FORMATTER.format(new Date(2024, 0, 7 + day)), // 2024-01-07 is a Sunday
);

export const WrappedRhythmCard = ({
    rhythm,
    streak,
}: {
    rhythm: WrappedRhythm;
    streak: ListeningStreak;
}) => {
    const { t } = useTranslation();
    const max = Math.max(1, ...rhythm.byWeekday);

    return (
        <Paper p="md" shadow="none" withBorder>
            <Stack gap="xs">
                <Text fw={600} size="md">
                    {t('streamifyWrapped.sectionRhythm')}
                </Text>
                <Group gap="md" grow>
                    <Stack gap={2}>
                        <Text c="dimmed" size="sm">
                            {t('streamifyWrapped.peakHour')}
                        </Text>
                        <Text fw={700} size="lg">
                            {rhythm.peakHour === null
                                ? '—'
                                : t('streamifyWrapped.peakHourValue', {
                                      hour: String(rhythm.peakHour).padStart(2, '0'),
                                  })}
                        </Text>
                    </Stack>
                    <Stack gap={2}>
                        <Text c="dimmed" size="sm">
                            {t('streamifyWrapped.peakWeekday')}
                        </Text>
                        <Text fw={700} size="lg">
                            {rhythm.peakWeekday === null
                                ? '—'
                                : (WEEKDAY_LABELS[rhythm.peakWeekday] ?? '—')}
                        </Text>
                    </Stack>
                    <Stack gap={2}>
                        <Text c="dimmed" size="sm">
                            {t('streamifyWrapped.longestStreak')}
                        </Text>
                        <Text fw={700} size="lg">
                            {streak.longestDays > 0
                                ? t('streamifyWrapped.streakDays', { count: streak.longestDays })
                                : '—'}
                        </Text>
                    </Stack>
                </Group>
                <Group align="flex-end" gap={6} h={80} wrap="nowrap">
                    {rhythm.byWeekday.map((count, day) => (
                        <Stack
                            gap={2}
                            h="100%"
                            key={day}
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
                                title={`${WEEKDAY_LABELS[day]} — ${count}`}
                            />
                            <Text c="dimmed" size="xs">
                                {WEEKDAY_LABELS[day]}
                            </Text>
                        </Stack>
                    ))}
                </Group>
                <Group gap="xs">
                    <Badge size="sm" variant="outline">
                        {t('streamifyWrapped.currentStreak', { count: streak.currentDays })}
                    </Badge>
                </Group>
                <Text c="dimmed" size="xs">
                    {t('streamifyWrapped.weekdayHint')}
                </Text>
            </Stack>
        </Paper>
    );
};
