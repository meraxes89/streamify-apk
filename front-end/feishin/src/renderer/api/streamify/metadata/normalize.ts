/**
 * streamify/metadata/normalize.ts — the client-side edge-payload validators
 * (the "schema parse errors" class of the failure separation).
 *
 * Narrow the UNKNOWN JSON the edge served into the typed payload family —
 * one validator per payload type, called by the provider method that owns
 * that endpoint. On a mismatch the provider logs the diagnostic and falls
 * back to the client-side InnerTube lane; only a second failure surfaces
 * the typed parse-error result. The checks mirror the worker's
 * validateYtmPayload + the payload type definitions (one schema, two
 * guards — the edge validates before CACHING, this side validates before
 * UPSERTING).
 */

import type {
    AlbumPayload,
    ArtistPayload,
    ExploreHomePayload,
    LyricsPayload,
    PlaylistPayload,
    RelatedPayload,
    SearchPayload,
    SearchSuggestPayload,
    UpNextPayload,
} from './types';

export type ValidationOutcome<T> = { ok: false; reason: string } | { ok: true; payload: T };

export function validateAlbumPayload(payload: unknown): ValidationOutcome<AlbumPayload> {
    if (!isKind(payload, 'album')) return { ok: false, reason: 'album: wrong kind' };
    if (typeof payload.title !== 'string' || payload.title === '') {
        return { ok: false, reason: 'album: title must be a non-empty string' };
    }
    if (!Array.isArray(payload.tracks)) {
        return { ok: false, reason: 'album: tracks must be an array' };
    }
    return { ok: true, payload: payload as unknown as AlbumPayload };
}

export function validateArtistPayload(payload: unknown): ValidationOutcome<ArtistPayload> {
    if (!isKind(payload, 'artist')) return { ok: false, reason: 'artist: wrong kind' };
    if (typeof payload.name !== 'string' || payload.name === '') {
        return { ok: false, reason: 'artist: name must be a non-empty string' };
    }
    if (!Array.isArray(payload.topSongs) || !Array.isArray(payload.albums)) {
        return { ok: false, reason: 'artist: topSongs/albums must be arrays' };
    }
    return { ok: true, payload: payload as unknown as ArtistPayload };
}

export function validateLyricsPayload(payload: unknown): ValidationOutcome<LyricsPayload> {
    if (!isKind(payload, 'lyrics')) return { ok: false, reason: 'lyrics: wrong kind' };
    if (typeof payload.synced !== 'boolean') {
        return { ok: false, reason: 'lyrics: synced must be boolean' };
    }
    if (!Array.isArray(payload.lines) || payload.lines.length === 0) {
        return { ok: false, reason: 'lyrics: lines must be a non-empty array' };
    }
    for (const line of payload.lines) {
        if (!isObject(line) || typeof line.text !== 'string' || line.text === '') {
            return { ok: false, reason: 'lyrics: a line lacks text' };
        }
        if (line.atMs !== null && typeof line.atMs !== 'number') {
            return { ok: false, reason: 'lyrics: atMs must be number or null' };
        }
    }
    return { ok: true, payload: payload as unknown as LyricsPayload };
}

export function validatePlaylistPayload(payload: unknown): ValidationOutcome<PlaylistPayload> {
    if (!isKind(payload, 'playlist')) return { ok: false, reason: 'playlist: wrong kind' };
    if (!Array.isArray(payload.tracks)) {
        return { ok: false, reason: 'playlist: tracks must be an array' };
    }
    for (const track of payload.tracks) {
        if (!isObject(track) || typeof track.title !== 'string' || track.title === '') {
            return { ok: false, reason: 'playlist: a track lacks title' };
        }
        if (track.videoId !== null && typeof track.videoId !== 'string') {
            return { ok: false, reason: 'playlist: videoId must be string or null' };
        }
    }
    if (payload.continuation !== null && typeof payload.continuation !== 'string') {
        return { ok: false, reason: 'playlist: continuation must be string or null' };
    }
    return { ok: true, payload: payload as unknown as PlaylistPayload };
}

export function validateRelatedPayload(payload: unknown): ValidationOutcome<RelatedPayload> {
    if (!isKind(payload, 'related')) return { ok: false, reason: 'related: wrong kind' };
    if (!Array.isArray(payload.shelves)) {
        return { ok: false, reason: 'related: shelves must be an array' };
    }
    return { ok: true, payload: payload as unknown as RelatedPayload };
}

export function validateSearchPayload(payload: unknown): ValidationOutcome<SearchPayload> {
    if (!isKind(payload, 'search')) return { ok: false, reason: 'search: wrong kind' };
    if (!Array.isArray(payload.results)) {
        return { ok: false, reason: 'search: results must be an array' };
    }
    return { ok: true, payload: payload as unknown as SearchPayload };
}

export function validateSearchSuggestPayload(
    payload: unknown,
): ValidationOutcome<SearchSuggestPayload> {
    if (!isKind(payload, 'search-suggest'))
        return { ok: false, reason: 'search-suggest: wrong kind' };
    if (!Array.isArray(payload.suggestions)) {
        return { ok: false, reason: 'search-suggest: suggestions must be an array' };
    }
    return { ok: true, payload: payload as unknown as SearchSuggestPayload };
}

export function validateShelvesPayload(
    payload: unknown,
    kind: 'explore' | 'home',
): ValidationOutcome<ExploreHomePayload> {
    if (!isKind(payload, kind)) return { ok: false, reason: `${kind}: wrong kind` };
    if (!Array.isArray(payload.shelves)) {
        return { ok: false, reason: `${kind}: shelves must be an array` };
    }
    return { ok: true, payload: payload as unknown as ExploreHomePayload };
}

export function validateUpNextPayload(payload: unknown): ValidationOutcome<UpNextPayload> {
    if (!isKind(payload, 'up-next')) return { ok: false, reason: 'up-next: wrong kind' };
    if (!Array.isArray(payload.tracks) || payload.tracks.length === 0) {
        return { ok: false, reason: 'up-next: tracks must be a non-empty array' };
    }
    return { ok: true, payload: payload as unknown as UpNextPayload };
}

function isKind(value: unknown, kind: string): value is Record<string, unknown> {
    return isObject(value) && value.kind === kind;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
