/**
 * lib/jam/transport.ts — the web Jam transport (WS-W engine facade
 * breadth, work item 1).
 *
 * ═════════════════════════════════════════════════════════════════════
 * TRANSPORT POSTURE — the ADR note (web vs the v4 P2P mesh)
 * ═════════════════════════════════════════════════════════════════════
 *
 * Plan §11 told the web to reuse the Supabase jam layer verbatim —
 * jam_rpc.sql + jam_lease.sql + realtime channels, websocket protocol
 * unchanged. Since then ANDROID moved to a v4 P2P mesh (JamWire binary
 * frames over LAN/WiFi-Direct/BLE/WebRTC data channels; the legacy
 * SupabaseJamClient was removed there as a dead path). The SQL layer that
 * the legacy client authored is transport-agnostic, and it is exactly what
 * the web rides:
 *
 *   OUTBOUND  `jam_mutation()` — the P7 SECURITY DEFINER RPC: JWT identity,
 *             room-membership check, append-only `jam_ops` journal (dedupe
 *             on session_code+op_id), pg_notify fanout. The op crosses as
 *             the Phase-2 param tuple.
 *   INBOUND   Postgres Changes on `public.jam_ops` (the publication
 *             jam_rpc.sql adds), filtered by session_code — one row per
 *             op. Each row converts to the frozen 48-byte `rust_48` image
 *             THROUGH the engine's pack lane (jamEngine.opRowToBytes — the
 *             FNV-1a checksum is the engine's, never TS-side math), then
 *             the batch folds via `apply_jam_delta`.
 *   LIVENESS  `jam_touch_presence()` (P8) on a 15 s cadence — the host-lease
 *             heartbeat. Lease claim/succession stays server-authoritative
 *             (jam_lease.sql's A+B election contract); the client only
 *             touches presence.
 *
 * What does NOT exist on the web (honest, §9.4 bucket B — never faked):
 *   • the v4 JAM4 binary frame grammar (TICK/SYNC/CONTINUATION clock
 *     discipline, topology, governance frames) — no LAN beacon, no BLE
 *     pairing, no WebRTC mesh. Position sync rides the engine's existing
 *     DAC-clock snapshot lane; the tick matrix is a later work item.
 *   • LAN/BLE join surfaces lower to INVITE LINKS (`#/?jam=<code>`), the
 *     web-adapted parity for the JoinFabric rails.
 *   • Where a future v4 mesh peer bridges in, its `kotlin_48` image stays
 *     a DISTINCT frozen format (the golden suite's non-convergence guard);
 *     conversion happens at that bridge, never in the engine.
 *
 * PRECISION (the honest caveat): the journal's BIGINT columns (op_id,
 * frac_bits, cad_id) serialize as JSON NUMBERS through PostgREST/Realtime
 * — JS doubles round above 2^53. All receivers parse the same JSON text,
 * so replicas agree on the rounded values (convergence holds); a sender's
 * exact bits can differ from receivers' view until the SQL layer
 * standardizes on TEXT payloads (a follow-up migration — deferred, called
 * out in the PR). This transport SENDS exact decimal strings for its own
 * u64 params (jamEngine.u64ForWire) and ACCEPTS both strings (exact) and
 * numbers (legacy peers) inbound.
 */

import { getSession, restRpc, fetchJamSessionRow } from '../supabase';
import { u64, u64ForWire, opRowToBytes, type JamOpsRow } from '../jamEngine';

export type { JamOpsRow } from '../jamEngine';

export type JamTransportStatus = 'idle' | 'connecting' | 'live' | 'error' | 'closed';

export interface JamTransportEvents {
  onStatus(status: JamTransportStatus, detail: string | null): void;
  /** Inbound journal rows (already deduped by the server; the store folds them). */
  onOps(rows: JamOpsRow[]): void;
}

/** One outbound op in the Phase-2 jam_mutation param shape. */
export interface JamMutationParams {
  sessionCode: string;
  opId: bigint;
  /** The 4-byte device nonce, as its u64 numeric form (the journal column). */
  senderNonce: bigint;
  opType: number;
  policy: number;
  cadId: bigint;
  fracBits: bigint;
  targetOpId: bigint;
}

