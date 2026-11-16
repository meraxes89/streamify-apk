/**
 * streamify/playlist-sync/wire.ts - the realtime broadcast wire (P3A).
 *
 * THE WIRE FORMAT (the jam Supabase discipline, mirrored from
 * front-end/web/src/lib/jam/transport.ts + lib/jamEngine.ts):
 *
 *   - Ops cross as the FROZEN 48-BYTE ROW (base64 here, Uint8Array on the
 *     wasm lane) - "the op wire crosses as bytes, never JSON", so the
 *     FNV-1a checksum seals the payload and receiving ends validate before
 *     applying. u64 fields never touch JSON numbers.
 *   - The track id rides OUT OF BAND next to the row (the JamTrackCodec
 *     discipline: the cad in the row is a one-way hash; the value travels
 *     alongside, exactly like the Kotlin TRACK_META frames).
 *   - Meta and delete messages carry their LWW clock as a decimal string
 *     (the u64 bridge: unix_ms<<16-class values are above 2^53).
 *
 * Channel: `playlist-ops:<userId>` - the signed-in user's own devices (the
 * multi-device sync case). Collaborators converge through the durable
 * tables + the sync pass (broadcast is a latency optimization; the
 * anti-entropy pass is the correctness mechanism - see sync.ts).
 */

import { type JamOp, jamOpFromBytes, jamOpIsValid, jamOpToBytes } from '../jam/jam-op';

export const PLAYLIST_BROADCAST_EVENT = 'p3a-playlists';
export const PLAYLIST_BROADCAST_CHANNEL_PREFIX = 'playlist-ops:';

export type PlaylistBroadcastMessage =
    | PlaylistDeleteMessage
    | PlaylistMetaMessage
    | PlaylistOpsMessage;

export interface PlaylistDeleteMessage {
    deleteOpId: string;
    kind: 'delete';
    playlistId: string;
    sender: string;
    v: 1;
}

export interface PlaylistMetaMessage {
    kind: 'meta';
    meta: PlaylistMetaWire;
    metaOpId: string;
    playlistId: string;
    sender: string;
    v: 1;
}

export interface PlaylistMetaWire {
    coverUrl: null | string;
    description: null | string;
    isPublic: boolean;
    name: string;
}

export interface PlaylistOpsMessage {
    kind: 'ops';
    ops: PlaylistOpWire[];
    playlistId: string;
    sender: string;
    v: 1;
}

/** One op in a broadcast message: the sealed 48-byte row + out-of-band value. */
export interface PlaylistOpWire {
    /** base64 of the 48-byte row (checksum-sealed). */
    row: string;
    /** The out-of-band track id (Add ops only). */
    trackId?: string;
}

// ---------------------------------------------------------------------------
// base64 (universal - works in node and the renderer)
// ---------------------------------------------------------------------------

export function base64ToBytes(base64: string): null | Uint8Array {
    try {
        if (typeof atob === 'function') {
            const binary = atob(base64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i += 1) {
                bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
        }
        const buffer = Buffer.from(base64, 'base64');
        return new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
    } catch {
        return null;
    }
}

/** Build an ops message from sealed ops (+ their out-of-band track ids). */
export function buildOpsMessage(
    sender: string,
    playlistId: string,
    ops: Array<{ op: JamOp; trackId: null | string }>,
): PlaylistOpsMessage {
    return {
        kind: 'ops',
        ops: ops.map(({ op, trackId }) => ({
            row: bytesToBase64(jamOpToBytes(op)),
            ...(trackId !== null && trackId !== '' ? { trackId } : {}),
        })),
        playlistId,
        sender,
        v: 1,
    };
}

// ---------------------------------------------------------------------------
// Message building + validating decode (the wire gate lives here)
// ---------------------------------------------------------------------------

export function bytesToBase64(bytes: Uint8Array): string {
    let binary = '';
    for (let i = 0; i < bytes.length; i += 1) {
        binary += String.fromCharCode(bytes[i]);
    }
    if (typeof btoa === 'function') {
        return btoa(binary);
    }
    return Buffer.from(bytes).toString('base64');
}

/** Decode + validate an inbound message; null when malformed. */
export function decodePlaylistBroadcast(raw: unknown): null | PlaylistBroadcastMessage {
    if (typeof raw !== 'object' || raw === null) {
        return null;
    }
    const message = raw as Partial<PlaylistBroadcastMessage> & { kind?: unknown };
    if (message.v !== 1) {
        return null;
    }
    if (typeof message.playlistId !== 'string' || typeof message.sender !== 'string') {
        return null;
    }
    if (message.kind === 'ops') {
        const ops = message.ops;
        if (!Array.isArray(ops)) {
            return null;
        }
        const decoded: PlaylistOpWire[] = [];
        for (const op of ops) {
            if (typeof op?.row !== 'string') {
                return null;
            }
            decoded.push({
                row: op.row,
                ...(typeof op.trackId === 'string' && op.trackId !== ''
                    ? { trackId: op.trackId }
                    : {}),
            });
        }
        return {
            kind: 'ops',
            ops: decoded,
            playlistId: message.playlistId,
            sender: message.sender,
            v: 1,
        };
    }
    if (message.kind === 'meta') {
        const meta = message.meta as PlaylistMetaWire | undefined;
        if (
            typeof message.metaOpId !== 'string' ||
            typeof meta?.name !== 'string' ||
            !/^\d+$/.test(message.metaOpId)
        ) {
            return null;
        }
        return {
            kind: 'meta',
            meta: {
                coverUrl: typeof meta.coverUrl === 'string' ? meta.coverUrl : null,
                description: typeof meta.description === 'string' ? meta.description : null,
                isPublic: meta.isPublic !== false,
                name: meta.name,
            },
            metaOpId: message.metaOpId,
            playlistId: message.playlistId,
            sender: message.sender,
            v: 1,
        };
    }
    if (message.kind === 'delete') {
        if (typeof message.deleteOpId !== 'string' || !/^\d+$/.test(message.deleteOpId)) {
            return null;
        }
        return {
            deleteOpId: message.deleteOpId,
            kind: 'delete',
            playlistId: message.playlistId,
            sender: message.sender,
            v: 1,
        };
    }
    return null;
}

/**
 * Decode one wire op into its validated JamOp (checksum + pads + finite
 * frac verified by the jam lane). Null when the row is corrupt.
 */
export function decodeWireOp(wire: PlaylistOpWire): null | { op: JamOp; trackId: null | string } {
    const bytes = base64ToBytes(wire.row);
    if (bytes === null) {
        return null;
    }
    const op = jamOpFromBytes(bytes);
    if (op === null || !jamOpIsValid(op)) {
        // The FNV-1a seal is recomputed over the packed span exactly like
        // the jam engines do - a tampered or truncated row never enters.
        return null;
    }
    return {
        op,
        trackId: typeof wire.trackId === 'string' && wire.trackId !== '' ? wire.trackId : null,
    };
}
