/**
 * streamify/metadata/provider.ts — the UNIFIED W1-A/W1-B seam.
 *
 * One module, three layers (the #200 rebase unification):
 *
 *   1. The W1-B contract (frozen first, this file's public surface):
 *      the `MetadataProvider` interface resolving `null | Ytm*` DTOs —
 *      every method resolves null on miss/error (NEVER a throw for a
 *      not-found, NEVER fabricated data — the fork's honest-empty
 *      doctrine), plus `installMetadataProvider` /
 *      `setMetadataProviderForTests` for injection.
 *   2. The W1-A engine (`createProvider` below, unchanged from #198):
 *      snapshot lookup → Edge KV (/ytm/meta on the relay) → [on edge
 *      miss/unavailable/invalid] client-side InnerTube (youtubei.js via
 *      the resolver's getYt) → validate → Snapshot Upsert, with the
 *      three-mode failure separation and the 429 circuit guard.
 *   3. The adapter (`unwrap` + the per-endpoint shapers): re-exposes the
 *      engine's typed `MetadataResult<T>` results as the W1-B `Ytm*`
 *      DTOs — not-found / parse-error / rate-limited all map to null
 *      (the honest miss), `ok` payloads map field-by-field. The
 *      catalog/lyrics call sites and the two-tier YTM lyrics fallback
 *      therefore hit Edge KV + InnerTube IMMEDIATELY (the stub stepped
 *      down — the default `getMetadataProvider()` serves the live
 *      pipeline).
 *
 *   `getPlaylistTyped` keeps the TYPED result contract (#198's importer
 *   depends on the not-found / rate-limited / parse-error separation to
 *   report honestly; the W1-B contract has no playlist method — mood
 *   tiles drive the radio instead).
 *
 * THE W1-A PIPELINE per call (front-end/decisions/waves/
 * W1A-edge-kv-metadata-importer.md):
 *
 *   snapshot lookup → Edge KV (/ytm/meta on the relay) → [on edge
 *   miss/unavailable/invalid] client-side InnerTube (youtubei.js via the
 *   resolver's getYt) → validate → Snapshot Upsert.
 *
 * FAILURE SEPARATION (three modes, never cross-contaminated):
 *   • not-found  — the edge answered missing:true, or both tiers
 *     normalized to an empty shelf. ZERO retries; remembered 15 min in
 *     the snapshot so repeat calls never re-hit.
 *   • rate-limited — 429 anywhere in the edge chain. The provider runs
 *     exponential backoff (3 attempts, 250 ms base, doubling) and then
 *     OPENS THE CIRCUIT for 60 s: every further edge call fails fast
 *     with the typed rate-limited result while the cooldown runs (the
 *     circuit guards the EDGE tier only — the client-side lane still
 *     gets its one attempt; if that also fails, rate-limited surfaces).
 *   • parse-error — a payload failed the client-side schema validator.
 *     The diagnostic is logged, a clean fallback runs; only a second
 *     failure surfaces the typed result.
 *
 * StreamifyController stays strictly snapshot-backed (it never parses an
 * innertube node). Nothing in this module touches Supabase — the P1
 * boundary rule names the Cloudflare edge cache as THE global metadata
 * cache.
 */

import type {
    AlbumPayload,
    ArtistPayload,
    ChaptersPayload,
    CounterpartPayload,
    ExploreHomePayload,
    LyricsPayload,
    MetadataPayload,
    MetadataResult,
    PlaylistPayload,
    RelatedPayload,
    SearchPayload,
    SearchSuggestPayload,
    ShelfItemPayload,
    UpNextPayload,
} from './types';

import { type EdgeFetch, edgeMetaCall } from './edge';
import {
    fallbackAlbum,
    fallbackArtist,
    fallbackChapters,
    fallbackCounterpart,
    fallbackExplore,
    fallbackHomeFeed,
    fallbackLyrics,
    fallbackPlaylist,
    fallbackRelated,
    fallbackSearch,
    fallbackSearchSuggestions,
    fallbackUpNext,
} from './innertube-fallback';
import {
    validateAlbumPayload,
    validateArtistPayload,
    validateChaptersPayload,
    validateCounterpartPayload,
    validateLyricsPayload,
    validatePlaylistPayload,
    validateRelatedPayload,
    validateSearchPayload,
    validateSearchSuggestPayload,
    validateShelvesPayload,
    validateUpNextPayload,
    type ValidationOutcome,
} from './normalize';
import { snapshotKey, snapshotLookup, snapshotUpsert } from './snapshot';

