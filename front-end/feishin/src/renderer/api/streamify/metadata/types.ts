/**
 * streamify/metadata/types.ts — the typed payload schemas + result types
 * for the W1-A metadata seam.
 *
 * THE CONTRACT (Engineer B wires W1-B against this): the payload family
 * mirrors the relay worker's normalized schemas (front-end/relay/src/
 * ytm-meta.ts). The two packages are separate workspaces — the SCHEMA is
 * the shared contract across the runtimes, not the code; both sides'
 * vitest suites assert the same shapes (the relay lane against its own
 * fixtures, this lane against the mirrored types). Every payload carries a
 * `kind` discriminator so consumers can switch exhaustively.
 *
 * Result separation (the failure-mode contract):
 *   'ok'          — payload present; `source` says which tier answered
 *                   (edge | fallback | snapshot).
 *   'not-found'   — the 404 class: missing entity / empty shelf. ZERO
 *                   retries by construction (a missing entity is not an
 *                   error condition; it is remembered for 15 min so
 *                   repeat calls in one session never re-hit).
 *   'rate-limited'— the 429 class: upstream rate limit after the
 *                   provider's exponential backoff, or the circuit guard
 *                   is open (60 s cooldown; further edge calls fail fast).
 *   'parse-error' — schema parse failure: a diagnostic is logged, a clean
 *                   fallback to the client-side InnerTube lane is
 *                   attempted, and only a second failure surfaces this
 *                   result.
 */

// ---------------------------------------------------------------------------
// Normalized payload schemas (mirrors front-end/relay/src/ytm-meta.ts —
// the W1-A ADR §G records the accepted duplication and why forcing a
// shared module across the workspace boundary would couple the worker to
// the feishin tree).
// ---------------------------------------------------------------------------

export interface AlbumPayload {
    artistText: null | string;
    browseId: string;
    gl: string;
    hl: string;
    kind: 'album';
    title: string;
    trackCountTotal: null | number;
    tracks: Array<{ durationMs: null | number; title: string; videoId: null | string }>;
    year: null | string;
}

export interface ArtistPayload {
    albums: Array<{ browseId: string; subtitle: null | string; title: string }>;
    browseId: string;
    description: null | string;
    gl: string;
    hl: string;
    kind: 'artist';
    listenersText: null | string;
    name: string;
    topSongs: Array<{ artistText: null | string; title: string; videoId: string }>;
}

export interface ChaptersPayload {
    /** W2-A: the normalized chapter row — startSeconds required,
     * endSeconds null on the last chapter (the UI closes it with the
     * element's duration). Rows cached by a W1-A-era worker that carry only
     * atMs are coerced by validateChaptersPayload (startSeconds = atMs/1000). */
    chapters: Array<{
        endSeconds: null | number;
        startSeconds: number;
        thumbnailUrl: null | string;
        title: string;
    }>;
    hl: string;
    kind: 'chapters';
    videoId: string;
}

/**
 * W2-A: the official-music-video counterpart of an audio track (the
 * 24 h-TTL endpoint — mirrors the worker's CounterpartPayload).
 * videoId null = no distinct official video (the toggle hides).
 */
export interface CounterpartPayload {
    artistText: null | string;
    confidence: 'strong' | 'weak' | null;
    durationMs: null | number;
    kind: 'counterpart';
    /** The audio track's videoId the resolution ran for. */
    sourceVideoId: string;
    /** The matched video's title (null when videoId is null). */
    title: null | string;
    /** The official music video's videoId, or null when none matched. */
    videoId: null | string;
}

export interface CreditsPayload {
    hl: string;
    kind: 'credits';
    roleGroups: Array<{
        credits: Array<{ name: string; subtitle: null | string }>;
        title: string;
    }>;
    videoId: string;
}

export type ExploreHomeKind = 'explore' | 'home';

export interface ExploreHomePayload {
    gl: string;
    hl: string;
    kind: ExploreHomeKind;
    shelves: Array<{ items: ShelfItemPayload[]; title: string }>;
}

export type HomePayload = ExploreHomePayload;

export interface LyricsPayload {
    hl: string;
    kind: 'lyrics';
    /** atMs null = unsynced line (plain text). */
    lines: Array<{ atMs: null | number; text: string }>;
    source: string;
    /** true iff every line carries a timestamp — LRCLIB-tier consumers
     * rank synced above plain text on exactly this flag. */
    synced: boolean;
    videoId: string;
}

export interface MetadataNotFound {
    kind: 'not-found';
}

