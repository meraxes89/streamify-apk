/**
 * features/streamify/smart-sync/components/smart-sync-panel.tsx — W2-B:
 * the Smart Sync section of /streamify-settings (the Offline tab).
 *
 * The toggle (enabled / explicit save-data opt-in), the honest storage
 * meter (X MB / 500 MB used, computed from the ACTUAL smart-sync sidecars
 * — never a guess), and "Clear Smart Sync Cache" (removes ONLY
 * source === 'smart-sync' entries; manual downloads stay). The breaker
 * cooldown, when active, is shown as its own honest note.
 */

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { SMART_SYNC_CAP_BYTES } from '/@/renderer/features/streamify/smart-sync/smart-sync-engine';
import { ensureSmartSyncEngine } from '/@/renderer/features/streamify/smart-sync/use-smart-sync';
import { useStreamifySettingsStore } from '/@/renderer/store/streamify-settings.store';
import { useStreamifySmartSyncStoreBase } from '/@/renderer/store/streamify-smart-sync.store';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Progress } from '/@/shared/components/progress/progress';
import { Stack } from '/@/shared/components/stack/stack';
import { Switch } from '/@/shared/components/switch/switch';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

function mb(bytes: number): number {
    return Math.round((bytes / (1024 * 1024)) * 10) / 10;
}

export const SmartSyncPanel = () => {
    const { t } = useTranslation();
    const settings = useStreamifySettingsStore((state) => state.settings);
    const setSettings = useStreamifySettingsStore((state) => state.set);
    const breaker = useStreamifySmartSyncStoreBase((state) => state.breaker);
    const storeUsage = useStreamifySmartSyncStoreBase((state) => state.usage);
    const [usage, setUsage] = useState(storeUsage);
    // 0 = "not sampled yet" (the interval effect stamps it — impure calls
    // stay out of render, the react-hooks/purity rule).
    const [now, setNow] = useState(0);

    useEffect(() => {
        setNow(Date.now());
        void ensureSmartSyncEngine().then((engine) => {
            if (engine === null) return;
            void engine.usage().then(setUsage);
        });
    }, []);

    // Keep the meter live: re-read usage periodically (sidecars change in
    // the background as the engine pre-fetches).
    useEffect(() => {
        const timer = setInterval(() => {
            setNow(Date.now());
            void ensureSmartSyncEngine().then((engine) => {
                if (engine === null) return;
                void engine.usage().then(setUsage);
            });
        }, 15_000);
        return () => clearInterval(timer);
    }, []);

    const clearCache = useCallback(() => {
        void ensureSmartSyncEngine().then((engine) => {
            if (engine === null) return;
            void engine.clearCache().then(() => engine.usage().then(setUsage));
        });
    }, []);

    const breakerActive = breaker.openUntilMs > now;
    const usedBytes = usage.usedBytes;
    const pct = Math.min(100, Math.round((usedBytes / SMART_SYNC_CAP_BYTES) * 100));

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamifySmartSync.title')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamifySmartSync.description')}
                </Text>
            </Stack>

            <Group justify="apart">
                <Stack gap={0}>
                    <Text size="sm">{t('streamifySmartSync.toggleLabel')}</Text>
                    <Text isMuted size="xs">
                        {t('streamifySmartSync.toggleDescription')}
                    </Text>
                </Stack>
                <Switch
                    checked={settings.smartSyncEnabled}
                    onChange={(e) =>
                        setSettings({
                            // Re-enabling after an explicit user action is the
                            // save-data opt-in the task defines.
                            smartSyncEnabled: e.currentTarget.checked,
                            smartSyncSaveDataOverride: e.currentTarget.checked
                                ? true
                                : settings.smartSyncSaveDataOverride,
                        })
                    }
                />
            </Group>

            <Divider />

            <Stack gap="xs">
                <Group justify="apart">
                    <Text size="sm">{t('streamifySmartSync.storageLabel')}</Text>
                    <Text isMuted size="sm" span>
                        {t('streamifySmartSync.storageUsed', {
                            cap: mb(SMART_SYNC_CAP_BYTES),
                            used: mb(usedBytes),
                        })}
                    </Text>
                </Group>
                <Progress value={pct} />
                <Text isMuted size="xs">
                    {t('streamifySmartSync.storageNote')}
                </Text>
            </Stack>

            <Group>
                <Button onClick={clearCache} size="xs" variant="subtle">
                    {t('streamifySmartSync.clearCache')}
                </Button>
            </Group>

            {breakerActive && (
                <Text isMuted size="xs">
                    {t('streamifySmartSync.cooldownNote', {
                        minutes: Math.max(1, Math.ceil((breaker.openUntilMs - now) / 60_000)),
                    })}
                </Text>
            )}
        </Stack>
    );
};
