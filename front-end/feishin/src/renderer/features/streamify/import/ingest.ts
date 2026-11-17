/**
 * features/streamify/import/ingest.ts — the batched ingestion engine
 * (W1-A's 1-Click importer core).
 *
 * FLOW (per target):
 *   1. provider.getPlaylist(listId) — page 1 (VL-normalized by the edge;
 *      OLAK5uy_ imports resolve through the same call — the album title
 *      comes from the tracks' album column, the playlist title from the
 *      page header).
 *   2. createStreamifyPlaylist — the Jam CRDT shell (the #190 surface the
 *      rest of the app uses; zero bespoke CRDT code here).
 *   3. Per page, IN ORDER: upsert the catalog `tracks` rows FIRST (the
 *      sync pass's playlist_tracks inserts are FK-bound to them), then
 *      mint the ADD ops via addTracksToStreamifyPlaylist (tail fracs,
 *      jam anchors — unchanged), then report progress.
 *   4. Continue while a continuation token remains and the caller's
 *      AbortSignal has not fired.
 *
 * CANCELLATION (the task contract): checked between pages. Batches that
 * already landed STAY — CRDT ops are real and converge; "rolling back"
 * would tombstone the whole batch, which is strictly worse. The summary
 * reports exactly what landed.
 *
 * Failure separation (the provider's typed results): not-found and
 * rate-limited surface as distinct terminal phases — no retry storms.
 */

import type { PlaylistPayload } from '/@/renderer/api/streamify/metadata/types';
import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';

import type { ParsedYtmTarget } from './url-parser';

import { upsertCatalogTracks } from '/@/renderer/api/streamify/cloud';
import { typedMetadataProvider } from '/@/renderer/api/streamify/metadata/provider';
import {
    addTracksToStreamifyPlaylist,
    createStreamifyPlaylist,
    ensureStreamifyPlaylistsSession,
} from '/@/renderer/api/streamify/playlist-sync/sync';

export type ImportPhase = 'cancelled' | 'done' | 'error' | 'importing' | 'resolving';

export interface ImportProgress {
    /** Terminal failure detail (phase 'error'). */
    error: null | string;
    importedCount: number;
    phase: ImportPhase;
    /** The created CRDT playlist id (null until page 1 resolves). */
    playlistId: null | string;
    /** null when YTM did not disclose a count. */
    totalExpected: null | number;
}

export interface ImportSummary {
    importedCount: number;
    playlistId: string;
    totalExpected: null | number;
}

export interface ImportYtmOptions {
    onProgress?: (progress: ImportProgress) => void;
    /** Typed provider seam override (tests inject a double here). */
    provider?: typeof typedMetadataProvider;
    server: null | ServerListItemWithCredential;
    serverId: string | undefined;
    signal?: AbortSignal;
    target: ParsedYtmTarget;
}

