/**
 * features/streamify/wrapped/components/wrapped-discovery-card.tsx — W3-B:
 * the Top Discovery stat — tracks first surfaced by Discover Mix / Automix
 * (W2-B / W1-B) that graduated into repeat plays. Honest empty state when
 * nothing has graduated yet (the origin ledger starts recording now).
 */

import type { DiscoveryGraduation } from '/@/renderer/features/streamify/wrapped/wrapped-engine';

import { useTranslation } from 'react-i18next';

import { Badge } from '/@/shared/components/badge/badge';
import { Group } from '/@/shared/components/group/group';
import { Paper } from '/@/shared/components/paper/paper';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';

export const WrappedDiscoveryCard = ({ discovery }: { discovery: DiscoveryGraduation }) => {
    const { t } = useTranslation();

    return (
        <Paper p="md" shadow="none" withBorder>
            <Stack gap="xs">
                <Text fw={600} size="md">
                    {t('streamifyWrapped.sectionDiscovery')}
                </Text>
                {discovery.top === null ? (
                    <Text c="dimmed" size="sm">
                        {t('streamifyWrapped.discoveryEmpty')}
                    </Text>
                ) : (
                    <>
                        <Text fw={700} size="lg">
                            {t('streamifyWrapped.discoveryGraduated', {
                                count: discovery.graduated.length,
                            })}
                        </Text>
                        <Group justify="space-between" wrap="nowrap">
                            <Group gap="xs" wrap="nowrap">
                                <Badge color="primary" size="sm" variant="outline">
                                    #{1}
                                </Badge>
                                <Text lineClamp={1} size="sm">
                                    {discovery.top.title} — {discovery.top.artist}
                                </Text>
                            </Group>
                            <Text c="dimmed" size="sm">
                                ×{discovery.top.count}
                            </Text>
                        </Group>
                        {discovery.graduated.length > 1
                            ? discovery.graduated.slice(1, 4).map((track, i) => (
                                  <Group justify="space-between" key={`${track.title}-${i}`}>
                                      <Group gap="xs" wrap="nowrap">
                                          <Text c="dimmed" size="sm">
                                              {i + 2}.
                                          </Text>
                                          <Text lineClamp={1} size="sm">
                                              {track.title} — {track.artist}
                                          </Text>
                                      </Group>
                                      <Text c="dimmed" size="sm">
                                          ×{track.count}
                                      </Text>
                                  </Group>
                              ))
                            : null}
                        <Text c="dimmed" size="xs">
                            {t('streamifyWrapped.discoveryHint')}
                        </Text>
                    </>
                )}
            </Stack>
        </Paper>
    );
};
