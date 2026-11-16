/**
 * streamify/playlist-sync/sync.ts - the anti-entropy pass (P3A).
 *
 * CONVERGENCE MODEL: the durable tables are a shared blackboard, the local
 * CRDT docs are the truth, and this pass is the bidirectional diff that
 * makes them agree:
 *
 *   1. LOAD   the user's playlist rows + playlist_tracks rows.
 *   2. MERGE  table -> docs: adopt newer registers (LWW by hybrid clock,
 *             deterministic ties), adopt unseen elements/playlists,
 *             tombstone locally-confirmed-but-vanished ones, ingest legacy
 *             v4 rows, and plan REPAIRS wherever local state wins.
 *   3. RESPACE any doc whose fracs hit the precision wall (the jam
 *             compaction discipline) - minted as local Reorder ops.
 *   4. REPAIR docs -> table: delete stale/loser rows, insert missing
 *             elements/playlists, rewrite reordered row ids, re-issue
 *             tombstones, patch metadata, re-densify the position cache.
 *             Every statement is idempotent (PK-conflict-ignoring inserts,
 *             range deletes, PK-rewrite patches), so a failed pass simply
 *             retries on the next trigger.
 *   5. FINALIZE: mark remote-confirmed elements, clear the outbox (the
 *             engine state is now durable), persist, invalidate queries.
 *
 * The repair loop makes the system self-healing: any transient
 * inconsistency (a lost race, a partial flush, a concurrent duplicate)
 * converges on the next pass because every rule is a deterministic
 * function of (local doc, table rows).
 */

import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';

import { invalidateStreamifyLibraryMemo } from '../engine';
import { deviceNonceOf, fnv1a32Of, playlistTrackCadId } from '../jam/cad';
import { FRAC_FIRST_INDEX, fracAfter, fracBetween } from '../jam/fractional-index';
import {
    fracBitsOf,
    fracFromBits,
    generateOpId,
    type JamOp,
    jamOpNew,
    jamOpToBytes,
    OP_ADD,
    OP_REMOVE,
    OP_REORDER,
    seedOpCounter,
} from '../jam/jam-op';
import {
    applyPlaylistDelete,
    applyPlaylistMeta,
    applyPlaylistOp,
    applyRespacePlan,
    createPlaylistDoc,
    orderedElements,
    planRespace,
    type PlaylistCrdtDoc,
    type PlaylistElement,
    type PlaylistMetaState,
} from '../jam/playlist-crdt';
import { resolveStreamifySessionAsync, type StreamifySession } from '../session';
import { isLegacyV4RowId, packElementUuid, unpackElementUuid } from './element-id';
import { invalidateStreamifyPlaylistQueries } from './invalidate';
import { PlaylistOutbox } from './outbox';
import { getPlaylistDeviceId, loadPlaylistState, savePlaylistState } from './persistence';
import {
    deleteElementRows,
    deletePlaylistRow,
    deleteTrackRow,
    fetchPlaylistRows,
    fetchTrackRows,
    insertPlaylistRows,
    insertTrackRows,
    patchPlaylistRow,
    patchTrackRow,
    type PgPlaylistRow,
    type PgTrackRow,
} from './postgrest';
import { broadcastPlaylistMessage, ensurePlaylistRealtime } from './realtime';
import { buildOpsMessage, bytesToBase64 } from './wire';

import {
    getStreamifyPlaylistsState,
    setStreamifyPlaylistsState,
} from '/@/renderer/store/streamify-playlists.store';
import { logger } from '/@/renderer/utils/logger';

/** How stale the replica may be before a read path kicks a background sync. */
const SYNC_STALE_MS = 60_000;
/** Debounce for the post-mutation background sync. */
const SCHEDULE_DEBOUNCE_MS = 300;
/** Position re-densify cap per pass (the cache is best-effort). */
const DENSIFY_CAP = 200;

export interface SyncPassResult {
    changed: boolean;
    playlistCount: number;
}

// ---------------------------------------------------------------------------
// Session binding
// ---------------------------------------------------------------------------

interface RepairPlan {
    deleteBlocks: Array<{ addOpId: bigint; playlistId: string }>;
    deletePlaylistIds: string[];
    deleteRowIds: string[];
    /** Elements that must be inserted (never durable, or not yet flushed). */
    insertElements: Array<{ element: PlaylistElement; playlistId: string }>;
    /** Playlists that exist locally but not (yet) in the table. */
    insertPlaylists: PlaylistCrdtDoc[];
    /** Elements whose local register must be (re)written to the table. */
    patchElements: Array<{
        addedAtMs: number;
        element: PlaylistElement;
        playlistId: string;
        rowId: string;
    }>;
    /** Playlists whose local meta register must be patched. */
    patchMeta: Array<{ patch: Record<string, unknown>; playlistId: string }>;
}

// ---------------------------------------------------------------------------
// The pass
// ---------------------------------------------------------------------------

/**
 * Bind the replica to a session (hydrating persisted state on account
 * switches). Idempotent per user; the token is refreshed in place by the
 * PostgREST lane's 401 handling.
 */
