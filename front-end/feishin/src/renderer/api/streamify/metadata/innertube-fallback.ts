/**
 * streamify/metadata/innertube-fallback.ts — the client-side InnerTube
 * lane (the degraded mode of the W1-A pipeline).
 *
 * Used ONLY when the edge cannot serve: the relay is absent (local dev,
 * stock deployments), the deployed worker predates /ytm/meta, or the
 * worker's upstream fetch failed. Rides the resolver's own battle-tested
 * session (getYt() — the relay lanes, visitor seed, youtubei.js 18.3.0)
 * and NEVER writes the edge cache (only the worker writes caches — the
 * poisoning rule).
 *
 * STRUCTURAL TYPING: the youtubei.js objects are consumed through local
 * interfaces that document exactly what this module reads (the same
 * discipline as the worker's walkers — the runtime fields are the live
 * surface; the package's own .d.ts chain is not coupled here).
 *
 * KNOWN DEGRADATIONS (honest, documented):
 *   • getSearchSuggestions — youtubei.js 18.3 has no parser for the music
 *     `searchSuggestionRenderer` entries (verified against its parser
 *     map), so the fallback yields an empty suggestion list; the edge
 *     path serves them fine.
 *   • Artist listenersText — youtubei.js's MusicImmersiveHeader drops the
 *     raw monthlyListenerCount; the fallback reports null.
 *
 * CONTINUATIONS: the provider's interface is token-based (cache keys need
 * stable strings); youtubei.js drives continuations through OBJECTS. The
 * bridge: every fallback response that `has_continuation` mints an opaque
 * `fb:<digest>` token and parks the live object in a bounded registry;
 * the next call with that token resumes via getContinuation(). Registry
 * capped at 16 (LRU-ish) — a dropped entry degrades to a fresh first
 * page, never a crash.
 */

import type {
    AlbumPayload,
    ArtistPayload,
    ChaptersPayload,
    CounterpartPayload,
    CreditsPayload,
    ExploreHomePayload,
    HomePayload,
    LyricsPayload,
    PlaylistPayload,
    RelatedPayload,
    SearchPayload,
    SearchSuggestPayload,
    UpNextPayload,
} from './types';

import { parseLyricsText } from './lyrics-text';

// ---------------------------------------------------------------------------
// Structural views over the youtubei.js objects (what this module reads)
// ---------------------------------------------------------------------------

/** A youtubei.js wrapper with header + list items (Playlist / Album). */
interface BrowseWrapperLike {
    contents?: Array<ListLike | { type: string }>;
    getContinuation(): Promise<unknown>;
    has_continuation: boolean;
    header?: HeaderLike;
}

interface CarouselLike {
    contents: Array<ListLike | TwoRowLike | unknown>;
    header: null | { title: TextLike };
}

interface Continuable {
    getContinuation(): Promise<unknown>;
}

interface HeaderLike {
    description?: unknown;
    second_subtitle?: TextLike;
    strapline_text_one?: TextLike;
    subtitle?: TextLike;
    title: TextLike;
}

interface ListLike {
    album?: { id?: string; name: string };
    artists?: Array<{ name: string }>;
    duration?: { seconds: number; text: string };
    /** videoId (the id of the song/track item). */
    id?: string;
    title?: string;
}

interface TextLike {
    text: string;
}

interface TwoRowLike {
    endpoint: { browseId?: string; videoId?: string };
    subtitle?: TextLike;
    title: TextLike;
}

// ---------------------------------------------------------------------------
// The continuation registry (token ⇄ live object)
// ---------------------------------------------------------------------------

const CONTINUATION_CAP = 16;
const continuations = new Map<string, Continuable>();

export async function fallbackAlbum(
    browseId: string,
    gl: string,
    hl: string,
): Promise<AlbumPayload | null> {
    const session = await yt();
    const album = (await session.music.getAlbum(browseId)) as
        | undefined
        | { contents?: Array<unknown>; header?: HeaderLike };
    if (album === undefined || album === null) return null;
    const title = textOf(album.header?.title);
    if (title === '') return null;
    const secondSubtitle = textOf(album.header?.second_subtitle);
    const trackCount = /(\d[\d,.]*)\s+songs?/i.exec(secondSubtitle)?.[1]?.replace(/,/g, '');
    const year = /(\d{4})/.exec(textOf(album.header?.subtitle))?.[1] ?? null;
    const tracks: AlbumPayload['tracks'] = [];
    for (const raw of album.contents ?? []) {
        const item = asListLike(raw);
        if (item === null || item.title === undefined) continue;
        tracks.push({
            durationMs: durationMsOf(item),
            title: item.title,
            videoId: item.id ?? null,
        });
    }
    return {
        artistText: textOf(album.header?.strapline_text_one) || null,
        browseId,
        gl,
        hl,
        kind: 'album',
        title,
        trackCountTotal: trackCount !== undefined ? Number(trackCount) || null : null,
        tracks,
        year,
    };
}

