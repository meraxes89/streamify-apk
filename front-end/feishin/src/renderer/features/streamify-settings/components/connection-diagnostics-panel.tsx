/**
 * features/streamify-settings/components/connection-diagnostics-panel.tsx
 * — the W3-A Connection & Plane Diagnostics card (fork-owned surface —
 * the streamify-settings feature's own panel, no upstream seam).
 *
 * THE CONTRACT:
 *   • USER-TRIGGERED ONLY: one run on panel mount + the "Run Health
 *     Check" button. NEVER a background loop (the Apps Script quota rule).
 *   • The three planes (solver / edge KV / cloud sync) render a badge
 *     (Healthy / Degraded / Unconfigured), latency ms, the REDACTED
 *     target (masked hostname + classifier), and — the edge plane — the
 *     observed cache state (HIT / MISS / BYPASS).
 *   • "Copy Redacted Diagnostic Report" copies formatDiagnosticReport's
 *     output (leak-free by construction; the w3a suite pins it against
 *     the P5-B patterns).
 */

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { getStreamifySupabase } from '/@/renderer/api/streamify/streamify-supabase';
import {
    type DiagnosticReport,
    formatDiagnosticReport,
    type PlaneResult,
    probeEdgeMetaCache,
    probeStreamSolver,
} from '/@/renderer/features/streamify-settings/connection-diagnostics';
import { logger } from '/@/renderer/utils/logger';
import { Badge } from '/@/shared/components/badge/badge';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';

/** The cloud plane's production check: the live session + one throwaway
 *  channel subscribe (websocket readiness), fully unsubscribed after. */
async function cloudCheck(): Promise<{
    latencyMs: number;
    ok: boolean;
    statusDetail: PlaneResult['statusDetail'];
}> {
    const startedAt = Date.now();
    const supabase = getStreamifySupabase();
    if (supabase === null) {
        return { latencyMs: 0, ok: false, statusDetail: 'not-configured' };
    }
    try {
        const { data } = await supabase.auth.getSession();
        if (data.session === null) {
            return { latencyMs: Date.now() - startedAt, ok: false, statusDetail: 'auth-live' };
        }
        // One throwaway channel: proves the realtime websocket end-to-end.
        const userId = data.session.user.id;
        const channel = supabase.channel(`streamify:diag:${userId}`, {
            config: { broadcast: { self: false } },
        });
        const latencyMs = Date.now() - startedAt;
        const verdict = await new Promise<{
            ok: boolean;
            statusDetail: PlaneResult['statusDetail'];
        }>((resolve) => {
            const timer = setTimeout(() => {
                resolve({ ok: false, statusDetail: 'timeout' });
            }, 5_000);
            try {
                void channel.subscribe((status: string) => {
                    clearTimeout(timer);
                    if (status === 'SUBSCRIBED') {
                        resolve({ ok: true, statusDetail: 'auth-live' });
                    } else if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT') {
                        resolve({ ok: false, statusDetail: 'channel-error' });
                    }
                });
            } catch {
                clearTimeout(timer);
                resolve({ ok: false, statusDetail: 'channel-error' });
            }
        });
        try {
            await channel.unsubscribe();
            await supabase.removeChannel(channel);
        } catch {
            // best-effort cleanup
        }
        return { latencyMs, ok: verdict.ok, statusDetail: verdict.statusDetail };
    } catch (err) {
        logger.warn('streamify diagnostics: cloud probe failed', {
            error: err instanceof Error ? err.message : String(err),
        });
        return { latencyMs: Date.now() - startedAt, ok: false, statusDetail: 'network-error' };
    }
}