export async function ensureStreamifyPlaylistsSession(
    server: null | ServerListItemWithCredential,
): Promise<null | StreamifySession> {
    const session = await resolveStreamifySessionAsync(server?.credential);
    if (session === null || session.userId === '' || session.accessToken === '') {
        // Signed out / anonymous: the honest empty replica (P1B semantics).
        return null;
    }
    const state = getStreamifyPlaylistsState();
    if (state.session !== null && state.session.userId === session.userId) {
        // Same account: keep the freshest token, keep the hydrated replica.
        setStreamifyPlaylistsState({ session });
        return session;
    }
    const hydrated = loadPlaylistState(session.userId);
    const deviceId = getPlaylistDeviceId();
    // Per-device counter seed: two devices minting in the same millisecond
    // overlap only at random (1/65536), not systematically at zero.
    seedOpCounter(fnv1a32Of(deviceId));
    setStreamifyPlaylistsState({
        deviceId,
        docs: hydrated.docs,
        outbox: hydrated.outbox,
        persistedRemote: hydrated.persistedRemote,
        session,
        syncedAt: null,
        syncing: false,
    });
    // The live lane (realtime + periodic sync) binds lazily, once per user.
    void ensurePlaylistRealtime(session);
    return session;
}

/** Run one anti-entropy pass. Throws on load/repair failure (the caller
 * keeps the outbox pending and retries on the next trigger). */