import { logger } from '/@/renderer/utils/logger';

// ---------------------------------------------------------------------------
// The W1-B normalized DTOs (the shared seam — W1-A normalizes INTO these)
// ---------------------------------------------------------------------------

export interface MetadataProvider {
    getAlbum(browseId: string, gl?: string, hl?: string): Promise<null | YtmAlbumDetail>;
    getArtist(browseId: string, gl?: string, hl?: string): Promise<null | YtmArtistDetail>;
    getExplore(gl?: string, hl?: string): Promise<null | YtmExplore>;
    getHomeFeed(gl?: string, hl?: string): Promise<null | YtmHomeFeed>;
    getLyrics(videoId: string, hl?: string): Promise<null | YtmLyrics>;
    getRelated(seedVideoId: string): Promise<null | YtmRelated>;
    getSearchSuggestions(input: string, gl?: string, hl?: string): Promise<null | string[]>;
    getUpNext(seedVideoId: string, continuation?: string): Promise<null | YtmUpNext>;
    search(
        query: string,
        filter?: YtmSearchFilter,
        continuation?: string,
        gl?: string,
        hl?: string,
    ): Promise<null | YtmSearchResults>;
}

/** The album page — tracks included (getAlbumDetail resolves it). */
export interface YtmAlbumDetail {
    artist: string;
    browseId: string;
    thumbnailUrl?: null | string;
    title: string;
    tracks: YtmSongRef[];
    year?: null | number;
}

/** One album-shaped release. kind splits the artist discography honestly. */
export interface YtmAlbumRef {
    artist: string;
    browseId: string;
    kind: 'album' | 'ep' | 'live' | 'single';
    thumbnailUrl?: null | string;
    title: string;
    trackCount?: null | number;
    year?: null | number;
}

/** The structured artist page — the discography split, pre-normalized. */
export interface YtmArtistDetail {
    albums: YtmAlbumRef[];
    /** Albums the artist appears ON but does not own (Featured On). */
    appearsOn: YtmAlbumRef[];
    browseId: string;
    description?: null | string;
    liveAlbums: YtmAlbumRef[];
    name: string;
    /** Fans-also-like (the artist page's related shelf). */
    relatedArtists: YtmArtistRef[];
    /** Singles & EPs (kind carries the split inside the buckets too). */
    singles: YtmAlbumRef[];
    subscribers?: null | number;
    thumbnailUrl?: null | string;
    topSongs: YtmSongRef[];
}

export interface YtmArtistRef {
    browseId: string;
    name: string;
    thumbnailUrl?: null | string;
}

export interface YtmExplore {
    genres: YtmTaxonomy[];
    moods: YtmTaxonomy[];
}

export interface YtmHomeFeed {
    sections: Array<{
        albums?: YtmAlbumRef[];
        songs?: YtmSongRef[];
        title: string;
    }>;
}

/**
 * Lyrics for one video. `lyrics` is LRC-formatted text ([mm:ss.SSS] lines)
 * when synced is true, plain text otherwise — the synced flag ALWAYS tells
 * the truth (W1-B's two-tier rule: timestamped lines beat plain text).
 */
export interface YtmLyrics {
    lang?: string;
    lyrics: string;
    synced: boolean;
}

export interface YtmPlaylistRef {
    channelName?: null | string;
    name: string;
    playlistId: string;
    thumbnailUrl?: null | string;
}

/** Related tracks for a seed song — alternate versions, covers, remixes. */
export interface YtmRelated {
    songs: YtmSongRef[];
}

export type YtmSearchFilter = 'albums' | 'artists' | 'playlists' | 'songs';

export interface YtmSearchResults {
    albums: YtmAlbumRef[];
    artists: YtmArtistRef[];
    continuation?: null | string;
    playlists: YtmPlaylistRef[];
    songs: YtmSongRef[];
}

/** One playable YTM track (a video in music context). */
export interface YtmSongRef {
    album?: null | string;
    artist: string;
    durationSec?: null | number;
    thumbnailUrl?: null | string;
    title: string;
    videoId: string;
}

/** One Explore taxonomy tile (a mood or a genre chip). */
export interface YtmTaxonomy {
    name: string;
    /** The mood/genre param (the id-stable token YTM addresses it by). */
    params: string;
    playlistId?: null | string;
}

// ---------------------------------------------------------------------------
// The contract (W1-B surface; the #200 unification keeps it stable —
// every W1-B call site compiles unchanged against this interface)
// ---------------------------------------------------------------------------

