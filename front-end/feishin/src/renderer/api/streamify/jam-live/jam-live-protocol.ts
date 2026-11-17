/**
 * streamify/jam-live/jam-live-protocol.ts - the host/follower playback sync
 * protocol for Streamify Jam rooms (P4B), as pure data + pure math.
 *
 * The transport-agnostic contract (see the ADR,
 * decisions/p4/P4B-jam-eq-theming-settings.md):
 *
 *   - the OP JOURNAL (jam_ops rows via jam_mutation + Realtime
 *     postgres_changes) carries QUEUE mutations only - the same append-only
 *     lane the web client and the Android engine share;
 *   - PLAYBACK DELTAS ride the same Supabase Realtime channel's BROADCAST
 *     lane (event `jam_sync`): the channel is joined with
 *     `broadcast: { self: false }` exactly like the web transport's config,
 *     so host-to-follower fan-out is native;
 *   - TRACK METADATA rides broadcast out-of-band (`jam_meta`) - the 48-byte
 *     op row carries only the cad id (the JamTrackCodec discipline), so the
 *     full playable QueueSong payload travels beside it;
 *   - LATE JOINERS bootstrap the journal with one PostgREST SELECT, then
 *     the host answers presence-join diffs with a full `jam_snapshot`
 *     (queue + metadata + playback position), which is idempotent to apply;
 *   - FOLLOWER DRIFT CORRECTION is threshold-based (the honest feishin-web
 *     posture): a hard window (2 s) seeks immediately; a soft window
 *     (750 ms) seeks after three consecutive offences. The spec's Kalman
 *     PLL / speed-scalar nudge stays deferred, exactly like the web lane.
 *
 * Room codes: 6 chars from the unambiguous 32-glyph alphabet
 * ABCDEFGHJKMNPQRSTUVWXYZ23456789 (I L O 0 1 excluded), always uppercase -
 * the Android JamPairing format, uppercased by every web path.
 */

import type { QueueSong } from '/@/shared/types/domain-types';

/** Room-code alphabet (I L O 0 1 excluded - unambiguous when spoken). */
export const JAM_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const JAM_CODE_LENGTH = 6;

/** Host lease cadence (matches the web presence touch interval). */
export const JAM_PRESENCE_INTERVAL_MS = 15_000;
/** Host tick cadence (playback position broadcasts). */
export const JAM_HOST_TICK_MS = 5_000;
/** Hard drift window: correct immediately. */
export const JAM_DRIFT_HARD_MS = 2_000;
/** Soft drift window: correct after JAM_SOFT_OFFENCES consecutive hits. */
export const JAM_DRIFT_SOFT_MS = 750;
export const JAM_SOFT_OFFENCES = 3;

export type JamBroadcastMessage =
    | JamMetaMessage
    | JamRequestMessage
    | JamSnapshotMessage
    | JamSyncMessage;

/** The out-of-band metadata message for one added track. */
export interface JamMetaMessage {
    cadId: string;
    track: JamTrackPayload;
    v: 1;
}

/** A follower's advisory control request (seek / skip). */
export interface JamRequestMessage {
    kind: 'request';
    /** The requester's position in ms at emit (host decides, may ignore). */
    positionMs: number;
    request: 'seek' | 'skip';
    v: 1;
}

/** Host catch-up for late joiners (idempotent to apply). */
export interface JamSnapshotMessage {
    /** The host's permission posture. */
    hostOnlySkipSeek?: boolean;
    kind: 'snapshot';
    ordered: Array<{
        addOpId: string;
        cadId: string;
        frac: number;
        /** Null while metadata has not arrived yet (rendered as pending). */
        track: JamTrackPayload | null;
    }>;
    playback: {
        currentAddOpId: null | string;
        currentTrack: JamTrackPayload | null;
        isPlaying: boolean;
        positionMs: number;
    };
    v: 1;
}

// ---------------------------------------------------------------------------
// Track payload - the out-of-band metadata (the cad rides the op row)
// ---------------------------------------------------------------------------

export type JamSyncKind = 'end' | 'pause' | 'play' | 'seek' | 'tick' | 'track';

export interface JamSyncMessage {
    /** Host wall-clock ms at emit - the follower's drift baseline. */
    emittedAt: number;
    /** Fencing token: stale-epoch messages are ignored by followers. */
    hostEpoch: number;
    /** The host's permission posture (piggybacked on track/tick). */
    hostOnlySkipSeek?: boolean;
    hostUserId: string;
    kind: JamSyncKind;
    /** Host playback position in ms at emit. */
    positionMs: number;
    /** Full payload (kind='track' only). */
    track?: JamTrackPayload | null;
    /** The queue element the host is playing (kind='track' only). */
    trackAddOpId?: null | string;
    v: 1;
}

// ---------------------------------------------------------------------------
// Playback sync messages (broadcast event `jam_sync`)
// ---------------------------------------------------------------------------