export async function runPlaylistSyncPass(
    session: StreamifySession,
    signal?: AbortSignal,
): Promise<SyncPassResult> {
    const state = getStreamifyPlaylistsState();
    const { deviceId, docs, outbox, persistedRemote } = state;
    const repairs = emptyRepairPlan();
    const insertedElements = new Set<PlaylistElement>();
    /** position cache as loaded (by row id) - the densify diff input. */
    const loadedPositionByRowId = new Map<string, number>();
    let replicaChanged = false;

    // ── 1. LOAD ────────────────────────────────────────────────────────
    const playlistRows = await fetchPlaylistRows(session, signal);
    const trackRows =
        playlistRows.length > 0
            ? await fetchTrackRows(
                  session,
                  playlistRows.map((row) => row.id),
                  signal,
              )
            : [];

    // Group the rows into element blocks: PACKED rows under their
    // (add_op_id) with the frac bits from the id; LEGACY v4 rows under
    // their derived add_op_id (the id's first half) with frac bits DERIVED
    // from the position grid (FRAC_FIRST_INDEX + position) - a v4 id's
    // second half is random, never an order key. Both kinds then flow
    // through the same block/winner/register logic.
    const blocksByPlaylist = new Map<string, Map<bigint, PgTrackRow[]>>();
    const fracBitsByRowId = new Map<string, bigint>();
    for (const row of trackRows) {
        loadedPositionByRowId.set(row.id, row.position);
        const parsed = unpackElementUuid(row.id);
        if (parsed === null) {
            continue;
        }
        if (isLegacyV4RowId(row.id)) {
            fracBitsByRowId.set(row.id, fracBitsOf(FRAC_FIRST_INDEX + Math.max(0, row.position)));
        } else {
            fracBitsByRowId.set(row.id, parsed.fracBits);
        }
        let blocks = blocksByPlaylist.get(row.playlist_id);
        if (blocks === undefined) {
            blocks = new Map();
            blocksByPlaylist.set(row.playlist_id, blocks);
        }
        const list = blocks.get(parsed.addOpId) ?? [];
        list.push(row);
        blocks.set(parsed.addOpId, list);
    }

    const seenPlaylistIds = new Set<string>();

    // ── 2. MERGE (per remote playlist row) ─────────────────────────────
    for (const row of playlistRows) {
        seenPlaylistIds.add(row.id);
        let doc = docs.get(row.id);
        const rowClockMs = parseMs(row.updated_at);

        if (doc === undefined) {
            // A playlist we have never seen (created elsewhere, or local
            // state was lost): adopt it wholesale from the row.
            doc = createPlaylistDoc(
                row.id,
                metaFromRow(row),
                clockFromMs(rowClockMs),
                row.created_at || new Date().toISOString(),
            );
            docs.set(row.id, doc);
            replicaChanged = true;
        } else if (doc.deletedAtOpId !== null) {
            // Local tombstone + row present: re-issue the delete (the
            // cascade cleans its tracks; every other replica then infers
            // the delete from the row's absence).
            repairs.deletePlaylistIds.push(row.id);
            continue;
        }
        persistedRemote.add(row.id);

        // Metadata LWW: local register vs the row (updated_at carries the
        // register clock in ms).
        const rowMeta = metaFromRow(row);
        const localMs = Number(doc.metaOpId >> 16n);
        if (rowClockMs > localMs) {
            if (metaDiffers(doc.meta, rowMeta)) {
                doc.meta = rowMeta;
                replicaChanged = true;
            }
            doc.metaOpId = clockFromMs(rowClockMs);
        } else if (metaDiffers(doc.meta, rowMeta)) {
            if (rowClockMs === localMs && metaKey(rowMeta) > metaKey(doc.meta)) {
                // Same-ms tie: the deterministic full-value pick.
                doc.meta = rowMeta;
                doc.metaOpId = clockFromMs(rowClockMs);
                replicaChanged = true;
            } else {
                // Local register wins: repair the row.
                repairs.patchMeta.push({
                    patch: {
                        cover_url: doc.meta.coverUrl,
                        description: doc.meta.description,
                        is_public: doc.meta.isPublic,
                        name: doc.meta.name,
                        updated_at: new Date(localMs).toISOString(),
                    },
                    playlistId: row.id,
                });
            }
        }

        // Element merge.
        const blocks = blocksByPlaylist.get(row.id) ?? new Map<bigint, PgTrackRow[]>();
        const pendingAdds = outbox.pendingAddOpIds(row.id);

        for (const element of doc.elements.values()) {
            if (element.trackId === '') {
                continue; // remove-before-add tombstone shell
            }
            const blockRows = blocks.get(element.addOpId);

            if (element.removedAtOpId !== null) {
                // Tombstoned locally: any surviving rows get re-deleted.
                if (blockRows !== undefined && blockRows.length > 0) {
                    repairs.deleteBlocks.push({
                        addOpId: element.addOpId,
                        playlistId: row.id,
                    });
                }
                continue;
            }

            if (blockRows === undefined || blockRows.length === 0) {
                if (!element.remoteConfirmed || pendingAdds.has(element.addOpId.toString(10))) {
                    // Not durable yet (ours unflushed, or a broadcast-received
                    // add whose origin has not flushed): materialize it.
                    repairs.insertElements.push({ element, playlistId: row.id });
                    insertedElements.add(element);
                } else {
                    // Was durable, now gone: removed remotely -> tombstone.
                    element.removedAtOpId = syntheticTombstoneClock();
                    element.rowId = null;
                    replicaChanged = true;
                }
                continue;
            }

            // The block exists: winner + loser cleanup + register compare.
            const winner = pickBlockWinner(blockRows, fracBitsByRowId);
            for (const candidate of blockRows) {
                if (candidate.id !== winner.id) {
                    repairs.deleteRowIds.push(candidate.id);
                }
            }
            const rowMs = parseMs(winner.added_at);
            const rowBits = fracBitsByRowId.get(winner.id) ?? 0n;
            const localBits = fracBitsOf(element.frac);
            const localMs = Number(element.orderOpId >> 16n);
            element.rowId = winner.id;

            if (rowMs > localMs || (rowMs === localMs && rowBits > localBits)) {
                // Row register wins: adopt it.
                const rowFrac = fracFromBits(rowBits);
                if (element.frac !== rowFrac || element.trackId !== winner.track_id) {
                    element.frac = rowFrac;
                    element.trackId = winner.track_id;
                    element.cadId = playlistTrackCadId(winner.track_id);
                    replicaChanged = true;
                }
                element.orderOpId = clockFromMs(rowMs);
            } else if (rowMs === localMs && rowBits === localBits) {
                // Identical register: converged (defensive value adoption).
                if (element.trackId !== winner.track_id) {
                    element.trackId = winner.track_id;
                    element.cadId = playlistTrackCadId(winner.track_id);
                    replicaChanged = true;
                }
            } else {
                // Local register wins: rewrite the winner row to it.
                repairs.patchElements.push({
                    addedAtMs: localMs,
                    element,
                    playlistId: row.id,
                    rowId: winner.id,
                });
            }
            element.remoteConfirmed = true;
        }

        // Blocks with no local element: remote adds (packed) or legacy
        // rows (derived) -> adopt through the same register shape.
        for (const [addOpId, blockRows] of blocks) {
            if (doc.elements.has(addOpId)) {
                continue;
            }
            const winner = pickBlockWinner(blockRows, fracBitsByRowId);
            for (const candidate of blockRows) {
                if (candidate.id !== winner.id) {
                    repairs.deleteRowIds.push(candidate.id);
                }
            }
            const bits = fracBitsByRowId.get(winner.id) ?? 0n;
            const frac = fracFromBits(bits);
            if (!Number.isFinite(frac) || frac <= 0) {
                continue; // hostile/corrupt row never enters the replica
            }
            doc.elements.set(addOpId, {
                addOpId,
                cadId: playlistTrackCadId(winner.track_id),
                frac,
                orderOpId: clockFromMs(parseMs(winner.added_at)),
                remoteConfirmed: true,
                removedAtOpId: null,
                rowId: winner.id,
                trackId: winner.track_id,
            });
            replicaChanged = true;
        }
    }

    // ── 2b. LOCAL DOCS NOT SEEN IN THE TABLE ──────────────────────────
    for (const doc of docs.values()) {
        if (seenPlaylistIds.has(doc.id) || doc.deletedAtOpId !== null) {
            continue;
        }
        if (persistedRemote.has(doc.id)) {
            // Confirmed durable before, gone now: deleted remotely.
            doc.deletedAtOpId = syntheticTombstoneClock();
            outbox.clearFor(doc.id);
            replicaChanged = true;
        } else {
            // Never durable (created offline / broadcast-learned): insert
            // the playlist row and all its live elements.
            repairs.insertPlaylists.push(doc);
            for (const element of doc.elements.values()) {
                if (element.removedAtOpId !== null || element.trackId === '') {
                    continue;
                }
                repairs.insertElements.push({ element, playlistId: doc.id });
                insertedElements.add(element);
            }
        }
    }

    // ── 3. RESPACE (the jam compaction discipline) ────────────────────
    const nonce = deviceNonceOf(deviceId);
    for (const doc of docs.values()) {
        if (doc.deletedAtOpId !== null || !doc.needsRebalance) {
            continue;
        }
        const plan = planRespace(doc, () => generateOpId());
        if (plan === null || plan.length === 0) {
            doc.needsRebalance = false;
            continue;
        }
        applyRespacePlan(doc, plan);
        replicaChanged = true;
        const ops: Array<{ op: JamOp; trackId: null | string }> = plan.map((step) => ({
            op: jamOpNew(
                step.orderOpId,
                nonce,
                OP_REORDER,
                0,
                step.target.cadId,
                step.frac,
                step.target.addOpId,
            ),
            trackId: null,
        }));
        enqueueOps(outbox, doc.id, ops);
        broadcastPlaylistMessage(session, buildOpsMessage(deviceId, doc.id, ops));
        // The respaced rows move: plan their rewrites/inserts now.
        for (const step of plan) {
            if (step.target.rowId !== null && !insertedElements.has(step.target)) {
                repairs.patchElements.push({
                    addedAtMs: Number(step.orderOpId >> 16n),
                    element: step.target,
                    playlistId: doc.id,
                    rowId: step.target.rowId,
                });
            } else if (step.target.rowId === null) {
                repairs.insertElements.push({ element: step.target, playlistId: doc.id });
                insertedElements.add(step.target);
            }
        }
    }

    // ── 4. REPAIR EXECUTION (idempotent statements, fail-fast) ────────
    for (const playlistId of repairs.deletePlaylistIds) {
        await deletePlaylistRow(session, playlistId);
    }
    for (const rowId of new Set(repairs.deleteRowIds)) {
        await deleteTrackRow(session, rowId);
    }
    for (const block of repairs.deleteBlocks) {
        await deleteElementRows(session, block.playlistId, block.addOpId);
    }
    if (repairs.insertPlaylists.length > 0) {
        await insertPlaylistRows(
            session,
            repairs.insertPlaylists.map((doc) => ({
                cover_url: doc.meta.coverUrl,
                created_at: doc.createdAt,
                description: doc.meta.description,
                id: doc.id,
                is_public: doc.meta.isPublic,
                name: doc.meta.name,
                updated_at: new Date(Number(doc.metaOpId >> 16n)).toISOString(),
                user_id: session.userId,
            })),
        );
        for (const doc of repairs.insertPlaylists) {
            persistedRemote.add(doc.id);
        }
    }

    // Row-shape helpers: the dense rank of every live element, computed
    // from the FINAL merged state (inserts and rewrites both read it).
    const denseByPlaylist = new Map<string, Map<bigint, number>>();
    const denseFor = (playlistId: string, doc: PlaylistCrdtDoc): Map<bigint, number> => {
        let dense = denseByPlaylist.get(playlistId);
        if (dense === undefined) {
            dense = new Map();
            orderedElements(doc).forEach((entry, index) => {
                dense?.set(entry.element.addOpId, index);
            });
            denseByPlaylist.set(playlistId, dense);
        }
        return dense;
    };

    // Row-id rewrites (reorders + respaces): deferred to execution time so
    // the FINAL frac (post-merge, post-respace) lands in the row id - and
    // the dense position rides the same PATCH, so a reordered row never
    // keeps a stale position cache.
    for (const entry of repairs.patchElements) {
        const { element } = entry;
        const newId = packElementUuid(element.addOpId, fracBitsOf(element.frac));
        const newAddedAt = new Date(entry.addedAtMs).toISOString();
        const doc = docs.get(entry.playlistId);
        const dense =
            doc !== undefined ? (denseFor(entry.playlistId, doc).get(element.addOpId) ?? 0) : 0;
        if (newId !== entry.rowId) {
            await patchTrackRow(session, entry.rowId, {
                added_at: newAddedAt,
                id: newId,
                position: dense,
            });
            element.rowId = newId;
        } else {
            await patchTrackRow(session, entry.rowId, {
                added_at: newAddedAt,
                position: dense,
            });
        }
        element.remoteConfirmed = true;
    }

    // Inserts: ids/positions computed from the final state.
    if (repairs.insertElements.length > 0) {
        const payloads: Array<{
            added_at: string;
            added_by: string;
            id: string;
            playlist_id: string;
            position: number;
            track_id: string;
        }> = [];
        for (const { element, playlistId } of repairs.insertElements) {
            const doc = docs.get(playlistId);
            const position =
                doc !== undefined ? (denseFor(playlistId, doc).get(element.addOpId) ?? 0) : 0;
            const rowId = packElementUuid(element.addOpId, fracBitsOf(element.frac));
            payloads.push({
                added_at: new Date(Number(element.orderOpId >> 16n)).toISOString(),
                added_by: session.userId,
                id: rowId,
                playlist_id: playlistId,
                position,
                track_id: element.trackId,
            });
            element.rowId = rowId;
        }
        await insertTrackRows(session, payloads);
        for (const element of insertedElements) {
            element.remoteConfirmed = true;
        }
    }

    // Metadata repairs.
    for (const entry of repairs.patchMeta) {
        await patchPlaylistRow(session, entry.playlistId, entry.patch);
    }

    // Position re-densify (the legacy web UI's order cache - best-effort).
    let densifyCount = 0;
    for (const doc of docs.values()) {
        if (doc.deletedAtOpId !== null || densifyCount >= DENSIFY_CAP) {
            continue;
        }
        const view = orderedElements(doc);
        for (let index = 0; index < view.length && densifyCount < DENSIFY_CAP; index += 1) {
            const element = view[index].element;
            if (element.rowId === null) {
                continue;
            }
            const stored = loadedPositionByRowId.get(element.rowId);
            if (stored !== undefined && stored !== index) {
                await patchTrackRow(session, element.rowId, { position: index });
                densifyCount += 1;
            }
        }
    }

    // ── 5. FINALIZE ───────────────────────────────────────────────────
    // The engine state is durable now: the outbox's purpose (delivery) is
    // served. Ops minted after the repair execution are re-materialized by
    // the next pass through the !remoteConfirmed path - the outbox is a
    // hint, remoteConfirmed is the truth.
    outbox.replace([]);
    if (repairs.insertElements.length > 0) {
        // New durable tracks: the snapshot's song index must learn them
        // (lazy reload on the next read).
        invalidateStreamifyLibraryMemo();
    }
    savePlaylistState(session.userId, docs, outbox, persistedRemote);
    setStreamifyPlaylistsState({
        docs,
        outbox,
        persistedRemote,
        syncedAt: Date.now(),
    });
    if (replicaChanged) {
        invalidateStreamifyPlaylistQueries();
    }
    return { changed: replicaChanged, playlistCount: docs.size };
}