/** One Up Next page (Radio / Automix). continuation pages the radio. */
export interface YtmUpNext {
    continuation?: null | string;
    songs: YtmSongRef[];
}

// ---------------------------------------------------------------------------
// The circuit guard (edge tier only)
// ---------------------------------------------------------------------------

const BACKOFF_BASE_MS = 250;
const BACKOFF_ATTEMPTS = 3;
const CIRCUIT_COOLDOWN_MS = 60 * 1000;

interface CircuitState {
    openUntilMs: number;
}

const circuit: CircuitState = { openUntilMs: 0 };

/** Injectable clock + logging + edge fetch (the test lane); production
 * defaults below. */
export interface ProviderDeps {
    edgeFetch?: EdgeFetch;
    log?: (message: string) => void;
    now?: () => number;
    sleep?: (ms: number) => Promise<void>;
}

const defaultDeps: ProviderDeps = {
    // Direct module import (not the store barrel): the barrel drags the
    // player store's chain in, which is fatal for the network-free node
    // test lane (same discipline as the P5-A canary panel).
    log: (message) => logger.warn(`[streamify/metadata] ${message}`),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

type EdgeAttemptOutcome =
    | { detail: string; kind: 'edge-unavailable' }
    | { kind: 'edge-not-found' }
    | { kind: 'edge-ok'; payload: unknown; state: 'fill' | 'fresh' | 'stale' }
    | { kind: 'edge-rate-limited' };

interface RunOptions<TPayload> {
    continuation?: string;
    /** The edge endpoint name. */
    endpoint: string;
    /** The fallback runner (client-side InnerTube lane). */
    fallback: () => Promise<null | TPayload>;
    filter?: YtmSearchFilter;
    /** The id half of the key identity (absent on query endpoints). */
    id?: string;
    query?: string;
    signal?: AbortSignal;
    /** Client-side guard for the FALLBACK payload too (double failure → parse-error). */
    validateEdge: (payload: unknown) => ValidationOutcome<TPayload>;
    validateFallback?: (payload: TPayload) => boolean;
}

/** The typed engine provider (#198's `createProvider` return type). */
interface TypedMetadataProvider {
    getAlbum(browseId: string, gl?: string, hl?: string): Promise<MetadataResult<AlbumPayload>>;
    getArtist(browseId: string, gl?: string, hl?: string): Promise<MetadataResult<ArtistPayload>>;
    /** W2-A: the timestamped chapters (7 d-TTL endpoint, seconds-space rows). */
    getChapters(videoId: string, hl?: string): Promise<MetadataResult<ChaptersPayload>>;
    getExplore(gl?: string, hl?: string): Promise<MetadataResult<ExploreHomePayload>>;
    getHomeFeed(gl?: string, hl?: string): Promise<MetadataResult<ExploreHomePayload>>;
    getLyrics(videoId: string, hl?: string): Promise<MetadataResult<LyricsPayload>>;
    getPlaylist(
        browseId: string,
        continuation?: string,
        signal?: AbortSignal,
    ): Promise<MetadataResult<PlaylistPayload>>;
    getRelated(seedVideoId: string): Promise<MetadataResult<RelatedPayload>>;
    getSearchSuggestions(
        input: string,
        gl?: string,
        hl?: string,
    ): Promise<MetadataResult<SearchSuggestPayload>>;
    getUpNext(seedVideoId: string, continuation?: string): Promise<MetadataResult<UpNextPayload>>;
    /**
     * W2-A: the official-music-video counterpart of an audio track (the
     * 24 h-TTL endpoint). videoId null in the payload = no distinct
     * official video (the toggle hides — never an error).
     */
    getVideoCounterpart(
        videoId: string,
        gl?: string,
        hl?: string,
    ): Promise<MetadataResult<CounterpartPayload>>;
    search(
        query: string,
        filter?: YtmSearchFilter,
        continuation?: string,
        gl?: string,
        hl?: string,
    ): Promise<MetadataResult<SearchPayload>>;
}

/**
 * Wrap the typed engine in the W1-B contract. Every method unwraps to
 * the honest miss; the mapping is pure field-shaping (no fabricated
 * data — absent facts resolve to their honest empties).
 */
function adaptProvider(engine: TypedMetadataProvider): MetadataProvider {
    return {
        async getAlbum(browseId, gl, hl) {
            const payload = unwrap(await engine.getAlbum(browseId, gl, hl));
            return payload === null ? null : albumPayloadToDetail(payload);
        },
        async getArtist(browseId, gl, hl) {
            const payload = unwrap(await engine.getArtist(browseId, gl, hl));
            return payload === null ? null : artistPayloadToDetail(payload);
        },
        async getExplore(gl, hl) {
            const payload = unwrap(await engine.getExplore(gl, hl));
            if (payload === null) return null;
            return { genres: taxonomiesOf(payload, 'genre'), moods: taxonomiesOf(payload, 'mood') };
        },
        async getHomeFeed(gl, hl) {
            const payload = unwrap(await engine.getHomeFeed(gl, hl));
            if (payload === null) return null;
            return {
                sections: payload.shelves.map((shelf) => {
                    const split = shelvesToSections([shelf]);
                    return {
                        albums: split.albums.length > 0 ? split.albums : undefined,
                        songs: split.songs.length > 0 ? split.songs : undefined,
                        title: shelf.title,
                    };
                }),
            };
        },
        async getLyrics(videoId, hl) {
            const payload = unwrap(await engine.getLyrics(videoId, hl));
            if (payload === null) return null;
            return {
                lang: payload.hl,
                lyrics: payload.lines.map((l) => l.text).join('\n'),
                synced: payload.synced,
            };
        },
        async getRelated(seedVideoId) {
            const payload = unwrap(await engine.getRelated(seedVideoId));
            if (payload === null) return null;
            return { songs: payload.shelves.flatMap((s) => s.items.map(itemToSongRef)) };
        },
        async getSearchSuggestions(input, gl, hl) {
            const payload = unwrap(await engine.getSearchSuggestions(input, gl, hl));
            return payload === null ? null : payload.suggestions;
        },
        async getUpNext(seedVideoId, continuation) {
            const payload = unwrap(await engine.getUpNext(seedVideoId, continuation));
            if (payload === null) return null;
            return {
                continuation: null,
                songs: payload.tracks.map((t) => ({
                    artist: t.artistText ?? '',
                    durationSec: t.durationMs === null ? null : Math.round(t.durationMs / 1000),
                    title: t.title,
                    videoId: t.videoId,
                })),
            };
        },
        async search(query, filter, continuation, gl, hl) {
            const payload = unwrap(await engine.search(query, filter, continuation, gl, hl));
            if (payload === null) return null;
            const songs: YtmSongRef[] = [];
            const albums: YtmAlbumRef[] = [];
            for (const r of payload.results) {
                const parts = subtitleParts(r.subtitle);
                if (r.videoId !== null) {
                    songs.push({
                        album: parts.extra,
                        artist: parts.artist ?? '',
                        durationSec: r.durationMs === null ? null : Math.round(r.durationMs / 1000),
                        title: r.title,
                        videoId: r.videoId,
                    });
                } else {
                    albums.push({
                        artist: parts.artist ?? '',
                        browseId: '',
                        kind: 'album',
                        thumbnailUrl: null,
                        title: r.title,
                        trackCount: null,
                        year: parseYearText(parts.extra),
                    });
                }
            }
            return {
                albums,
                artists: [],
                continuation: payload.continuation,
                playlists: [],
                songs,
            };
        },
    };
}

// ---------------------------------------------------------------------------
// The edge call with backoff + circuit
// ---------------------------------------------------------------------------

function albumPayloadToDetail(payload: AlbumPayload): YtmAlbumDetail {
    return {
        artist: payload.artistText ?? '',
        browseId: payload.browseId,
        thumbnailUrl: null,
        title: payload.title,
        tracks: payload.tracks.map((t) => ({
            album: payload.title,
            artist: payload.artistText ?? '',
            durationSec: t.durationMs === null ? null : Math.round(t.durationMs / 1000),
            title: t.title,
            videoId: t.videoId ?? '',
        })),
        year: parseYearText(payload.year),
    };
}

function artistPayloadToDetail(payload: ArtistPayload): YtmArtistDetail {
    const albums = payload.albums.map((a) => ({
        artist: payload.name,
        browseId: a.browseId,
        kind: 'album' as const,
        thumbnailUrl: null,
        title: a.title,
        trackCount: parseCountText(a.subtitle),
        year: parseYearText(a.subtitle),
    }));
    const topSongs = payload.topSongs.map((s) => ({
        artist: s.artistText ?? payload.name,
        durationSec: null,
        title: s.title,
        videoId: s.videoId,
    }));
    return {
        albums,
        appearsOn: [],
        browseId: payload.browseId,
        description: payload.description,
        liveAlbums: [],
        name: payload.name,
        relatedArtists: [],
        singles: [],
        subscribers: parseCountText(payload.listenersText),
        thumbnailUrl: null,
        topSongs,
    };
}

// ---------------------------------------------------------------------------
// The generic pipeline runner
// ---------------------------------------------------------------------------

function createProvider(deps: ProviderDeps = {}): TypedMetadataProvider {
    const resolve = (gl?: string, hl?: string) => ({
        gl: (gl ?? 'US').trim() || 'US',
        hl: (hl ?? 'en').trim() || 'en',
    });

    return {
        async getAlbum(browseId, gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'album',
                    fallback: () => fallbackAlbum(browseId, d.gl, d.hl),
                    id: browseId,
                    validateEdge: validateAlbumPayload,
                },
                deps,
                d,
            );
        },
        async getArtist(browseId, gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'artist',
                    fallback: () => fallbackArtist(browseId, d.gl, d.hl),
                    id: browseId,
                    validateEdge: validateArtistPayload,
                },
                deps,
                d,
            );
        },
        async getChapters(videoId, hl) {
            const d = resolve(undefined, hl);
            return run<ChaptersPayload>(
                {
                    endpoint: 'chapters',
                    fallback: () => fallbackChapters(videoId, d.hl),
                    id: videoId,
                    validateEdge: validateChaptersPayload,
                    validateFallback: (payload) => payload.chapters.length > 0,
                },
                deps,
                d,
            );
        },
        async getExplore(gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'explore',
                    fallback: () => fallbackExplore(d.gl, d.hl),
                    id: 'FEmusic_explore',
                    validateEdge: (payload) => validateShelvesPayload(payload, 'explore'),
                },
                deps,
                d,
            );
        },
        async getHomeFeed(gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'home',
                    fallback: () => fallbackHomeFeed(d.gl, d.hl),
                    id: 'FEmusic_home',
                    validateEdge: (payload) => validateShelvesPayload(payload, 'home'),
                },
                deps,
                d,
            );
        },
        async getLyrics(videoId, hl) {
            const d = resolve(undefined, hl);
            return run<LyricsPayload>(
                {
                    endpoint: 'lyrics',
                    fallback: () => fallbackLyrics(videoId, d.hl),
                    id: videoId,
                    validateEdge: validateLyricsPayload,
                    validateFallback: (payload) =>
                        payload.lines.length > 0 && typeof payload.synced === 'boolean',
                },
                deps,
                d,
            );
        },
        async getPlaylist(browseId, continuation, signal) {
            const d = resolve();
            return run<PlaylistPayload>(
                {
                    continuation,
                    endpoint: 'playlist',
                    fallback: () => fallbackPlaylist(browseId, continuation ?? null),
                    id: browseId,
                    signal,
                    validateEdge: validatePlaylistPayload,
                    validateFallback: (payload) => payload.tracks.length > 0,
                },
                deps,
                d,
            );
        },
        async getRelated(seedVideoId) {
            const d = resolve();
            return run(
                {
                    endpoint: 'related',
                    fallback: () => fallbackRelated(seedVideoId),
                    id: seedVideoId,
                    validateEdge: validateRelatedPayload,
                },
                deps,
                d,
            );
        },
        async getSearchSuggestions(input, gl, hl) {
            const d = resolve(gl, hl);
            return run<SearchSuggestPayload>(
                {
                    endpoint: 'search-suggest',
                    fallback: () => fallbackSearchSuggestions(input),
                    query: input,
                    validateEdge: validateSearchSuggestPayload,
                    validateFallback: (payload) => payload.suggestions.length > 0,
                },
                deps,
                d,
            );
        },
        async getUpNext(seedVideoId) {
            const d = resolve();
            return run(
                {
                    endpoint: 'up-next',
                    fallback: () => fallbackUpNext(seedVideoId),
                    id: seedVideoId,
                    validateEdge: validateUpNextPayload,
                },
                deps,
                d,
            );
        },
        async getVideoCounterpart(videoId, gl, hl) {
            const d = resolve(gl, hl);
            return run<CounterpartPayload>(
                {
                    endpoint: 'counterpart',
                    fallback: () => fallbackCounterpart(videoId, d.gl, d.hl),
                    id: videoId,
                    validateEdge: validateCounterpartPayload,
                    validateFallback: (payload) =>
                        typeof payload.sourceVideoId === 'string' &&
                        (payload.videoId === null || payload.videoId !== ''),
                },
                deps,
                d,
            );
        },
        async search(query, filter, continuation, gl, hl) {
            const d = resolve(gl, hl);
            return run<SearchPayload>(
                {
                    continuation,
                    endpoint: 'search',
                    fallback: () => fallbackSearch(query, filter ?? null),
                    filter,
                    query,
                    validateEdge: validateSearchPayload,
                    validateFallback: (payload) => payload.results.length > 0,
                },
                deps,
                d,
            );
        },
    };
}

