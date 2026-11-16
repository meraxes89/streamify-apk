/**
 * streamify/playlist-sync/realtime.ts - the live lane (P3A).
 *
 * Supabase Realtime BROADCAST channel `playlist-ops:<userId>` carries the
 * ops/metadata/delete deltas between the signed-in user's own devices.
 * Broadcast needs no database objects and no migrations - the durable
 * tables + the anti-entropy pass (sync.ts) remain the correctness
 * mechanism; this lane only cuts the latency of the multi-device case
 * from "next sync pass" to "immediately".
 *
 * Receives are applied through the same CRDT merge every other op path
 * uses (applyPlaylistOp / applyPlaylistMeta / applyPlaylistDelete), then
 * persisted and surfaced through the TanStack Query invalidation seam -
 * remote deltas update open playlist screens live, without a manual
 * reload.
 *
 * The lifecycle binder (interval sync + online-listener + channel
 * subscription) is idempotent per user; the module is importable in the
 * network-free test lane (all client access is lazy and guarded).
 */

import type { RealtimeChannel } from '@supabase/supabase-js';

import type { StreamifySession } from '../session';

import { applyPlaylistDelete, applyPlaylistMeta, applyPlaylistOp } from '../jam/playlist-crdt';
import { invalidateStreamifyPlaylistQueries } from './invalidate';
import { savePlaylistState } from './persistence';
import { schedulePlaylistSync } from './sync';
import {
    decodePlaylistBroadcast,
    decodeWireOp,
    PLAYLIST_BROADCAST_CHANNEL_PREFIX,
    PLAYLIST_BROADCAST_EVENT,
    type PlaylistBroadcastMessage,
} from './wire';

import { getStreamifyPlaylistsState } from '/@/renderer/store/streamify-playlists.store';
import { logger } from '/@/renderer/utils/logger';

/** How often the idle replica re-runs the anti-entropy pass. */
const PERIODIC_SYNC_MS = 90_000;

interface BoundRealtime {
    channel: null | RealtimeChannel;
    interval: ReturnType<typeof setInterval>;
    userId: string;
}

let bound: BoundRealtime | null = null;

/** Fire-and-forget broadcast (durability never depends on it). */
export function broadcastPlaylistMessage(
    session: StreamifySession,
    message: PlaylistBroadcastMessage,
): void {
    if (bound === null || bound.userId !== session.userId || bound.channel === null) {
        return;
    }
    try {
        // Fire-and-forget: the promise settles on the websocket write;
        // durability never depends on the send (the sync pass is the
        // correctness mechanism).
        void bound.channel
            .send({
                event: PLAYLIST_BROADCAST_EVENT,
                payload: message,
                topic: PLAYLIST_BROADCAST_CHANNEL_PREFIX + session.userId,
                type: 'broadcast',
            })
            .catch((sendError: unknown) => {
                logger.debug('Streamify playlist broadcast send rejected', {
                    action: 'streamify_playlist_broadcast_send_rejected',
                    error: sendError instanceof Error ? sendError.message : String(sendError),
                });
            });
    } catch (error) {
        logger.debug('Streamify playlist broadcast send failed', {
            action: 'streamify_playlist_broadcast_send_failed',
            error: error instanceof Error ? error.message : String(error),
        });
    }
}

/**
 * Bind the live lane for a session (idempotent per user). Called from the
 * sync module's session switch; failures degrade to sync-pass-only
 * convergence (logged, never fatal).
 */
export async function ensurePlaylistRealtime(session: StreamifySession): Promise<void> {
    if (bound !== null && bound.userId === session.userId) {
        return;
    }
    if (bound !== null) {
        teardownBound();
    }
    const interval = setInterval(() => {
        schedulePlaylistSync(0);
    }, PERIODIC_SYNC_MS);
    bound = { channel: null, interval, userId: session.userId };

    // The broadcast channel rides the supabase-js realtime client; a
    // missing client (unconfigured deployment) simply stays on the
    // sync-pass-only lane.
    try {
        const supabaseModule = await import('../streamify-supabase');
        const supabase = supabaseModule.getStreamifySupabase();
        if (supabase === null) {
            return;
        }
        const channel = supabase.channel(PLAYLIST_BROADCAST_CHANNEL_PREFIX + session.userId, {
            config: { broadcast: { self: false } },
        });
        channel.on('broadcast', { event: PLAYLIST_BROADCAST_EVENT }, (payload) => {
            handleBroadcast(session.userId, payload.payload);
        });
        channel.subscribe();
        bound.channel = channel;
    } catch (error) {
        logger.warn('Streamify playlist realtime bind failed (sync-pass only)', {
            action: 'streamify_playlist_realtime_bind_failed',
            error: error instanceof Error ? error.message : String(error),
        });
    }

    // Flush/merge on reconnect (offline resilience trigger).
    try {
        if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
            window.addEventListener('online', () => {
                schedulePlaylistSync(0);
            });
        }
    } catch {
        // no window (tests) - the interval still runs
    }
}

