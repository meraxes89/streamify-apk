/**
 * jam-session-controller tests (P4B) - the host/follower lifecycle against
 * a fake transport + fake player bridge, network-free by construction.
 *
 * Covers the DoD battery: room creation, join + journal bootstrap, queue
 * mutations minting the right ops, host-to-follower playback delta sync,
 * drift correction, permission-gated requests, outbox retry, and the
 * leave/desync cleanup.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

import type { JamJournalRow } from '../jam-live/jam-live-wire';

import {
    JAM_CONTROL_EVENT,
    JAM_META_EVENT,
    JAM_REACTION_EVENT,
    JAM_SYNC_EVENT,
    type JamTrackPayload,
} from '../jam-live/jam-live-protocol';
import {
    type JamOp,
    jamOpToBytes,
    OP_ADD,
    OP_REMOVE,
    OP_REORDER,
    OP_VOTE,
    resetOpMintForTests,
} from '../jam-live/jam-live-wire';
import {
    type JamPlayerBridge,
    type JamPlayerEvent,
    type JamTransportFactory,
    type JamTransportLike,
    StreamifyJamSessionController,
} from '../jam-live/jam-session-controller';

import { useStreamifyJamStoreBase } from '/@/renderer/store/streamify-jam.store';
import {
    resetStreamifySettingsStoreForTests,
    useStreamifySettingsStoreBase,
} from '/@/renderer/store/streamify-settings.store';

class FakeBridge implements JamPlayerBridge {
    applied: Array<{ positionMs: number; trackId: string }> = [];
    paused = false;
    played = false;
    positionMs = 0;
    seeks: number[] = [];
    track: JamTrackPayload | null = null;

    private handler: ((event: JamPlayerEvent) => void) | null = null;

    applyTrack(track: JamTrackPayload, positionMs: number): void {
        this.applied.push({ positionMs, trackId: track.id });
        this.track = track;
        this.played = true;
    }

    emit(event: JamPlayerEvent): void {
        this.handler?.(event);
    }

    getCurrentTrack(): JamTrackPayload | null {
        return this.track;
    }

    getPositionMs(): number {
        return this.positionMs;
    }

    isPlaying(): boolean {
        return this.played && !this.paused;
    }

    next(): void {
        this.emit({ track: this.otherTrack(), type: 'track' });
    }

    pause(): void {
        this.paused = true;
        this.emit({ isPlaying: false, type: 'status' });
    }

    play(): void {
        this.played = true;
        this.paused = false;
        this.emit({ isPlaying: true, type: 'status' });
    }

    seekTo(positionMs: number): void {
        this.seeks.push(positionMs);
        this.positionMs = positionMs;
    }

    subscribe(handler: (event: JamPlayerEvent) => void): () => void {
        this.handler = handler;
        return () => {
            this.handler = null;
        };
    }

    private otherTrack(): JamTrackPayload {
        return { ...fakeTrack(), id: 't-next', name: 'Next Track' };
    }
}

class FakeTransport implements JamTransportLike {
    broadcastLog: Array<[string, unknown]> = [];
    connectCalls = 0;
    heartbeatAnswers: Array<'DEMOTED' | 'HOST' | null> = [];
    heartbeats: Array<{ monoMs: number; positionMs: number }> = [];
    inserted: null | { hostUserId: null | string } = null;
    journal: JamJournalRow[] = [];
    leaveCalls = 0;
    opsSent: JamOp[] = [];
    presenceTouches = 0;
    sendFailures = 0;
    sessionExists = true;

    private events!: Parameters<JamTransportFactory>[1];

    bind(events: Parameters<JamTransportFactory>[1]): void {
        this.events = events;
    }

    broadcastControl(message: unknown): void {
        this.broadcastLog.push([JAM_CONTROL_EVENT, message]);
    }

    broadcastMeta(message: unknown): void {
        this.broadcastLog.push([JAM_META_EVENT, message]);
    }

    // W3-B: the ephemeral reaction lane (recorded, never journaled).
    broadcastReaction(message: unknown): void {
        this.broadcastLog.push([JAM_REACTION_EVENT, message]);
    }

    broadcastSync(message: unknown): void {
        this.broadcastLog.push([JAM_SYNC_EVENT, message]);
    }

    async connect(): Promise<void> {
        this.connectCalls += 1;
        this.events.onStatus('live');
    }

    deliverBroadcast(event: string, raw: unknown): void {
        this.events.onBroadcast(event, raw);
    }

    // Test surface: feed events in.
    deliverOps(ops: JamOp[]): void {
        this.events.onOps(
            ops.map((op) => {
                const bytes = jamOpToBytes(op);
                const view = new DataView(bytes.buffer);
                return {
                    frac_bits: view.getBigUint64(24, true).toString(10),
                    op_id: op.opId.toString(10),
                    op_type: op.opType,
                    policy_flags: op.policyFlags,
                    sender_nonce: view.getUint32(8, true).toString(10),
                    target_add_op_id: op.targetAddOpId.toString(10),
                    track_cad_id: op.trackCadId.toString(10),
                };
            }),
        );
    }

    deliverPresence(entries: Array<{ deviceId: string; userId?: null | string }>): void {
        this.events.onPresence(
            entries.map((entry) => ({
                deviceId: entry.deviceId,
                lastSeenMs: 1_000,
                name: null,
                userId: entry.userId ?? null,
            })),
        );
    }

    async fetchJournal(): Promise<JamJournalRow[]> {
        return [...this.journal];
    }

    async fetchSessionRow() {
        if (!this.sessionExists) return null;
        return {
            hostEpoch: 7,
            hostUserId: 'host-user',
            id: 'session-1',
            participantIds: ['host-user'],
            sessionCode: 'ABC234',
        };
    }

    async heartbeat(_sessionId: string, positionMs: number, monoMs: number) {
        this.heartbeats.push({ monoMs, positionMs });
        return this.heartbeatAnswers.shift() ?? null;
    }

    async insertSessionRow(hostUserId: null | string) {
        this.inserted = { hostUserId };
        return {
            hostEpoch: 0,
            hostUserId,
            id: 'session-1',
            participantIds: hostUserId ? [hostUserId] : [],
            sessionCode: 'ABC234',
        };
    }

    async leave(): Promise<void> {
        this.leaveCalls += 1;
        this.events.onStatus('closed');
    }

    async sendOp(op: JamOp) {
        if (this.sendFailures > 0) {
            this.sendFailures -= 1;
            return { error: 'simulated failure', ok: false };
        }
        this.opsSent.push(op);
        return { ok: true };
    }

    async touchPresence(): Promise<void> {
        this.presenceTouches += 1;
    }
}

const fakeTrack = (): JamTrackPayload =>
    ({
        _itemType: 'song',
        _serverId: 'streamify',
        _serverType: 'streamify',
        album: null,
        albumArtistName: 'Artist',
        albumArtists: [],
        albumId: '',
        artistName: 'Artist',
        artists: [],
        bitDepth: null,
        bitRate: 128,
        blurHash: null,
        bpm: null,
        channels: null,
        codec: null,
        comment: null,
        compilation: null,
        container: null,
        createdAt: '',
        date: null,
        discNumber: 0,
        discSubtitle: null,
        duration: 200,
        explicitStatus: null,
        folderId: null,
        gain: null,
        genres: [],
        id: 't1',
        imageId: null,
        imageUrl: null,
        lastPlayedAt: null,
        libraryId: null,
        libraryName: null,
        lyrics: null,
        mbzAlbumId: null,
        mbzAlbumType: null,
        mbzRecordingId: null,
        mbzReleaseGroupId: null,
        mbzTrackId: null,
        missing: null,
        name: 'Track One',
        originalDate: null,
        originalYear: null,
        participants: null,
        path: null,
        peak: null,
        playCount: 0,
        releaseDate: null,
        size: null,
        updatedAt: '',
        year: null,
    }) as unknown as JamTrackPayload;

const makeController = (opts?: {
    bridge?: FakeBridge;
    transport?: FakeTransport;
    userId?: null | string;
}) => {
    const bridge = opts?.bridge ?? new FakeBridge();
    const transport = opts?.transport ?? new FakeTransport();
    const factory: JamTransportFactory = (code, events) => {
        expect(code).toMatch(/^[A-HJKMNP-Z2-9]{6}$/);
        transport.bind(events);
        return transport;
    };
    const controller = new StreamifyJamSessionController({
        bridge,
        deviceId: 'dev-test',
        now: () => 50_000,
        transportFactory: factory,
        userId: opts?.userId ?? 'host-user',
    });
    return { bridge, controller, transport };
};

const rowOf = (op: JamOp): JamJournalRow => {
    const bytes = jamOpToBytes(op);
    const view = new DataView(bytes.buffer);
    return {
        frac_bits: view.getBigUint64(24, true).toString(10),
        op_id: op.opId.toString(10),
        op_type: op.opType,
        policy_flags: op.policyFlags,
        sender_nonce: view.getUint32(8, true).toString(10),
        target_add_op_id: op.targetAddOpId.toString(10),
        track_cad_id: op.trackCadId.toString(10),
    };
};

describe('jam-session-controller', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        resetOpMintForTests(10_000n);
        resetStreamifySettingsStoreForTests();
        useStreamifyJamStoreBase.getState().reset();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('creates a room as host (row insert, connect, live status)', async () => {
        const { controller, transport } = makeController();
        const error = await controller.createRoom();
        expect(error).toBeNull();
        expect(transport.inserted).toEqual({ hostUserId: 'host-user' });
        expect(transport.connectCalls).toBe(1);
        const state = useStreamifyJamStoreBase.getState();
        expect(state.status).toBe('live');
        expect(state.sessionIsHost).toBe(true);
        expect(state.session?.roomCode).toMatch(/^[A-HJKMNP-Z2-9]{6}$/);
    });

    it('rejects joins for unknown rooms', async () => {
        const transport = new FakeTransport();
        transport.sessionExists = false;
        const { controller } = makeController({ transport, userId: 'follower' });
        const error = await controller.joinRoom('abc234');
        expect(error?.code).toBe('session-not-found');
        expect(useStreamifyJamStoreBase.getState().status).toBe('error');
    });

    it('rejects invalid codes', async () => {
        const { controller } = makeController({ userId: 'follower' });
        const error = await controller.joinRoom('nope!!');
        expect(error?.code).toBe('invalid-code');
    });

    it('joins as follower, bootstraps the journal, and renders the queue', async () => {
        const transport = new FakeTransport();
        // A journal someone else wrote: add, add, remove the first.
        const add1 = seededOp(1n << 16n, OP_ADD, 100n, 0.5, 0n);
        const add2 = seededOp(2n << 16n, OP_ADD, 200n, 1.5, 0n);
        const remove1 = seededOp(3n << 16n, OP_REMOVE, 100n, 0, 1n << 16n);
        transport.journal = [rowOf(add1), rowOf(add2), rowOf(remove1)];
        const { controller } = makeController({ transport, userId: 'follower' });

        const error = await controller.joinRoom('ABC234');
        expect(error).toBeNull();
        const state = useStreamifyJamStoreBase.getState();
        expect(state.status).toBe('live');
        expect(state.sessionIsHost).toBe(false);
        expect(state.queue).toHaveLength(1);
        expect(state.queue[0].cadId).toBe('200');
    });

    it('mints Add ops at the tail with cad + meta broadcast, Remove/Reorder/Vote against the target', async () => {
        const { bridge, controller, transport } = makeController();
        await controller.createRoom();

        controller.addTrack(fakeTrack());
        expect(transport.opsSent).toHaveLength(1);
        const add = transport.opsSent[0];
        expect(add.opType).toBe(OP_ADD);
        expect(add.fracIndex).toBe(0.5); // first tail anchor
        expect(transport.broadcastLog.some(([event]) => event === JAM_META_EVENT)).toBe(true);

        const addOpId = add.opId;
        controller.upvoteTrack(addOpId.toString(10));
        controller.moveTrack(addOpId.toString(10), 0);
        controller.removeTrack(addOpId.toString(10));
        expect(transport.opsSent.map((op) => op.opType)).toEqual([
            OP_ADD,
            OP_VOTE,
            OP_REORDER,
            OP_REMOVE,
        ]);
        for (const op of transport.opsSent.slice(1)) {
            expect(op.targetAddOpId).toBe(addOpId);
        }
        // local-first: queue went up then came back down
        expect(useStreamifyJamStoreBase.getState().queue).toHaveLength(0);
        void bridge;
    });

    it('retries failed sends from the outbox on the next tick (host)', async () => {
        const { controller, transport } = makeController();
        await controller.createRoom();
        transport.sendFailures = 1;

        controller.addTrack(fakeTrack());
        expect(transport.opsSent).toHaveLength(0); // send failed
        expect(useStreamifyJamStoreBase.getState().queue).toHaveLength(1); // local-first kept

        await vi.advanceTimersByTimeAsync(5_500); // host tick flushes the outbox
        expect(transport.opsSent).toHaveLength(1);
        expect(transport.opsSent[0].opType).toBe(OP_ADD);
    });

    it('host broadcasts track/play/pause/seek deltas from player events', async () => {
        const { bridge, controller, transport } = makeController();
        await controller.createRoom();

        bridge.emit({ track: fakeTrack(), type: 'track' });
        bridge.play();
        bridge.emit({ positionMs: 12_345, type: 'seek' });
        bridge.pause();

        const syncs = transport.broadcastLog
            .filter(([event]) => event === JAM_SYNC_EVENT)
            .map(([, payload]) => payload as { kind: string; positionMs: number });
        expect(syncs.map((s) => s.kind)).toEqual(['track', 'play', 'seek', 'pause']);
        expect(syncs[2].positionMs).toBe(12_345);
        expect(syncs[0].kind).toBe('track');
    });

    it('follower applies host deltas: track, play, pause, and hard-drift seek', async () => {
        const transport = new FakeTransport();
        const { bridge, controller } = makeController({ transport, userId: 'follower' });
        await controller.joinRoom('ABC234');

        transport.deliverBroadcast(JAM_SYNC_EVENT, {
            emittedAt: 50_000,
            hostEpoch: 7,
            hostOnlySkipSeek: true,
            hostUserId: 'host-user',
            kind: 'track',
            positionMs: 10_000,
            track: fakeTrack(),
            v: 1,
        });
        expect(bridge.applied).toEqual([{ positionMs: 10_000, trackId: 't1' }]);
        expect(useStreamifyJamStoreBase.getState().hostOnlySkipSeek).toBe(true);

        transport.deliverBroadcast(JAM_SYNC_EVENT, {
            emittedAt: 50_000,
            hostEpoch: 7,
            hostUserId: 'host-user',
            kind: 'play',
            positionMs: 10_000,
            v: 1,
        });
        expect(bridge.played).toBe(true);

        // Hard drift: local is 5 s behind the host estimate.
        bridge.positionMs = 10_000;
        transport.deliverBroadcast(JAM_SYNC_EVENT, {
            emittedAt: 50_000,
            hostEpoch: 7,
            hostUserId: 'host-user',
            kind: 'tick',
            positionMs: 15_000,
            v: 1,
        });
        expect(bridge.seeks).toEqual([15_000]);

        transport.deliverBroadcast(JAM_SYNC_EVENT, {
            emittedAt: 50_000,
            hostEpoch: 7,
            hostUserId: 'host-user',
            kind: 'pause',
            positionMs: 15_000,
            v: 1,
        });
        expect(bridge.paused).toBe(true);
    });

    it('follower stale-epoch deltas are ignored (fencing)', async () => {
        const transport = new FakeTransport();
        const { bridge, controller } = makeController({ transport, userId: 'follower' });
        await controller.joinRoom('ABC234'); // session row carries hostEpoch 7

        bridge.positionMs = 0;
        transport.deliverBroadcast(JAM_SYNC_EVENT, {
            emittedAt: 50_000,
            hostEpoch: 3,
            hostUserId: 'host-user',
            kind: 'tick',
            positionMs: 99_000,
            v: 1,
        });
        expect(bridge.seeks).toEqual([]);
    });

    it('host applies follower requests only when permissions allow', async () => {
        const hostBridge = new FakeBridge();
        const {
            bridge: hostAsBridge,
            controller,
            transport,
        } = makeController({
            bridge: hostBridge,
        });
        void hostAsBridge;
        await controller.createRoom();

        // Open permissions: seek request applies.
        transport.deliverBroadcast(JAM_CONTROL_EVENT, {
            kind: 'request',
            positionMs: 42_000,
            request: 'seek',
            v: 1,
        });
        expect(hostBridge.seeks).toEqual([42_000]);

        // Host-only mode: skip request ignored.
        useStreamifySettingsStoreBase.getState().set({ jamHostOnlySkipSeek: true });
        const before = hostBridge.applied.length;
        transport.deliverBroadcast(JAM_CONTROL_EVENT, {
            kind: 'request',
            positionMs: 1_000,
            request: 'skip',
            v: 1,
        });
        expect(hostBridge.applied.length).toBe(before);
    });

    it('follower advisory requests broadcast on the control lane', async () => {
        const transport = new FakeTransport();
        const { controller } = makeController({ transport, userId: 'follower' });
        await controller.joinRoom('ABC234');

        controller.requestControl('skip');
        expect(transport.broadcastLog).toContainEqual([
            JAM_CONTROL_EVENT,
            { kind: 'request', positionMs: 0, request: 'skip', v: 1 },
        ]);
    });

    it('host answers presence joins with a queue snapshot', async () => {
        const { controller, transport } = makeController();
        await controller.createRoom();
        controller.addTrack(fakeTrack());

        transport.deliverPresence([
            { deviceId: 'dev-test' },
            { deviceId: 'dev-other', userId: 'user-2' },
        ]);
        const snapshot = transport.broadcastLog.find(
            ([event, payload]) =>
                event === JAM_CONTROL_EVENT && (payload as { kind: string }).kind === 'snapshot',
        );
        expect(snapshot).toBeTruthy();
        const payload = snapshot![1] as {
            ordered: Array<{ cadId: string }>;
            playback: { isPlaying: boolean };
        };
        expect(payload.ordered).toHaveLength(1);
        expect(payload.playback.isPlaying).toBe(false);
        expect(
            useStreamifyJamStoreBase.getState().participants.some((p) => p.isHost && p.isSelf),
        ).toBe(true);
    });

    it('snapshot fills metadata and starts a fresh follower when the host is playing', async () => {
        const transport = new FakeTransport();
        // The journal carries the Add (the snapshot only fills metadata and
        // playback state - ops never ride snapshots, by design).
        transport.journal = [rowOf(seededOp(1n << 16n, OP_ADD, 100n, 0.5, 0n))];
        const { bridge, controller } = makeController({ transport, userId: 'follower' });
        await controller.joinRoom('ABC234');
        expect(useStreamifyJamStoreBase.getState().queue).toHaveLength(1);
        expect(useStreamifyJamStoreBase.getState().queue[0].track).toBeNull(); // meta pending

        transport.deliverBroadcast(JAM_CONTROL_EVENT, {
            hostOnlySkipSeek: false,
            kind: 'snapshot',
            ordered: [{ addOpId: '1', cadId: '100', frac: 0.5, track: fakeTrack() }],
            playback: {
                currentAddOpId: '1',
                currentTrack: fakeTrack(),
                isPlaying: true,
                positionMs: 3_000,
            },
            v: 1,
        });
        expect(bridge.applied).toEqual([{ positionMs: 3_000, trackId: 't1' }]);
        expect(useStreamifyJamStoreBase.getState().queue[0].track?.id).toBe('t1');
    });

    it('host heartbeat demotion latches the store flag', async () => {
        const transport = new FakeTransport();
        transport.heartbeatAnswers = ['DEMOTED'];
        const { controller } = makeController({ transport });
        await controller.createRoom();

        await vi.advanceTimersByTimeAsync(16_000);
        expect(transport.heartbeats.length).toBeGreaterThanOrEqual(1);
        expect(useStreamifyJamStoreBase.getState().hostDemoted).toBe(true);
    });

    it('leave cleans up (transport left, state reset, desync latched)', async () => {
        const { controller, transport } = makeController();
        await controller.createRoom();
        controller.addTrack(fakeTrack());

        await controller.leave();
        expect(transport.leaveCalls).toBe(1);
        const state = useStreamifyJamStoreBase.getState();
        expect(state.status).toBe('idle');
        expect(state.session).toBeNull();
        expect(state.queue).toHaveLength(0);
        expect(state.isDesynced).toBe(true);

        // The controller is reusable after leave.
        const again = await controller.joinRoom('ABC234');
        expect(again).toBeNull();
        expect(useStreamifyJamStoreBase.getState().status).toBe('live');
    });

    it('end message parks the follower as ended and tears the transport down', async () => {
        const transport = new FakeTransport();
        const { controller } = makeController({ transport, userId: 'follower' });
        await controller.joinRoom('ABC234');

        transport.deliverBroadcast(JAM_SYNC_EVENT, {
            emittedAt: 50_000,
            hostEpoch: 7,
            hostUserId: 'host-user',
            kind: 'end',
            positionMs: 0,
            v: 1,
        });
        await vi.advanceTimersByTimeAsync(0);
        expect(useStreamifyJamStoreBase.getState().status).toBe('ended');
        expect(transport.leaveCalls).toBe(1);
    });

    it('live ops converge the queue in real time', async () => {
        const transport = new FakeTransport();
        const { controller } = makeController({ transport, userId: 'follower' });
        await controller.joinRoom('ABC234');

        const add = seededOp(20n << 16n, OP_ADD, 300n, 0.5, 0n);
        transport.deliverOps([add]);
        expect(useStreamifyJamStoreBase.getState().queue).toHaveLength(1);

        const remove = seededOp(21n << 16n, OP_REMOVE, 300n, 0, 20n << 16n);
        transport.deliverOps([remove]);
        expect(useStreamifyJamStoreBase.getState().queue).toHaveLength(0);
    });
});

// Test helper: op with an explicit nonce (the journal-row path re-mints
// the checksum, so the sealed value does not matter here).
const seededOp = (
    opId: bigint,
    opType: number,
    cadId: bigint,
    frac: number,
    target: bigint,
): JamOp => {
    const op: JamOp = {
        checksum: 0,
        fracIndex: frac,
        opId,
        opType,
        policyFlags: 0,
        senderNonce: new Uint8Array([0, 0, 0, 1]),
        targetAddOpId: target,
        trackCadId: cadId,
    };
    return op;
};