/** A register clock from a millisecond timestamp (counter bits zero). */
function clockFromMs(ms: number): bigint {
    return BigInt(Math.max(0, Math.floor(ms))) << 16n;
}

function emptyRepairPlan(): RepairPlan {
    return {
        deleteBlocks: [],
        deletePlaylistIds: [],
        deleteRowIds: [],
        insertElements: [],
        insertPlaylists: [],
        patchElements: [],
        patchMeta: [],
    };
}

function metaDiffers(a: PlaylistMetaState, b: PlaylistMetaState): boolean {
    return metaKey(a) !== metaKey(b);
}

function metaFromRow(row: PgPlaylistRow): PlaylistMetaState {
    return {
        coverUrl: row.cover_url,
        description: row.description,
        isPublic: row.is_public !== false,
        name: row.name,
    };
}

/** Deterministic full-value comparison key (the same-ms tie-break). */
function metaKey(meta: PlaylistMetaState): string {
    return `${meta.name}\u0000${meta.description ?? ''}\u0000${meta.isPublic ? '1' : '0'}\u0000${meta.coverUrl ?? ''}`;
}

function parseMs(iso: null | string): number {
    if (iso === null || iso === '') {
        return 0;
    }
    const ms = Date.parse(iso);
    return Number.isFinite(ms) ? ms : 0;
}