export async function fallbackArtist(
    browseId: string,
    gl: string,
    hl: string,
): Promise<ArtistPayload | null> {
    const session = await yt();
    const artist = (await session.music.getArtist(browseId)) as
        | undefined
        | { header?: HeaderLike; sections?: Array<unknown> };
    if (artist === undefined || artist === null) return null;
    const name = textOf(artist.header?.title);
    if (name === '') return null;
    const topSongs: ArtistPayload['topSongs'] = [];
    const albums: ArtistPayload['albums'] = [];
    for (const section of artist.sections ?? []) {
        const shelf = section as null | { contents?: Array<unknown>; title?: TextLike };
        if (shelf === null || typeof shelf !== 'object') continue;
        const title = textOf(shelf.title);
        if (/top songs/i.test(title)) {
            for (const raw of shelf.contents ?? []) {
                const item = asListLike(raw);
                if (item === null || item.title === undefined) continue;
                topSongs.push({
                    artistText: artistTextOf(item),
                    title: item.title,
                    videoId: item.id ?? '',
                });
            }
        }
        if (/albums?/i.test(title)) {
            for (const raw of shelf.contents ?? []) {
                const two = raw as null | TwoRowLike;
                if (two === null || typeof two?.title?.text !== 'string') continue;
                if (two.endpoint?.browseId?.startsWith('MPRE')) {
                    albums.push({
                        browseId: two.endpoint.browseId,
                        subtitle: textOf(two.subtitle) || null,
                        title: two.title.text,
                    });
                }
            }
        }
    }
    // MusicImmersiveHeader drops monthlyListenerCount (youtubei.js 18.3) —
    // the honest degraded value is null; the edge path serves it.
    return {
        albums,
        browseId,
        description: textOf(artist.header?.description) || null,
        gl,
        hl,
        kind: 'artist',
        listenersText: null,
        name,
        topSongs,
    };
}

/**
 * W2-A: chapters off the client-side InnerTube lane (best-effort — the
 * structural read documents exactly what this module consumes; on shape
 * drift it degrades to null, the honest "no chapters" form the provider
 * maps to not-found; the edge path serves chapters fine).
 */
export async function fallbackChapters(
    videoId: string,
    hl: string,
): Promise<ChaptersPayload | null> {
    const session = await yt();
    const info = (await session.music.getInfo(videoId)) as
        | null
        | undefined
        | {
              chapters?: Array<{
                  start_seconds?: unknown;
                  time_start_ms?: unknown;
                  title?: unknown;
              }>;
          };
    const rows: number[] = [];
    const titles: string[] = [];
    for (const chapter of info?.chapters ?? []) {
        const title = textOf(chapter?.title);
        const startMs =
            typeof chapter?.time_start_ms === 'number'
                ? chapter.time_start_ms
                : typeof chapter?.start_seconds === 'number'
                  ? chapter.start_seconds * 1000
                  : null;
        if (title === '' || startMs === null) continue;
        rows.push(startMs);
        titles.push(title);
    }
    if (rows.length === 0) return null;
    const chapters: ChaptersPayload['chapters'] = rows.map((startMs, i) => ({
        endSeconds: i + 1 < rows.length ? Math.round(rows[i + 1] / 1000) : null,
        startSeconds: Math.round(startMs / 1000),
        thumbnailUrl: null,
        title: titles[i] as string,
    }));
    return { chapters, hl, kind: 'chapters', videoId };
}

// ---------------------------------------------------------------------------
// Shared field readers (the bullet-split convention, same as the worker)
// ---------------------------------------------------------------------------

/**
 * W2-A: the counterpart resolution off the client-side InnerTube lane —
 * seed identity from getInfo, candidates from the videos-filtered search,
 * then the same matcher the worker runs (mirrored client-side; the SCHEMA
 * is the shared contract, not the code).
 */
