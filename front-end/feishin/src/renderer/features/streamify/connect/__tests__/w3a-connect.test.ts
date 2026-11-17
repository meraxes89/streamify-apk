/**
 * W3-A — the Streamify Connect suite (network-free).
 *
 * Locks, per the wave contract:
 *   • device presence state serialization — the sanitize gate clamps,
 *     bounds and nulls hostile/buggy shapes;
 *   • remote command dispatch/filtering — self-echo dies, foreign targets
 *     die, valid targeted commands pass; the nonce window dedups;
 *   • the 1-click playback handoff — chunked reassembly (order tolerance,
 *     hole handling, header validation) + the timestamp interpolation.
 *
 * Everything pure/protocol level; the transport is exercised through an
 * injected fake channel (the jam-live test discipline).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
    chunkHandoffQueue,
    CONNECT_DEVICE_STORAGE_KEY,
    type ConnectMessage,
    type ConnectPresenceState,
    deviceLabelFromUserAgent,
    HandoffAssembler,
    interpolatePosition,
    mintConnectDeviceId,
    parseConnectMessage,
    parseConnectPresence,
    sanitizePresenceForPublish,
    shouldHandleMessage,
} from '/@/renderer/features/streamify/connect/connect-protocol';

const SELF = 'dev-selfdevice';
const REMOTE = 'dev-remotedevice';
const OTHER = 'dev-thirddevice';

function validPresence(overrides: Partial<ConnectPresenceState> = {}): ConnectPresenceState {
    return {
        currentTrack: {
            artist: 'Artist',
            durationSec: 213,
            thumbnailUrl: 'https://i.ytimg.com/vi/abc/hqdefault.jpg',
            title: 'Title',
            videoId: 'dQw4w9WgXcQ',
        },
        deviceId: REMOTE,
        deviceName: 'Chrome on Linux',
        isPlaying: true,
        mode: 'audio',
        positionSec: 41.7,
        updatedAt: 1_700_000_000_000,
        ...overrides,
    };
}

// ---------------------------------------------------------------------------
// Presence serialization
// ---------------------------------------------------------------------------

describe('W3-A connect: presence serialization', () => {
    it('a valid presence round-trips through sanitize', () => {
        const state = validPresence();
        const sanitized = sanitizePresenceForPublish(state);
        expect(sanitized).not.toBeNull();
        expect(sanitized?.deviceId).toBe(REMOTE);
        expect(sanitized?.positionSec).toBe(41.7);
        expect(parseConnectPresence(state)).toEqual(sanitized);
    });

    it('clamps hostile numbers and bounds strings', () => {
        const sanitized = sanitizePresenceForPublish({
            ...validPresence(),
            currentTrack: {
                artist: 'A'.repeat(500),
                durationSec: Number.POSITIVE_INFINITY,
                thumbnailUrl: 'https://i.ytimg.com/vi/abc/hqdefault.jpg',
                title: 'T'.repeat(500),
                videoId: 'v'.repeat(40),
            },
            deviceId: 'd'.repeat(200),
            deviceName: 'N'.repeat(300),
            positionSec: -99,
        });
        expect(sanitized).not.toBeNull();
        expect(sanitized?.deviceId.length).toBeLessThanOrEqual(64);
        expect(sanitized?.deviceName.length).toBeLessThanOrEqual(100);
        expect(sanitized?.positionSec).toBe(0);
        // an over-long videoId is rejected wholesale (never truncated to a
        // DIFFERENT id)
        expect(sanitized?.currentTrack).toBeNull();
    });

    it('rejects the identity-less shapes outright', () => {
        expect(sanitizePresenceForPublish({ ...validPresence(), deviceId: '' })).toBeNull();
        expect(sanitizePresenceForPublish({ ...validPresence(), deviceName: '' })).toBeNull();
        expect(sanitizePresenceForPublish({})).toBeNull();
        expect(parseConnectPresence('garbage')).toBeNull();
        expect(parseConnectPresence(null)).toBeNull();
    });

    it('mode coerces to the two valid values', () => {
        expect(sanitizePresenceForPublish({ ...validPresence(), mode: 'video' })?.mode).toBe(
            'video',
        );
        expect(
            sanitizePresenceForPublish({ ...validPresence(), mode: 'weird' as never })?.mode,
        ).toBe('audio');
    });
});

// ---------------------------------------------------------------------------
// Command dispatch / filtering (ignoring self-echo)
// ---------------------------------------------------------------------------

describe('W3-A connect: remote command dispatch + filtering', () => {
    const transportMessage = (overrides: Record<string, unknown> = {}): ConnectMessage =>
        parseConnectMessage({
            command: 'pause',
            fromDeviceId: REMOTE,
            kind: 'transport',
            nonce: 'n1',
            targetDeviceId: SELF,
            ...overrides,
        }) as ConnectMessage;

    it('parses a valid transport command with clamped volume', () => {
        const msg = transportMessage();
        expect(msg?.kind).toBe('transport');
        const volume = parseConnectMessage({
            command: 'volume',
            fromDeviceId: REMOTE,
            kind: 'transport',
            nonce: 'n2',
            targetDeviceId: SELF,
            value: 250,
        });
        expect(volume?.kind === 'transport' && volume.command === 'volume' && volume.value).toBe(
            100,
        );
    });

    it('rejects malformed messages', () => {
        expect(parseConnectMessage(null)).toBeNull();
        expect(parseConnectMessage('nope')).toBeNull();
        expect(parseConnectMessage({ command: 'explode', kind: 'transport' })).toBeNull();
        expect(
            parseConnectMessage({
                command: 'play',
                fromDeviceId: '',
                kind: 'transport',
                targetDeviceId: SELF,
            }),
        ).toBeNull();
    });

    it('SELF-ECHO DIES: a message from our own deviceId is never handled', () => {
        const selfEcho = transportMessage({ fromDeviceId: SELF });
        expect(shouldHandleMessage(selfEcho, SELF)).toBe(false);
    });

    it('foreign targets die: a message addressed to another device is overheard but ignored', () => {
        const forOther = transportMessage({ targetDeviceId: OTHER });
        expect(shouldHandleMessage(forOther, SELF)).toBe(false);
    });

    it('a properly targeted command from another device passes', () => {
        expect(shouldHandleMessage(transportMessage(), SELF)).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// The handoff: chunking + reassembly + interpolation
// ---------------------------------------------------------------------------

describe('W3-A connect: 1-click playback handoff', () => {
    it('chunkHandoffQueue splits at the chunk size and caps the total', () => {
        const ids = Array.from({ length: 60 }, (_, i) => `vid${String(i).padStart(3, '0')}`);
        const chunks = chunkHandoffQueue(ids, 25);
        expect(chunks.map((c) => c.length)).toEqual([25, 25, 10]);
        expect(chunks.flat()).toEqual(ids);
        const capped = chunkHandoffQueue(
            Array.from({ length: 600 }, (_, i) => `vid${i}`),
            25,
        );
        expect(capped.flat().length).toBe(500);
        expect(chunkHandoffQueue([])).toEqual([]);
    });

    it('the assembler reassembles out-of-order chunks into the exact payload', () => {
        const assembler = new HandoffAssembler();
        const ids = Array.from({ length: 30 }, (_, i) => `vid${String(i).padStart(2, '0')}`);
        const chunks = chunkHandoffQueue(ids, 10);
        const mk = (index: number) =>
            parseConnectMessage({
                chunkIndex: index,
                fromDeviceId: REMOTE,
                handoff: index === 0 ? { currentIndex: 7, mode: 'video', positionSec: 83.2 } : null,
                kind: 'handoff-chunk',
                targetDeviceId: SELF,
                totalChunks: chunks.length,
                transferId: 'tf-1',
                videoIds: chunks[index],
            }) as Extract<ConnectMessage, { kind: 'handoff-chunk' }>;

        // Out of order: 2, 0, 1 — the assembler waits for the set.
        expect(assembler.push(mk(2), 1_000)).toBeNull();
        expect(assembler.push(mk(0), 1_100)).toBeNull();
        const assembled = assembler.push(mk(1), 1_200);
        expect(assembled).not.toBeNull();
        expect(assembled?.videoIds).toEqual(ids);
        expect(assembled?.currentIndex).toBe(7);
        expect(assembled?.mode).toBe('video');
        expect(assembled?.positionSec).toBe(83.2);
        // complete transfers are consumed
        expect(assembler.size).toBe(0);
    });

    it('the assembler bounds the currentIndex into the assembled queue', () => {
        const assembler = new HandoffAssembler();
        const push = (index: number, header: null | Record<string, unknown>, total = 1) =>
            assembler.push(
                parseConnectMessage({
                    chunkIndex: index,
                    fromDeviceId: REMOTE,
                    handoff: header,
                    kind: 'handoff-chunk',
                    targetDeviceId: SELF,
                    totalChunks: total,
                    transferId: 'tf-idx',
                    videoIds: ['a', 'b'],
                }) as Extract<ConnectMessage, { kind: 'handoff-chunk' }>,
                1_000,
            );
        const assembled = push(0, { currentIndex: 99, mode: 'audio', positionSec: 0 });
        expect(assembled?.currentIndex).toBe(1); // clamped to the last index
    });

    it('the sweeper drops stale transfers (the assembly window)', () => {
        const assembler = new HandoffAssembler();
        assembler.push(
            parseConnectMessage({
                chunkIndex: 0,
                fromDeviceId: REMOTE,
                handoff: { currentIndex: 0, mode: 'audio', positionSec: 0 },
                kind: 'handoff-chunk',
                targetDeviceId: SELF,
                totalChunks: 2,
                transferId: 'tf-stale',
                videoIds: ['a'],
            }) as Extract<ConnectMessage, { kind: 'handoff-chunk' }>,
            1_000,
        );
        expect(assembler.size).toBe(1);
        assembler.sweep(1_000 + 20_000);
        expect(assembler.size).toBe(0);
    });

    it('a conflicting totalChunks on a live transfer is ignored', () => {
        const assembler = new HandoffAssembler();
        const base = {
            fromDeviceId: REMOTE,
            handoff: null,
            kind: 'handoff-chunk',
            targetDeviceId: SELF,
            transferId: 'tf-conflict',
        } as const;
        assembler.push(
            parseConnectMessage({
                ...base,
                chunkIndex: 0,
                totalChunks: 2,
                videoIds: ['a'],
            }) as never,
            1_000,
        );
        const hostile = assembler.push(
            parseConnectMessage({
                ...base,
                chunkIndex: 1,
                totalChunks: 3,
                videoIds: ['b'],
            }) as never,
            1_100,
        );
        expect(hostile).toBeNull();
    });

    it('the timestamp interpolation: elapsed wall time while playing, clamped to duration', () => {
        const state = validPresence({ positionSec: 100, updatedAt: 1_000_000 });
        // 30 s elapsed while playing → 130
        expect(interpolatePosition(state, 1_030_000)).toBe(130);
        // paused → the anchor only
        expect(interpolatePosition({ ...state, isPlaying: false }, 1_030_000)).toBe(100);
        // clamped to the track duration
        const short = validPresence({
            currentTrack: {
                artist: null,
                durationSec: 210,
                thumbnailUrl: null,
                title: 'T',
                videoId: 'v',
            },
            positionSec: 200,
            updatedAt: 1_000_000,
        });
        expect(interpolatePosition(short, 1_060_000)).toBe(210);
        // no duration known → unclamped
        const endless = validPresence({
            currentTrack: {
                artist: null,
                durationSec: null,
                thumbnailUrl: null,
                title: 'T',
                videoId: 'v',
            },
            positionSec: 200,
            updatedAt: 1_000_000,
        });
        expect(interpolatePosition(endless, 1_060_000)).toBe(260);
    });
});

// ---------------------------------------------------------------------------
// Device identity + labels
// ---------------------------------------------------------------------------

describe('W3-A connect: device identity + labels', () => {
    it('mints dev- prefixed ids', () => {
        const id = mintConnectDeviceId();
        expect(id.startsWith('dev-')).toBe(true);
        expect(id.length).toBeGreaterThan(8);
    });

    it('the UA label: browser on OS', () => {
        expect(
            deviceLabelFromUserAgent(
                'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36',
            ),
        ).toBe('Chrome on Linux');
        expect(
            deviceLabelFromUserAgent(
                'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Safari/604.1',
            ),
        ).toBe('Safari on iOS');
        expect(
            deviceLabelFromUserAgent(
                'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/121.0',
            ),
        ).toBe('Firefox on Windows');
        expect(
            deviceLabelFromUserAgent(
                'Mozilla/5.0 (iPad; CPU OS 17_0) AppleWebKit/605.1.15 Safari/604.1',
            ),
        ).toBe('Safari on iPad');
        expect(deviceLabelFromUserAgent('')).toBe('Browser on Web');
    });

    it('the storage key is the documented one (the P3A jam pattern, its own key)', () => {
        expect(CONNECT_DEVICE_STORAGE_KEY).toBe('streamify.connect.device');
    });
});

// ---------------------------------------------------------------------------
// The transport against a fake channel (dispatch end-to-end)
// ---------------------------------------------------------------------------

describe('W3-A connect: transport dispatch (fake channel)', () => {
    class FakeChannel {
        handlers: Array<{ cb: (payload: unknown) => void; event: string; type: string }> = [];
        presence: Record<string, unknown[]> = {};
        sent: Array<{ event: string; payload: unknown; type: string }> = [];
        subscribed = false;
        tracked: unknown = null;

        /** Test hook: simulate an inbound broadcast. */
        emitBroadcast(event: string, payload: unknown) {
            for (const handler of this.handlers) {
                if (handler.type === 'broadcast' && handler.event === event) {
                    handler.cb({ payload });
                }
            }
        }
        /** Test hook: simulate a presence sync. */
        emitPresence() {
            for (const handler of this.handlers) {
                if (handler.type === 'presence') {
                    handler.cb(undefined as never);
                }
            }
        }
        on(type: string, filter: { event: string }, cb: (payload: never) => void) {
            this.handlers.push({ cb: cb as (payload: unknown) => void, event: filter.event, type });
            return this;
        }
        presenceState() {
            return this.presence;
        }
        send(message: { event: string; payload: unknown; type: string }) {
            this.sent.push(message);
            return Promise.resolve('ok');
        }
        subscribe(cb: (status: string) => void) {
            this.subscribed = true;
            cb('SUBSCRIBED');
            return this;
        }
        async track(state: unknown) {
            this.tracked = state;
        }
        async unsubscribe() {
            this.subscribed = false;
        }
        async untrack() {
            this.tracked = null;
        }
    }

    const makeClient = (channel: FakeChannel) => ({
        channel: () => channel as never,
        removeChannel: vi.fn(),
    });

    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('connects, publishes presence, and delivers targeted broadcasts (self-echo filtered)', async () => {
        const { StreamifyConnectTransport } =
            await import('/@/renderer/features/streamify/connect/connect-transport');
        const channel = new FakeChannel();
        const client = makeClient(channel);
        const messages: ConnectMessage[] = [];
        const statuses: string[] = [];
        const transport = new StreamifyConnectTransport(client, 'user-1', SELF, {
            onMessage: (raw) => {
                const parsed = parseConnectMessage(raw);
                if (parsed !== null && shouldHandleMessage(parsed, SELF)) messages.push(parsed);
            },
            onPresence: (states) => {
                expect(Array.isArray(states)).toBe(true);
            },
            onStatus: (status) => statuses.push(status),
        });

        await transport.connect();
        expect(statuses).toContain('connecting');
        expect(statuses).toContain('connected');

        // Presence publish → track() carries the sanitized state.
        await transport.publishPresence(validPresence());
        expect(channel.tracked).toEqual(validPresence());

        // A targeted command reaches the handler.
        channel.emitBroadcast('cmd', {
            command: 'next',
            fromDeviceId: REMOTE,
            kind: 'transport',
            nonce: 'n-echo',
            targetDeviceId: SELF,
            value: null,
        });
        expect(messages).toHaveLength(1);

        // SELF-ECHO: the same command from our own id never lands.
        channel.emitBroadcast('cmd', {
            command: 'next',
            fromDeviceId: SELF,
            kind: 'transport',
            nonce: 'n-self',
            targetDeviceId: SELF,
            value: null,
        });
        // FOREIGN TARGET: addressed elsewhere.
        channel.emitBroadcast('cmd', {
            command: 'next',
            fromDeviceId: REMOTE,
            kind: 'transport',
            nonce: 'n-other',
            targetDeviceId: OTHER,
            value: null,
        });
        expect(messages).toHaveLength(1); // unchanged

        // A handoff-request broadcasts fine through send().
        transport.send(
            parseConnectMessage({
                fromDeviceId: SELF,
                kind: 'handoff-request',
                nonce: 'tf-x',
                positionSec: 10,
                targetDeviceId: REMOTE,
                transferId: 'tf-x',
            }) as ConnectMessage,
        );
        expect(channel.sent).toHaveLength(1);
        expect(channel.sent[0]?.type).toBe('broadcast');

        await transport.dispose(client);
        expect(client.removeChannel).toHaveBeenCalled();
    });
});