/**
 * A playable track as it crosses the wire. The full QueueSong minus the
 * client-local fields (_uniqueId, _contextPlaylistId) - followers mint
 * their own unique ids on arrival.
 */
export type JamTrackPayload = Omit<QueueSong, '_contextPlaylistId' | '_uniqueId'>;

/** The web invite link for a room (hash-router query, like the web app). */
export function buildJamInviteLink(baseUrl: string, code: string): string {
    const normalized = normalizeJamRoomCode(code);
    if (!normalized) {
        return baseUrl;
    }
    const separator = baseUrl.includes('?') ? '&' : '?';
    return `${baseUrl}${separator}jam=${normalized}`;
}

/** Mint a room code with an injectable RNG (tests pin the stream). */
export function generateJamRoomCode(rand: () => number = Math.random): string {
    let out = '';
    for (let i = 0; i < JAM_CODE_LENGTH; i += 1) {
        out += JAM_CODE_ALPHABET[Math.floor(rand() * JAM_CODE_ALPHABET.length) % 32];
    }
    return out;
}

/**
 * Extract a room code from a shareable link. Accepts the web invite format
 * (`#/?jam=CODE` or any `?jam=CODE` query) and a bare code.
 */
export function inviteRoomCode(input: string): null | string {
    const match = /[?&]jam=([A-Za-z0-9]+)/.exec(input);
    if (match) {
        return normalizeJamRoomCode(match[1]);
    }
    return normalizeJamRoomCode(input);
}

/** Normalize user input into a canonical room code (null when invalid). */
export function normalizeJamRoomCode(input: string): null | string {
    const trimmed = input.trim().toUpperCase();
    if (trimmed.length !== JAM_CODE_LENGTH) {
        return null;
    }
    for (const ch of trimmed) {
        if (!JAM_CODE_ALPHABET.includes(ch)) {
            return null;
        }
    }
    return trimmed;
}

/** Broadcast event names on the room channel. */
export const JAM_SYNC_EVENT = 'jam_sync';
export const JAM_META_EVENT = 'jam_meta';
export const JAM_CONTROL_EVENT = 'jam_control';

// ---------------------------------------------------------------------------
// Ephemeral live reactions (W3-B — broadcast-only, never journaled)
// ---------------------------------------------------------------------------

/** The ephemeral reaction broadcast event (display-only, no persistence). */
export const JAM_REACTION_EVENT = 'jam_reaction';

/** The whitelisted reaction glyphs — the decoder rejects everything else. */
export const JAM_REACTION_EMOJIS: readonly string[] = ['🔥', '❤️', '🎧', '⚡', '👏'];

export type DriftAction = { action: 'none' } | { action: 'seek'; seekToMs: number };

/** One floating reaction as it crosses the wire. */
export interface JamReactionMessage {
    /** A JAM_REACTION_EMOJIS member (validated on decode). */
    emoji: string;
    kind: 'reaction';
    /** Sender nonce hex — display-only attribution, never authoritative. */
    senderNonce: string;
    /** Sender wall-clock ms at emit (stale badges age out client-side). */
    ts: number;
    v: 1;
}

// ---------------------------------------------------------------------------
// Drift math (pure, testable)
// ---------------------------------------------------------------------------

/**
 * Drift decision for one tick/seek observation. `softOffences` is the
 * caller's running count of consecutive soft-window hits (reset on any
 * in-window observation or applied correction).
 */
export function computeDriftCorrection(
    localPositionMs: number,
    hostPositionMs: number,
    softOffences: number,
): DriftAction {
    const drift = localPositionMs - hostPositionMs;
    const abs = Math.abs(drift);
    if (abs >= JAM_DRIFT_HARD_MS) {
        return { action: 'seek', seekToMs: hostPositionMs };
    }
    if (abs >= JAM_DRIFT_SOFT_MS && softOffences + 1 >= JAM_SOFT_OFFENCES) {
        return { action: 'seek', seekToMs: hostPositionMs };
    }
    return { action: 'none' };
}

/** Validate a `jam_reaction` payload (null when malformed). */
export function decodeJamReactionMessage(raw: unknown): JamReactionMessage | null {
    if (typeof raw !== 'object' || raw === null) {
        return null;
    }
    const msg = raw as Partial<JamReactionMessage>;
    if (msg.v !== 1 || msg.kind !== 'reaction') {
        return null;
    }
    if (typeof msg.emoji !== 'string' || !JAM_REACTION_EMOJIS.includes(msg.emoji)) {
        return null;
    }
    if (typeof msg.ts !== 'number' || !Number.isFinite(msg.ts) || msg.ts < 0) {
        return null;
    }
    if (typeof msg.senderNonce !== 'string' || msg.senderNonce === '') {
        return null;
    }
    return { emoji: msg.emoji, kind: 'reaction', senderNonce: msg.senderNonce, ts: msg.ts, v: 1 };
}