/** P4-A (/debug): read-only introspection of the realtime binding —
 * whether the broadcast channel is bound for a user. Read-only; the
 * subscription lifecycle stays untouched. */
export function playlistRealtimeStatus(): {
    bound: boolean;
    channelLive: boolean;
    userId: null | string;
} {
    if (bound === null) return { bound: false, channelLive: false, userId: null };
    return {
        bound: true,
        channelLive: bound.channel !== null,
        userId: bound.userId,
    };
}

/** Test-only: drop the bound channel + timers. */
export function resetPlaylistRealtimeForTests(): void {
    teardownBound();
}

/** One inbound broadcast: validate, merge, persist, invalidate. */
function handleBroadcast(userId: string, raw: unknown): void {
    const state = getStreamifyPlaylistsState();
    if (state.session === null || state.session.userId !== userId) {
        return; // stale channel from a previous session
    }
    const message = decodePlaylistBroadcast(raw);
    if (message === null || message.sender === state.deviceId) {
        return; // malformed, or our own echo (self:false normally filters)
    }
    const { deviceId, docs, outbox } = state;
    let changed = false;

    if (message.kind === 'delete') {
        const doc = docs.get(message.playlistId);
        if (doc !== undefined) {
            applyPlaylistDelete(doc, BigInt(message.deleteOpId));
            outbox.clearFor(message.playlistId);
            changed = true;
        }
    } else if (message.kind === 'meta') {
        let doc = docs.get(message.playlistId);
        if (doc === undefined) {
            // A playlist created on another device: adopt the shell; the
            // next sync pass backfills createdAt + the durable row.
            doc = {
                createdAt: new Date().toISOString(),
                deletedAtOpId: null,
                elements: new Map(),
                id: message.playlistId,
                meta: {
                    coverUrl: message.meta.coverUrl,
                    description: message.meta.description,
                    isPublic: message.meta.isPublic,
                    name: message.meta.name,
                },
                metaOpId: 0n,
                needsRebalance: false,
            };
            docs.set(message.playlistId, doc);
        }
        if (applyPlaylistMeta(doc, BigInt(message.metaOpId), message.meta)) {
            changed = true;
        }
    } else {
        const doc = docs.get(message.playlistId);
        if (doc === undefined || doc.deletedAtOpId !== null) {
            return; // ops for an unknown playlist arrive via the sync pass
        }
        for (const wire of message.ops) {
            const decoded = decodeWireOp(wire);
            if (decoded === null) {
                continue; // corrupt rows never enter the replica
            }
            // Adds without the out-of-band track id are rejected by the
            // CRDT lane itself (a bare cad is not invertible).
            const outcome = applyPlaylistOp(doc, decoded);
            if (outcome.applied) {
                changed = true;
            }
        }
    }

    if (!changed) {
        return;
    }
    savePlaylistState(userId, docs, outbox, state.persistedRemote);
    invalidateStreamifyPlaylistQueries();
    // A remote add/remove may touch tracks our snapshot has not loaded;
    // the next read reloads the snapshot (lazy memo invalidation lives in
    // the controller's read path).
    schedulePlaylistSync();
    logger.debug('Streamify playlist broadcast applied', {
        action: 'streamify_playlist_broadcast_applied',
        deviceId,
        kind: message.kind,
        playlistId: message.playlistId,
    });
}

function teardownBound(): void {
    if (bound === null) {
        return;
    }
    clearInterval(bound.interval);
    bound = null;
}