const HEARTBEAT_MS = 25_000;

/**
 * The realtime channel client: one Phoenix-style WebSocket against the
 * project's realtime endpoint, one topic (`realtime:jam_<CODE>`) carrying
 * the jam_ops Postgres Changes subscription. Raw protocol by house style —
 * zero supabase-js dependencies (lib/supabase.ts's discipline).
 */
export class JamRealtimeTransport {
  private readonly code: string;
  private readonly events: JamTransportEvents;
  private ws: WebSocket | null = null;
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private ref = 0;
  private joined = false;
  private presenceTimer: ReturnType<typeof setInterval> | null = null;
  private sessionId: string | null = null;
  private closedByUs = false;

  constructor(sessionCode: string, events: JamTransportEvents) {
    this.code = sessionCode.toUpperCase();
    this.events = events;
  }

  /** The room's journal topic. */
  private get topic(): string {
    return `realtime:jam_${this.code}`;
  }

  /**
   * Connect: open the socket, join the topic with the jam_ops changes
   * config, and start the liveness cadence. Never rejects — status verdicts
   * arrive through onStatus (the honest-degradation lane).
   */
  connect(): void {
    this.events.onStatus('connecting', null);
    const url = (import.meta.env.VITE_SUPABASE_URL as string | undefined) ?? '';
    const anon = (import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined) ?? '';
    if (url === '' || anon === '') {
      this.events.onStatus(
        'error',
        'Supabase not configured — the Jam transport needs the realtime endpoint',
      );
      return;
    }
    const wsBase = url.replace(/\/+$/, '').replace(/^http/, 'ws');
    let socket: WebSocket;
    try {
      socket = new WebSocket(
        `${wsBase}/realtime/v1/websocket?apikey=${encodeURIComponent(anon)}&vsn=1.0.0`,
      );
    } catch (err) {
      this.events.onStatus(
        'error',
        `realtime socket failed: ${err instanceof Error ? err.message : String(err)}`,
      );
      return;
    }
    this.ws = socket;

    socket.addEventListener('open', () => {
      this.send('phx_join', this.topic, {
        config: {
          postgres_changes: [
            {
              event: '*',
              schema: 'public',
              table: 'jam_ops',
              filter: `session_code=eq.${this.code}`,
            },
          ],
          broadcast: { self: false },
          presence: { key: '' },
        },
      });
      this.heartbeat = setInterval(() => {
        this.send('heartbeat', 'phoenix', {});
      }, HEARTBEAT_MS);
      void this.startPresence();
    });

    socket.addEventListener('message', (ev) => {
      this.onMessage(String(ev.data));
    });

    socket.addEventListener('close', () => {
      this.stopTimers();
      if (!this.closedByUs) {
        this.events.onStatus('error', 'realtime channel closed by the server');
      } else {
        this.events.onStatus('closed', null);
      }
    });

    socket.addEventListener('error', () => {
      this.events.onStatus('error', 'realtime socket error');
    });
  }

  /** Graceful leave. */
  close(): void {
    this.closedByUs = true;
    if (this.joined) this.send('phx_leave', this.topic, {});
    this.stopTimers();
    if (this.ws !== null && this.ws.readyState <= WebSocket.OPEN) {
      this.ws.close();
    }
    this.events.onStatus('closed', null);
  }

  /**
   * Submit one op through the auth-bound mutation path (jam_mutation).
   * Resolves with the server's verdict; throws with its verbatim error
   * ('unauthenticated' | 'session_not_found' | 'not_a_participant' | …).
   */
  async sendOp(op: JamMutationParams): Promise<void> {
    await restRpc('jam_mutation', {
      p_session_code: this.code,
      p_op_id: u64ForWire(op.opId),
      p_sender_nonce: u64ForWire(op.senderNonce),
      p_op_type: op.opType,
      p_policy: op.policy,
      p_cad_id: u64ForWire(op.cadId),
      p_frac_bits: u64ForWire(op.fracBits),
      p_target_op_id: u64ForWire(op.targetOpId),
    });
  }