async function edgeWithBackoff(
    call: Parameters<typeof edgeMetaCall>[0],
    deps: ProviderDeps,
): Promise<EdgeAttemptOutcome> {
    const now = deps.now ?? Date.now;
    if (circuit.openUntilMs > now()) {
        return { kind: 'edge-rate-limited' };
    }
    let delay = BACKOFF_BASE_MS;
    for (let attempt = 1; attempt <= BACKOFF_ATTEMPTS; attempt += 1) {
        if (signalAborted(call.signal)) {
            return { detail: 'aborted', kind: 'edge-unavailable' };
        }
        const outcome = await edgeMetaCall(call, deps.edgeFetch);
        if (outcome.kind === 'ok') {
            return { kind: 'edge-ok', payload: outcome.payload, state: outcome.state };
        }
        if (outcome.kind === 'not-found') {
            return { kind: 'edge-not-found' };
        }
        if (outcome.kind === 'rate-limited') {
            if (attempt < BACKOFF_ATTEMPTS) {
                // Retry-After is a FLOOR on the exponential ramp, never a
                // replacement — the doubling still climbs underneath it.
                const waitMs = Math.max(delay, outcome.retryAfterMs ?? 0);
                await sleep(deps, waitMs);
                delay *= 2;
                continue;
            }
            break;
        }
        // unavailable — one shot is enough (network/5xx/absent relay is not
        // transient enough to burn the caller's latency budget on).
        return { detail: outcome.detail, kind: 'edge-unavailable' };
    }
    // Backoff exhausted on 429s — open the circuit.
    circuit.openUntilMs = now() + CIRCUIT_COOLDOWN_MS;
    return { kind: 'edge-rate-limited' };
}

