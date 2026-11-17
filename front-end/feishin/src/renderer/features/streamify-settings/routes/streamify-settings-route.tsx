/**
 * features/streamify-settings/routes/streamify-settings-route.tsx - the
 * dedicated Streamify settings surface (P4B).
 *
 * Five sections: Audio & Playback, Network & Relay, Jam & Privacy, Theme
 * (the brand-theme toggle; the preset itself registers additively through
 * the custom-theme runtime seam), and Account & Session. Everything is
 * persisted through streamify-settings.store ('streamify.settings.v1') and
 * applied through the upstream player/settings stores where they own the
 * value (crossfade, gapless, equalizer).
 */

import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import { PageHeader } from '/@/renderer/components/page-header/page-header';
import { AnimatedPage } from '/@/renderer/features/shared/components/animated-page';
import { PageErrorBoundary } from '/@/renderer/features/shared/components/page-error-boundary';
import { AccountSessionPanel } from '/@/renderer/features/streamify-settings/components/account-session-panel';
import { AudioPlaybackPanel } from '/@/renderer/features/streamify-settings/components/audio-playback-panel';
import { ConnectionDiagnosticsPanel } from '/@/renderer/features/streamify-settings/components/connection-diagnostics-panel';
import { EngineEgressPanel } from '/@/renderer/features/streamify-settings/components/engine-egress-panel';
import { JamPrivacyPanel } from '/@/renderer/features/streamify-settings/components/jam-privacy-panel';
import { NetworkRelayPanel } from '/@/renderer/features/streamify-settings/components/network-relay-panel';
import {
    ensureStreamifyThemeRegistered,
    STREAMIFY_THEME_LABEL,
} from '/@/renderer/features/streamify-settings/theme/streamify-theme';
import { SmartSyncPanel } from '/@/renderer/features/streamify/smart-sync/components/smart-sync-panel';
import { useStreamifySettingsStore } from '/@/renderer/store/streamify-settings.store';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { Switch } from '/@/shared/components/switch/switch';
import { Tabs } from '/@/shared/components/tabs/tabs';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

const BrandThemeSection = () => {
    const { t } = useTranslation();
    const settings = useStreamifySettingsStore((state) => state.settings);
    const setSettings = useStreamifySettingsStore((state) => state.set);

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.settings.theme')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamify.settings.themeDescription')}
                </Text>
            </Stack>
            <Group justify="apart">
                <Stack gap={0}>
                    <Text size="sm">{STREAMIFY_THEME_LABEL}</Text>
                    <Text isMuted size="xs">
                        {t('streamify.settings.brandThemeDescription')}
                    </Text>
                </Stack>
                <Switch
                    checked={settings.brandThemeEnabled}
                    onChange={(e) => setSettings({ brandThemeEnabled: e.currentTarget.checked })}
                />
            </Group>
            <Divider />
            <Text isMuted size="xs">
                {t('streamify.settings.themeUpstreamNote')}
            </Text>
        </Stack>
    );
};

const StreamifySettingsRoute = () => {
    const { t } = useTranslation();
    const load = useStreamifySettingsStore((state) => state.load);

    useEffect(() => {
        load();
        void ensureStreamifyThemeRegistered();
    }, [load]);

    return (
        <PageErrorBoundary>
            <AnimatedPage>
                <Stack h="100%" p="1rem">
                    <PageHeader title={t('streamify.settings.title')} />
                    <Tabs defaultValue="audio" keepMounted={false}>
                        <Tabs.List>
                            <Tabs.Tab value="audio">{t('streamify.settings.tabAudio')}</Tabs.Tab>
                            <Tabs.Tab value="network">
                                {t('streamify.settings.tabNetwork')}
                            </Tabs.Tab>
                            <Tabs.Tab value="offline">
                                {t('streamify.settings.tabOffline')}
                            </Tabs.Tab>
                            <Tabs.Tab value="jam">{t('streamify.settings.tabJam')}</Tabs.Tab>
                            <Tabs.Tab value="theme">{t('streamify.settings.tabTheme')}</Tabs.Tab>
                            <Tabs.Tab value="account">
                                {t('streamify.settings.tabAccount')}
                            </Tabs.Tab>
                        </Tabs.List>
                        <Tabs.Panel px="xs" py="md" value="audio">
                            <AudioPlaybackPanel />
                        </Tabs.Panel>
                        <Tabs.Panel px="xs" py="md" value="network">
                            <NetworkRelayPanel />
                            {/* G5: the unburnable lane - the user's own Apps
                                Script egress, with a ping and a licensed-track
                                posture probe (opt-in, off by default). */}
                            <EngineEgressPanel />
                            {/* W3-A: the 3-plane connection & health diagnostics
                                (solver / Edge KV / cloud sync — user-triggered
                                probes, redacted targets). Fork-owned surface. */}
                            <ConnectionDiagnosticsPanel />
                        </Tabs.Panel>
                        <Tabs.Panel px="xs" py="md" value="offline">
                            <SmartSyncPanel />
                        </Tabs.Panel>
                        <Tabs.Panel px="xs" py="md" value="jam">
                            <JamPrivacyPanel />
                        </Tabs.Panel>
                        <Tabs.Panel px="xs" py="md" value="theme">
                            <BrandThemeSection />
                        </Tabs.Panel>
                        <Tabs.Panel px="xs" py="md" value="account">
                            <AccountSessionPanel />
                        </Tabs.Panel>
                    </Tabs>
                </Stack>
            </AnimatedPage>
        </PageErrorBoundary>
    );
};

export default StreamifySettingsRoute;