/** The winner of one element's block: max by (added_at ms, frac bits). */
function pickBlockWinner(rows: PgTrackRow[], fracBitsByRowId: Map<string, bigint>): PgTrackRow {
    let winner = rows[0];
    let winnerMs = parseMs(winner.added_at);
    let winnerBits = fracBitsByRowId.get(winner.id) ?? 0n;
    for (let i = 1; i < rows.length; i += 1) {
        const row = rows[i];
        const ms = parseMs(row.added_at);
        const bits = fracBitsByRowId.get(row.id) ?? 0n;
        if (ms > winnerMs || (ms === winnerMs && bits > winnerBits)) {
            winner = row;
            winnerMs = ms;
            winnerBits = bits;
        }
    }
    return winner;
}

/** The absorbing tombstone clock for removals inferred from the table. */
function syntheticTombstoneClock(): bigint {
    return (BigInt(Date.now()) << 16n) | 0xffffn;
}

// ---------------------------------------------------------------------------
// The read-path gate + background scheduling
// ---------------------------------------------------------------------------

let inFlightPass: null | Promise<SyncPassResult> = null;
let scheduleTimer: null | ReturnType<typeof setTimeout> = null;

/** addToPlaylist: append Adds (tail fracs, jam anchors). */
export async function addTracksToStreamifyPlaylist(
    server: null | ServerListItemWithCredential,
    playlistId: string,
    trackIds: string[],
    serverId: string | undefined,
): Promise<void> {
    if (trackIds.length === 0) {
        return;
    }
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        throw new Error('Streamify session is not active - sign in first');
    }
    const state = getStreamifyPlaylistsState();
    const doc = requireDoc(playlistId);
    const nonce = deviceNonceOf(state.deviceId);

    let lastFrac: null | number = null;
    const view = orderedElements(doc);
    if (view.length > 0) {
        lastFrac = view[view.length - 1].frac;
    }
    const ops: Array<{ op: JamOp; trackId: null | string }> = [];
    for (const trackId of trackIds) {
        const frac = fracAfter(lastFrac);
        lastFrac = frac.value;
        const opId = generateOpId();
        const op = jamOpNew(opId, nonce, OP_ADD, 0, playlistTrackCadId(trackId), frac.value, 0n);
        applyPlaylistOp(doc, { op, trackId });
        ops.push({ op, trackId });
    }
    enqueueOps(state.outbox, playlistId, ops);
    broadcastPlaylistMessage(session, buildOpsMessage(state.deviceId, playlistId, ops));
    afterLocalChange(serverId);
}