// ---------------------------------------------------------------------------
// The adapter — W1-A typed results → W1-B DTOs (the #200 unification)
// ---------------------------------------------------------------------------

/** Shelf item → song ref (search/up-next/home-feed song shelves). */
function itemToSongRef(item: ShelfItemPayload): YtmSongRef {
    const parts = subtitleParts(item.subtitle);
    return {
        album: parts.extra,
        artist: parts.artist ?? '',
        durationSec: null,
        thumbnailUrl: item.thumbnail,
        title: item.title,
        videoId: item.videoId ?? '',
    };
}

function logDiagnostic(deps: ProviderDeps, message: string): void {
    (deps.log ?? defaultDeps.log)?.(message);
}

function parseCountText(text: null | string | undefined): null | number {
    if (text === null || text === undefined) return null;
    const m = /(\d[\d,.]*)/.exec(text);
    return m === null ? null : Number(m[1].replace(/,/g, ''));
}

function parseYearText(text: null | string | undefined): null | number {
    if (text === null || text === undefined) return null;
    const m = /(\d{4})/.exec(text);
    return m === null ? null : Number(m[1]);
}

async function run<TPayload>(
    options: RunOptions<TPayload>,
    deps: ProviderDeps,
    defaults: { gl: string; hl: string },
): Promise<MetadataResult<TPayload>> {
    const now = deps.now ?? Date.now;
    const key = snapshotKey(
        options.endpoint,
        options.query ?? options.id ?? '',
        defaults.gl,
        defaults.hl,
        options.continuation,
    );

    // Stage 0: the snapshot (last-known-good, or a fresh negative entry).
    const cached = snapshotLookup(key, now());
    if (cached !== null) {
        if (cached.negative) return { kind: 'not-found' };
        return { kind: 'ok', payload: cached.payload as TPayload, source: 'snapshot' };
    }

    if (signalAborted(options.signal)) {
        return { detail: 'aborted before the edge call', kind: 'parse-error' };
    }

    // Stage 1: the edge (backoff + circuit).
    const edgeOutcome = await edgeWithBackoff(
        {
            continuation: options.continuation,
            endpoint: options.endpoint,
            filter: options.filter,
            gl: defaults.gl,
            hl: defaults.hl,
            id: options.id === '' ? undefined : options.id,
            query: options.query,
            signal: options.signal,
        },
        deps,
    );

    if (edgeOutcome.kind === 'edge-not-found') {
        // The 404 class: remember it 15 min, zero retries, no fallback — a
        // missing entity is not transient.
        snapshotUpsert(key, null, now());
        return { kind: 'not-found' };
    }

    if (edgeOutcome.kind === 'edge-ok') {
        const validated = options.validateEdge(edgeOutcome.payload);
        if (validated.ok) {
            snapshotUpsert(key, validated.payload as MetadataPayload, now());
            return { kind: 'ok', payload: validated.payload, source: 'edge' };
        }
        // Parse-error class: diagnostic + clean fallback below.
        logDiagnostic(
            deps,
            `edge payload failed validation (${options.endpoint}): ${validated.reason}`,
        );
    } else if (edgeOutcome.kind === 'edge-unavailable' && edgeOutcome.detail !== 'aborted') {
        logDiagnostic(deps, `edge unavailable (${options.endpoint}): ${edgeOutcome.detail}`);
    }

    if (signalAborted(options.signal)) {
        return { detail: 'aborted before the fallback', kind: 'parse-error' };
    }

    // Stage 2: the client-side InnerTube fallback.
    try {
        const payload = await options.fallback();
        if (payload !== null) {
            if (options.validateFallback !== undefined && !options.validateFallback(payload)) {
                return {
                    detail: `fallback payload failed validation (${options.endpoint})`,
                    kind: 'parse-error',
                };
            }
            snapshotUpsert(key, payload as MetadataPayload, now());
            return { kind: 'ok', payload, source: 'fallback' };
        }
    } catch (err) {
        logDiagnostic(
            deps,
            `fallback threw (${options.endpoint}): ${err instanceof Error ? err.message : String(err)}`,
        );
    }

    // Stage 3: the honest terminal results.
    if (edgeOutcome.kind === 'edge-rate-limited') {
        return { kind: 'rate-limited' };
    }
    if (edgeOutcome.kind === 'edge-unavailable' && edgeOutcome.detail === 'aborted') {
        return { detail: 'aborted', kind: 'parse-error' };
    }
    if (edgeOutcome.kind === 'edge-ok') {
        return {
            detail: `payload failed validation on both tiers (${options.endpoint})`,
            kind: 'parse-error',
        };
    }
    // Edge unavailable + fallback empty: the entity simply does not resolve
    // — the not-found form (zero retry storm; the snapshot remembers).
    snapshotUpsert(key, null, now());
    return { kind: 'not-found' };
}

