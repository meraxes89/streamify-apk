/**
 * features/streamify-settings/components/audio-playback-panel.tsx -
 * Audio & Playback preferences (P4B): codec ceiling, crossfade duration
 * (0-12 s, applied live to the player store), gapless toggle, and the EQ
 * quick-link (opens the player-bar drawer on the equalizer tab).
 */

import { useTranslation } from 'react-i18next';

import { usePlayerStore, usePlayerStoreBase } from '/@/renderer/store/player.store';
import { useStreamifyJamStoreBase } from '/@/renderer/store/streamify-jam.store';
import { useStreamifySettingsStore } from '/@/renderer/store/streamify-settings.store';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Select } from '/@/shared/components/select/select';
import { Slider } from '/@/shared/components/slider/slider';
import { Stack } from '/@/shared/components/stack/stack';
import { Switch } from '/@/shared/components/switch/switch';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { PlayerStyle } from '/@/shared/types/types';

const CODEC_DATA = [
    { label: 'Auto', value: 'auto' },
    { label: 'Opus', value: 'opus' },
    { label: 'AAC', value: 'aac' },
];

export const AudioPlaybackPanel = () => {
    const { t } = useTranslation();
    const settings = useStreamifySettingsStore((state) => state.settings);
    const setSettings = useStreamifySettingsStore((state) => state.set);
    const crossfadeDuration = usePlayerStore((state) => state.player.crossfadeDuration);
    const transitionType = usePlayerStore((state) => state.player.transitionType);

    const applyCrossfade = (seconds: number) => {
        setSettings({ crossfadeDurationSec: seconds });
        usePlayerStoreBase.getState().setCrossfadeDuration(seconds);
    };

    const applyGapless = (gapless: boolean) => {
        setSettings({ gapless });
        usePlayerStoreBase
            .getState()
            .setTransitionType(gapless ? PlayerStyle.GAPLESS : PlayerStyle.CROSSFADE);
    };

    const gapless = transitionType === PlayerStyle.GAPLESS;

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.settings.codecCeiling')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamify.settings.codecCeilingDescription')}
                </Text>
                <Select
                    data={CODEC_DATA}
                    onChange={(value) =>
                        setSettings({ codecCeiling: (value ?? 'auto') as 'aac' | 'auto' | 'opus' })
                    }
                    value={settings.codecCeiling}
                    w={200}
                />
            </Stack>

            <Divider />

            <Stack gap={4}>
                <Group justify="apart">
                    <Text size="sm">{t('streamify.settings.crossfade')}</Text>
                    <Text isMuted size="sm" span>
                        {crossfadeDuration}s
                    </Text>
                </Group>
                <Slider
                    max={12}
                    min={0}
                    onChange={applyCrossfade}
                    step={1}
                    value={crossfadeDuration}
                />
                <Text isMuted size="xs">
                    {t('streamify.settings.crossfadeDescription')}
                </Text>
            </Stack>

            <Divider />

            <Group justify="apart">
                <Stack gap={0}>
                    <Text size="sm">{t('streamify.settings.gapless')}</Text>
                    <Text isMuted size="xs">
                        {t('streamify.settings.gaplessDescription')}
                    </Text>
                </Stack>
                <Switch checked={gapless} onChange={(e) => applyGapless(e.currentTarget.checked)} />
            </Group>

            <Divider />

            <Group justify="apart">
                <Stack gap={0}>
                    <Text size="sm">{t('streamify.settings.eqQuickLink')}</Text>
                    <Text isMuted size="xs">
                        {t('streamify.settings.eqQuickLinkDescription')}
                    </Text>
                </Stack>
                <Button
                    onClick={() => useStreamifyJamStoreBase.getState().requestEqPanel()}
                    size="sm"
                    variant="outline"
                >
                    {t('streamify.settings.openEqualizer')}
                </Button>
            </Group>
        </Stack>
    );
};