/** createPlaylist: mint the doc + meta register, insert deferred to sync. */
export async function createStreamifyPlaylist(
    server: null | ServerListItemWithCredential,
    args: {
        description: null | string;
        isPublic: boolean;
        name: string;
        serverId: string | undefined;
    },
): Promise<{ id: string }> {
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        throw new Error('Streamify session is not active - sign in first');
    }
    const state = getStreamifyPlaylistsState();
    const metaOpId = generateOpId();
    const id = crypto.randomUUID();
    const doc = createPlaylistDoc(
        id,
        {
            coverUrl: null,
            description: args.description,
            isPublic: args.isPublic,
            name: args.name,
        },
        metaOpId,
        new Date().toISOString(),
    );
    state.docs.set(id, doc);
    broadcastPlaylistMessage(session, {
        kind: 'meta',
        meta: {
            coverUrl: doc.meta.coverUrl,
            description: doc.meta.description,
            isPublic: doc.meta.isPublic,
            name: doc.meta.name,
        },
        metaOpId: metaOpId.toString(10),
        playlistId: id,
        sender: state.deviceId,
        v: 1,
    });
    afterLocalChange(args.serverId);
    return { id };
}

/** deletePlaylist: absorbing tombstone + pending-op cleanup. */
export async function deleteStreamifyPlaylist(
    server: null | ServerListItemWithCredential,
    playlistId: string,
    serverId: string | undefined,
): Promise<void> {
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        throw new Error('Streamify session is not active - sign in first');
    }
    const doc = getStreamifyPlaylistsState().docs.get(playlistId);
    if (doc === undefined) {
        return; // idempotent delete of an unknown playlist
    }
    const deleteOpId = generateOpId();
    applyPlaylistDelete(doc, deleteOpId);
    getStreamifyPlaylistsState().outbox.clearFor(playlistId);
    broadcastPlaylistMessage(session, {
        deleteOpId: deleteOpId.toString(10),
        kind: 'delete',
        playlistId,
        sender: getStreamifyPlaylistsState().deviceId,
        v: 1,
    });
    afterLocalChange(serverId);
}

// ---------------------------------------------------------------------------
// The mutation surface (local-first: apply + persist + invalidate now,
// materialize via the scheduled sync pass)
// ---------------------------------------------------------------------------

/**
 * The read gate: bind the session, and on the FIRST read of a session
 * await the initial sync (P1B parity: load once, then local-first). A
 * failed first sync serves the persisted replica when it has content and
 * rethrows only when the replica is empty (react-query surfaces the error).
 * Later reads kick a background sync once the replica goes stale.
 */
export async function ensurePlaylistsSynced(
    server: null | ServerListItemWithCredential,
    signal?: AbortSignal,
): Promise<Map<string, PlaylistCrdtDoc>> {
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        return new Map();
    }
    const { docs, syncedAt } = getStreamifyPlaylistsState();
    if (syncedAt === null) {
        try {
            await runPlaylistSyncPassGuarded(session, signal);
        } catch (error) {
            logger.warn('Streamify playlist sync failed', {
                action: 'streamify_playlist_sync_failed',
                error: error instanceof Error ? error.message : String(error),
            });
            if (docs.size === 0) {
                throw error;
            }
            // Serve the persisted replica; the scheduler retries.
            schedulePlaylistSync(2_000);
        }
    } else if (Date.now() - syncedAt > SYNC_STALE_MS) {
        schedulePlaylistSync();
    }
    return getStreamifyPlaylistsState().docs;
}

/**
 * movePlaylistItem: reorder ONE element. The new frac lands between the
 * ending-index neighbours of the view WITHOUT the moved element (the jam
 * insert helper), so the move reads naturally from either direction.
 */
