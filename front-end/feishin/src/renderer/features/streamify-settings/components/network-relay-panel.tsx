/**
 * features/streamify-settings/components/network-relay-panel.tsx -
 * Network & Relay (P4B): the custom STREAMIFY_RELAY_BASE override for
 * self-hosted Cloudflare Worker relays, with URL validation and a
 * reachability health check (HEAD with a 5 s abort).
 */

import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { validateRelayBaseUrl } from '/@/renderer/store/streamify-settings.store';
import { useStreamifySettingsStore } from '/@/renderer/store/streamify-settings.store';
import { Badge } from '/@/shared/components/badge/badge';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { TextInput } from '/@/shared/components/text-input/text-input';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

export const NetworkRelayPanel = () => {
    const { t } = useTranslation();
    const settings = useStreamifySettingsStore((state) => state.settings);
    const relayHealth = useStreamifySettingsStore((state) => state.relayHealth);
    const setSettings = useStreamifySettingsStore((state) => state.set);
    const checkRelayHealth = useStreamifySettingsStore((state) => state.checkRelayHealth);

    const [draft, setDraft] = useState(settings.relayBaseOverride);
    const validation = validateRelayBaseUrl(draft);

    const save = () => {
        if (!validation.ok) return;
        setSettings({ relayBaseOverride: draft.trim() });
    };

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.settings.relayBase')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamify.settings.relayBaseDescription')}
                </Text>
                <Group gap="xs" wrap="nowrap">
                    <TextInput
                        error={validation.ok ? undefined : t('streamify.settings.relayInvalid')}
                        onChange={(event) => setDraft(event.currentTarget.value)}
                        placeholder="https://my-relay.example.workers.dev"
                        style={{ flex: 1 }}
                        value={draft}
                        w="auto"
                    />
                    <Button
                        disabled={!validation.ok || draft.trim() === settings.relayBaseOverride}
                        onClick={save}
                        size="sm"
                        variant="filled"
                    >
                        {t('streamify.settings.save')}
                    </Button>
                    <Button
                        disabled={!validation.ok || draft.trim() === ''}
                        onClick={() => void checkRelayHealth(draft)}
                        size="sm"
                        variant="outline"
                    >
                        {t('streamify.settings.testRelay')}
                    </Button>
                </Group>
            </Stack>

            <Divider />

            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.settings.relayHealth')}</TextTitle>
                {relayHealth ? (
                    <Group gap="xs">
                        <Badge
                            color={
                                relayHealth.status === 'ok'
                                    ? 'green'
                                    : relayHealth.status === 'checking'
                                      ? 'gray'
                                      : 'red'
                            }
                            variant="outline"
                        >
                            {t(`streamify.settings.relayStatus.${relayHealth.status}`)}
                        </Badge>
                        {relayHealth.latencyMs !== null ? (
                            <Text isMuted size="sm" span>
                                {t('streamify.settings.relayLatency', {
                                    ms: relayHealth.latencyMs,
                                })}
                            </Text>
                        ) : null}
                        {relayHealth.error ? (
                            <Text c="var(--theme-colors-state-error)" size="sm" span>
                                {relayHealth.error}
                            </Text>
                        ) : null}
                    </Group>
                ) : (
                    <Text isMuted size="sm">
                        {t('streamify.settings.relayHealthIdle')}
                    </Text>
                )}
            </Stack>
        </Stack>
    );
};