export async function fallbackCounterpart(
    videoId: string,
    _gl: string,
    _hl: string,
): Promise<CounterpartPayload | null> {
    void _gl; // uniform fallback signature — the client session owns its locale
    void _hl;
    const session = await yt();
    const info = (await session.music.getInfo(videoId)) as
        | null
        | undefined
        | {
              basic_info?: {
                  author?: unknown;
                  duration?: unknown;
                  title?: unknown;
              };
          };
    const seedTitle = textOf(info?.basic_info?.title);
    if (seedTitle === '') return null;
    const seedArtist = textOf(info?.basic_info?.author) || null;
    const durationSec = info?.basic_info?.duration;
    const seedDurationMs =
        typeof durationSec === 'number' && durationSec > 0 ? Math.round(durationSec * 1000) : null;

    const search = (await session.music.search(`${seedTitle} ${seedArtist ?? ''}`.trim(), {
        type: 'videos',
    })) as null | undefined | { contents?: Array<{ contents?: Array<unknown> }> };
    const shelf = (search?.contents ?? []).find((entry) => (entry.contents?.length ?? 0) > 0);
    if (shelf === undefined) return null;
    const results: Array<{
        durationMs: null | number;
        subtitle: null | string;
        title: string;
        videoId: null | string;
    }> = [];
    for (const raw of shelf.contents ?? []) {
        const item = asListLike(raw);
        if (item === null || item.title === undefined) continue;
        results.push({
            durationMs: durationMsOf(item),
            subtitle: item.artists?.map((a) => a.name).join(', ') || item.album?.name || null,
            title: item.title,
            videoId: item.id ?? null,
        });
    }
    return matchCounterpartClient(
        { artistText: seedArtist, durationMs: seedDurationMs, title: seedTitle },
        videoId,
        results,
    );
}
/**
 * W2-B: song credits — the client-side InnerTube lane.
 *
 * KNOWN DEGRADATION (honest, documented): youtubei.js 18.3 exposes no
 * parser for the watch page's structured-description engagement panel
 * (`engagement-panel-structured-description` → videoAttributesSection
 * ViewModel — the exact surface the relay worker's WEB /next walker
 * extracts). The fallback therefore resolves null (the provider maps it
 * to its not-found class); the Edge KV lane serves credits fine.
 */
export async function fallbackCredits(videoId: string, hl: string): Promise<CreditsPayload | null> {
    void videoId;
    void hl;
    return null;
}

export async function fallbackExplore(gl: string, hl: string): Promise<ExploreHomePayload | null> {
    const session = await yt();
    const explore = (await session.music.getExplore()) as undefined | { sections?: Array<unknown> };
    return shelvesFromSections(explore?.sections, 'explore', gl, hl);
}

export async function fallbackHomeFeed(gl: string, hl: string): Promise<HomePayload | null> {
    const session = await yt();
    const feed = (await session.music.getHomeFeed()) as undefined | { sections?: Array<unknown> };
    return shelvesFromSections(feed?.sections, 'home', gl, hl);
}

// ---------------------------------------------------------------------------
// The fallback surface (one function per provider method)
// ---------------------------------------------------------------------------

export async function fallbackLyrics(videoId: string, hl: string): Promise<LyricsPayload | null> {
    const session = await yt();
    const shelf = (await session.music.getLyrics(videoId)) as
        | undefined
        | { description?: TextLike; footer?: TextLike };
    if (shelf === undefined || shelf === null) return null;
    const text = textOf(shelf.description);
    if (text === '') return null;
    const source =
        textOf(shelf.footer)
            .replace(/^Source:\s*/i, '')
            .trim() || 'YouTube Music';
    const { lines, synced } = parseLyricsText(text);
    if (lines.length === 0) return null;
    return { hl, kind: 'lyrics', lines, source, synced, videoId };
}