export async function moveStreamifyPlaylistItem(
    server: null | ServerListItemWithCredential,
    playlistId: string,
    args: { endingIndex: number; startingIndex: number; trackId: string },
    serverId: string | undefined,
): Promise<void> {
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        throw new Error('Streamify session is not active - sign in first');
    }
    const state = getStreamifyPlaylistsState();
    const doc = requireDoc(playlistId);
    const nonce = deviceNonceOf(state.deviceId);
    const view = orderedElements(doc);
    if (view.length === 0) {
        return;
    }

    // The track id identifies the element when the rows were re-sorted
    // client-side; otherwise the starting index is authoritative.
    let element: PlaylistElement | undefined;
    if (args.trackId !== '') {
        element = view.find((entry) => entry.element.trackId === args.trackId)?.element;
    }
    if (element === undefined) {
        const from = Math.min(Math.max(args.startingIndex, 0), view.length - 1);
        element = view[from].element;
    }

    // The neighbours around the destination, excluding the moved element.
    const remaining = view.filter((entry) => entry.element.addOpId !== element?.addOpId);
    const to = Math.min(Math.max(args.endingIndex, 0), remaining.length);
    const prev = to > 0 ? remaining[to - 1].frac : null;
    const next = to < remaining.length ? remaining[to].frac : null;
    const midpoint = fracBetween(prev, next);

    const op = jamOpNew(
        generateOpId(),
        nonce,
        OP_REORDER,
        0,
        element.cadId,
        midpoint.value,
        element.addOpId,
    );
    applyPlaylistOp(doc, { op, trackId: null });
    enqueueOps(state.outbox, playlistId, [{ op, trackId: null }]);
    broadcastPlaylistMessage(
        session,
        buildOpsMessage(state.deviceId, playlistId, [{ op, trackId: null }]),
    );
    afterLocalChange(serverId);
}

/**
 * removeFromPlaylist: songIds may be FEISHIN SONG IDS (remove every element
 * holding that track) or PLAYLIST ITEM IDS (the element add-op id in
 * decimal form - the Navidrome-style per-item removal the playlistItemId
 * lane feeds). Both resolve to element sets; each removal is a Remove op.
 * Returns the number of elements removed.
 */
export async function removeTracksFromStreamifyPlaylist(
    server: null | ServerListItemWithCredential,
    playlistId: string,
    songIds: string[],
    serverId: string | undefined,
): Promise<number> {
    if (songIds.length === 0) {
        return 0;
    }
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        throw new Error('Streamify session is not active - sign in first');
    }
    const state = getStreamifyPlaylistsState();
    const doc = requireDoc(playlistId);
    const nonce = deviceNonceOf(state.deviceId);
    const wanted = new Set(songIds);

    const targets: PlaylistElement[] = [];
    for (const element of doc.elements.values()) {
        if (element.removedAtOpId !== null || element.trackId === '') {
            continue;
        }
        if (wanted.has(element.trackId) || wanted.has(element.addOpId.toString(10))) {
            targets.push(element);
        }
    }
    if (targets.length === 0) {
        return 0;
    }
    const ops: Array<{ op: JamOp; trackId: null | string }> = [];
    for (const element of targets) {
        const opId = generateOpId();
        const op = jamOpNew(
            opId,
            nonce,
            OP_REMOVE,
            0,
            element.cadId,
            element.frac,
            element.addOpId,
        );
        applyPlaylistOp(doc, { op, trackId: null });
        element.rowId = null;
        ops.push({ op, trackId: null });
    }
    enqueueOps(state.outbox, playlistId, ops);
    broadcastPlaylistMessage(session, buildOpsMessage(state.deviceId, playlistId, ops));
    afterLocalChange(serverId);
    return targets.length;
}

/**
 * replacePlaylist / setPlaylistSongs: converge the ordered view onto the
 * given song list. Existing elements are matched to desired slots greedily
 * in current order (stable for duplicates); surplus elements are Removed;
 * missing tracks are Added; matched elements whose index changed are
 * Reordered onto a fresh dense grid (FRAC_FIRST_INDEX + i) - one reorder
 * per element by construction, so no register ever competes with itself.
 */
export async function replaceStreamifyPlaylistSongs(
    server: null | ServerListItemWithCredential,
    playlistId: string,
    songIds: string[],
    serverId: string | undefined,
): Promise<void> {
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        throw new Error('Streamify session is not active - sign in first');
    }
    const state = getStreamifyPlaylistsState();
    const doc = requireDoc(playlistId);
    const nonce = deviceNonceOf(state.deviceId);

    // Stable matching: current order over desired slots.
    const view = orderedElements(doc);
    const unmatched = new Map<string, PlaylistElement[]>();
    for (const entry of view) {
        const list = unmatched.get(entry.element.trackId) ?? [];
        list.push(entry.element);
        unmatched.set(entry.element.trackId, list);
    }
    const desired: Array<null | PlaylistElement> = songIds.map((trackId) => {
        const list = unmatched.get(trackId);
        if (list === undefined || list.length === 0) {
            return null;
        }
        return list.shift() ?? null;
    });
    // Everything still unmatched is surplus -> Removed.
    const surplus: PlaylistElement[] = [];
    for (const list of unmatched.values()) {
        surplus.push(...list);
    }

    const ops: Array<{ op: JamOp; trackId: null | string }> = [];
    const mint = (opType: number, cadId: bigint, frac: number, target: bigint): JamOp =>
        jamOpNew(generateOpId(), nonce, opType, 0, cadId, frac, target);

    for (const element of surplus) {
        const op = mint(OP_REMOVE, element.cadId, element.frac, element.addOpId);
        applyPlaylistOp(doc, { op, trackId: null });
        element.rowId = null;
        ops.push({ op, trackId: null });
    }

    songIds.forEach((trackId, index) => {
        const existing = desired[index];
        const freshFrac = FRAC_FIRST_INDEX + index;
        if (existing === null) {
            const op = mint(OP_ADD, playlistTrackCadId(trackId), freshFrac, 0n);
            applyPlaylistOp(doc, { op, trackId });
            ops.push({ op, trackId });
            return;
        }
        if (existing.frac !== freshFrac) {
            // One reorder per element onto the fresh grid.
            const op = mint(OP_REORDER, existing.cadId, freshFrac, existing.addOpId);
            applyPlaylistOp(doc, { op, trackId: null });
            ops.push({ op, trackId: null });
        }
    });

    enqueueOps(state.outbox, playlistId, ops);
    broadcastPlaylistMessage(session, buildOpsMessage(state.deviceId, playlistId, ops));
    afterLocalChange(serverId);
}

