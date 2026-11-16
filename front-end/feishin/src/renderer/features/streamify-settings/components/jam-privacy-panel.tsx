/**
 * features/streamify-settings/components/jam-privacy-panel.tsx - Jam &
 * Privacy (P4B): default room permissions for rooms I host (open queue vs.
 * host-only skip/seek) and the scrobble tracking toggle.
 */

import { useTranslation } from 'react-i18next';

import { useStreamifySettingsStore } from '/@/renderer/store/streamify-settings.store';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { Switch } from '/@/shared/components/switch/switch';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

export const JamPrivacyPanel = () => {
    const { t } = useTranslation();
    const settings = useStreamifySettingsStore((state) => state.settings);
    const setSettings = useStreamifySettingsStore((state) => state.set);

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.settings.jamPermissions')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamify.settings.jamPermissionsDescription')}
                </Text>
            </Stack>

            <Group justify="apart">
                <Stack gap={0}>
                    <Text size="sm">{t('streamify.settings.jamOpenQueue')}</Text>
                    <Text isMuted size="xs">
                        {t('streamify.settings.jamOpenQueueDescription')}
                    </Text>
                </Stack>
                <Switch
                    checked={settings.jamOpenQueue}
                    onChange={(e) => setSettings({ jamOpenQueue: e.currentTarget.checked })}
                />
            </Group>

            <Group justify="apart">
                <Stack gap={0}>
                    <Text size="sm">{t('streamify.settings.jamHostOnly')}</Text>
                    <Text isMuted size="xs">
                        {t('streamify.settings.jamHostOnlyDescription')}
                    </Text>
                </Stack>
                <Switch
                    checked={settings.jamHostOnlySkipSeek}
                    onChange={(e) => setSettings({ jamHostOnlySkipSeek: e.currentTarget.checked })}
                />
            </Group>

            <Divider />

            <Group justify="apart">
                <Stack gap={0}>
                    <Text size="sm">{t('streamify.settings.scrobble')}</Text>
                    <Text isMuted size="xs">
                        {t('streamify.settings.scrobbleDescription')}
                    </Text>
                </Stack>
                <Switch
                    checked={settings.scrobbleEnabled}
                    onChange={(e) => setSettings({ scrobbleEnabled: e.currentTarget.checked })}
                />
            </Group>
        </Stack>
    );
};
