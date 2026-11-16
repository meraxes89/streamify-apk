/**
 * lib/jamEngine.ts — the JAM half of the engine bridge (WS-W engine
 * facade breadth, work item 1: the Jam CRDT cluster).
 *
 * BRIDGE CONTRACT (the house rule): every JS call goes through a bridge
 * file — the generated engine module (src/wasm-engine) is imported only by
 * bridge code. The engine's wasm surface has two domain halves, exactly
 * like the UDL's StreamifyEngine: the CONTROL plane (lib/wasmEngine.ts —
 * playback state machine, queue, snapshots) and the JAM session lane (this
 * file). UI code imports either bridge, never the generated module.
 *
 * This file owns:
 *   • the jam DTO types (handles, sync verdicts, ops, tracks);
 *   • the free-fn wrappers (the Kotlin jam objects: FractionalIndexEngine,
 *     JamTrackCodec, the CAD-ID mint, the op-id mint, the 48-byte op wire
 *     pack/unpack);
 *   • the u64 BRIDGE DISCIPLINE: op ids / cad ids are unix_ms<<16-class
 *     values — above 2^53, where JS numbers lose exactness. They cross the
 *     wasm boundary as decimal STRINGS and are parsed BigInt here. The
 *     48-byte op wire crosses as Uint8Array (never JSON), so precision is
 *     exact everywhere it matters.
 *
 * The module is the SAME one lib/wasmEngine.ts instantiates (wasm-bindgen
 * init is idempotent — the memoized promise is shared), so there is exactly
 * one wasm engine per page, control plane and jam lane included.
 */

// (The port type is not needed here — the session ops ride the port's
// own lane in lib/wasmEngine.ts; this file owns the free fns + types.)

// The wasm-pack output (generated territory — see src/wasm-engine/).
type JamModule = typeof import('../wasm-engine/streamify_engine_wasm.js');

let modulePromise: Promise<JamModule> | null = null;

/** Load the engine module (memoized; reuses the port's instantiation). */
function ensureModule(): Promise<JamModule> {
  if (modulePromise === null) {
    modulePromise = import('../wasm-engine/streamify_engine_wasm.js').then(async (mod) => {
      await mod.default(); // idempotent (the memoized init promise)
      return mod;
    });
    modulePromise.catch(() => {
      modulePromise = null;
    });
  }
  return modulePromise;
}

// ---------------------------------------------------------------------------
// DTO types (the wasm facade's JSON shapes, typed)
// ---------------------------------------------------------------------------

/** The engine session handle (u64s as decimal strings — parse BigInt). */
export interface JamSessionHandle {
  sessionId: string;
  sessionName: string;
  createdAtUnixMs: string;
}

/** One delta batch's verdict (apply_jam_delta). */
export interface JamSyncResult {
  opsApplied: number;
  opsRejected: number;
  stateHash: string;
  needsRebalance: boolean;
}

/** The local replica's room view (get_jam_room_state). */
export interface JamRoomState {
  roomCode: string;
  isHost: boolean;
  syncOffsetMs: number;
  participants: string[];
}

/** The 48-byte op row's JSON view (u64s as decimal strings). */
export interface JamOpDto {
  opId: string;
  /** 8 hex chars. */
  senderNonce: string;
  /** 1=Add 2=Remove 3=Reorder 4=Vote. */
  opType: number;
  policyFlags: number;
  cadId: string;
  frac: number;
  target: string;
}

/** FractionalIndexEngine.Midpoint. */
export interface FracMidpoint {
  value: number;
  needsRebalance: boolean;
}

/** One queue entry (the JamTrackCodec wire shape, camelCase). */
export interface JamTrack {
  id: number;
  title: string;
  artist: string;
  album: string;
  filepath: string;
  coverArtPath: string;
  durationSec: number;
  ytmVideoId?: string;
  isrc?: string;
  addedBy?: string;
}

// ---------------------------------------------------------------------------
// u64 bridge helpers
// ---------------------------------------------------------------------------

/** Decimal-string → BigInt (the u64 bridge; 0 for unparseable). */
export function u64(value: string | number | null | undefined): bigint {
  if (typeof value === 'number') return BigInt(Math.trunc(value));
  if (value === null || value === undefined || value === '') return 0n;
  try {
    return BigInt(value);
  } catch {
    return 0n;
  }
}

/**
 * A u64 for a JSON transport payload: exact decimal STRING when above
 * 2^53 (where JS numbers round), plain number below. Receiving ends
 * should prefer strings; the number form exists for pre-JSON-string
 * peers (documented in the transport ADR note — see lib/jam/transport.ts).
 */
export function u64ForWire(value: bigint): string | number {
  const n = Number(value);
  if (Number.isSafeInteger(n)) return n;
  return value.toString();
}

// ---------------------------------------------------------------------------
// The free-fn wrappers (the Kotlin jam objects, through the engine)
// ---------------------------------------------------------------------------

/** Ensure the jam engine is loaded (the app calls this before any op). */
export async function ensureJamEngine(): Promise<void> {
  await ensureModule();
}

/** `JamOp.generateOpId()` — decimal string (parse with u64()). */
export async function generateOpId(): Promise<string> {
  const mod = await ensureModule();
  return mod.jam_generate_op_id();
}

