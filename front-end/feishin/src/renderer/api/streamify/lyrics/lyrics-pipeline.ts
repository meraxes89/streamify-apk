/**
 * api/streamify/lyrics/lyrics-pipeline.ts — the W1-B two-tier lyrics
 * resolution (LRCLIB primary → YTM InnerTube fallback).
 *
 * THE ORDER (locked on the discussion page, see decisions/waves/):
 *   1. LRCLIB (tier 1, Feishin's own primary source): timestamped LRC
 *      stays the first choice. Queried with the snapshot row's
 *      title/artist/album/duration facts.
 *   2. YTM InnerTube (tier 2, provider.getLyrics — Edge KV 7d TTL behind
 *      the W1-A seam): fills the misses LRCLIB commonly has on regional,
 *      indie and day-one releases.
 *
 * THE synced RULE: `synced: true` beats plain text ACROSS tiers — the
 * precedence is LRCLIB-synced > YTM-synced > LRCLIB-plain > YTM-plain.
 * A plain-text LRCLIB hit never shadows a timestamped YTM result.
 *
 * Upstream features/lyrics/ is untouched: this pipeline feeds the
 * controller's getStructuredLyrics/getLyrics (the "local" tier
 * fetchLocalLyrics already prefers), so the stock precedence machinery
 * (override > preferLocal > remote) keeps its exact semantics.
 */

import type { SongRow } from '../library';
import type { MetadataProvider } from '../metadata/provider';

import { getMetadataProvider } from '../metadata/provider';
import { type LrclibSearchResult, searchLrclib } from './lrclib-web';

import { StructuredLyric, SynchronizedLyrics } from '/@/shared/types/domain-types';

// ---------------------------------------------------------------------------
// LRC parsing ([mm:ss.SSS] text — the same grammar upstream's formatLyrics
// accepts; kept local so the api layer never imports feature modules)
// ---------------------------------------------------------------------------

const LRC_LINE = /\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]([^\n]*)/g;

interface LyricCandidate {
    lang: string;
    lyrics: string;
    source: string;
    synced: boolean;
}

// ---------------------------------------------------------------------------
// The candidates, in precedence order
// ---------------------------------------------------------------------------

/** Parse LRC text into Feishin's synchronized-lines DTO. */
export function parseLrc(lrc: string): SynchronizedLyrics {
    const lines: SynchronizedLyrics = [];
    for (const m of lrc.matchAll(LRC_LINE)) {
        const [, mm, ss, frac, text] = m;
        const minutes = Number(mm);
        const seconds = Number(ss);
        const millis =
            frac === undefined ? 0 : frac.length === 3 ? Number(frac) : Number(frac) * 10;
        const value = text.trim();
        if (value === '') continue; // header/metadata timestamps carry no text
        lines.push({ startMs: (minutes * 60 + seconds) * 1000 + millis, text: value });
    }
    return lines.sort((a, b) => a.startMs - b.startMs);
}

const LRCLIB_SOURCE = 'lrclib.net';
const YTM_SOURCE = 'YouTube Music';

export interface LyricsPipelineOptions {
    /** The hl locale hint forwarded to the YTM tier. */
    hl?: string;
    /** Overrides the provider (tests). Defaults to the installed one. */
    provider?: MetadataProvider;
    signal?: AbortSignal;
}

/**
 * The two-tier fetch: LRCLIB first, YTM fallback, synced-over-plain
 * precedence. Resolves the chosen candidate or null on a total miss.
 */
export async function fetchLyricsCandidate(
    song: Pick<SongRow, 'album' | 'artist' | 'durationSec' | 'streamUrl' | 'title'>,
    opts: LyricsPipelineOptions = {},
): Promise<LyricCandidate | null> {
    const provider = opts.provider ?? getMetadataProvider();
    const lrclib = await searchLrclib(song, opts.signal);
    const videoId = song.streamUrl ?? '';
    const ytm = await ytmCandidates(videoId, opts.hl, provider);
    const ordered = orderCandidates(lrclib, ytm);
    return ordered[0] ?? null;
}

// ---------------------------------------------------------------------------
// The pipeline
// ---------------------------------------------------------------------------

