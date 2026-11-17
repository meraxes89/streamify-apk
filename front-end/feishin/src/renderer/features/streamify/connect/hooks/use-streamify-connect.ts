/**
 * features/streamify/connect/hooks/use-streamify-connect.ts — the W3-A
 * Streamify Connect controller.
 *
 * WHAT IT OWNS (ADR §A):
 *   • the per-browser-profile device identity (localStorage-minted id +
 *     the UA-derived label — the P3A jam pattern, its own storage key);
 *   • presence publishing: on track change / play-pause / seek (throttled
 *     ≥ PRESENCE_MIN_INTERVAL_MS apart) + a keepalive while playing —
 *     the published shape is the sanitized ConnectPresenceState;
 *   • the command lane: inbound transport commands execute through the
 *     player store's OWN pipelines (mediaPlay/mediaPause/mediaNext/
 *     mediaPrevious/mediaSeekToTimestamp/setVolume — the same surfaces the
 *     stock media-session hook drives); echo/target filtering per
 *     connect-protocol;
 *   • the handoff orchestration BOTH directions:
 *       — inbound 'handoff-request' → pause local playback, then stream
 *         the queue's videoIds back in chunks (the target's exact
 *         positionSec + mode ride chunk 0);
 *       — outbound (the UI's "Transfer here") → request, reassemble the
 *         chunks, map the ids through THIS device's library snapshot
 *         (same account — unknown ids skipped honestly), setQueue, seek
 *         to the exact timestamp, resume; the W2-A video mode restores
 *         through the same public toggle pipeline (autoplay-safe: the
 *         handoff IS a user gesture).
 *
 * GRACEFUL DEGRADATION: unauthenticated / Supabase-unconfigured / a
 * channel error → the controller stays dormant (status 'offline', no
 * reconnect storm — one attempt per mount, none automatic).
 */

