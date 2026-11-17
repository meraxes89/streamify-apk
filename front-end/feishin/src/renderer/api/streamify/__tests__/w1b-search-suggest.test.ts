/**
 * w1b-search-suggest.test.ts — the predictive typeahead lock (W1-B):
 *
 *   - the 250–300 ms debounce: no provider call before the window, one
 *     after; a rapid keystroke burst collapses to the LAST input;
 *   - AbortController cancellation: a superseded round never emits its
 *     suggestions (cancel() aborts the in-flight provider call and
 *     resolves the pending round empty);
 *   - the merge: local snapshot suggestions first, remote appended,
 *     deduped, capped;
 *   - the pre-W1-A stub resolves the local-only list (honest).
 *
 * Network-free by construction: fake timers drive the injected scheduler,
 * the provider is a fake, the snapshot is a fixture.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The unified provider (#200) carries W1-A's engine, whose module scope
// touches the logger; the node lane has no localStorage — mock it like
// metadata-provider.test.ts does.
vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

import type { LibrarySnapshot, SongRow } from '../library';
import type { MetadataProvider } from '../metadata/provider';

import {
    createSearchSuggester,
    localSuggestions,
    SEARCH_SUGGEST_DEBOUNCE_MS,
} from '../catalog/search-suggest';
import { setMetadataProviderForTests, stubMetadataProvider } from '../metadata/provider';

function fakeProvider(suggestions: null | string[]): MetadataProvider {
    return {
        ...stubMetadataProvider,
        getSearchSuggestions: vi.fn(async () => suggestions),
    };
}

/** The fake scheduler: timers carry due times; the clock fires them in order. */
function fakeScheduler() {
    const timers: Array<{ due: number; fire: () => void; id: number }> = [];
    let nextId = 1;
    let now = 0;
    return {
        advance(ms: number): void {
            const target = now + ms;
            for (;;) {
                const due = timers.filter((t) => t.due <= target).sort((a, b) => a.due - b.due)[0];
                if (due === undefined) break;
                now = due.due;
                due.fire();
                const index = timers.indexOf(due);
                if (index >= 0) timers.splice(index, 1);
            }
            now = target;
        },
        scheduler: {
            clear: (handle: unknown) => {
                const id = handle as number;
                const index = timers.findIndex((t) => t.id === id);
                if (index >= 0) timers.splice(index, 1);
            },
            set: (fn: () => void, ms: number) => {
                const timer = { due: now + ms, fire: fn, id: nextId };
                nextId += 1;
                timers.push(timer);
                return timer.id;
            },
        },
    };
}

function snapshotOf(songs: SongRow[]): LibrarySnapshot {
    return {
        albumById: new Map(),
        albums: [],
        artistById: new Map(),
        artists: [],
        likedAlbumIds: new Set<string>(),
        likedArtistIds: new Set<string>(),
        likedIds: [],
        loadedAt: '2026-12-01T00:00:00.000Z',
        playEvents: [],
        playlistById: new Map(),
        playlists: [],
        sessionKey: 'test',
        songById: new Map(songs.map((s) => [s.id, s])),
        songs,
    };
}

function songRow(title: string, artist: string): SongRow {
    return {
        addedRank: 0,
        album: null,
        artist,
        coverUrl: null,
        durationSec: 100,
        id: `trk_${title}`,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: null,
        title,
    };
}

beforeEach(() => {
    setMetadataProviderForTests(null);
});

afterEach(() => {
    setMetadataProviderForTests(null);
});

describe('localSuggestions (the snapshot tier)', () => {
    it('matches song/artist/album titles, case-insensitively, deduped and capped', () => {
        const snapshot = snapshotOf([
            songRow('Blue Song', 'A'),
            songRow('Bluebird', 'B'),
            songRow('Other', 'Blue Artist'),
        ]);
        snapshot.artists = [
            {
                addedRank: 0,
                albumIds: [],
                coverUrl: null,
                durationMs: 0,
                id: 'art_blue',
                key: 'blue artist',
                lastPlayedAt: null,
                name: 'Blue Artist',
                playCount: 0,
                songIds: [],
            },
        ];
        const out = localSuggestions(snapshot, 'blue');
        expect(out).toEqual(['Blue Song', 'Bluebird', 'Blue Artist']);
    });

    it('an empty input or null snapshot resolves []', () => {
        expect(localSuggestions(null, 'x')).toEqual([]);
        expect(localSuggestions(snapshotOf([]), '')).toEqual([]);
    });
});

