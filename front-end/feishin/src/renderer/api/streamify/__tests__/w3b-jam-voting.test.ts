/**
 * W3-B — Jam live-queue voting & ephemeral reactions: the per-sender vote
 * toggle (+1 per device, re-votes never double), the (voteCount DESC,
 * addedAt ASC) presentation order that never disturbs the playing track,
 * the reaction wire decoder (whitelist + strict validation), the 500 ms
 * flood-control gate, and the controller wiring end-to-end (voting-order
 * publish + reaction broadcast) against minimal fakes. Network-free.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

import type { JamJournalRow, JamOp } from '../jam-live/jam-live-wire';
import type {
    JamPlayerBridge,
    JamPlayerEvent,
    JamTransportFactory,
} from '../jam-live/jam-session-controller';

import {
    decodeJamReactionMessage,
    JAM_CONTROL_EVENT,
    JAM_META_EVENT,
    JAM_REACTION_EMOJIS,
    JAM_REACTION_EVENT,
    JAM_SYNC_EVENT,
    type JamTrackPayload,
} from '../jam-live/jam-live-protocol';
import { jamOpNew, OP_ADD, OP_VOTE } from '../jam-live/jam-live-wire';
import {
    applyJamQueueOps,
    createJamQueueDoc,
    type JamQueueElement,
    jamQueueOrderedLive,
} from '../jam-live/jam-queue-crdt';
import { StreamifyJamSessionController } from '../jam-live/jam-session-controller';

import {
    canSendJamReaction,
    hasMyJamVote,
    isJamReactionEmoji,
    jamVotingOrder,
} from '/@/renderer/features/streamify/jam/jam-voting';
import { useStreamifyJamStoreBase } from '/@/renderer/store/streamify-jam.store';

const SENDER_A = new Uint8Array([0xaa, 0xbb, 0xcc, 0xdd]);
const SENDER_B = new Uint8Array([0x11, 0x22, 0x33, 0x44]);
const SENDER_HEX_A = 'aabbccdd';

/** op ids as unix_ms<<16|counter-style bigints (ascending = later). */
const opId = (n: number): bigint => BigInt(n) << 16n;

const trackPayload = (id: string): JamTrackPayload =>
    ({
        album: 'Album',
        artistName: 'Artist',
        duration: 200,
        id,
        imageUrl: null,
        name: `Track ${id}`,
    }) as unknown as JamTrackPayload;

function addOp(op: bigint, sender: Uint8Array, cad: bigint, frac: number): JamOp {
    return jamOpNew(op, sender, OP_ADD, 0, cad, frac, 0n);
}

function voteOp(op: bigint, sender: Uint8Array, target: bigint): JamOp {
    return jamOpNew(op, sender, OP_VOTE, 0, 0n, 0.5, target);
}

beforeEach(() => {
    useStreamifyJamStoreBase.getState().reset();
});

afterEach(() => {
    useStreamifyJamStoreBase.getState().reset();
});

// ---------------------------------------------------------------------------
// Per-sender vote semantics (the fold register)
// ---------------------------------------------------------------------------

describe('w3b jam voting — +1 per sender, idempotent toggle', () => {
    it('two votes from one sender count once; two senders count twice', () => {
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [
            addOp(opId(1), SENDER_A, 100n, 0.5),
            voteOp(opId(2), SENDER_A, opId(1)),
            voteOp(opId(3), SENDER_A, opId(1)), // re-vote: same sender
            voteOp(opId(4), SENDER_B, opId(1)),
        ]);
        const element = doc.elements.get(opId(1)) as JamQueueElement;
        expect(element.votes.size).toBe(2);
        expect(hasMyJamVote(element, SENDER_HEX_A)).toBe(true);
        expect(hasMyJamVote(element, '00000000')).toBe(false);
    });

    it('the fold converges under any delivery order (duplicated votes included)', () => {
        const ops = [
            addOp(opId(1), SENDER_A, 100n, 0.5),
            addOp(opId(2), SENDER_B, 200n, 1.5),
            voteOp(opId(3), SENDER_B, opId(2)),
            voteOp(opId(4), SENDER_A, opId(1)),
        ];
        const forward = createJamQueueDoc();
        applyJamQueueOps(forward, ops);
        const shuffled = createJamQueueDoc();
        applyJamQueueOps(shuffled, [...ops].reverse());
        applyJamQueueOps(shuffled, [voteOp(opId(5), SENDER_A, opId(1))]); // dup re-vote
        const live = (doc: ReturnType<typeof createJamQueueDoc>) =>
            jamQueueOrderedLive(doc).map((el) => `${el.addOpId}:${el.votes.size}`);
        expect(live(forward)).toEqual(live(shuffled));
    });
});