/** Run (or join) the in-flight pass; never concurrent with itself. */
export function runPlaylistSyncPassGuarded(
    session: StreamifySession,
    signal?: AbortSignal,
): Promise<SyncPassResult> {
    if (inFlightPass !== null) {
        return inFlightPass;
    }
    setStreamifyPlaylistsState({ syncing: true });
    const docsAtStart = getStreamifyPlaylistsState().docs;
    inFlightPass = runPlaylistSyncPass(session, signal)
        .then((result) => {
            if (getStreamifyPlaylistsState().docs !== docsAtStart) {
                // The session switched mid-pass: the other user's hydration
                // owns the store now; this pass's finalize already wrote its
                // own user's persistence. Drop the stale result silently.
                return result;
            }
            return result;
        })
        .finally(() => {
            inFlightPass = null;
            setStreamifyPlaylistsState({ syncing: false });
        });
    return inFlightPass;
}

/** Debounced background sync (mutations, staleness, online events). */
export function schedulePlaylistSync(delayMs: number = SCHEDULE_DEBOUNCE_MS): void {
    if (scheduleTimer !== null) {
        clearTimeout(scheduleTimer);
    }
    scheduleTimer = setTimeout(() => {
        scheduleTimer = null;
        const { session } = getStreamifyPlaylistsState();
        if (session === null) {
            return;
        }
        void runPlaylistSyncPassGuarded(session).catch((error: unknown) => {
            logger.warn('Streamify playlist background sync failed', {
                action: 'streamify_playlist_sync_bg_failed',
                error: error instanceof Error ? error.message : String(error),
            });
        });
    }, delayMs);
}

/** updatePlaylist: metadata LWW register write. */
export async function updateStreamifyPlaylistMeta(
    server: null | ServerListItemWithCredential,
    playlistId: string,
    meta: PlaylistMetaState,
    serverId: string | undefined,
): Promise<void> {
    const session = await ensureStreamifyPlaylistsSession(server);
    if (session === null) {
        throw new Error('Streamify session is not active - sign in first');
    }
    const doc = requireDoc(playlistId);
    const metaOpId = generateOpId();
    // The register write is a full snapshot (LWW semantics); a stale
    // concurrent rename loses cleanly on every replica.
    if (!applyPlaylistMeta(doc, metaOpId, meta)) {
        throw new Error(`Playlist not found: ${playlistId}`);
    }
    broadcastPlaylistMessage(session, {
        kind: 'meta',
        meta: {
            coverUrl: doc.meta.coverUrl,
            description: doc.meta.description,
            isPublic: doc.meta.isPublic,
            name: doc.meta.name,
        },
        metaOpId: metaOpId.toString(10),
        playlistId,
        sender: getStreamifyPlaylistsState().deviceId,
        v: 1,
    });
    afterLocalChange(serverId);
}

function afterLocalChange(serverId: string | undefined): void {
    persistReplica();
    invalidateStreamifyPlaylistQueries(serverId);
    schedulePlaylistSync();
}

function enqueueOps(
    outbox: PlaylistOutbox,
    playlistId: string,
    ops: Array<{ op: JamOp; trackId: null | string }>,
): void {
    outbox.enqueue(
        ops.map(({ op, trackId }) => ({
            opId: op.opId.toString(10),
            opType: op.opType,
            playlistId,
            row: bytesToBase64(jamOpToBytes(op)),
            trackId,
        })),
    );
}

function persistReplica(): void {
    const { docs, outbox, persistedRemote, session } = getStreamifyPlaylistsState();
    if (session !== null) {
        savePlaylistState(session.userId, docs, outbox, persistedRemote);
    }
}

function requireDoc(playlistId: string): PlaylistCrdtDoc {
    const doc = getStreamifyPlaylistsState().docs.get(playlistId);
    if (doc === undefined) {
        throw new Error(`Playlist not found: ${playlistId}`);
    }
    if (doc.deletedAtOpId !== null) {
        throw new Error(`Playlist not found: ${playlistId}`);
    }
    return doc;
}