/** The canonical CAD-ID for a track (decimal string; parse with u64()). */
export async function canonicalCadId(
  title: string,
  artist: string,
  durationSec: number,
): Promise<string> {
  const mod = await ensureModule();
  return mod.canonical_cad_id(title, artist, Math.max(0, Math.round(durationSec)));
}

/** Pack one op JSON into the frozen 48-byte wire row (checksum sealed). */
export async function packOp(op: JamOpDto): Promise<Uint8Array> {
  const mod = await ensureModule();
  return mod.jam_op_to_bytes(JSON.stringify(op));
}

/** Unpack a 48-byte row; null when structurally corrupt. */
export async function unpackOp(bytes: Uint8Array): Promise<JamOpDto | null> {
  const mod = await ensureModule();
  const raw = mod.jam_op_from_bytes(bytes);
  if (raw === 'null') return null;
  return JSON.parse(raw) as JamOpDto;
}

/** FractionalIndexEngine.after. */
export async function fracAfter(last: number | null): Promise<FracMidpoint> {
  const mod = await ensureModule();
  return JSON.parse(mod.frac_after(last ?? null));
}

/** FractionalIndexEngine.before. */
export async function fracBefore(first: number | null): Promise<FracMidpoint> {
  const mod = await ensureModule();
  return JSON.parse(mod.frac_before(first ?? null));
}

/** FractionalIndexEngine.between. */
export async function fracBetween(
  prev: number | null,
  next: number | null,
): Promise<FracMidpoint> {
  const mod = await ensureModule();
  return JSON.parse(mod.frac_between(prev ?? null, next ?? null));
}

/** FractionalIndexEngine.indexForInsertAt (position clamped internally). */
export async function fracIndexForInsertAt(
  position: number,
  orderedFracs: number[],
): Promise<FracMidpoint> {
  const mod = await ensureModule();
  return JSON.parse(mod.frac_index_for_insert_at(position, orderedFracs));
}

/** FractionalIndexEngine.collide. */
export async function fracCollide(a: number, b: number): Promise<boolean> {
  const mod = await ensureModule();
  return mod.frac_collide(a, b);
}

/** Encode one queue entry (normalize; blank optionals dropped). */
export async function encodeJamTrack(track: JamTrack): Promise<string> {
  const mod = await ensureModule();
  return mod.jam_track_to_json(JSON.stringify(track));
}

/** Decode one queue entry; null when the title is blank/missing. */
export async function decodeJamTrack(raw: string): Promise<JamTrack | null> {
  const mod = await ensureModule();
  const out = mod.jam_track_from_json(raw);
  if (out === 'null') return null;
  return JSON.parse(out) as JamTrack;
}

/** Java String.hashCode parity (the id fallback when a row lacks `id`). */
export function javaStringHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i += 1) {
    h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  }
  return h;
}

// ---------------------------------------------------------------------------
// The jam_ops DB row → 48-byte wire row (the transport's inbound conversion)
// ---------------------------------------------------------------------------

/**
 * One jam_ops journal row as Postgres Changes delivers it (columns 1:1 with
 * the Phase-2 SQL; BIGINTs arrive as JSON numbers — see the precision note
 * in lib/jam/transport.ts).
 */
export interface JamOpsRow {
  op_id: string | number;
  sender_nonce: string | number;
  op_type: number;
  policy_flags: number;
  track_cad_id: string | number;
  frac_bits: string | number;
  target_add_op_id: string | number;
}

/** IEEE754 raw bits (exact integer) → f64 (the CRDT's frac domain). */
function fracFromBits(bitsRaw: string | number): number {
  const bits = u64(bitsRaw);
  // A DataView round-trip keeps this exact for any f64 the CRDT uses.
  const buf = new ArrayBuffer(8);
  new DataView(buf).setBigUint64(0, bits, true);
  return new DataView(buf).getFloat64(0, true);
}

/** f64 → IEEE754 raw bits (exact integer). */
export function fracToBits(frac: number): bigint {
  const buf = new ArrayBuffer(8);
  new DataView(buf).setFloat64(0, frac, true);
  return new DataView(buf).getBigUint64(0, true);
}

/**
 * Convert one inbound jam_ops row into the 48-byte wire row — THROUGH the
 * engine's pack lane (jam_op_to_bytes computes the FNV-1a checksum and
 * zeroes the pads; the conversion itself lives here at the transport
 * boundary, never inside the engine).
 */
export async function opRowToBytes(row: JamOpsRow): Promise<Uint8Array | null> {
  const senderNonceBigInt = u64(row.sender_nonce);
  // The journal's BIGINT sender_nonce → the wire's [u8;4] nonce: the low
  // 32 bits, hex-encoded (the device-nonce half the v4 header carries).
  const nonceHex = (senderNonceBigInt & 0xffffffffn).toString(16).padStart(8, '0');
  const op: JamOpDto = {
    opId: String(row.op_id),
    senderNonce: nonceHex,
    opType: row.op_type,
    policyFlags: row.policy_flags ?? 0,
    cadId: String(row.track_cad_id),
    frac: fracFromBits(row.frac_bits),
    target: String(row.target_add_op_id ?? 0),
  };
  try {
    return await packOp(op);
  } catch {
    return null; // corrupt row (unknown op type / bad fields) never enters
  }
}