// ---------------------------------------------------------------------------
// The voting presentation order
// ---------------------------------------------------------------------------

describe('w3b jam voting — (voteCount DESC, addedAt ASC) order', () => {
    it('sorts by votes, ties break by added time, and the pinned track never moves', () => {
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [
            addOp(opId(1), SENDER_A, 100n, 0.5), // earliest add
            addOp(opId(2), SENDER_B, 200n, 1.5),
            addOp(opId(3), SENDER_A, 300n, 2.5), // latest add
            voteOp(opId(4), SENDER_B, opId(3)),
            voteOp(opId(5), SENDER_A, opId(3)), // track 3: 2 votes
            voteOp(opId(6), SENDER_A, opId(2)), // track 2: 1 vote
        ]);
        const ordered = jamQueueOrderedLive(doc);
        const ids = (els: JamQueueElement[]) => els.map((el) => el.addOpId.toString(10));
        expect(ids(jamVotingOrder(ordered))).toEqual([
            opId(3).toString(),
            opId(2).toString(),
            opId(1).toString(),
        ]);

        // Tie (both 0 votes) → addedAt ASC.
        const doc2 = createJamQueueDoc();
        applyJamQueueOps(doc2, [
            addOp(opId(10), SENDER_A, 100n, 0.5),
            addOp(opId(20), SENDER_B, 200n, 1.5),
        ]);
        expect(ids(jamVotingOrder(jamQueueOrderedLive(doc2)))).toEqual([
            opId(10).toString(),
            opId(20).toString(),
        ]);

        // The pinned (currently playing) element stays first even outvoted.
        const pinned = jamVotingOrder(ordered, { pinnedAddOpId: opId(1).toString() });
        expect(ids(pinned)).toEqual([opId(1).toString(), opId(3).toString(), opId(2).toString()]);

        // A pinned id that does not resolve is ignored gracefully.
        expect(ids(jamVotingOrder(ordered, { pinnedAddOpId: '999' }))).toEqual([
            opId(3).toString(),
            opId(2).toString(),
            opId(1).toString(),
        ]);
    });
});

// ---------------------------------------------------------------------------
// The reaction lane (decoder + throttle)
// ---------------------------------------------------------------------------

describe('w3b jam reactions — wire decoder and flood control', () => {
    it('decodes whitelisted reactions and rejects everything else', () => {
        const good = decodeJamReactionMessage({
            emoji: '🔥',
            kind: 'reaction',
            senderNonce: 'aabbccdd',
            ts: 1234,
            v: 1,
        });
        expect(good?.emoji).toBe('🔥');
        expect(
            decodeJamReactionMessage({
                emoji: '💣',
                kind: 'reaction',
                senderNonce: 'x',
                ts: 1,
                v: 1,
            }),
        ).toBeNull();
        expect(
            decodeJamReactionMessage({
                emoji: '🔥',
                kind: 'reaction',
                senderNonce: 'x',
                ts: -1,
                v: 1,
            }),
        ).toBeNull();
        expect(
            decodeJamReactionMessage({
                emoji: '🔥',
                kind: 'reaction',
                senderNonce: '',
                ts: 1,
                v: 1,
            }),
        ).toBeNull();
        expect(
            decodeJamReactionMessage({
                emoji: '🔥',
                kind: 'reaction',
                senderNonce: 'x',
                ts: 1,
                v: 2,
            }),
        ).toBeNull();
        expect(
            decodeJamReactionMessage({ emoji: '🔥', kind: 'sync', senderNonce: 'x', ts: 1, v: 1 }),
        ).toBeNull();
        expect(decodeJamReactionMessage('nope')).toBeNull();
        expect(JAM_REACTION_EMOJIS).toEqual(['🔥', '❤️', '🎧', '⚡', '👏']);
        expect(isJamReactionEmoji('👏')).toBe(true);
        expect(isJamReactionEmoji('nope')).toBe(false);
    });

    it('throttles to one reaction per 500 ms (boundary-inclusive)', () => {
        expect(canSendJamReaction(Number.NEGATIVE_INFINITY, 1_000)).toBe(true);
        expect(canSendJamReaction(1_000, 1_499)).toBe(false);
        expect(canSendJamReaction(1_000, 1_500)).toBe(true);
        expect(canSendJamReaction(1_000, 2_000)).toBe(true);
    });

    it('the store feed caps and ages out reactions with unique keys', () => {
        const now = Date.now();
        for (let i = 0; i < 30; i += 1) {
            useStreamifyJamStoreBase.getState().pushReaction('🔥', now - i * 100);
        }
        const reactions = useStreamifyJamStoreBase.getState().reactions;
        expect(reactions.length).toBeLessThanOrEqual(24);
        expect(new Set(reactions.map((r) => r.key)).size).toBe(reactions.length);
        useStreamifyJamStoreBase.getState().reset();
        expect(useStreamifyJamStoreBase.getState().reactions).toHaveLength(0);
    });
});

