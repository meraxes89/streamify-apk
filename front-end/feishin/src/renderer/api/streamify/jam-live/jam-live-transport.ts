/**
 * streamify/jam-live/jam-live-transport.ts - the Supabase realtime + RPC
 * lane for a Jam listening room (P4B).
 *
 * Wire-compatible with the web client's transport
 * (front-end/web/src/lib/jam/transport.ts) while riding supabase-js
 * instead of the raw Phoenix socket:
 *
 *   - channel topic `realtime:jam_<CODE>` (supabase-js prefixes the
 *     `realtime:` namespace itself, so the channel name here is
 *     `jam_<CODE>` with the code uppercased);
 *   - postgres_changes on `public.jam_ops` filtered by
 *     `session_code=eq.<CODE>` - the append-only op journal (the ONLY
 *     mutation lane, through the `jam_mutation` SECURITY DEFINER RPC);
 *   - broadcast `self: false` - the host/follower playback-delta lane
 *     (events jam_sync / jam_meta / jam_control);
 *   - presence keyed by device id - the live participant roster (the
 *     authoritative roster stays the DB row; presence is the fast view);
 *   - `jam_touch_presence` every 15 s (membership keepalive),
 *     `jam_heartbeat` for the host lease (HOST | DEMOTED).
 *
 * u64 RPC parameters cross as decimal strings (the bridge discipline - see
 * jam-live-wire.ts).
 */

import type { RealtimeChannel, SupabaseClient } from '@supabase/supabase-js';

import type { JamBroadcastMessage, JamSyncMessage } from './jam-live-protocol';
import type { JamJournalRow, JamOp } from './jam-live-wire';

import {
    JAM_CONTROL_EVENT,
    JAM_META_EVENT,
    JAM_SYNC_EVENT,
    type JamMetaMessage,
} from './jam-live-protocol';
import { jamOpToDto } from './jam-live-wire';

export interface JamMutationResult {
    error?: null | string;
    ok: boolean;
}

export interface JamPresenceEntry {
    deviceId: string;
    lastSeenMs: number;
    name: null | string;
    userId: null | string;
}

/** The `listening_sessions` columns a client needs. */
export interface JamSessionRow {
    hostEpoch: number;
    hostUserId: null | string;
    id: string;
    participantIds: string[];
    sessionCode: string;
}

export interface JamTransportDeps {
    client: SupabaseClient;
    code: string;
    deviceId: string;
    events: JamTransportEvents;
    /** Presence payload (display name / user id), tracked on subscribe. */
    presenceMeta: { name: null | string; userId: null | string };
}

export interface JamTransportEvents {
    /** Raw broadcast payload by event name; the controller validates. */
    onBroadcast(event: string, raw: unknown): void;
    onOps(rows: JamJournalRow[]): void;
    onPresence(entries: JamPresenceEntry[]): void;
    onStatus(status: JamTransportStatus, detail?: string): void;
}

export type JamTransportStatus = 'closed' | 'connecting' | 'error' | 'live';

const JAM_OPS_COLUMNS =
    'op_id,sender_nonce,op_type,policy_flags,track_cad_id,frac_bits,target_add_op_id';

/** Normalize a raw realtime/PostgREST row defensively. */
const toJournalRow = (raw: Record<string, unknown>): JamJournalRow => ({
    frac_bits: (raw.frac_bits ?? 0) as JamJournalRow['frac_bits'],
    op_id: (raw.op_id ?? 0) as JamJournalRow['op_id'],
    op_type: Number(raw.op_type ?? 0),
    policy_flags: (raw.policy_flags ?? 0) as JamJournalRow['policy_flags'],
    sender_nonce: (raw.sender_nonce ?? 0) as JamJournalRow['sender_nonce'],
    target_add_op_id: (raw.target_add_op_id ?? 0) as JamJournalRow['target_add_op_id'],
    track_cad_id: (raw.track_cad_id ?? 0) as JamJournalRow['track_cad_id'],
});

export class JamLiveTransport {
    private readonly channel: RealtimeChannel;

    private closed = false;

    private readonly deps: JamTransportDeps;

    constructor(deps: JamTransportDeps) {
        this.deps = deps;
        const code = deps.code.toUpperCase();
        this.channel = deps.client.channel(`jam_${code}`, {
            config: {
                broadcast: { self: false },
                presence: { key: deps.deviceId },
            },
        });
    }

