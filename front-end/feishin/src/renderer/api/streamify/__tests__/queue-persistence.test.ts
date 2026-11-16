/**
 * P3-B — the URL-free play-queue serializer: round-trip, corruption
 * discard, and the never-persists-a-URL guarantee (network-free).
 */
import { describe, expect, it } from 'vitest';

import {
    type AsyncKV,
    createQueuePersistence,
    deserializeQueue,
    LOCAL_MAX_CHARS,
    PERSIST_FORMAT_VERSION,
    type PersistedPlayQueue,
    serializeQueue,
    type SyncKV,
} from '../playback/queue-persistence';

function asyncMemKV(): AsyncKV & { dump(): Promise<null | string> } {
    let value: null | string = null;
    return {
        clear: async () => {
            value = null;
        },
        dump: async () => value,
        get: async () => value,
        set: async (v: string) => {
            value = v;
        },
    };
}

function memKV(): SyncKV {
    let value: null | string = null;
    return {
        clear: () => {
            value = null;
        },
        get: () => value,
        set: (v: string) => {
            value = v;
        },
    };
}

const baseQueue = (): PersistedPlayQueue => ({
    currentIndex: 1,
    positionMs: 42_500,
    savedAt: 1731000000000,
    session: {
        playedMs: 30_000,
        scrobbled: false,
        startedAt: 1730999990000,
        trackId: 'trk_2',
    },
    songIds: ['trk_1', 'trk_2', 'trk_3'],
    v: PERSIST_FORMAT_VERSION,
});

describe('serializeQueue / deserializeQueue round-trip', () => {
    it('round-trips ids, order, cursor, offset, and the scrobble session', () => {
        const queue = baseQueue();
        const parsed = deserializeQueue(serializeQueue(queue));
        expect(parsed).not.toBeNull();
        expect(parsed).toEqual(queue);
    });

    it('never persists a resolved stream URL — even one smuggled onto the payload', () => {
        const smuggled = {
            ...baseQueue(),
            // A hostile/buggy caller appending URL-ish fields:
            streamUrl: 'https://googlevideo.com/videoplayback?expire=9999',
            urls: ['https://example.com/audio.mp4'],
        } as unknown as PersistedPlayQueue;
        const raw = serializeQueue(smuggled);
        expect(raw).not.toContain('googlevideo');
        expect(raw).not.toContain('example.com');
        expect(raw).not.toContain('mp4');
        // And the strict-shape parse still round-trips the honest fields.
        expect(deserializeQueue(raw)).toEqual(baseQueue());
    });

    it('drops unknown queue payload fields on the way back in', () => {
        const queue = baseQueue();
        const doctored = JSON.parse(serializeQueue(queue)) as Record<string, unknown>;
        doctored.playOrder = [2, 0, 1]; // #187-era field that no longer exists
        doctored.repeat = 'all';
        expect(deserializeQueue(JSON.stringify(doctored))).toEqual(baseQueue());
    });
});

describe('deserializeQueue corruption policy (discard, never crash)', () => {
    it.each([
        ['not json at all', '{{{'],
        ['wrong version', JSON.stringify({ ...baseQueue(), v: 2 })],
        ['empty song list', JSON.stringify({ ...baseQueue(), songIds: [] })],
        ['duplicate ids', JSON.stringify({ ...baseQueue(), songIds: ['a', 'a'] })],
        ['cursor out of range', JSON.stringify({ ...baseQueue(), currentIndex: 3 })],
        ['negative position', JSON.stringify({ ...baseQueue(), positionMs: -1 })],
        [
            'session referencing an unknown track',
            JSON.stringify({
                ...baseQueue(),
                session: { ...baseQueue().session!, trackId: 'nope' },
            }),
        ],
        [
            'session with a negative played time',
            JSON.stringify({ ...baseQueue(), session: { ...baseQueue().session!, playedMs: -5 } }),
        ],
        ['array instead of object', '[]'],
        ['null', 'null'],
    ])('discards: %s', (_name, raw) => {
        expect(deserializeQueue(raw as string)).toBeNull();
    });

    it('accepts a null session and a null cursor', () => {
        const queue: PersistedPlayQueue = { ...baseQueue(), currentIndex: null, session: null };
        expect(deserializeQueue(serializeQueue(queue))).toEqual(queue);
    });
});

describe('createQueuePersistence lane routing', () => {
    it('saves small payloads to the local lane and clears the remote lane', async () => {
        const local = memKV();
        const remote = asyncMemKV();
        await remote.set('stale twin');
        const persistence = createQueuePersistence({ local, remote });

        await persistence.save(baseQueue());

        expect(local.get()).not.toBeNull();
        expect(await remote.dump()).toBeNull();
        const loaded = await persistence.load();
        expect(loaded).toEqual(baseQueue());
    });

    it('routes oversized payloads to the unbounded lane (single-source invariant)', async () => {
        const local = memKV();
        const remote = asyncMemKV();
        const persistence = createQueuePersistence({
            local,
            localMaxChars: 10, // force the big lane
            remote,
        });

        await persistence.save(baseQueue());

        expect(local.get()).toBeNull();
        expect(await remote.dump()).not.toBeNull();
        expect(await persistence.load()).toEqual(baseQueue());
    });

    it('degrades honestly when the payload overflows and no big lane exists', async () => {
        const local = memKV();
        const persistence = createQueuePersistence({ local, localMaxChars: 10, remote: null });
        await persistence.save(baseQueue());
        expect(local.get()).toBeNull();
        expect(await persistence.load()).toBeNull();
    });

    it('falls back to the big lane when the small lane throws QuotaExceeded', async () => {
        const local: SyncKV = {
            clear: () => undefined,
            get: () => null,
            set: () => {
                throw new DOMException('quota', 'QuotaExceededError');
            },
        };
        const remote = asyncMemKV();
        const persistence = createQueuePersistence({ local, remote });

        await persistence.save(baseQueue());

        expect(await remote.dump()).not.toBeNull();
        expect(await persistence.load()).toEqual(baseQueue());
    });

    it('clears a corrupt small-lane payload and checks the big lane (pre-routing write)', async () => {
        const local = memKV();
        local.set('not json');
        const remote = asyncMemKV();
        await remote.set(serializeQueue(baseQueue()));
        const persistence = createQueuePersistence({ local, remote });

        expect(await persistence.load()).toEqual(baseQueue());
        expect(local.get()).toBeNull();
    });

    it('LOCAL_MAX_CHARS is the documented 750k', () => {
        expect(LOCAL_MAX_CHARS).toBe(750_000);
    });

    it('clear() empties both lanes', async () => {
        const local = memKV();
        const remote = asyncMemKV();
        const persistence = createQueuePersistence({ local, remote });
        await persistence.save(baseQueue());
        await persistence.clear();
        expect(local.get()).toBeNull();
        expect(await remote.dump()).toBeNull();
        expect(await persistence.load()).toBeNull();
    });
});