/** Explore/home shelves → the section split (albums vs songs per item). */
function shelvesToSections(shelves: Array<{ items: ShelfItemPayload[]; title: string }>): {
    albums: YtmAlbumRef[];
    songs: YtmSongRef[];
} {
    const albums: YtmAlbumRef[] = [];
    const songs: YtmSongRef[] = [];
    for (const shelf of shelves) {
        for (const item of shelf.items) {
            // The item's own navigation decides its shape: a browseId with
            // no videoId is an album/artist tile; a videoId is playable.
            if (item.videoId !== null) {
                songs.push(itemToSongRef(item));
            } else if (item.browseId !== null) {
                const parts = subtitleParts(item.subtitle);
                albums.push({
                    artist: parts.artist ?? '',
                    browseId: item.browseId,
                    kind: 'album',
                    thumbnailUrl: item.thumbnail,
                    title: item.title,
                    trackCount: parseCountText(parts.extra),
                    year: parseYearText(parts.extra),
                });
            }
        }
    }
    return { albums, songs };
}

/** Fresh abort read — a function boundary per call: the signal can fire
 * mid-await, so a narrowed `aborted === false` from an earlier check must
 * never suppress a later one. */
function signalAborted(signal: AbortSignal | undefined): boolean {
    return signal?.aborted === true;
}