/**
 * Estimate the host's CURRENT position from a sync message: while the host
 * is playing, position advances with elapsed wall time (Cristian-lite: the
 * message carries the host's emit timestamp; one-way latency is unbilled,
 * which only ever under-corrects - the safe direction for re-sync).
 */
export function estimateHostPositionMs(msg: JamSyncMessage, nowMs: number): number {
    if (msg.kind === 'pause' || msg.kind === 'end') {
        return msg.positionMs;
    }
    const elapsed = Math.max(0, nowMs - msg.emittedAt);
    return msg.positionMs + elapsed;
}

// ---------------------------------------------------------------------------
// Message validation (untrusted wire input)
// ---------------------------------------------------------------------------

const SYNC_KINDS: ReadonlySet<string> = new Set(['end', 'pause', 'play', 'seek', 'tick', 'track']);

/**
 * Validate a `jam_control` payload. The control lane carries requests and
 * snapshots (sync/meta ride their own events), so the decoded union is
 * exactly those two.
 */
export function decodeJamControlMessage(
    raw: unknown,
): JamRequestMessage | JamSnapshotMessage | null {
    if (typeof raw !== 'object' || raw === null) {
        return null;
    }
    const msg = raw as { kind?: unknown; v?: unknown };
    if (msg.v !== 1) {
        return null;
    }
    if (msg.kind === 'request') {
        const req = raw as Partial<JamRequestMessage>;
        if (
            (req.request !== 'seek' && req.request !== 'skip') ||
            typeof req.positionMs !== 'number' ||
            !Number.isFinite(req.positionMs)
        ) {
            return null;
        }
        return { kind: 'request', positionMs: req.positionMs, request: req.request, v: 1 };
    }
    if (msg.kind === 'snapshot') {
        const snap = raw as Partial<JamSnapshotMessage>;
        if (!Array.isArray(snap.ordered)) {
            return null;
        }
        const playback = snap.playback;
        if (
            !playback ||
            typeof playback.isPlaying !== 'boolean' ||
            !Number.isFinite(playback.positionMs)
        ) {
            return null;
        }
        return {
            hostOnlySkipSeek: snap.hostOnlySkipSeek === true,
            kind: 'snapshot',
            ordered: snap.ordered.filter(
                (entry): entry is JamSnapshotMessage['ordered'][number] =>
                    typeof entry?.addOpId === 'string' &&
                    typeof entry?.cadId === 'string' &&
                    (entry?.track === null || typeof entry?.track?.id === 'string'),
            ),
            playback: {
                currentAddOpId: playback.currentAddOpId ?? null,
                currentTrack:
                    playback.currentTrack && typeof playback.currentTrack.id === 'string'
                        ? playback.currentTrack
                        : null,
                isPlaying: playback.isPlaying,
                positionMs: playback.positionMs,
            },
            v: 1,
        };
    }
    return null;
}

/** Validate a `jam_meta` payload (null when malformed). */
export function decodeJamMetaMessage(raw: unknown): JamMetaMessage | null {
    if (typeof raw !== 'object' || raw === null) {
        return null;
    }
    const msg = raw as Partial<JamMetaMessage>;
    if (msg.v !== 1 || typeof msg.cadId !== 'string' || msg.cadId === '') {
        return null;
    }
    if (!msg.track || typeof msg.track.id !== 'string' || typeof msg.track.name !== 'string') {
        return null;
    }
    return { cadId: msg.cadId, track: msg.track, v: 1 };
}

/** Validate a `jam_sync` payload (null when malformed). */
export function decodeJamSyncMessage(raw: unknown): JamSyncMessage | null {
    if (typeof raw !== 'object' || raw === null) {
        return null;
    }
    const msg = raw as Partial<JamSyncMessage>;
    if (msg.v !== 1 || typeof msg.kind !== 'string' || !SYNC_KINDS.has(msg.kind)) {
        return null;
    }
    const { emittedAt, hostEpoch, hostUserId, kind, positionMs } = msg;
    if (
        typeof positionMs !== 'number' ||
        !Number.isFinite(positionMs) ||
        positionMs < 0 ||
        typeof emittedAt !== 'number' ||
        !Number.isFinite(emittedAt) ||
        typeof hostUserId !== 'string' ||
        typeof hostEpoch !== 'number' ||
        !Number.isInteger(hostEpoch) ||
        hostEpoch < 0
    ) {
        return null;
    }
    if (msg.kind === 'track' && (!msg.track || typeof msg.track.id !== 'string')) {
        return null;
    }
    return {
        emittedAt,
        hostEpoch,
        hostOnlySkipSeek: msg.hostOnlySkipSeek === true,
        hostUserId,
        kind: kind as JamSyncKind,
        positionMs,
        track: msg.track ?? null,
        trackAddOpId: msg.trackAddOpId ?? null,
        v: 1,
    };
}