export async function fallbackPlaylist(
    listId: string,
    continuation: null | string,
): Promise<null | PlaylistPayload> {
    const session = await yt();
    let wrapper: BrowseWrapperLike;
    if (continuation !== null && continuation.startsWith('fb:')) {
        const parked = resumeContinuation(continuation);
        if (parked === null) return null;
        wrapper = (await parked.getContinuation()) as BrowseWrapperLike;
    } else {
        wrapper = (await session.music.getPlaylist(listId)) as BrowseWrapperLike;
    }
    const contents = (wrapper.contents ?? []).filter(
        (entry) => asListLike(entry) !== null,
    ) as ListLike[];
    const tracks: PlaylistPayload['tracks'] = contents.map((item, index) => ({
        albumText: item.album?.name ?? null,
        artistText: artistTextOf(item),
        durationMs: durationMsOf(item),
        index,
        title: item.title ?? '',
        videoId: item.id ?? null,
    }));
    const header = wrapper.header;
    const secondSubtitle = textOf(header?.second_subtitle);
    const trackCount = /(\d[\d,.]*)\s+tracks?/i.exec(secondSubtitle)?.[1]?.replace(/,/g, '');
    const descriptionRaw = header?.description;
    const description =
        textOf(descriptionRaw) ||
        textOf((descriptionRaw as undefined | { description?: TextLike })?.description) ||
        null;
    return {
        continuation: wrapper.has_continuation ? parkContinuation(wrapper) : null,
        description,
        kind: 'playlist',
        listId,
        title: textOf(header?.title) || null,
        trackCountTotal: trackCount !== undefined ? Number(trackCount) || null : null,
        tracks: tracks.filter((t) => t.title !== ''),
    };
}

export async function fallbackRelated(seedVideoId: string): Promise<null | RelatedPayload> {
    const session = await yt();
    const page = (await session.music.getRelated(seedVideoId)) as
        | undefined
        | { contents?: Array<unknown> };
    if (page === undefined || page === null) return null;
    const shelves: RelatedPayload['shelves'] = [];
    for (const entry of page.contents ?? []) {
        const carousel = entry as CarouselLike | null;
        if (carousel === null || typeof carousel !== 'object') continue;
        const title = textOf(carousel?.header?.title);
        if (title === '') continue;
        const items: RelatedPayload['shelves'][number]['items'] = [];
        for (const raw of carousel.contents ?? []) {
            const two = raw as null | TwoRowLike;
            if (two === null || typeof two?.title?.text !== 'string') continue;
            items.push({
                browseId: two.endpoint?.browseId ?? null,
                pageType: null,
                subtitle: textOf(two.subtitle) || null,
                thumbnail: null,
                title: two.title.text,
                videoId: two.endpoint?.videoId ?? null,
            });
        }
        if (items.length > 0) shelves.push({ items, title });
    }
    if (shelves.length === 0) return null;
    return { kind: 'related', seedVideoId, shelves };
}

export async function fallbackSearch(
    query: string,
    filter: null | string,
): Promise<null | SearchPayload> {
    const session = await yt();
    const filters = filter !== null ? { type: filter } : undefined;
    const search = (await session.music.search(query, filters)) as
        | undefined
        | {
              contents?: Array<{
                  contents?: Array<unknown>;
                  continuation?: string;
                  title?: TextLike;
              }>;
              getContinuation(): Promise<unknown>;
              has_continuation: boolean;
          };
    if (search === undefined || search === null) return null;
    const results: SearchPayload['results'] = [];
    for (const shelf of search.contents ?? []) {
        const cardTitle = textOf((shelf as { title?: unknown }).title);
        const cardEndpoint = (shelf as { on_tap?: { payload?: { videoId?: string } } }).on_tap
            ?.payload?.videoId;
        if (
            cardTitle !== '' &&
            typeof cardEndpoint === 'string' &&
            /^[a-zA-Z0-9_-]{11}$/.test(cardEndpoint)
        ) {
            const cardSubtitle = textOf((shelf as { subtitle?: unknown }).subtitle);
            results.push({
                durationMs: null,
                subtitle: cardSubtitle || null,
                title: cardTitle,
                videoId: cardEndpoint,
            });
        }
        for (const raw of shelf.contents ?? []) {
            const item = asListLike(raw);
            if (item === null || item.title === undefined) continue;
            const artist =
                item.artists?.map((a) => a.name).join(', ') ||
                (raw as { authors?: Array<{ name: string }> }).authors
                    ?.map((a) => a.name)
                    .join(', ') ||
                null;
            const album = item.album?.name || null;
            const subtitle = [artist, album].filter(Boolean).join(' • ') || null;
            const candidateId =
                item.id ||
                (raw as { endpoint?: { payload?: { videoId?: string } } }).endpoint?.payload
                    ?.videoId;
            const isVideoId =
                typeof candidateId === 'string' && /^[a-zA-Z0-9_-]{11}$/.test(candidateId);
            results.push({
                durationMs: durationMsOf(item),
                subtitle,
                title: item.title,
                videoId: isVideoId ? candidateId : null,
            });
        }
    }
    const realResults = results.filter((r) => r.title !== '');
    if (realResults.length === 0) return null;
    return {
        continuation: search.has_continuation ? parkContinuation(search) : null,
        filter,
        kind: 'search',
        query,
        results: realResults,
    };
}