  // -- internals ------------------------------------------------------------

  private send(event: string, topic: string, payload: Record<string, unknown>): void {
    if (this.ws === null || this.ws.readyState !== WebSocket.OPEN) return;
    this.ref += 1;
    this.ws.send(JSON.stringify({ topic, event, payload, ref: String(this.ref) }));
  }

  private onMessage(raw: string): void {
    let msg: {
      topic?: string;
      event?: string;
      payload?: {
        status?: string;
        response?: { reason?: string };
        record?: Record<string, unknown>;
        data?: { record?: Record<string, unknown> };
        type?: string;
      };
    };
    try {
      msg = JSON.parse(raw);
    } catch {
      return; // malformed frame — ignored, never fatal
    }
    if (msg.topic !== this.topic) {
      return; // phoenix system traffic (heartbeats etc.) — not ours
    }
    if (msg.event === 'phx_reply') {
      const ok = msg.payload?.status === 'ok';
      if (ok && !this.joined) {
        this.joined = true;
        this.events.onStatus('live', null);
      } else if (!ok) {
        this.events.onStatus(
          'error',
          `channel join refused: ${msg.payload?.response?.reason ?? 'unknown'}`,
        );
      }
      return;
    }
    if (msg.event === 'postgres_changes') {
      const record = msg.payload?.record ?? msg.payload?.data?.record;
      if (record !== undefined) {
        const row: JamOpsRow = {
          op_id: (record.op_id ?? 0) as string | number,
          sender_nonce: (record.sender_nonce ?? 0) as string | number,
          op_type: Number(record.op_type ?? 0),
          policy_flags: Number(record.policy_flags ?? 0),
          track_cad_id: (record.track_cad_id ?? 0) as string | number,
          frac_bits: (record.frac_bits ?? 0) as string | number,
          target_add_op_id: (record.target_add_op_id ?? 0) as string | number,
        };
        this.events.onOps([row]);
      }
    }
  }

  /**
   * The P8 liveness cadence: resolve the room's lease row once (the honest
   * verdict when unreadable), then touch presence every 15 s. The lease
   * claim itself stays server-side (jam_lease.sql's election contract).
   */
  private async startPresence(): Promise<void> {
    try {
      const row = await fetchJamSessionRow(this.code);
      if (row === null) {
        this.events.onStatus('error', 'jam room not found (or unreadable) for this session code');
        return;
      }
      this.sessionId = row.id;
      await this.touchPresence();
      this.presenceTimer = setInterval(() => {
        void this.touchPresence();
      }, 15_000);
    } catch (err) {
      this.events.onStatus(
        'error',
        `presence start failed: ${err instanceof Error ? err.message : String(err)}`,
      );
    }
  }

  private async touchPresence(): Promise<void> {
    const session = await getSession();
    if (session === null || this.sessionId === null) return;
    try {
      await restRpc('jam_touch_presence', {
        p_session_id: this.sessionId,
        p_user_id: session.user.id,
      });
    } catch {
      // A missed touch is a degradation, not a disconnect — the lease
      // window (P8) tolerates it; the next cadence retries.
    }
  }

  private stopTimers(): void {
    if (this.heartbeat !== null) {
      clearInterval(this.heartbeat);
      this.heartbeat = null;
    }
    if (this.presenceTimer !== null) {
      clearInterval(this.presenceTimer);
      this.presenceTimer = null;
    }
  }
}

/** Parse an inbound BIGINT field exactly when possible (string form wins). */
export function inboundU64(value: string | number | null | undefined): bigint {
  return u64(value ?? 0);
}

/**
 * Convert one inbound journal row to the 48-byte engine wire row — the
 * transport's one conversion step (re-exported for the store).
 */
export async function rowToWireBytes(row: JamOpsRow): Promise<Uint8Array | null> {
  return opRowToBytes(row);
}