/** "Artist • 2023" / "Artist • Album • 3:45" subtitle splits. */
function subtitleParts(subtitle: null | string): { artist: null | string; extra: null | string } {
    if (subtitle === null) return { artist: null, extra: null };
    const segments = subtitle
        .split(' • ')
        .map((s) => s.trim())
        .filter((s) => s !== '');
    return { artist: segments[0] ?? null, extra: segments[1] ?? null };
}

/** Shelf taxonomy extraction: the Explore page's named sections carry the
 * mood/genre chips; the browseId is the id-stable token per tile. */
function taxonomiesOf(payload: ExploreHomePayload, kind: 'genre' | 'mood'): YtmTaxonomy[] {
    const out: YtmTaxonomy[] = [];
    for (const shelf of payload.shelves) {
        const title = shelf.title.toLowerCase();
        const isMoodShelf = kind === 'mood' && /moods?/.test(title);
        const isGenreShelf = kind === 'genre' && /genres?/.test(title) && !/moods?/.test(title);
        if (!isMoodShelf && !isGenreShelf) continue;
        for (const item of shelf.items) {
            if (item.browseId === null) continue;
            out.push({
                name: item.title,
                params: item.browseId,
                playlistId: null,
            });
        }
    }
    return out;
}

/**
 * The honest-miss unwrap: not-found / parse-error / rate-limited all map
 * to null. The typed forms stay available through `getPlaylistTyped`
 * (the importer's failure separation) — the W1-B catalog/lyrics callers
 * only ever need the payload or its honest absence.
 */