export async function importYtmTarget(options: ImportYtmOptions): Promise<ImportSummary> {
    const provider = options.provider ?? typedMetadataProvider;
    const report = (progress: ImportProgress) => options.onProgress?.(progress);

    report({
        error: null,
        importedCount: 0,
        phase: 'resolving',
        playlistId: null,
        totalExpected: null,
    });

    // ---- Page 1: resolve the shell --------------------------------------
    const page1 = await provider.getPlaylist(
        options.target.listId,
        undefined,
        options.signal ?? undefined,
    );
    if (page1.kind === 'not-found') {
        report({
            error: 'Playlist not found on YouTube Music',
            importedCount: 0,
            phase: 'error',
            playlistId: null,
            totalExpected: null,
        });
        throw new Error('ytm-import: not-found');
    }
    if (page1.kind === 'rate-limited') {
        report({
            error: 'YouTube Music rate limit — try again in a minute',
            importedCount: 0,
            phase: 'error',
            playlistId: null,
            totalExpected: null,
        });
        throw new Error('ytm-import: rate-limited');
    }
    if (page1.kind === 'parse-error') {
        report({
            error: `Could not read the playlist (${page1.detail})`,
            importedCount: 0,
            phase: 'error',
            playlistId: null,
            totalExpected: null,
        });
        throw new Error('ytm-import: parse-error');
    }

    // ---- The CRDT shell ---------------------------------------------------
    const session = await ensureStreamifyPlaylistsSession(options.server);
    if (session === null) {
        report({
            error: 'Streamify session is not active — sign in first',
            importedCount: 0,
            phase: 'error',
            playlistId: null,
            totalExpected: null,
        });
        throw new Error('ytm-import: no-session');
    }
    const created = await createStreamifyPlaylist(options.server, {
        description:
            options.target.kind === 'album'
                ? 'Album imported from YouTube Music'
                : 'Playlist imported from YouTube Music',
        isPublic: false,
        name: nameForPage1(options.target, page1.payload),
        serverId: options.serverId,
    });

    let imported = 0;
    let totalExpected = page1.payload.trackCountTotal;
    const ingestPage = async (page: PlaylistPayload): Promise<void> => {
        // Catalog rows FIRST (the FK the sync pass's inserts depend on).
        const catalogOk = await upsertCatalogTracks(
            session,
            toCatalogRows(page),
            options.signal ?? undefined,
        );
        if (!catalogOk) {
            // The FK rows are merge-duplicates-tolerant; a failure here is
            // rare (offline) — the ops still mint (the outbox retries).
        }
        const ids = playableIds(page);
        if (ids.length > 0) {
            await addTracksToStreamifyPlaylist(options.server, created.id, ids, options.serverId);
            imported += ids.length;
        }
        if (totalExpected === null && page.trackCountTotal !== null) {
            totalExpected = page.trackCountTotal;
        }
        report({
            error: null,
            importedCount: imported,
            phase: 'importing',
            playlistId: created.id,
            totalExpected,
        });
    };

    await ingestPage(page1.payload);

    // ---- Continuation pages, batch by batch -------------------------------
    let continuation = page1.payload.continuation;
    while (continuation !== null && options.signal?.aborted !== true) {
        const nextPage = await provider.getPlaylist(
            options.target.listId,
            continuation,
            options.signal ?? undefined,
        );
        if (nextPage.kind === 'not-found' || nextPage.kind === 'rate-limited') {
            // Degraded mid-flight: keep what landed, report honestly.
            break;
        }
        if (nextPage.kind === 'parse-error') {
            break;
        }
        await ingestPage(nextPage.payload);
        continuation = nextPage.payload.continuation;
    }

    // ---- Terminal phase ----------------------------------------------------
    if (options.signal?.aborted === true) {
        report({
            error: null,
            importedCount: imported,
            phase: 'cancelled',
            playlistId: created.id,
            totalExpected,
        });
        return { importedCount: imported, playlistId: created.id, totalExpected };
    }
    report({
        error: null,
        importedCount: imported,
        phase: 'done',
        playlistId: created.id,
        totalExpected,
    });
    return { importedCount: imported, playlistId: created.id, totalExpected };
}

/** Derive the playlist name from page 1 (header title for playlists; the
 * tracks' shared album column for OLAK5uy_ album imports). */
function nameForPage1(target: ParsedYtmTarget, page: PlaylistPayload): string {
    if (target.kind === 'album') {
        const fromColumn = page.tracks.find((track) => track.albumText !== null)?.albumText;
        if (fromColumn) return fromColumn;
    }
    if (page.title !== null && page.title !== '') return page.title;
    return target.kind === 'album' ? 'Imported album' : 'Imported playlist';
}

function playableIds(page: PlaylistPayload): string[] {
    return page.tracks
        .filter((track) => track.videoId !== null && track.title !== '')
        .map((track) => track.videoId as string);
}

function toCatalogRows(page: PlaylistPayload) {
    return page.tracks
        .filter((track) => track.videoId !== null)
        .map((track) => ({
            album: track.albumText,
            artist: track.artistText ?? 'Unknown artist',
            coverUrl: null,
            durationSec: track.durationMs === null ? null : Math.round(track.durationMs / 1000),
            id: track.videoId as string,
            title: track.title,
            videoId: track.videoId,
        }));
}