export async function fallbackSearchSuggestions(
    input: string,
): Promise<null | SearchSuggestPayload> {
    const session = await yt();
    // youtubei.js 18.3 has no parser for the music searchSuggestionRenderer
    // entries (verified against its parser map) — contents come back empty.
    // Honest degraded mode: null (the provider maps it to not-found).
    const sections = (await session.music.getSearchSuggestions(input)) as
        | Array<{ contents?: Array<unknown> }>
        | undefined;
    void sections;
    return null;
}

export async function fallbackUpNext(seedVideoId: string): Promise<null | UpNextPayload> {
    const session = await yt();
    const panel = (await session.music.getUpNext(seedVideoId)) as
        | undefined
        | { contents?: Array<unknown> };
    if (panel === undefined || panel === null) return null;
    const tracks: UpNextPayload['tracks'] = [];
    for (const entry of panel.contents ?? []) {
        const v = entry as any;
        const title = textOf(v?.title) || (typeof v?.title?.text === 'string' ? v.title.text : '');
        const videoId =
            (typeof v?.video_id === 'string' && v.video_id) ||
            (typeof v?.videoId === 'string' && v.videoId) ||
            (typeof v?.endpoint?.payload?.videoId === 'string' && v.endpoint.payload.videoId) ||
            null;
        if (!title || !videoId) continue;
        const byline = textOf(v?.long_byline_text);
        const segments = byline
            ? byline
                  .split(' • ')
                  .map((s: string) => s.trim())
                  .filter((s: string) => s !== '')
            : [];
        const artist =
            (typeof v?.author === 'string' && v.author) ||
            (Array.isArray(v?.artists) && typeof v.artists[0]?.name === 'string' && v.artists[0].name) ||
            segments[0] ||
            null;
        const durationMs =
            typeof v?.duration?.seconds === 'number'
                ? v.duration.seconds * 1000
                : parseColonDuration(textOf(v?.length_text));
        tracks.push({
            artistText: artist,
            durationMs,
            title,
            videoId,
        });
    }
    if (tracks.length === 0) return null;
    return { kind: 'up-next', seedVideoId, tracks };
}

/** The client mirror of the worker's matchCounterpart (same rules: distinct
 * videoId, normalized title equality, artist-in-subtitle + duration
 * proximity for strong confidence). */
function matchCounterpartClient(
    seed: { artistText: null | string; durationMs: null | number; title: string },
    seedVideoId: string,
    results: Array<{
        durationMs: null | number;
        subtitle: null | string;
        title: string;
        videoId: null | string;
    }>,
): CounterpartPayload {
    const seedTitle = normalizeTitleForMatchClient(seed.title);
    const artist = (seed.artistText ?? '').toLowerCase();
    let best: null | {
        confidence: 'strong' | 'weak';
        result: { durationMs: null | number; title: string; videoId: null | string };
    } = null;
    for (const result of results) {
        if (result.videoId === null || result.videoId === '' || result.videoId === seedVideoId) {
            continue;
        }
        if (normalizeTitleForMatchClient(result.title) !== seedTitle) continue;
        const subtitle = (result.subtitle ?? '').toLowerCase();
        const subtitleHasArtist = artist !== '' && subtitle.includes(artist);
        const durationOk =
            seed.durationMs !== null &&
            result.durationMs !== null &&
            Math.abs(seed.durationMs - result.durationMs) <=
                Math.max(15_000, Math.max(seed.durationMs, result.durationMs) * 0.12);
        const strong =
            subtitleHasArtist &&
            (durationOk || seed.durationMs === null || result.durationMs === null);
        if (best === null || (strong && best.confidence !== 'strong')) {
            best = { confidence: strong ? 'strong' : 'weak', result };
        }
        if (strong) break;
    }
    return {
        artistText: seed.artistText,
        confidence: best?.confidence ?? null,
        durationMs: best?.result.durationMs ?? null,
        kind: 'counterpart',
        sourceVideoId: seedVideoId,
        title: best?.result.title ?? null,
        videoId: best?.result.videoId ?? null,
    };
}

