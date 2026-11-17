/**
 * features/streamify-settings/components/engine-egress-panel.tsx - G5: the
 * Engine Egress (GSF) panel.
 *
 * The user-facing surface of the unburnable lane: point the app at your
 * deployed tools/gsf Apps Script (it egresses from Google's own ASN, the one
 * class YouTube's classifiers treat as first-party), ping it, and run the
 * live licensed-track posture probe (the whole G5 bet in one button - script
 * reachable AND the Google-ASN egress serving licensed playability).
 *
 * The lane is ADDITIVE and off by default: an empty field clears the
 * per-device localStorage override ('streamify.gsf.url'), leaving the
 * deployment default (?gsf= / baked VITE_GSF_URL) untouched. Redaction rule:
 * never shows the raw /exec URL - only the reachability badge and the
 * egress IPs Google returns.
 */

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
    GSF_PROBE_LICENSED_VIDEO_ID,
    gsfConfigured,
    gsfUrl,
    refreshGsfHealth,
    gsfProbePosture,
    setGsfUrl,
    type GsfHealth,
    type GsfProbeResult,
} from '/@/renderer/api/streamify/playback/resolver/lib/gsfLane';
import { Badge } from '/@/shared/components/badge/badge';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { TextInput } from '/@/shared/components/text-input/text-input';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';

const GSF_URL_RE = /^https:\/\/script\.google\.com\/macros\/s\/[^/]{10,}\/exec\/?$/;

type ProbeState =
    | { kind: 'idle' }
    | { kind: 'probing' }
    | { kind: 'done'; result: GsfProbeResult }
    | { kind: 'error'; message: string };

export const EngineEgressPanel = () => {
    const { t } = useTranslation();
    const [draft, setDraft] = useState(() => gsfUrl() ?? '');
    const [health, setHealth] = useState<GsfHealth | null>(null);
    const [probe, setProbe] = useState<ProbeState>({ kind: 'idle' });

    useEffect(() => {
        if (!gsfConfigured()) {
            setHealth(null);
            return;
        }
        let cancelled = false;
        void refreshGsfHealth().then((result) => {
            if (!cancelled) setHealth(result);
        });
        return () => {
            cancelled = true;
        };
    }, []);

    const trimmed = draft.trim();
    const isEmpty = trimmed.length === 0;
    const validationOk = isEmpty || GSF_URL_RE.test(trimmed);

    const save = () => {
        if (!validationOk) return;
        setGsfUrl(isEmpty ? null : trimmed);
        setDraft(gsfUrl() ?? '');
        setProbe({ kind: 'idle' });
        if (isEmpty) {
            setHealth(null);
            return;
        }
        void refreshGsfHealth().then(setHealth);
    };

    const test = () => {
        if (!gsfConfigured()) {
            setProbe({ kind: 'error', message: t('streamify.settings.engineEgressNotConfigured') });
            return;
        }
        setProbe({ kind: 'probing' });
        void (async () => {
            const result = await gsfProbePosture();
            setHealth(result.ping);
            setProbe({ kind: 'done', result });
        })().catch((err: unknown) => {
            setProbe({
                kind: 'error',
                message: err instanceof Error ? err.message : String(err),
            });
        });
    };

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.settings.engineEgress')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamify.settings.engineEgressDescription')}
                </Text>
                <Group gap="xs" wrap="nowrap">
                    <TextInput
                        error={validationOk ? undefined : t('streamify.settings.engineEgressInvalid')}
                        onChange={(event) => setDraft(event.currentTarget.value)}
                        placeholder="https://script.google.com/macros/s/…/exec"
                        style={{ flex: 1 }}
                        value={draft}
                        w="auto"
                    />
                    <Button disabled={!validationOk} onClick={save} size="sm" variant="filled">
                        {t('streamify.settings.save')}
                    </Button>
                    <Button
                        disabled={!gsfConfigured() || probe.kind === 'probing'}
                        onClick={test}
                        size="sm"
                        variant="outline"
                    >
                        {t('streamify.settings.testRelay')}
                    </Button>
                </Group>
            </Stack>

            <Divider />

            <Stack gap={4}>
                <TextTitle order={6}>{t('streamify.settings.engineEgressStatus')}</TextTitle>
                {!gsfConfigured() ? (
                    <Text isMuted size="sm">
                        {t('streamify.settings.engineEgressIdle')}
                    </Text>
                ) : (
                    <Group gap="xs">
                        <Badge color={health?.ok ? 'green' : health ? 'red' : 'gray'} variant="outline">
                            {health?.ok
                                ? t('streamify.settings.engineEgressPingOk')
                                : health
                                  ? t('streamify.settings.engineEgressPingBad')
                                  : t('streamify.settings.engineEgressPingIdle')}
                        </Badge>
                        {health?.egressIp ? (
                            <Text isMuted size="sm" span>
                                {t('streamify.settings.engineEgressEgress', { ip: health.egressIp })}
                            </Text>
                        ) : health?.error ? (
                            <Text c="var(--theme-colors-state-error)" size="sm" span>
                                {health.error}
                            </Text>
                        ) : null}
                    </Group>
                )}
                {probe.kind === 'done' && probe.result.licensed ? (
                    <Group gap="xs">
                        <Badge
                            color={probe.result.licensed.status === 'OK' ? 'green' : 'red'}
                            variant="outline"
                        >
                            {t('streamify.settings.engineEgressLicensed', {
                                status: probe.result.licensed.status,
                            })}
                        </Badge>
                        {probe.result.licensed.reason ? (
                            <Text c="var(--theme-colors-state-error)" size="sm" span>
                                {probe.result.licensed.reason}
                            </Text>
                        ) : null}
                    </Group>
                ) : probe.kind === 'error' ? (
                    <Text c="var(--theme-colors-state-error)" size="sm">
                        {probe.message}
                    </Text>
                ) : (
                    <Text isMuted size="xs">
                        {t('streamify.settings.engineEgressNote', {
                            videoId: GSF_PROBE_LICENSED_VIDEO_ID,
                        })}
                    </Text>
                )}
            </Stack>
        </Stack>
    );
};