// ---------------------------------------------------------------------------
// Controller wiring (minimal fakes; the publish + broadcast seams)
// ---------------------------------------------------------------------------

class MiniBridge implements JamPlayerBridge {
    applied: Array<{ positionMs: number; trackId: string }> = [];
    current: JamTrackPayload | null = null;
    paused = false;
    played = false;
    positionMs = 0;

    applyTrack(track: JamTrackPayload, positionMs: number): void {
        this.applied.push({ positionMs, trackId: track.id });
        this.current = track;
    }

    getCurrentTrack(): JamTrackPayload | null {
        return this.current;
    }

    getPositionMs(): number {
        return this.positionMs;
    }

    isPlaying(): boolean {
        return this.played && !this.paused;
    }

    next(): void {
        this.current = null;
    }

    pause(): void {
        this.paused = true;
    }

    play(): void {
        this.played = true;
        this.paused = false;
    }

    seekTo(positionMs: number): void {
        this.positionMs = positionMs;
    }

    subscribe(handler: (event: JamPlayerEvent) => void): () => void {
        // Test fake: no player events are emitted in this suite.
        void handler;
        return () => undefined;
    }
}

class MiniTransport {
    broadcasts: Array<[string, unknown]> = [];
    leaveCalls = 0;
    opsSent: JamOp[] = [];
    presenceTouches = 0;

    private events: null | Parameters<JamTransportFactory>[1] = null;

    bind(events: Parameters<JamTransportFactory>[1]): void {
        this.events = events;
    }

    broadcastControl(message: unknown): void {
        this.broadcasts.push([JAM_CONTROL_EVENT, message]);
    }

    broadcastMeta(message: unknown): void {
        this.broadcasts.push([JAM_META_EVENT, message]);
    }

    broadcastReaction(message: unknown): void {
        this.broadcasts.push([JAM_REACTION_EVENT, message]);
    }

    broadcastSync(message: unknown): void {
        this.broadcasts.push([JAM_SYNC_EVENT, message]);
    }

    async connect(): Promise<void> {
        this.events?.onStatus('live');
    }

    deliverBroadcast(event: string, raw: unknown): void {
        this.events?.onBroadcast(event, raw);
    }

    async fetchJournal(): Promise<JamJournalRow[]> {
        return [];
    }

    async fetchSessionRow() {
        return {
            hostEpoch: 0,
            hostUserId: 'host-user',
            id: 'session-1',
            participantIds: [],
            sessionCode: 'ABC234',
        };
    }

    async heartbeat() {
        return null;
    }

    async insertSessionRow(hostUserId: null | string) {
        return {
            hostEpoch: 0,
            hostUserId,
            id: 'session-1',
            participantIds: [],
            sessionCode: 'ABC234',
        };
    }

    async leave(): Promise<void> {
        this.leaveCalls += 1;
    }