import type { Song } from '/@/shared/types/domain-types';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';
import { normalizeSong } from '/@/renderer/api/streamify/normalize';
import { STREAMIFY_SERVER_ID } from '/@/renderer/api/streamify/streamify-server-id';
import {
    getStreamifySession,
    getStreamifySupabase,
    isStreamifyAuthEnabled,
} from '/@/renderer/api/streamify/streamify-supabase';
import {
    chunkHandoffQueue,
    CONNECT_DEVICE_STORAGE_KEY,
    type ConnectMessage,
    type ConnectPresenceState,
    type ConnectTransportCommand,
    deviceLabelFromUserAgent,
    HandoffAssembler,
    HandoffHeader,
    interpolatePosition,
    mintConnectDeviceId,
    parseConnectMessage,
    sanitizePresenceForPublish,
    shouldHandleMessage,
} from '/@/renderer/features/streamify/connect/connect-protocol';
import { StreamifyConnectTransport } from '/@/renderer/features/streamify/connect/connect-transport';
import { requestVideoModeAtPosition } from '/@/renderer/features/streamify/video/hooks/use-video-mode';
import { streamifyVideoStoreBase } from '/@/renderer/features/streamify/video/streamify-video.store';
import { useAuthStore } from '/@/renderer/store';
import { usePlayerStoreBase } from '/@/renderer/store';
import { useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { logger } from '/@/renderer/utils/logger';
import { PlayerStatus, ServerType } from '/@/shared/types/types';

const PRESENCE_MIN_INTERVAL_MS = 3_000;
const PRESENCE_KEEPALIVE_MS = 15_000;
const HANDOFF_SWEEP_INTERVAL_MS = 5_000;
const RECENT_NONCE_WINDOW = 32;

export interface ConnectControllerView {
    devices: RemoteDevice[];
    requestHandoff: (targetDeviceId: string) => void;
    sendTransportCommand: (
        command: ConnectTransportCommand,
        targetDeviceId: string,
        value?: number,
    ) => void;
    status: ConnectStatus;
}

export type ConnectStatus = 'connected' | 'connecting' | 'offline';

export interface RemoteDevice {
    deviceName: string;
    interpolatedPositionSec: number;
    isPlaying: boolean;
    mode: 'audio' | 'video';
    presence: ConnectPresenceState;
}

/** Await the supabase session (the diagnostics + tests seam). */
export async function resolveConnectUserId(): Promise<null | string> {
    if (!isStreamifyAuthEnabled()) return null;
    const session = await getStreamifySession();
    return session?.user.id ?? null;
}

/**
 * The connect lane's session gate (the popover renders null when off):
 * authenticated + Streamify current + supabase configured.
 */
export function useIsConnectAvailable(): boolean {
    const serverType = useAuthStore((state) => state.currentServer?.type);
    return serverType === ServerType.STREAMIFY && isStreamifyAuthEnabled();
}

export function useStreamifyConnect(): ConnectControllerView {
    const [devices, setDevices] = useState<RemoteDevice[]>([]);
    const [status, setStatus] = useState<ConnectStatus>('offline');

    const identity = useMemo(() => readDeviceIdentity(), []);
    const transportRef = useRef<null | StreamifyConnectTransport>(null);
    const assemblerRef = useRef(new HandoffAssembler());
    const recentNoncesRef = useRef<string[]>([]);
    const lastPublishRef = useRef(0);

    // The authenticated user this controller is scoped to (null = dormant).
    const userId =
        useAuthStore((state) =>
            state.currentServer?.type === ServerType.STREAMIFY ? state.currentServer.userId : null,
        ) ?? null;
    const isConfigured = isStreamifyAuthEnabled() && userId !== null;

    /** Dedup guard: a nonce is acted on once (rebroadcasts / reconnect
     *  replays must never double-fire a command). */
    const nonceIsNew = useCallback((nonce: string) => {
        if (nonce === '') return true; // unnonced messages pass through
        if (recentNoncesRef.current.includes(nonce)) return false;
        recentNoncesRef.current.push(nonce);
        if (recentNoncesRef.current.length > RECENT_NONCE_WINDOW) {
            recentNoncesRef.current.splice(0, recentNoncesRef.current.length - RECENT_NONCE_WINDOW);
        }
        return true;
    }, []);

    /** Publish presence (throttled). */
    const publishPresence = useCallback(
        (force = false) => {
            const transport = transportRef.current;
            if (transport === null) return;
            const now = Date.now();
            if (!force && now - lastPublishRef.current < PRESENCE_MIN_INTERVAL_MS) return;
            lastPublishRef.current = now;
            void transport.publishPresence(
                readLocalPresence(identity.deviceId, identity.deviceName),
            );
        },
        [identity.deviceId, identity.deviceName],
    );

    // ------------------------------------------------------------------
    // The inbound command executor
    // ------------------------------------------------------------------
    const executeTransportCommand = useCallback(
        (command: ConnectTransportCommand, value: null | number) => {
            const store = usePlayerStoreBase.getState();
            switch (command) {
                case 'next':
                    store.mediaNext(false);
                    break;
                case 'pause':
                    store.mediaPause();
                    break;
                case 'play':
                    store.mediaPlay();
                    break;
                case 'prev':
                    store.mediaPrevious(false);
                    break;
                case 'seek':
                    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
                        store.mediaSeekToTimestamp(value);
                    }
                    break;
                case 'volume':
                    if (typeof value === 'number' && Number.isFinite(value)) {
                        store.setVolume(Math.min(100, Math.max(0, Math.round(value))));
                    }
                    break;
            }
        },
        [],
    );

    /** Load a received handoff: map ids through the local library snapshot,
     *  setQueue, seek to the exact timestamp, resume. */
    const applyHandoff = useCallback(
        (assembled: {
            currentIndex: number;
            mode: 'audio' | 'video';
            positionSec: number;
            videoIds: string[];
        }) => {
            const snapshot = currentStreamifyLibrary();
            if (snapshot === null) {
                logger.warn('streamify connect: handoff skipped — no library snapshot');
                return;
            }
            const songs: Song[] = [];
            let index = assembled.currentIndex;
            for (const videoId of assembled.videoIds) {
                const row = snapshot.songById.get(videoId);
                if (row === undefined) continue; // honest skip — never fabricate
                songs.push(normalizeSong(row, snapshot, STREAMIFY_SERVER_ID));
            }
            if (songs.length === 0) {
                logger.warn('streamify connect: handoff skipped — no known tracks');
                return;
            }
            if (index >= songs.length) index = songs.length - 1;
            usePlayerStoreBase.getState().setQueue(songs, index, 0);
            usePlayerStoreBase.getState().mediaSeekToTimestamp(assembled.positionSec);
            usePlayerStoreBase.getState().mediaPlay();
            publishPresence(true);
            if (assembled.mode === 'video') {
                // The W2-A public pipeline: resolves the counterpart + the
                // muxed stream, pauses audio, hands the overlay the exact
                // preserved timecode. A miss degrades to audio (its own
                // honest-degrade rule); autoplay is safe (user gesture).
                void requestVideoModeAtPosition(assembled.positionSec);
            }
        },
        [publishPresence],
    );

    /** Respond to an inbound handoff request: pause, then chunk the queue. */
    const respondToHandoffRequest = useCallback(
        (message: Extract<ConnectMessage, { kind: 'handoff-request' }>) => {
            const transport = transportRef.current;
            if (transport === null) return;
            const store = usePlayerStoreBase.getState();
            const queue = store.getQueue();
            const videoIds = queue.items.map((song) => song.id);
            if (videoIds.length === 0) {
                logger.warn('streamify connect: handoff request with an empty queue');
                return;
            }
            // The task contract: pause the remote (this) device first.
            store.mediaPause();
            const chunks = chunkHandoffQueue(videoIds);
            const header: HandoffHeader = {
                currentIndex: store.player.index,
                mode: streamifyVideoStoreBase.getState().mode === 'video' ? 'video' : 'audio',
                positionSec: useTimestampStoreBase.getState().timestamp,
            };
            chunks.forEach((chunk, chunkIndex) => {
                transport.send({
                    chunkIndex,
                    fromDeviceId: identity.deviceId,
                    handoff: chunkIndex === 0 ? header : null,
                    kind: 'handoff-chunk',
                    targetDeviceId: message.fromDeviceId,
                    totalChunks: chunks.length,
                    transferId: message.transferId,
                    videoIds: chunk,
                });
            });
        },
        [identity.deviceId],
    );

    // ------------------------------------------------------------------
    // Lifecycle: join the channel when configured, leave when not
    // ------------------------------------------------------------------
    useEffect(() => {
        if (!isConfigured || userId === null) {
            setStatus('offline');
            setDevices([]);
            const stale = transportRef.current;
            transportRef.current = null;
            if (stale) {
                const supabase = getStreamifySupabase();
                if (supabase) void stale.dispose(supabase);
                else void stale.leave();
            }
            return undefined;
        }

        const supabase = getStreamifySupabase();
        if (supabase === null) {
            setStatus('offline');
            return undefined;
        }

        let disposed = false;
        const transport = new StreamifyConnectTransport(supabase, userId, identity.deviceId, {
            onMessage: (raw) => {
                const message = parseConnectMessage(raw);
                if (message === null) return;
                if (!shouldHandleMessage(message, identity.deviceId)) return;
                // The nonce gate: transport + handoff-request carry one;
                // handoff-chunks are deduped by the assembler's transferId.
                const nonce = message.kind === 'handoff-chunk' ? message.transferId : message.nonce;
                if (!nonceIsNew(nonce)) return;
                if (message.kind === 'transport') {
                    executeTransportCommand(message.command, message.value);
                    publishPresence(true);
                } else if (message.kind === 'handoff-request') {
                    respondToHandoffRequest(message);
                } else if (message.kind === 'handoff-chunk') {
                    const assembled = assemblerRef.current.push(message, Date.now());
                    if (assembled !== null) {
                        applyHandoff(assembled);
                    }
                }
            },
            onPresence: (states) => {
                if (disposed) return;
                const now = Date.now();
                setDevices(
                    states
                        .filter((state) => state.deviceId !== identity.deviceId)
                        .map((presence) => ({
                            deviceName: presence.deviceName,
                            interpolatedPositionSec: interpolatePosition(presence, now),
                            isPlaying: presence.isPlaying,
                            mode: presence.mode,
                            presence,
                        })),
                );
            },
            onStatus: (next) => {
                if (disposed) return;
                setStatus(
                    next === 'connected'
                        ? 'connected'
                        : next === 'connecting'
                          ? 'connecting'
                          : 'offline',
                );
            },
        });
        transportRef.current = transport;

        void (async () => {
            try {
                await transport.connect();
                if (disposed) return;
                publishPresence(true);
            } catch (err) {
                logger.warn('streamify connect: channel failed', {
                    error: err instanceof Error ? err.message : String(err),
                });
            }
        })();

        // The stale-transfer sweeper.
        const sweeper = setInterval(() => {
            assemblerRef.current.sweep(Date.now());
        }, HANDOFF_SWEEP_INTERVAL_MS);

        return () => {
            disposed = true;
            clearInterval(sweeper);
            transportRef.current = null;
            void transport.dispose(supabase);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [isConfigured, userId, identity.deviceId]);

    // Presence publishing: track/status changes + the playing keepalive.
    useEffect(() => {
        if (status !== 'connected') return undefined;
        let lastTrackId: null | string = null;
        let lastStatus: null | PlayerStatus = null;
        const unsubscribeSong = usePlayerStoreBase.subscribe((state) => {
            const song = state.getCurrentSong();
            if ((song?.id ?? null) !== lastTrackId) {
                lastTrackId = song?.id ?? null;
                publishPresence(true);
            }
        });
        const unsubscribeStatus = usePlayerStoreBase.subscribe((state) => {
            if (state.player.status !== lastStatus) {
                lastStatus = state.player.status;
                publishPresence(true);
            }
        });
        const keepalive = setInterval(() => {
            const playing = usePlayerStoreBase.getState().player.status === PlayerStatus.PLAYING;
            if (playing) publishPresence();
        }, PRESENCE_KEEPALIVE_MS);
        return () => {
            unsubscribeSong();
            unsubscribeStatus();
            clearInterval(keepalive);
        };
    }, [status, publishPresence]);

    // ------------------------------------------------------------------
    // The outbound actions (the popover's props)
    // ------------------------------------------------------------------
    const sendTransportCommand = useCallback(
        (command: ConnectTransportCommand, targetDeviceId: string, value?: number) => {
            transportRef.current?.send({
                command,
                fromDeviceId: identity.deviceId,
                kind: 'transport',
                nonce: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
                targetDeviceId,
                value: value ?? null,
            });
        },
        [identity.deviceId],
    );

    const requestHandoff = useCallback(
        (targetDeviceId: string) => {
            const transport = transportRef.current;
            if (transport === null) return;
            const transferId = `tf-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
            const target = devices.find((d) => d.presence.deviceId === targetDeviceId);
            transport.send({
                fromDeviceId: identity.deviceId,
                kind: 'handoff-request',
                nonce: transferId,
                positionSec: target?.interpolatedPositionSec ?? 0,
                targetDeviceId,
                transferId,
            });
        },
        [devices, identity.deviceId],
    );

    return {
        devices,
        requestHandoff,
        sendTransportCommand,
        status,
    };
}

/** The device identity (stable across the session; module-level memo). */
function readDeviceIdentity(): { deviceId: string; deviceName: string } {
    try {
        let deviceId = window.localStorage.getItem(CONNECT_DEVICE_STORAGE_KEY);
        if (deviceId === null) {
            deviceId = mintConnectDeviceId();
            window.localStorage.setItem(CONNECT_DEVICE_STORAGE_KEY, deviceId);
        }
        const deviceName = deviceLabelFromUserAgent(window.navigator.userAgent);
        return { deviceId, deviceName };
    } catch {
        // Private mode / a locked-down JSDOM: an ephemeral identity — the
        // session still functions; presence just re-mints on next load.
        return { deviceId: mintConnectDeviceId(), deviceName: 'Web Browser' };
    }
}

/** Read the publishable now-playing state off the live stores. */
function readLocalPresence(deviceId: string, deviceName: string): ConnectPresenceState {
    const player = usePlayerStoreBase.getState();
    const song = player.getCurrentSong();
    const isStreamify = useAuthStore.getState().currentServer?.type === ServerType.STREAMIFY;
    const currentTrack =
        song && isStreamify
            ? {
                  artist: song.artistName ?? null,
                  durationSec: song.duration > 0 ? song.duration / 1000 : null,
                  thumbnailUrl: song.imageUrl ?? null,
                  title: song.name ?? '',
                  videoId: song.id,
              }
            : null;
    return sanitizePresenceForPublish({
        currentTrack,
        deviceId,
        deviceName,
        isPlaying: player.player.status === PlayerStatus.PLAYING,
        // The W2-A mode: the video store owns the live truth (audio is the
        // default and the overlay's video sessions publish 'video').
        mode: streamifyVideoStoreBase.getState().mode === 'video' ? 'video' : 'audio',
        positionSec: useTimestampStoreBase.getState().timestamp,
        updatedAt: Date.now(),
    }) as ConnectPresenceState;
}