export interface MetadataOk<T> {
    kind: 'ok';
    /** Which tier answered — the quality badge surface (W1-B) reads this. */
    payload: T;
    source: 'edge' | 'fallback' | 'snapshot';
}

export interface MetadataParseError {
    detail: string;
    kind: 'parse-error';
}

export type MetadataPayload =
    | AlbumPayload
    | ArtistPayload
    | ChaptersPayload
    | CounterpartPayload
    | CreditsPayload
    | ExploreHomePayload
    | LyricsPayload
    | PlaylistPayload
    | RelatedPayload
    | SearchPayload
    | SearchSuggestPayload
    | UpNextPayload;

/** The provider seam StreamifyController's W1-B wiring consumes. */
export interface MetadataProvider {
    getAlbum(browseId: string, gl?: string, hl?: string): Promise<MetadataResult<AlbumPayload>>;
    getArtist(browseId: string, gl?: string, hl?: string): Promise<MetadataResult<ArtistPayload>>;
    /**
     * W2-A: the timestamped chapters of a video (the 7 d-TTL endpoint —
     * DESCRIPTION_CHAPTERS / AUTO_CHAPTERS markers, normalized to
     * seconds-space boundaries). An empty/not-found result is the honest
     * "this video has no chapters" form.
     */
    getChapters(videoId: string, hl?: string): Promise<MetadataResult<ChaptersPayload>>;
    /** W2-B: the credits lane (7-day TTL class — the Liner Notes surface). */
    getCredits(videoId: string, hl?: string): Promise<MetadataResult<CreditsPayload>>;
    getExplore(gl?: string, hl?: string): Promise<MetadataResult<ExploreHomePayload>>;
    getHomeFeed(gl?: string, hl?: string): Promise<MetadataResult<HomePayload>>;
    getLyrics(videoId: string, hl?: string): Promise<MetadataResult<LyricsPayload>>;
    /**
     * ONE page of a playlist (the importer drives continuation pages in
     * batches). `signal` aborts the in-flight leg immediately.
     */
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
     * W2-A: resolve the official-music-video counterpart of an audio track
     * (the 24 h-TTL endpoint). videoId null in the payload = no distinct
     * official video exists (the toggle hides — never an error).
     */
    getVideoCounterpart(
        videoId: string,
        gl?: string,
        hl?: string,
    ): Promise<MetadataResult<CounterpartPayload>>;
    search(
        query: string,
        filter?: MetadataSearchFilter,
        continuation?: string,
        gl?: string,
        hl?: string,
    ): Promise<MetadataResult<SearchPayload>>;
}

export interface MetadataRateLimited {
    kind: 'rate-limited';
}

export type MetadataResult<T> =
    | MetadataNotFound
    | MetadataOk<T>
    | MetadataParseError
    | MetadataRateLimited;

export type MetadataSearchFilter = 'albums' | 'artists' | 'playlists' | 'songs';

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

export interface PlaylistPayload {
    /** Continuation for the NEXT page (null on the last page). */
    continuation: null | string;
    description: null | string;
    kind: 'playlist';
    listId: string;
    title: null | string;
    /** Total track count from the header, when YTM discloses it. */
    trackCountTotal: null | number;
    tracks: PlaylistTrack[];
}

export interface PlaylistTrack {
    albumText: null | string;
    artistText: null | string;
    durationMs: null | number;
    index: number;
    title: string;
    videoId: null | string;
}

export interface RelatedPayload {
    kind: 'related';
    seedVideoId: string;
    shelves: Array<{ items: ShelfItemPayload[]; title: string }>;
}

export interface SearchPayload {
    continuation: null | string;
    filter: null | string;
    kind: 'search';
    query: string;
    results: Array<{
        durationMs: null | number;
        subtitle: null | string;
        title: string;
        videoId: null | string;
    }>;
}

export interface SearchSuggestPayload {
    input: string;
    kind: 'search-suggest';
    suggestions: string[];
}

export interface ShelfItemPayload {
    browseId: null | string;
    pageType: null | string;
    subtitle: null | string;
    thumbnail: null | string;
    title: string;
    videoId: null | string;
}

export interface UpNextPayload {
    kind: 'up-next';
    seedVideoId: string;
    tracks: Array<{
        artistText: null | string;
        durationMs: null | number;
        title: string;
        videoId: string;
    }>;
}

/** The negative-entry TTL for the snapshot (not-found remembrance). */
export const SNAPSHOT_NEGATIVE_TTL_MS = 15 * 60 * 1000;