    async sendOp(op: JamOp) {
        this.opsSent.push(op);
        return { ok: true };
    }

    async touchPresence(): Promise<void> {
        this.presenceTouches += 1;
    }
}

describe('w3b jam — the controller wiring', () => {
    it('vote toggle is idempotent, the queue publishes in voting order, and the playing track pins', async () => {
        const bridge = new MiniBridge();
        const transport = new MiniTransport();
        const factory: JamTransportFactory = (_code, events) => {
            transport.bind(events);
            return transport;
        };
        let clock = 10_000;
        const controller = new StreamifyJamSessionController({
            bridge,
            deviceId: 'dev-a',
            now: () => clock,
            transportFactory: factory,
            userId: 'host-user',
        });
        await controller.createRoom();

        controller.addTrack(trackPayload('one')); // op 1
        clock += 10;
        controller.addTrack(trackPayload('two')); // op 2
        clock += 10;

        // No votes yet: addedAt ASC.
        let queue = useStreamifyJamStoreBase.getState().queue;
        expect(queue.map((i) => i.track?.id)).toEqual(['one', 'two']);
        expect(queue.every((i) => i.voted === false)).toBe(true);

        // Vote "two" twice from this device: one op, idempotent toggle.
        controller.upvoteTrack(queue[1]!.addOpId);
        controller.upvoteTrack(queue[1]!.addOpId);
        expect(transport.opsSent.filter((op) => op.opType === OP_VOTE)).toHaveLength(1);

        queue = useStreamifyJamStoreBase.getState().queue;
        expect(queue.map((i) => i.track?.id)).toEqual(['two', 'one']); // vote-sorted
        expect(queue[0]!.voted).toBe(true);
        expect(queue[0]!.upvotes).toBe(1);

        // The host now plays "one" (the queue element) → pinned first.
        bridge.current = trackPayload('one');
        controller.upvoteTrack(queue[1]!.addOpId); // any publish trigger
        queue = useStreamifyJamStoreBase.getState().queue;
        expect(queue.map((i) => i.track?.id)).toEqual(['one', 'two']);

        await controller.leave();
    });

    it('reactions broadcast (whitelist + 500 ms gate) and round-trip through the decoder', async () => {
        const bridge = new MiniBridge();
        const transport = new MiniTransport();
        const factory: JamTransportFactory = (_code, events) => {
            transport.bind(events);
            return transport;
        };
        let clock = 50_000;
        const controller = new StreamifyJamSessionController({
            bridge,
            deviceId: 'dev-b',
            now: () => clock,
            transportFactory: factory,
            userId: null,
        });
        await controller.createRoom();

        controller.sendReaction('🔥'); // emitted
        controller.sendReaction('❤️'); // inside the 500 ms window — dropped
        controller.sendReaction('nope'); // not whitelisted — dropped
        expect(transport.broadcasts).toHaveLength(1);
        expect(transport.broadcasts[0]![1]).toMatchObject({ emoji: '🔥', v: 1 });

        // Local echo is in the store; the wire payload decodes cleanly.
        expect(useStreamifyJamStoreBase.getState().reactions).toHaveLength(1);
        const decoded = decodeJamReactionMessage(transport.broadcasts[0]![1]);
        expect(decoded?.emoji).toBe('🔥');
        expect(decoded?.senderNonce).toHaveLength(8);

        clock += 500;
        controller.sendReaction('❤️'); // past the gate — emitted
        expect(transport.broadcasts).toHaveLength(2);

        // An inbound reaction from a peer lands in the local feed; a
        // malformed one is dropped silently (zero errors offline).
        const before = useStreamifyJamStoreBase.getState().reactions.length;
        transport.deliverBroadcast(JAM_REACTION_EVENT, {
            emoji: '⚡',
            kind: 'reaction',
            senderNonce: '11223344',
            ts: clock,
            v: 1,
        });
        transport.deliverBroadcast(JAM_REACTION_EVENT, { emoji: '💣', v: 1 });
        expect(useStreamifyJamStoreBase.getState().reactions).toHaveLength(before + 1);
        expect(useStreamifyJamStoreBase.getState().reactions.at(-1)?.emoji).toBe('⚡');

        await controller.leave();
    });
});
