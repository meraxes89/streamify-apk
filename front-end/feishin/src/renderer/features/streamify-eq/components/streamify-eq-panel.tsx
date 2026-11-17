/**
 * features/streamify-eq/components/streamify-eq-panel.tsx - the Streamify
 * equalizer surface (P4B): preset bank (built-ins + saved customs), the
 * frequency-response curve, per-band sliders, preamp, and the bypass state.
 *
 * Band/preamp values write through settings.playback.equalizer (the
 * upstream schema), so the upstream EQ panel and the cold-start graph
 * builder stay coherent with this one. The DSP chain itself is owned by
 * use-streamify-eq-graph (mounted from the player-bar trigger).
 */

import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { EqCurve } from '/@/renderer/features/streamify-eq/components/eq-curve';
import {
    BUILTIN_EQ_PRESETS,
    EQ_MAX_GAIN_DB,
    EQ_MAX_PREAMP_DB,
    type EqPreset,
} from '/@/renderer/features/streamify-eq/utils/eq-math';
import {
    decodeEqPresetShareCode,
    encodeEqPresetShareCode,
    type EqPresetShareError,
} from '/@/renderer/features/streamify/eq-presets/preset-codec';
import { useSettingsStore } from '/@/renderer/store/settings.store';
import { selectAllEqPresets, useStreamifyEqStore } from '/@/renderer/store/streamify-eq.store';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Popover } from '/@/shared/components/popover/popover';
import { Select } from '/@/shared/components/select/select';
import { Slider } from '/@/shared/components/slider/slider';
import { Stack } from '/@/shared/components/stack/stack';
import { Switch } from '/@/shared/components/switch/switch';
import { TextInput } from '/@/shared/components/text-input/text-input';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';

const MANUAL_VALUE = '__manual';

const bandLabel = (freq: number): string => (freq >= 1000 ? `${freq / 1000}k` : `${freq}`);

/** Typed share-codec errors → literal i18n keys (check-i18n scans these). */
const shareErrorKey = (error: EqPresetShareError): string => {
    switch (error.code) {
        case 'bad-bands':
            return 'streamify.eq.shareErrorBadBands';
        case 'bad-base64':
            return 'streamify.eq.shareErrorBadBase64';
        case 'bad-json':
            return 'streamify.eq.shareErrorBadJson';
        case 'bad-schema':
            return 'streamify.eq.shareErrorBadSchema';
        default:
            return 'streamify.eq.shareErrorEmpty';
    }
};

