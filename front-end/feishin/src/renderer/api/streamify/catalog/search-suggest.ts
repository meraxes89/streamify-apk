/**
 * api/streamify/catalog/search-suggest.ts — the W1-B predictive
 * typeahead: yt.music.getSearchSuggestions behind a client-side 250–300 ms
 * debounce with AbortController cancellation (the agreed UX contract),
 * merged with the local snapshot's matching terms so the library always
 * suggests even offline / pre-W1-A.
 *
 * Feishin's stock search bar is upstream and stays untouched; this module
 * is the fork-side suggester surface (a controller-facing query lane plus
 * the pure debouncer, both network-free testable).
 */

import type { Album, AlbumArtist, Song } from '/@/shared/types/domain-types';

import type { LibrarySnapshot } from '../library';
import type { MetadataProvider } from '../metadata/provider';

import { getMetadataProvider } from '../metadata/provider';
import { normalizeAlbum, normalizeAlbumArtist, normalizeSong } from '../normalize';
import { type SearchLimits, searchSnapshot } from '../query';
import {
    songBySig,
    songByVideoId,
    ytmArtistToAlbumArtist,
    ytmToAlbum,
    ytmToSong,
} from './ytm-mappers';

/** The debounce window (ms) — the agreed 250–300 ms band, midpoint. */
export const SEARCH_SUGGEST_DEBOUNCE_MS = 280;

/** Providers above this count of local matches skip the remote tier. */
const LOCAL_SUGGEST_CAP = 8;

export interface ScopedSearchOptions {
    filter?: 'albums' | 'artists' | 'playlists' | 'songs';
    provider?: MetadataProvider;
    /** The feishin server id (_serverId on every emitted DTO). */
    serverId?: string;
    signal?: AbortSignal;
}

export interface ScopedSearchResult {
    albumArtists: AlbumArtist[];
    albums: Album[];
    /** Playlists ride the fork DTO (feishin's SearchResponse has no slot —
     *  the controller drops them; documented in the ADR). */
    playlists: Array<{ id: string; name: string }>;
    songs: Song[];
}

export interface SearchSuggesterOptions {
    debounceMs?: number;
    /** Timer injection (tests). Defaults to globalThis.setTimeout/clearTimeout. */
    scheduler?: {
        clear: (handle: unknown) => void;
        set: (fn: () => void, ms: number) => unknown;
    };
}

export interface SearchSuggestSession {
    /** Cancel any in-flight/pending suggestion (AbortController). */
    cancel(): void;
    /** Queue an input; resolves the merged suggestions when the debounce fires. */
    suggest(input: string): Promise<string[]>;
}

/**
 * Create a debounced suggestion session. One session owns one pending
 * timer and one AbortController — a new input cancels the previous
 * pending round (both the timer and any in-flight provider call).
 */
export function createSearchSuggester(
    snapshotProvider: () => LibrarySnapshot | null,
    options: SearchSuggesterOptions & { provider?: MetadataProvider } = {},
): SearchSuggestSession {
    const provider = options.provider ?? getMetadataProvider();
    const debounceMs = options.debounceMs ?? SEARCH_SUGGEST_DEBOUNCE_MS;
    const scheduler =
        options.scheduler ??
        ({
            clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
            set: (fn, ms) => setTimeout(fn, ms),
        } satisfies NonNullable<SearchSuggesterOptions['scheduler']>);

    let timer: unknown = null;
    let controller: AbortController | null = null;
    let pending: ((value: string[]) => void) | null = null;
    let seq = 0;

    const cancel = (): void => {
        if (timer !== null) scheduler.clear(timer);
        timer = null;
        controller?.abort();
        controller = null;
        if (pending !== null) {
            pending([]);
            pending = null;
        }
    };

    return {
        cancel,
        suggest(input: string): Promise<string[]> {
            const round = ++seq;
            cancel(); // a new keystroke retires the previous round
            const trimmed = input.trim();
            if (trimmed === '') return Promise.resolve([]);
            return new Promise<string[]>((resolve) => {
                pending = resolve;
                timer = scheduler.set(() => {
                    timer = null;
                    controller = new AbortController();
                    const done = (value: string[]): void => {
                        if (round !== seq) return; // a newer round owns the slot
                        pending = null;
                        controller = null;
                        resolve(value);
                    };
                    void runSuggestion(
                        provider,
                        snapshotProvider(),
                        trimmed,
                        controller.signal,
                    ).then(done, () => done(localSuggestions(snapshotProvider(), trimmed)));
                }, debounceMs);
            });
        },
    };
}

// ---------------------------------------------------------------------------
// The scoped search merge (entity-scoped search across songs / albums /
// artists / playlists — the controller's `search` body)
// ---------------------------------------------------------------------------

/** Local library suggestions: song/artist/album titles matching the input. */
export function localSuggestions(snapshot: LibrarySnapshot | null, input: string): string[] {
    if (snapshot === null) return [];
    const needle = input.trim().toLowerCase();
    if (needle === '') return [];
    const out: string[] = [];
    const seen = new Set<string>();
    const push = (value: string): void => {
        const key = value.toLowerCase();
        if (key === '' || seen.has(key)) return;
        seen.add(key);
        out.push(value);
    };
    for (const song of snapshot.songs) {
        if (song.title.toLowerCase().includes(needle)) push(song.title);
        if (out.length >= LOCAL_SUGGEST_CAP) return out.slice(0, LOCAL_SUGGEST_CAP);
    }
    for (const artist of snapshot.artists) {
        if (artist.name.toLowerCase().includes(needle)) push(artist.name);
        if (out.length >= LOCAL_SUGGEST_CAP) return out.slice(0, LOCAL_SUGGEST_CAP);
    }
    for (const album of snapshot.albums) {
        if (album.name.toLowerCase().includes(needle)) push(album.name);
        if (out.length >= LOCAL_SUGGEST_CAP) return out.slice(0, LOCAL_SUGGEST_CAP);
    }
    return out.slice(0, LOCAL_SUGGEST_CAP);
}