function unwrap<TPayload>(result: MetadataResult<TPayload>): null | TPayload {
    return result.kind === 'ok' ? result.payload : null;
}

// ---------------------------------------------------------------------------
// The stub + the installers (W1-B seam, unchanged)
// ---------------------------------------------------------------------------

/**
 * The pre-W1-A stub: every method resolves an honest miss. Kept for
 * tests and for snapshot-only deployments — the DEFAULT installer below
 * stopped serving it the moment #198's engine landed (the #200
 * unification).
 */
export const stubMetadataProvider: MetadataProvider = {
    getAlbum: async () => null,
    getArtist: async () => null,
    getExplore: async () => null,
    getHomeFeed: async () => null,
    getLyrics: async () => null,
    getRelated: async () => null,
    getSearchSuggestions: async () => null,
    getUpNext: async () => null,
    search: async () => null,
};

/** The W1-A engine singleton behind the W1-B DTO contract. */
export const metadataProvider: MetadataProvider = adaptProvider(createProvider());

let installed: MetadataProvider = metadataProvider;

/** The provider every W1-B consumer resolves through. */
export function getMetadataProvider(): MetadataProvider {
    return installed;
}

/**
 * W1-A's registration entry point: install an Edge KV-backed
 * implementation (idempotent, last write wins). Call sites in
 * api/streamify/{catalog,lyrics}/ never change when this flips.
 */
export function installMetadataProvider(provider: MetadataProvider): void {
    installed = provider;
}

/** Test seam: inject a fake provider (vitest isolation). */
export function setMetadataProviderForTests(provider: MetadataProvider | null): void {
    installed = provider ?? stubMetadataProvider;
}

// ---------------------------------------------------------------------------
// The typed-engine seams (#198's importer lane)
// ---------------------------------------------------------------------------

/** The typed engine singleton (the importer's provider). */
export const typedMetadataProvider: TypedMetadataProvider = createProvider();

/** Test seam: build an isolated typed provider with injected deps. */
export function createMetadataProviderForTests(deps: ProviderDeps): TypedMetadataProvider {
    return createProvider(deps);
}

/**
 * W2-A: the typed chapters read — the video overlay / chapter strip's
 * seam (same typed-result separation the importer's playlist read keeps;
 * the W1-B DTO contract has no chapters method by design).
 */
export function getChaptersTyped(
    videoId: string,
    hl?: string,
): Promise<MetadataResult<ChaptersPayload>> {
    return typedMetadataProvider.getChapters(videoId, hl);
}

/**
 * The importer's typed playlist read: keeps the not-found / rate-limited
 * / parse-error separation #198's ingest.ts reports on. The W1-B
 * contract has no playlist method (mood tiles drive the radio instead).
 */
export function getPlaylistTyped(
    browseId: string,
    continuation?: string,
    signal?: AbortSignal,
): Promise<MetadataResult<PlaylistPayload>> {
    return typedMetadataProvider.getPlaylist(browseId, continuation, signal);
}

/**
 * W2-A: the typed counterpart read — the Audio ↔ Video toggle's seam
 * (videoId null in the payload is the honest "no distinct official
 * video", NOT a failure).
 */
export function getVideoCounterpartTyped(
    videoId: string,
    gl?: string,
    hl?: string,
): Promise<MetadataResult<CounterpartPayload>> {
    return typedMetadataProvider.getVideoCounterpart(videoId, gl, hl);
}

/** Test seam: reset the circuit + snapshot state between cases. */
export function resetMetadataProviderForTests(): void {
    circuit.openUntilMs = 0;
}

async function sleep(deps: ProviderDeps, ms: number): Promise<void> {
    await (deps.sleep ?? defaultDeps.sleep)?.(ms);
}
