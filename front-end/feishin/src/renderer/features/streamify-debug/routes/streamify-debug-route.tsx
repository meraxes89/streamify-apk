/**
 * features/streamify-debug/routes/streamify-debug-route.tsx — P4-A: the
 * diagnostics console, strictly gated (use-debug-gate: DEV, the persisted
 * developer-mode grant, or ?debug=1 + authenticated admin). Everyone
 * else gets a clean redirect to / — standard users never see raw
 * internals.
 *
 * PANELS (read-only + one safe action):
 *   1. Resolver rung & cache — the recent resolve log (last rung per
 *      track, expiry, itag/codec) + cache peek.
 *   2. OPFS & cipher wasm — storage quota/usage, init status, the
 *      chunk-cipher self-test, download counts.
 *   3. Scrobble outbox — pending depth, last flush timestamp, the
 *      manual "Flush Outbox" button (drain with live adapters).
 *   4. Playlist CRDT sync — outbox depth, last sync/persist timestamps,
 *      in-flight flag, realtime binding status.
 *   5. Wrapped ledger — local vs cloud row counts.
 */

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router';

import { DebugPanel } from '../components/debug-panel';
import { useDebugGate } from '../hooks/use-debug-gate';

import { recordPlayEventStrict } from '/@/renderer/api/streamify/cloud';
import { recentResolveLog } from '/@/renderer/api/streamify/playback/resolver/audio/get-stream-url';
import { getStreamifyScrobbleBridge } from '/@/renderer/api/streamify/playback/scrobble-bridge';
import { playlistPersistTimestamp } from '/@/renderer/api/streamify/playlist-sync/persistence';
import { playlistRealtimeStatus } from '/@/renderer/api/streamify/playlist-sync/realtime';
import { resolveStreamifySession } from '/@/renderer/api/streamify/session';
import { ledgerDebugInfo } from '/@/renderer/api/streamify/wrapped/aggregates';
import { AnimatedPage } from '/@/renderer/features/shared/components/animated-page';
import { PageErrorBoundary } from '/@/renderer/features/shared/components/page-error-boundary';
import { useAuthStore } from '/@/renderer/store';
import {
    getStreamifyDebugState,
    setStreamifyDebugState,
    useStreamifyDebugStoreBase,
} from '/@/renderer/store/streamify-debug.store';
import { useStreamifyDownloadsStoreBase } from '/@/renderer/store/streamify-downloads.store';
import { getStreamifyPlaylistsState } from '/@/renderer/store/streamify-playlists.store';
import { Badge } from '/@/shared/components/badge/badge';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { Switch } from '/@/shared/components/switch/switch';
import { Text } from '/@/shared/components/text/text';

function relative(iso: null | number | string): string {
    if (iso === null) return '—';
    const ts = typeof iso === 'number' ? iso : Date.parse(String(iso));
    if (!Number.isFinite(ts)) return '—';
    const delta = Date.now() - ts;
    if (delta < 60_000) return `${Math.max(1, Math.round(delta / 1000))}s ago`;
    if (delta < 3_600_000) return `${Math.round(delta / 60_000)}m ago`;
    return `${Math.round(delta / 3_600_000)}h ago`;
}