/**
 * Entity-scoped search: the snapshot's local matches FIRST (the library
 * is what is playable/offline today), then the provider's scoped results,
 * deduped by title+artist (songs) / title+artist (albums) / name
 * (artists). Section pagination applies AFTER the merge, exactly like
 * searchSnapshot's sliceSection semantics.
 */
export async function scopedSearch(
    snapshot: LibrarySnapshot,
    term: string,
    limits: SearchLimits,
    opts: ScopedSearchOptions = {},
): Promise<ScopedSearchResult> {
    const provider = opts.provider ?? getMetadataProvider();
    const serverId = opts.serverId ?? snapshot.sessionKey;
    const local = searchSnapshot(snapshot, term, limits);

    const result: ScopedSearchResult = {
        albumArtists: local.albumArtistRows.map((row) => localAlbumArtist(row, snapshot, serverId)),
        albums: local.albumRows.map((row) => localAlbum(row, snapshot, serverId)),
        playlists: [],
        songs: local.songRows.map((row) => localSong(row, snapshot, serverId)),
    };
    if (term.trim() === '') return result;

    const remote = await provider.search(term.trim(), opts.filter, undefined, undefined, undefined);
    if (remote === null) return result;

    // Songs: snapshot ids win over ytm: ids for the same track.
    const byVideoId = songByVideoId(snapshot);
    const bySig = songBySig(snapshot);
    const songKeys = new Set(result.songs.map((s) => songKeyOf(s)));
    for (const ref of remote.songs) {
        const song = ytmToSong(ref, snapshot, serverId, { bySig, byVideoId });
        // Keep ONE row per (title, artist): the snapshot's if it exists.
        if (songKeys.has(songKeyOf(song))) continue;
        songKeys.add(songKeyOf(song));
        result.songs.push(song);
    }

    const albumKeys = new Set(result.albums.map((a) => `${a.name}|${a.albumArtistName}`));
    for (const ref of remote.albums) {
        const key = `${ref.title}|${ref.artist}`;
        if (albumKeys.has(key)) continue;
        albumKeys.add(key);
        result.albums.push(ytmToAlbum(ref, serverId));
    }

    const artistKeys = new Set(result.albumArtists.map((a) => a.name.toLowerCase()));
    for (const ref of remote.artists) {
        if (artistKeys.has(ref.name.toLowerCase())) continue;
        artistKeys.add(ref.name.toLowerCase());
        result.albumArtists.push(ytmArtistToAlbumArtist(ref, serverId));
    }

    for (const pl of remote.playlists) {
        result.playlists.push({ id: pl.playlistId, name: pl.name });
    }

    // Section slicing (the merge-then-paginate contract).
    return {
        albumArtists: sliceSection(
            result.albumArtists,
            limits.albumArtistStartIndex,
            limits.albumArtistLimit,
        ),
        albums: sliceSection(result.albums, limits.albumStartIndex, limits.albumLimit),
        playlists: result.playlists,
        songs: sliceSection(result.songs, limits.songStartIndex, limits.songLimit),
    };
}

function localAlbum(
    row: LibrarySnapshot['albums'][number],
    snapshot: LibrarySnapshot,
    serverId: string,
): Album {
    return normalizeAlbum(row, snapshot, serverId);
}

function localAlbumArtist(
    row: LibrarySnapshot['artists'][number],
    snapshot: LibrarySnapshot,
    serverId: string,
): AlbumArtist {
    return normalizeAlbumArtist(row, snapshot, serverId);
}

function localSong(
    row: LibrarySnapshot['songs'][number],
    snapshot: LibrarySnapshot,
    serverId: string,
): Song {
    return normalizeSong(row, snapshot, serverId);
}

async function runSuggestion(
    provider: MetadataProvider,
    snapshot: LibrarySnapshot | null,
    input: string,
    signal: AbortSignal,
): Promise<string[]> {
    const local = localSuggestions(snapshot, input);
    if (local.length >= LOCAL_SUGGEST_CAP) return local;
    if (signal.aborted) return local; // cancelled before the call went out
    // The stub resolves null (honest miss) — local-only suggestions.
    const remote = await provider.getSearchSuggestions(input, undefined, undefined);
    if (signal.aborted || remote === null) return local;
    const merged = [...local];
    const seen = new Set(local.map((s) => s.toLowerCase()));
    for (const suggestion of remote) {
        if (merged.length >= LOCAL_SUGGEST_CAP) break;
        const key = suggestion.toLowerCase();
        if (key === '' || seen.has(key)) continue;
        seen.add(key);
        merged.push(suggestion);
    }
    return merged;
}

function sliceSection<T>(rows: T[], startIndex?: number, limit?: number): T[] {
    const start = Math.max(0, startIndex ?? 0);
    const end = limit === undefined ? undefined : start + Math.max(0, limit);
    return end === undefined ? rows.slice(start) : rows.slice(start, end);
}

function songKeyOf(song: Song): string {
    return `${song.name.toLowerCase()}|${song.artistName.toLowerCase()}`;
}