    broadcastControl(message: JamBroadcastMessage): void {
        void this.channel.send({
            event: JAM_CONTROL_EVENT,
            payload: message,
            type: 'broadcast',
        });
    }

    broadcastMeta(message: JamMetaMessage): void {
        void this.channel.send({
            event: JAM_META_EVENT,
            payload: message,
            type: 'broadcast',
        });
    }

    broadcastSync(message: JamSyncMessage): void {
        void this.channel.send({
            event: JAM_SYNC_EVENT,
            payload: message,
            type: 'broadcast',
        });
    }

    /** Subscribe to the room channel (journal + broadcast + presence). */
    async connect(): Promise<void> {
        this.deps.events.onStatus('connecting');
        this.channel
            .on(
                'postgres_changes',
                {
                    event: '*',
                    filter: `session_code=eq.${this.deps.code.toUpperCase()}`,
                    schema: 'public',
                    table: 'jam_ops',
                },
                (payload: { new: Record<string, unknown> }) => {
                    if (this.closed) return;
                    const row = toJournalRow(payload.new ?? {});
                    this.deps.events.onOps([row]);
                },
            )
            .on('broadcast', { event: JAM_SYNC_EVENT }, (payload: { payload: unknown }) => {
                if (this.closed) return;
                this.deps.events.onBroadcast(JAM_SYNC_EVENT, payload.payload);
            })
            .on('broadcast', { event: JAM_META_EVENT }, (payload: { payload: unknown }) => {
                if (this.closed) return;
                this.deps.events.onBroadcast(JAM_META_EVENT, payload.payload);
            })
            .on('broadcast', { event: JAM_CONTROL_EVENT }, (payload: { payload: unknown }) => {
                if (this.closed) return;
                this.deps.events.onBroadcast(JAM_CONTROL_EVENT, payload.payload);
            })
            .on('presence', { event: 'sync' }, () => {
                if (this.closed) return;
                this.deps.events.onPresence(this.readPresence());
            });

        await new Promise<void>((resolve, reject) => {
            this.channel.subscribe(async (status: string) => {
                if (status === 'SUBSCRIBED') {
                    try {
                        await this.channel.track({
                            lastSeenMs: Date.now(),
                            name: this.deps.presenceMeta.name,
                            userId: this.deps.presenceMeta.userId,
                        });
                    } catch {
                        // Presence tracking is best-effort; the DB roster via
                        // jam_touch_presence remains the membership fence.
                    }
                    this.deps.events.onStatus('live');
                    resolve();
                } else if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT') {
                    this.deps.events.onStatus('error', status);
                    reject(new Error(`jam channel ${status}`));
                }
            });
        });
    }

    /** The bootstrap journal (full history, ascending op id). */
    async fetchJournal(): Promise<JamJournalRow[]> {
        const { data, error } = await this.deps.client
            .from('jam_ops')
            .select(JAM_OPS_COLUMNS)
            .eq('session_code', this.deps.code.toUpperCase())
            .order('op_id', { ascending: true });
        if (error) {
            throw new Error(`jam journal fetch failed: ${error.message}`);
        }
        return (data ?? []).map((row) => toJournalRow(row as Record<string, unknown>));
    }

    /** Resolve the room's `listening_sessions` row (null when absent). */
    async fetchSessionRow(): Promise<JamSessionRow | null> {
        const { data, error } = await this.deps.client
            .from('listening_sessions')
            .select('id,host_user_id,participant_ids,host_epoch')
            .eq('session_code', this.deps.code.toUpperCase())
            .maybeSingle();
        if (error) {
            throw new Error(`jam session fetch failed: ${error.message}`);
        }
        if (!data) return null;
        const row = data as {
            host_epoch: null | number;
            host_user_id: null | string;
            id: string;
            participant_ids: null | string[];
        };
        return {
            hostEpoch: row.host_epoch ?? 0,
            hostUserId: row.host_user_id,
            id: row.id,
            participantIds: row.participant_ids ?? [],
            sessionCode: this.deps.code.toUpperCase(),
        };
    }

    /** Host lease renewal. Returns 'HOST' | 'DEMOTED' | null (no lease). */
    async heartbeat(
        sessionId: string,
        positionMs: number,
        monoMs: number,
    ): Promise<'DEMOTED' | 'HOST' | null> {
        const { data, error } = await this.deps.client.rpc('jam_heartbeat', {
            p_mono_ms: monoMs,
            p_pos_ms: positionMs,
            p_session_id: sessionId,
        });
        if (error) return null;
        return data === 'HOST' || data === 'DEMOTED' ? data : null;
    }

    /** Create the room row (host). Returns the inserted row. */
    async insertSessionRow(hostUserId: null | string): Promise<JamSessionRow> {
        const { data, error } = await this.deps.client
            .from('listening_sessions')
            .insert({
                host_user_id: hostUserId,
                participant_ids: hostUserId ? [hostUserId] : [],
                session_code: this.deps.code.toUpperCase(),
            })
            .select('id,host_user_id,participant_ids,host_epoch')
            .single();
        if (error) {
            throw new Error(`jam session insert failed: ${error.message}`);
        }
        const row = data as {
            host_epoch: null | number;
            host_user_id: null | string;
            id: string;
            participant_ids: null | string[];
        };
        return {
            hostEpoch: row.host_epoch ?? 0,
            hostUserId: row.host_user_id,
            id: row.id,
            participantIds: row.participant_ids ?? [],
            sessionCode: this.deps.code.toUpperCase(),
        };
    }

    /** Cleanly leave: untrack presence, unsubscribe, remove the channel. */
    async leave(): Promise<void> {
        if (this.closed) return;
        this.closed = true;
        try {
            await this.channel.untrack();
        } catch {
            // best-effort
        }
        try {
            this.deps.client.removeChannel(this.channel);
        } catch {
            // best-effort
        }
        this.deps.events.onStatus('closed');
    }

    /** Append one op through the SECURITY DEFINER mutation RPC. */
    async sendOp(op: JamOp): Promise<JamMutationResult> {
        const dto = jamOpToDto(op);
        const { data, error } = await this.deps.client.rpc('jam_mutation', {
            p_cad_id: dto.cadId,
            p_frac_bits: fracBitsWire(op),
            p_op_id: dto.opId,
            p_op_type: dto.opType,
            p_policy: dto.policyFlags,
            p_sender_nonce: senderNonceWire(op),
            p_session_code: this.deps.code.toUpperCase(),
            p_target_op_id: dto.target,
        });
        if (error) {
            return { error: error.message, ok: false };
        }
        const verdict = data as null | { error?: string; ok?: boolean };
        if (verdict && verdict.ok === false) {
            return { error: verdict.error ?? 'rejected', ok: false };
        }
        return { ok: true };
    }

    /** Membership keepalive (both roles, 15 s cadence). */
    async touchPresence(sessionId: string, userId: null | string): Promise<void> {
        if (!userId) return;
        await this.deps.client.rpc('jam_touch_presence', {
            p_session_id: sessionId,
            p_user_id: userId,
        });
    }

    private readPresence(): JamPresenceEntry[] {
        const state = this.channel.presenceState() as Record<
            string,
            Array<{
                lastSeenMs?: number;
                name?: null | string;
                user_id?: null | string;
                userId?: null | string;
            }>
        >;
        const entries: JamPresenceEntry[] = [];
        for (const [deviceId, metas] of Object.entries(state)) {
            const meta = metas[0];
            if (!meta) continue;
            entries.push({
                deviceId,
                lastSeenMs: typeof meta.lastSeenMs === 'number' ? meta.lastSeenMs : Date.now(),
                name: meta.name ?? null,
                userId: meta.userId ?? meta.user_id ?? null,
            });
        }
        return entries.sort((a, b) => (a.deviceId < b.deviceId ? -1 : 1));
    }
}

/** frac bits as the exact decimal string (u64 bridge discipline). */
function fracBitsWire(op: JamOp): string {
    const view = new DataView(new ArrayBuffer(8));
    view.setFloat64(0, op.fracIndex, true);
    return view.getBigUint64(0, true).toString(10);
}

/** sender nonce as u32 decimal string (the RPC takes BIGINT). */
function senderNonceWire(op: JamOp): string {
    const view = new DataView(new ArrayBuffer(4));
    for (let i = 0; i < 4; i += 1) {
        view.setUint8(i, op.senderNonce[i] ?? 0);
    }
    return view.getUint32(0, true).toString(10);
}