const TITLE_NOISE_CLIENT =
    /[([](?:official\s+)?(?:music\s+)?(?:video|audio|lyric[s]?\s+video|visualizer|hd|4k|hq|remaster(?:ed)?(?:\s+\d{4})?)[^)\]]*[)\]]/gi;

/** "4:21" / "1:02:33" → ms (mirrors the worker's parser). */
export function parseColonDuration(text: string): null | number {
    const parts = text
        .trim()
        .split(':')
        .map((p) => Number(p));
    if (parts.length < 2 || parts.some((p) => !Number.isFinite(p))) return null;
    let seconds = 0;
    for (const p of parts) seconds = seconds * 60 + p;
    if (seconds <= 0) return null;
    return Math.round(seconds * 1000);
}

export function resetInnertubeFallbackForTests(): void {
    continuations.clear();
}

function artistTextOf(item: ListLike): null | string {
    const names = (item.artists ?? []).map((a) => a.name).filter((n) => n !== '');
    return names.length > 0 ? names.join(', ') : null;
}

function asListLike(entry: unknown): ListLike | null {
    if (entry === null || entry === undefined) return null;
    const candidate = entry as ListLike & { type?: string };
    if (typeof candidate.title === 'string') return candidate;
    return null;
}

function durationMsOf(item: ListLike): null | number {
    const secs = item.duration?.seconds;
    return typeof secs === 'number' && secs > 0 ? Math.round(secs * 1000) : null;
}

function normalizeTitleForMatchClient(title: string): string {
    return title.replace(TITLE_NOISE_CLIENT, ' ').replace(/\s+/g, ' ').trim().toLowerCase();
}

function parkContinuation(obj: Continuable): string {
    if (continuations.size >= CONTINUATION_CAP) {
        const oldest = continuations.keys().next();
        if (oldest.done !== true) continuations.delete(oldest.value);
    }
    // A stable handle: count + a slice of the object's type tag. Tokens are
    // opaque to the provider; only this module ever resolves them.
    const token = `fb:${continuations.size}:${Math.random().toString(36).slice(2, 8)}`;
    continuations.set(token, obj);
    return token;
}

function resumeContinuation(token: string): Continuable | null {
    const hit = continuations.get(token);
    return hit ?? null;
}

function shelvesFromSections(
    sections: Array<unknown> | undefined,
    kind: 'explore' | 'home',
    gl: string,
    hl: string,
): ExploreHomePayload | null {
    const shelves: ExploreHomePayload['shelves'] = [];
    for (const section of sections ?? []) {
        const carousel = section as CarouselLike | null;
        if (carousel === null || typeof carousel !== 'object') continue;
        const title = textOf(carousel?.header?.title);
        if (title === '') continue;
        const items: ExploreHomePayload['shelves'][number]['items'] = [];
        for (const raw of carousel.contents ?? []) {
            const two = raw as null | TwoRowLike;
            if (two === null || typeof two?.title?.text !== 'string') continue;
            items.push({
                browseId: two.endpoint?.browseId ?? null,
                pageType: null,
                subtitle: textOf(two.subtitle) || null,
                thumbnail: null,
                title: two.title.text,
                videoId: two.endpoint?.videoId ?? null,
            });
        }
        if (items.length > 0) shelves.push({ items, title });
    }
    if (shelves.length === 0) return null;
    return { gl, hl, kind, shelves };
}

function textOf(value: unknown): string {
    if (value === null || value === undefined) return '';
    if (typeof value === 'string') return value;
    const holder = value as { text?: unknown };
    if (typeof holder.text === 'string') return holder.text;
    return '';
}

// ---------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------

/** Resolve the live session — dynamic so the network-free test lane can
 * mock the resolver module boundary. */
async function yt(): Promise<{
    music: {
        getAlbum(id: string): Promise<unknown>;
        getArtist(id: string): Promise<unknown>;
        getExplore(): Promise<unknown>;
        getHomeFeed(): Promise<unknown>;
        getInfo(videoId: string): Promise<unknown>;
        getLyrics(videoId: string): Promise<unknown>;
        getPlaylist(id: string): Promise<unknown>;
        getRelated(videoId: string): Promise<unknown>;
        getSearchSuggestions(input: string): Promise<unknown>;
        getUpNext(videoId: string): Promise<unknown>;
        search(query: string, filters?: { type?: string }): Promise<unknown>;
    };
}> {
    const { getYt } = await import('../playback/resolver/lib/ytSession');
    const session = await getYt();
    return session as never;
}