describe('createSearchSuggester (debounce + cancellation)', () => {
    it('fires no provider call before the debounce window and one after', async () => {
        const provider = fakeProvider(['remote one']);
        const clock = fakeScheduler();
        const suggester = createSearchSuggester(() => snapshotOf([]), {
            provider,
            scheduler: clock.scheduler,
        });

        const promise = suggester.suggest('qu');
        clock.advance(SEARCH_SUGGEST_DEBOUNCE_MS - 1);
        expect(provider.getSearchSuggestions).not.toHaveBeenCalled();

        clock.advance(1);
        await expect(promise).resolves.toEqual(['remote one']);
        expect(provider.getSearchSuggestions).toHaveBeenCalledTimes(1);
    });

    it('a rapid keystroke burst collapses to the last input (older rounds resolve empty)', async () => {
        const provider = fakeProvider(['final remote']);
        const clock = fakeScheduler();
        const suggester = createSearchSuggester(() => snapshotOf([]), {
            provider,
            scheduler: clock.scheduler,
        });

        const first = suggester.suggest('a');
        clock.advance(100);
        const second = suggester.suggest('ab');
        clock.advance(100);
        const third = suggester.suggest('abc');

        clock.advance(SEARCH_SUGGEST_DEBOUNCE_MS);
        expect(await first).toEqual([]); // superseded by 'ab'
        expect(await second).toEqual([]); // superseded by 'abc'
        expect(await third).toEqual(['final remote']);
        expect(provider.getSearchSuggestions).toHaveBeenCalledTimes(1);
        expect(provider.getSearchSuggestions).toHaveBeenCalledWith('abc', undefined, undefined);
    });

    it('cancel() aborts the in-flight round and resolves it empty', async () => {
        const provider = fakeProvider(['late']);
        const clock = fakeScheduler();
        const suggester = createSearchSuggester(() => snapshotOf([]), {
            provider,
            scheduler: clock.scheduler,
        });

        const promise = suggester.suggest('x');
        clock.advance(SEARCH_SUGGEST_DEBOUNCE_MS); // provider call in flight
        suggester.cancel();
        await expect(promise).resolves.toEqual([]);
    });

    it('merges local snapshot suggestions first, remote appended, deduped', async () => {
        const snapshot = snapshotOf([songRow('Local Blue', 'A')]);
        const provider = fakeProvider(['local blue', 'Remote Blue']);
        const clock = fakeScheduler();
        const suggester = createSearchSuggester(() => snapshot, {
            provider,
            scheduler: clock.scheduler,
        });

        const promise = suggester.suggest('blue');
        clock.advance(SEARCH_SUGGEST_DEBOUNCE_MS);
        expect(await promise).toEqual(['Local Blue', 'Remote Blue']);
    });

    it('a saturated local tier skips the provider entirely (offline-friendly)', async () => {
        const snapshot = snapshotOf([
            songRow('Blue 1', 'A'),
            songRow('Blue 2', 'A'),
            songRow('Blue 3', 'A'),
            songRow('Blue 4', 'A'),
            songRow('Blue 5', 'A'),
            songRow('Blue 6', 'A'),
            songRow('Blue 7', 'A'),
            songRow('Blue 8', 'A'),
        ]);
        const provider = fakeProvider(['never asked']);
        const clock = fakeScheduler();
        const suggester = createSearchSuggester(() => snapshot, {
            provider,
            scheduler: clock.scheduler,
        });

        const promise = suggester.suggest('Blue');
        clock.advance(SEARCH_SUGGEST_DEBOUNCE_MS);
        expect(await promise).toHaveLength(8);
        expect(provider.getSearchSuggestions).not.toHaveBeenCalled();
    });

    it('the pre-W1-A stub resolves the local-only list (honest miss)', async () => {
        const snapshot = snapshotOf([songRow('Local Blue', 'A')]);
        const clock = fakeScheduler();
        const suggester = createSearchSuggester(() => snapshot, {
            scheduler: clock.scheduler,
        });

        const promise = suggester.suggest('blue');
        clock.advance(SEARCH_SUGGEST_DEBOUNCE_MS);
        expect(await promise).toEqual(['Local Blue']);
    });

    it('an empty input resolves [] without scheduling any timer', async () => {
        const clock = fakeScheduler();
        const suggester = createSearchSuggester(() => snapshotOf([]), {
            scheduler: clock.scheduler,
        });
        await expect(suggester.suggest('   ')).resolves.toEqual([]);
    });
});