const StreamifyDebugRoute = () => {
    const { t } = useTranslation();
    const gate = useDebugGate();
    const server = useAuthStore((state) => state.currentServer);
    const developerMode = useStreamifyDebugStoreBase((s) => s.developerMode);
    const wasmHealth = useStreamifyDownloadsStoreBase((s) => s.wasm);
    const storage = useStreamifyDownloadsStoreBase((s) => s.storage);
    const entries = useStreamifyDownloadsStoreBase((s) => s.entries);
    const [flushNote, setFlushNote] = useState<null | string>(null);
    const [tick, setTick] = useState(0);

    useEffect(() => {
        const id = setInterval(() => setTick((n) => n + 1), 2000);
        return () => clearInterval(id);
    }, []);
    void tick; // re-render driver for the read-only panels

    if (!gate.allowed) {
        return <Navigate replace to="/" />;
    }

    const bridge = getStreamifyScrobbleBridge();
    const pendingScrobbles = bridge.pendingScrobbles();
    const playlists = getStreamifyPlaylistsState();
    const realtime = playlistRealtimeStatus();
    const ledger = ledgerDebugInfo();
    const resolveLog = recentResolveLog().slice(-12).reverse();
    const userId = playlists.session?.userId ?? '';

    const onFlush = async () => {
        const session =
            server?.credential !== undefined ? resolveStreamifySession(server.credential) : null;
        if (session === null || session.accessToken === '') {
            setFlushNote(t('streamifyDebug.flushNeedsSession'));
            return;
        }
        const sent = await bridge.drain({
            canSend: () => session !== null && session.accessToken !== '',
            send: (event) =>
                recordPlayEventStrict(session, {
                    artist: event.artist,
                    completionRatio:
                        event.durationMs > 0 ? Math.min(1, event.playedMs / event.durationMs) : 0,
                    durationPlayedSec: event.playedMs / 1000,
                    title: event.title,
                    trackSig: event.trackId,
                }),
        });
        setFlushNote(t('streamifyDebug.flushDone', { count: sent }));
    };

    return (
        <AnimatedPage>
            <PageErrorBoundary>
                <Stack gap="md" p="md">
                    <Group justify="space-between">
                        <Group gap="xs">
                            <Text fw={700} size="xl">
                                {t('streamifyDebug.pageTitle')}
                            </Text>
                            <Badge color="var(--theme-colors-warning)" size="sm" variant="dot">
                                {gate.via}
                            </Badge>
                        </Group>
                        <Group gap="xs">
                            <Text c="dimmed" size="sm">
                                {t('streamifyDebug.developerMode')}
                            </Text>
                            <Switch
                                aria-label={t('streamifyDebug.developerMode')}
                                checked={developerMode}
                                onChange={(e) =>
                                    setStreamifyDebugState({
                                        developerMode: e.currentTarget.checked,
                                    })
                                }
                            />
                        </Group>
                    </Group>

                    <DebugPanel title={t('streamifyDebug.panelResolver')}>
                        {resolveLog.length === 0 ? (
                            <Text c="dimmed" size="sm">
                                {t('streamifyDebug.resolverEmpty')}
                            </Text>
                        ) : (
                            resolveLog.map((r) => (
                                <Group justify="space-between" key={`${r.trackId}-${r.at}`}>
                                    <Text lineClamp={1} size="sm">
                                        {r.trackId}
                                    </Text>
                                    <Text c="dimmed" size="sm">
                                        {r.rung} · {r.itag} · {r.codec}
                                        {r.audioOnly ? '' : ' (muxed)'}
                                    </Text>
                                </Group>
                            ))
                        )}
                    </DebugPanel>

                    <DebugPanel title={t('streamifyDebug.panelStorage')}>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.wasmStatus')}</Text>
                            <Text c="dimmed" size="sm">
                                {wasmHealth.status}
                                {wasmHealth.selfTest !== null
                                    ? ` · self-test ${wasmHealth.selfTest === 0 ? 'ok' : 'failed'}`
                                    : ''}
                            </Text>
                        </Group>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.storageQuota')}</Text>
                            <Text c="dimmed" size="sm">
                                {storage === null
                                    ? '—'
                                    : `${(storage.usage / 1024 / 1024).toFixed(1)} MiB / ${(storage.quota / 1024 / 1024).toFixed(0)} MiB`}
                            </Text>
                        </Group>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.downloadedEntries')}</Text>
                            <Text c="dimmed" size="sm">
                                {
                                    Object.values(entries).filter((e) => e.integrity === 'complete')
                                        .length
                                }{' '}
                                ok ·{' '}
                                {
                                    Object.values(entries).filter((e) => e.integrity === 'corrupt')
                                        .length
                                }{' '}
                                corrupt
                            </Text>
                        </Group>
                    </DebugPanel>

                    <DebugPanel
                        action={
                            <>
                                {flushNote !== null ? (
                                    <Text c="dimmed" mr="xs" size="sm">
                                        {flushNote}
                                    </Text>
                                ) : null}
                                <Button onClick={onFlush} size="sm" variant="outline">
                                    {t('streamifyDebug.flushOutbox')}
                                </Button>
                            </>
                        }
                        title={t('streamifyDebug.panelScrobble')}
                    >
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.scrobbleDepth')}</Text>
                            <Text c="dimmed" size="sm">
                                {pendingScrobbles.length}
                            </Text>
                        </Group>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.scrobbleLastFlush')}</Text>
                            <Text c="dimmed" size="sm">
                                {relative(bridge.lastFlushAt)}
                            </Text>
                        </Group>
                    </DebugPanel>

                    <DebugPanel title={t('streamifyDebug.panelCrdt')}>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.crdtOutbox')}</Text>
                            <Text c="dimmed" size="sm">
                                {playlists.outbox.size}
                            </Text>
                        </Group>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.crdtLastSync')}</Text>
                            <Text c="dimmed" size="sm">
                                {playlists.syncing ? 'in flight' : relative(playlists.syncedAt)}
                            </Text>
                        </Group>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.crdtLastPersist')}</Text>
                            <Text c="dimmed" size="sm">
                                {relative(userId !== '' ? playlistPersistTimestamp(userId) : null)}
                            </Text>
                        </Group>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.crdtRealtime')}</Text>
                            <Text c="dimmed" size="sm">
                                {realtime.bound ? 'bound' : 'unbound'}
                                {realtime.bound
                                    ? realtime.channelLive
                                        ? ' · live'
                                        : ' · no channel'
                                    : ''}
                            </Text>
                        </Group>
                    </DebugPanel>

                    <DebugPanel title={t('streamifyDebug.panelLedger')}>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.ledgerLocal')}</Text>
                            <Text c="dimmed" size="sm">
                                {ledger.local}
                            </Text>
                        </Group>
                        <Group justify="space-between">
                            <Text size="sm">{t('streamifyDebug.ledgerCloud')}</Text>
                            <Text c="dimmed" size="sm">
                                {ledger.cloud}
                            </Text>
                        </Group>
                    </DebugPanel>
                </Stack>
            </PageErrorBoundary>
        </AnimatedPage>
    );
};

void getStreamifyDebugState;

export default StreamifyDebugRoute;