/** The controller's getLyrics body (single-lyrics shape: string | lines). */
export async function fetchLyricsText(
    song: SongRow,
    opts: LyricsPipelineOptions = {},
): Promise<null | string | SynchronizedLyrics> {
    const candidate = await fetchLyricsCandidate(song, opts);
    if (candidate === null) return null;
    if (candidate.synced) {
        const lines = parseLrc(candidate.lyrics);
        if (lines.length > 0) return lines;
    }
    return candidate.synced ? stripLrcTimestamps(candidate.lyrics) : candidate.lyrics;
}

/** The controller's getStructuredLyrics body (multi-structured shape). */
export async function fetchStructuredLyrics(
    song: SongRow,
    opts: LyricsPipelineOptions = {},
): Promise<StructuredLyric[]> {
    const candidate = await fetchLyricsCandidate(song, opts);
    return candidate === null ? [] : [toStructuredLyric(song, candidate)];
}

/**
 * Resolve the two-tier candidates for a song row. Pure ordering logic —
 * exported for the network-free suites.
 */
export function orderCandidates(
    lrclib: LrclibSearchResult | null,
    ytm: LyricCandidate[],
): LyricCandidate[] {
    // synced beats plain ACROSS tiers; within a tier, LRCLIB stays first.
    const local = lrclib === null ? [] : lrclibCandidates(lrclib);
    const synced = [...local.filter((c) => c.synced), ...ytm.filter((c) => c.synced)];
    const plain = [...local.filter((c) => !c.synced), ...ytm.filter((c) => !c.synced)];
    return [...synced, ...plain];
}

// ---------------------------------------------------------------------------
// The DTO shapers (feishin's structured-lyrics contract)
// ---------------------------------------------------------------------------

/** Drop [mm:ss.SSS] prefixes (a degraded synced payload → plain text). */
export function stripLrcTimestamps(lrc: string): string {
    return lrc
        .split('\n')
        .map((line) => line.replace(/\[(\d{1,2}):(\d{1,2})(?:[.:]\d{1,3})?]/, '').trim())
        .filter((line) => line !== '')
        .join('\n');
}

/** Shape one candidate into feishin's StructuredLyric DTO. */
export function toStructuredLyric(song: SongRow, candidate: LyricCandidate): StructuredLyric {
    if (candidate.synced) {
        const lines = parseLrc(candidate.lyrics);
        if (lines.length > 0) {
            return {
                ...baseOf(song, candidate),
                kind: 'main',
                lyrics: lines,
                synced: true,
            };
        }
        // synced flag lied (no parseable timestamps): degrade to plain —
        // the flag we emit stays truthful.
        return {
            ...baseOf(song, candidate),
            lyrics: stripLrcTimestamps(candidate.lyrics),
            synced: false,
        };
    }
    return {
        ...baseOf(song, candidate),
        lyrics: candidate.lyrics,
        synced: false,
    };
}

function baseOf(song: SongRow, candidate: LyricCandidate) {
    return {
        artist: song.artist,
        lang: candidate.lang,
        name: song.title,
        offsetMs: null,
        remote: false,
        source: candidate.source,
    };
}

function lrclibCandidates(hit: LrclibSearchResult): LyricCandidate[] {
    const out: LyricCandidate[] = [];
    if (hit.syncedLyrics !== null && hit.syncedLyrics.trim() !== '') {
        out.push({ lang: 'en', lyrics: hit.syncedLyrics, source: LRCLIB_SOURCE, synced: true });
    }
    if (hit.plainLyrics !== null && hit.plainLyrics.trim() !== '') {
        out.push({ lang: 'en', lyrics: hit.plainLyrics, source: LRCLIB_SOURCE, synced: false });
    }
    return out;
}

async function ytmCandidates(
    videoId: string,
    hl: string | undefined,
    provider: MetadataProvider,
): Promise<LyricCandidate[]> {
    if (videoId === '') return [];
    const ytm = await provider.getLyrics(videoId, hl);
    if (ytm === null || ytm.lyrics.trim() === '') return [];
    return [
        {
            lang: ytm.lang ?? 'en',
            lyrics: ytm.lyrics,
            source: YTM_SOURCE,
            synced: ytm.synced === true,
        },
    ];
}