export const ConnectionDiagnosticsPanel = () => {
    const { t } = useTranslation();
    const [report, setReport] = useState<DiagnosticReport | null>(null);
    const [running, setRunning] = useState(false);

    const runDiagnostics = useCallback(async () => {
        setRunning(true);
        try {
            const solver = await probeStreamSolver();
            const edge = await probeEdgeMetaCache();
            // The cloud plane: unconfigured Supabase is the honest third
            // state, not an error.
            let cloud: PlaneResult;
            if (getStreamifySupabase() === null) {
                cloud = {
                    cacheState: null,
                    latencyMs: null,
                    maskedTarget: null,
                    overall: 'unconfigured',
                    plane: 'cloud',
                    status: null,
                    statusDetail: 'not-configured',
                };
            } else {
                const check = await cloudCheck();
                cloud = {
                    cacheState: null,
                    latencyMs: check.latencyMs,
                    maskedTarget: '…supabase.co',
                    overall: check.ok ? 'healthy' : 'degraded',
                    plane: 'cloud',
                    status: check.ok ? 200 : null,
                    statusDetail: check.statusDetail,
                };
            }
            setReport({ planes: [solver, edge, cloud], ranAtMs: Date.now() });
        } finally {
            setRunning(false);
        }
    }, []);

    // One run on mount (the task's "runs once on mount of
    // /streamify-settings") — this panel mounts keepMounted={false} inside
    // the tabs, so the run fires when the Network tab is first opened.
    useEffect(() => {
        void runDiagnostics();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const copyReport = async () => {
        if (report === null) return;
        try {
            await navigator.clipboard.writeText(formatDiagnosticReport(report));
            toast.success({ message: t('streamify.diagnostics.copied') });
        } catch {
            toast.error({ message: t('streamify.diagnostics.copyFailed') });
        }
    };

    const badgeFor = (overall: PlaneResult['overall']) =>
        overall === 'healthy' ? (
            <Badge color="green" size="sm" variant="filled">
                {t('streamify.diagnostics.statusHealthy')}
            </Badge>
        ) : overall === 'degraded' ? (
            <Badge color="red" size="sm" variant="filled">
                {t('streamify.diagnostics.statusDegraded')}
            </Badge>
        ) : (
            <Badge color="gray" size="sm" variant="filled">
                {t('streamify.diagnostics.statusUnconfigured')}
            </Badge>
        );

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.diagnostics.title')}</TextTitle>
                <Text isMuted size="sm">
                    {t('streamify.diagnostics.description')}
                </Text>
            </Stack>
            <Group gap="xs" wrap="nowrap">
                <Button
                    loading={running}
                    onClick={() => void runDiagnostics()}
                    size="sm"
                    variant="filled"
                >
                    {t('streamify.diagnostics.runButton')}
                </Button>
                <Button
                    disabled={report === null}
                    onClick={() => void copyReport()}
                    size="sm"
                    variant="outline"
                >
                    {t('streamify.diagnostics.copyButton')}
                </Button>
            </Group>
            {report !== null ? (
                <Stack gap="sm">
                    {report.planes.map((plane) => (
                        <Stack
                            gap="xs"
                            key={plane.plane}
                            style={{
                                border: '1px solid rgb(var(--theme-colors-text) / 0.15)',
                                borderRadius: '6px',
                                padding: '8px 10px',
                            }}
                        >
                            <Group justify="apart" wrap="nowrap">
                                <Text size="sm" weight={600}>
                                    {t(`streamify.diagnostics.plane_${plane.plane}`)}
                                </Text>
                                {badgeFor(plane.overall)}
                            </Group>
                            <Text isMuted size="xs">
                                {plane.maskedTarget === null
                                    ? t('streamify.diagnostics.targetUnset')
                                    : `${plane.maskedTarget} · ${
                                          plane.latencyMs !== null
                                              ? t('streamify.diagnostics.latencyMs', {
                                                    ms: plane.latencyMs,
                                                })
                                              : t('streamify.diagnostics.latencyUnknown')
                                      }`}
                            </Text>
                            {plane.plane === 'edge' ? (
                                <Text isMuted size="xs">
                                    {plane.cacheState !== null
                                        ? t(`streamify.diagnostics.cache_${plane.cacheState}`)
                                        : t('streamify.diagnostics.cache_unknown')}
                                </Text>
                            ) : null}
                            {plane.statusDetail !== 'ok' && plane.overall !== 'unconfigured' ? (
                                <Text isMuted size="xs">
                                    {t(`streamify.diagnostics.detail_${plane.statusDetail}`)}
                                </Text>
                            ) : null}
                        </Stack>
                    ))}
                </Stack>
            ) : null}
            <Text isMuted size="xs">
                {t('streamify.diagnostics.redactionNote')}
            </Text>
        </Stack>
    );
};