export const StreamifyEqPanel = () => {
    const { t } = useTranslation();
    const equalizer = useSettingsStore((state) => state.playback.equalizer);
    const webAudioEnabled = useSettingsStore((state) => state.playback.webAudio);
    const eq = useStreamifyEqStore();

    const [presetName, setPresetName] = useState('');
    // W3-B: the share-code lane (import popover + copy-out) and rename mode.
    const [importOpened, setImportOpened] = useState(false);
    const [importCode, setImportCode] = useState('');
    const [importError, setImportError] = useState<null | string>(null);
    const [renameMode, setRenameMode] = useState(false);

    useEffect(() => {
        eq.load();
        // Idempotent hydration of persisted custom presets.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const presets = useMemo(() => selectAllEqPresets(eq), [eq]);
    const selectedValue = eq.selectedPresetId ?? MANUAL_VALUE;

    const presetData = useMemo(
        () => [
            ...presets.map((preset) => ({ label: preset.name, value: preset.id })),
            { label: t('streamify.eq.presetManual'), value: MANUAL_VALUE },
        ],
        [presets, t],
    );

    const handleApplyPreset = (value: string) => {
        if (value === MANUAL_VALUE) {
            eq.setCustom();
            return;
        }
        eq.applyPreset(value);
    };

    const selectedCustom = useMemo(
        () => presets.find((preset: EqPreset) => preset.id === eq.selectedPresetId),
        [presets, eq.selectedPresetId],
    );

    const handleSavePreset = () => {
        if (renameMode && selectedCustom) {
            eq.renameCustomPreset(selectedCustom.id, presetName);
            setRenameMode(false);
            setPresetName('');
            return;
        }
        const saved = eq.saveCustomPreset(presetName);
        if (saved) {
            setPresetName('');
            toast.success({ message: t('streamify.eq.presetSaved', { name: saved.name }) });
        }
    };

    const handleImport = () => {
        const result = decodeEqPresetShareCode(importCode);
        if (!result.ok) {
            setImportError(t(shareErrorKey(result.error)));
            return;
        }
        const imported = eq.importCustomPreset(result.preset);
        setImportCode('');
        setImportError(null);
        setImportOpened(false);
        toast.success({ message: t('streamify.eq.importDone', { name: imported.name }) });
    };

    const handleCopyShareCode = () => {
        const selected = presets.find((preset) => preset.id === eq.selectedPresetId);
        if (!selected) return;
        const code = encodeEqPresetShareCode(selected);
        void navigator.clipboard?.writeText(code).then(() => {
            toast.success({ message: t('streamify.eq.shareCodeCopied') });
        });
    };

    return (
        <Stack gap="sm">
            {webAudioEnabled ? null : (
                <Text c="var(--theme-colors-state-warning)" size="sm">
                    {t('streamify.eq.webAudioDisabled')}
                </Text>
            )}
            {eq.bypassed ? (
                <Text c="var(--theme-colors-state-error)" size="sm">
                    {t('streamify.eq.bypassed', { reason: eq.bypassReason ?? '' })}
                </Text>
            ) : null}

            <Group gap="xs" grow>
                <Select
                    data={presetData}
                    onChange={(value) => handleApplyPreset(value ?? MANUAL_VALUE)}
                    value={selectedValue}
                />
                <Switch
                    checked={equalizer.enabled}
                    label={t('streamify.eq.enabled')}
                    onChange={(event) =>
                        useSettingsStore.getState().actions.setSettings({
                            playback: {
                                equalizer: { ...equalizer, enabled: event.currentTarget.checked },
                            },
                        })
                    }
                />
            </Group>

            <EqCurve bands={equalizer.bands} preamp={equalizer.preamp} />

            <Group gap="xs" grow justify="apart" wrap="nowrap">
                {equalizer.bands.map((band, index) => (
                    <Stack align="center" gap={4} key={`${band.freq}-${index}`}>
                        <Slider
                            label={(value) => `${value > 0 ? '+' : ''}${value.toFixed(1)} dB`}
                            max={EQ_MAX_GAIN_DB}
                            min={-EQ_MAX_GAIN_DB}
                            onChange={(value) => eq.setBandGain(index, value)}
                            orientation="vertical"
                            step={0.5}
                            style={{ height: 120 }}
                            value={band.gain}
                        />
                        <Text size="xs" span>
                            {bandLabel(band.freq)}
                        </Text>
                    </Stack>
                ))}
            </Group>

            <Stack gap={4}>
                <Group justify="apart">
                    <Text size="sm">{t('streamify.eq.preamp')}</Text>
                    <Text size="sm" span>
                        {equalizer.preamp > 0 ? '+' : ''}
                        {equalizer.preamp.toFixed(1)} dB
                    </Text>
                </Group>
                <Slider
                    max={EQ_MAX_PREAMP_DB}
                    min={-EQ_MAX_PREAMP_DB}
                    onChange={(value) => eq.setPreamp(value)}
                    step={0.5}
                    value={equalizer.preamp}
                />
            </Stack>

            <Divider />

            <Stack gap="xs">
                <TextTitle order={5}>{t('streamify.eq.customPresets')}</TextTitle>
                <Group gap="xs">
                    <TextInput
                        onChange={(event) => setPresetName(event.currentTarget.value)}
                        placeholder={
                            renameMode && selectedCustom
                                ? selectedCustom.name
                                : t('streamify.eq.presetNamePlaceholder')
                        }
                        value={presetName}
                        w={200}
                    />
                    <Button
                        disabled={presetName.trim() === ''}
                        onClick={handleSavePreset}
                        size="sm"
                        variant="filled"
                    >
                        {renameMode && selectedCustom
                            ? t('streamify.eq.saveName')
                            : t('streamify.eq.savePreset')}
                    </Button>
                    {selectedCustom && selectedCustom.id.startsWith('custom:') ? (
                        <>
                            <Button
                                onClick={() => {
                                    setRenameMode((mode) => !mode);
                                    setPresetName(renameMode ? '' : selectedCustom.name);
                                }}
                                size="sm"
                                variant="light"
                            >
                                {renameMode
                                    ? t('streamify.eq.cancelRename')
                                    : t('streamify.eq.renamePreset')}
                            </Button>
                            <Button
                                color="error"
                                onClick={() => {
                                    eq.deleteCustomPreset(selectedCustom.id);
                                    setRenameMode(false);
                                    setPresetName('');
                                }}
                                size="sm"
                                variant="outline"
                            >
                                {t('streamify.eq.deletePreset')}
                            </Button>
                        </>
                    ) : null}
                </Group>
                <Group gap="xs">
                    <Button
                        disabled={eq.selectedPresetId === null}
                        onClick={handleCopyShareCode}
                        size="sm"
                        variant="light"
                    >
                        {t('streamify.eq.shareCode')}
                    </Button>
                    <Popover
                        onChange={(opened) => {
                            setImportOpened(opened);
                            if (!opened) {
                                setImportError(null);
                                setImportCode('');
                            }
                        }}
                        opened={importOpened}
                        position="bottom"
                        width={340}
                    >
                        <Popover.Target>
                            <Button onClick={() => setImportOpened(true)} size="sm" variant="light">
                                {t('streamify.eq.importShareCode')}
                            </Button>
                        </Popover.Target>
                        <Popover.Dropdown>
                            <Stack gap="xs">
                                <Text size="sm">{t('streamify.eq.importIntro')}</Text>
                                <TextInput
                                    onChange={(event) => setImportCode(event.currentTarget.value)}
                                    placeholder={t('streamify.eq.shareCodePlaceholder')}
                                    value={importCode}
                                />
                                {importError !== null ? (
                                    <Text c="var(--theme-colors-state-error)" size="xs">
                                        {importError}
                                    </Text>
                                ) : null}
                                <Button
                                    disabled={importCode.trim() === ''}
                                    onClick={handleImport}
                                    size="xs"
                                    variant="filled"
                                >
                                    {t('streamify.eq.importPreset')}
                                </Button>
                            </Stack>
                        </Popover.Dropdown>
                    </Popover>
                </Group>
                <Text isMuted size="xs">
                    {t('streamify.eq.builtinCount', { count: BUILTIN_EQ_PRESETS.length })}
                </Text>
            </Stack>
        </Stack>
    );
};
