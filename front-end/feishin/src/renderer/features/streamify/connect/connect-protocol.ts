/**
 * features/streamify/connect/connect-protocol.ts — the W3-A Streamify
 * Connect wire protocol (PURE — the network-free test lane locks every
 * rule in this file).
 *
 * THE CHANNEL (ADR §A): user-scoped Supabase Realtime
 * `streamify:connect:<userId>` — presence keyed by deviceId (the live
 * device roster + now-playing state) + broadcast `self: false` (the
 * command lane: transport controls and playback-handoff chunks).
 *
 * ECHO / TARGETING RULES (the conflict contract):
 *   • A device NEVER acts on a message whose `fromDeviceId` equals its own
 *     (self-echo — the transport's `self: false` already suppresses the
 *     sender's own copy; this filter is the belt-and-braces layer and the
 *     documented semantic for two tabs of one browser profile, which share
 *     one deviceId).
 *   • A device NEVER acts on a message whose `targetDeviceId` is set and
 *     differs from its own (commands are device-targeted; a third device
 *     overhears but stays silent).
 *
 * PRESENCE (lightweight, throttled by the controller): the shape below is
 * the ENTIRE published state — one JSON object per device, replaced
 * atomically on every `track()`.
 *
 * HANDOFF (the queue transfer): chunked broadcast — the sending device
 * splits its queue's videoIds into CHUNK_SIZE slices; the receiver
 * reassembles by (transferId) with a bounded assembly window, then maps
 * the ids through ITS OWN library snapshot (same account — the sync
 * engine converges the catalog; unknown ids are skipped honestly, never
 * fabricated).
 */

// ---------------------------------------------------------------------------
// Presence
// ---------------------------------------------------------------------------

/** One broadcast message on the command lane (the discriminated union). */
export type ConnectMessage =
    | {
          chunkIndex: number;
          fromDeviceId: string;
          /** Rides chunk 0 only. */
          handoff: HandoffHeader | null;
          kind: 'handoff-chunk';
          targetDeviceId: string;
          totalChunks: number;
          transferId: string;
          videoIds: string[];
      }
    | {
          command: ConnectTransportCommand;
          fromDeviceId: string;
          kind: 'transport';
          /** Request-echo nonce (a controller-side recent-command filter). */
          nonce: string;
          targetDeviceId: string;
          /** seek: seconds; volume: 0..100. */
          value: null | number;
      }
    | {
          fromDeviceId: string;
          kind: 'handoff-request';
          nonce: string;
          /** The requester's interpolated view (informational; the target's
           *  own position wins in the response). */
          positionSec: number;
          targetDeviceId: string;
          transferId: string;
      };

export interface ConnectPresenceState {
    /** null when the device is idle (no active track). */
    currentTrack: ConnectTrackState | null;
    /** The publishing device's stable id (localStorage-minted). */
    deviceId: string;
    /** Human-readable label ("Chrome on Linux"). */
    deviceName: string;
    isPlaying: boolean;
    /** The W2-A playback mode ('audio' | 'video'). */
    mode: 'audio' | 'video';
    /** The audio position at `updatedAt` (seconds; viewers interpolate). */
    positionSec: number;
    /** Epoch ms — the interpolation anchor. */
    updatedAt: number;
}

export interface ConnectTrackState {
    artist: null | string;
    durationSec: null | number;
    /** A thumbnail URL (i.ytimg.com …) — display-only. */
    thumbnailUrl: null | string;
    title: string;
    /** The YTM videoId — also the queue/handoff identity. */
    videoId: string;
}

export type ConnectTransportCommand = 'next' | 'pause' | 'play' | 'prev' | 'seek' | 'volume';

/** The handoff header (rides handoff-chunk 0). */
export interface HandoffHeader {
    currentIndex: number;
    mode: 'audio' | 'video';
    positionSec: number;
}

// ---------------------------------------------------------------------------
// Broadcast messages
// ---------------------------------------------------------------------------

/**
 * The interpolated live position: the anchor plus elapsed wall time while
 * playing, clamped to the track duration. This is the number the handoff
 * viewer and the device list display.
 */
export function interpolatePosition(state: ConnectPresenceState, nowMs: number): number {
    if (!state.isPlaying || state.updatedAt <= 0) return state.positionSec;
    const elapsedSec = Math.max(0, (nowMs - state.updatedAt) / 1000);
    const raw = state.positionSec + elapsedSec;
    if (state.currentTrack?.durationSec != null && state.currentTrack.durationSec > 0) {
        return Math.min(raw, state.currentTrack.durationSec);
    }
    return raw;
}

/** Parse + validate a raw broadcast payload into a typed message. */
export function parseConnectMessage(raw: unknown): ConnectMessage | null {
    if (raw === null || typeof raw !== 'object') return null;
    const msg = raw as Record<string, unknown>;
    const fromDeviceId = typeof msg.fromDeviceId === 'string' ? msg.fromDeviceId : '';
    const targetDeviceId = typeof msg.targetDeviceId === 'string' ? msg.targetDeviceId : '';
    const nonce = typeof msg.nonce === 'string' ? msg.nonce.slice(0, 48) : '';
    if (fromDeviceId === '' || targetDeviceId === '') return null;

    if (msg.kind === 'transport') {
        const command = msg.command;
        if (
            command !== 'play' &&
            command !== 'pause' &&
            command !== 'next' &&
            command !== 'prev' &&
            command !== 'seek' &&
            command !== 'volume'
        ) {
            return null;
        }
        const value =
            typeof msg.value === 'number' && Number.isFinite(msg.value) ? msg.value : null;
        return {
            command,
            fromDeviceId,
            kind: 'transport',
            nonce,
            targetDeviceId,
            value:
                command === 'volume' && value !== null ? Math.min(100, Math.max(0, value)) : value,
        };
    }

    if (msg.kind === 'handoff-request') {
        const transferId = typeof msg.transferId === 'string' ? msg.transferId.slice(0, 48) : '';
        if (transferId === '') return null;
        const positionSec =
            typeof msg.positionSec === 'number' && Number.isFinite(msg.positionSec)
                ? Math.max(0, msg.positionSec)
                : 0;
        return {
            fromDeviceId,
            kind: 'handoff-request',
            nonce,
            positionSec,
            targetDeviceId,
            transferId,
        };
    }

    if (msg.kind === 'handoff-chunk') {
        const transferId = typeof msg.transferId === 'string' ? msg.transferId.slice(0, 48) : '';
        if (transferId === '') return null;
        const chunkIndex = typeof msg.chunkIndex === 'number' ? msg.chunkIndex : -1;
        const totalChunks = typeof msg.totalChunks === 'number' ? msg.totalChunks : -1;
        if (chunkIndex < 0 || totalChunks < 1 || chunkIndex >= totalChunks) return null;
        if (!Array.isArray(msg.videoIds)) return null;
        const videoIds: string[] = [];
        for (const id of msg.videoIds) {
            if (typeof id === 'string' && id !== '' && id.length <= 32) videoIds.push(id);
        }
        let handoff: HandoffHeader | null = null;
        if (chunkIndex === 0 && msg.handoff !== null && typeof msg.handoff === 'object') {
            const h = msg.handoff as Record<string, unknown>;
            const positionSec =
                typeof h.positionSec === 'number' && Number.isFinite(h.positionSec)
                    ? Math.max(0, h.positionSec)
                    : 0;
            const currentIndex =
                typeof h.currentIndex === 'number' && Number.isFinite(h.currentIndex)
                    ? Math.max(0, Math.round(h.currentIndex))
                    : 0;
            handoff = {
                currentIndex,
                mode: h.mode === 'video' ? 'video' : 'audio',
                positionSec,
            };
        }
        return {
            chunkIndex,
            fromDeviceId,
            handoff,
            kind: 'handoff-chunk',
            targetDeviceId,
            totalChunks,
            transferId,
            videoIds,
        };
    }

    return null;
}

/** Validate an incoming presence object (the same rules, read-side). */
export function parseConnectPresence(raw: unknown): ConnectPresenceState | null {
    if (raw === null || typeof raw !== 'object') return null;
    return sanitizePresenceForPublish(raw as Partial<ConnectPresenceState>);
}

/** The publish-side sanitizer: clamp + strip before the object ever
 *  reaches the wire (a hostile/buggy caller cannot smuggle a 50 MB blob
 *  into presence). */
export function sanitizePresenceForPublish(
    raw: Partial<ConnectPresenceState>,
): ConnectPresenceState | null {
    if (typeof raw.deviceId !== 'string' || raw.deviceId === '') return null;
    if (typeof raw.deviceName !== 'string' || raw.deviceName === '') return null;
    const isPlaying = raw.isPlaying === true;
    const positionSec =
        typeof raw.positionSec === 'number' && Number.isFinite(raw.positionSec)
            ? Math.max(0, Math.round(raw.positionSec * 10) / 10)
            : 0;
    const updatedAt =
        typeof raw.updatedAt === 'number' && Number.isFinite(raw.updatedAt) ? raw.updatedAt : 0;
    let currentTrack: ConnectTrackState | null = null;
    const track = raw.currentTrack;
    if (track !== null && track !== undefined && typeof track === 'object') {
        if (
            typeof track.videoId === 'string' &&
            track.videoId !== '' &&
            track.videoId.length <= 32
        ) {
            currentTrack = {
                artist:
                    typeof track.artist === 'string' && track.artist !== ''
                        ? track.artist.slice(0, 200)
                        : null,
                durationSec:
                    typeof track.durationSec === 'number' && Number.isFinite(track.durationSec)
                        ? track.durationSec
                        : null,
                thumbnailUrl:
                    typeof track.thumbnailUrl === 'string' && track.thumbnailUrl !== ''
                        ? track.thumbnailUrl.slice(0, 500)
                        : null,
                title: typeof track.title === 'string' ? track.title.slice(0, 200) : '',
                videoId: track.videoId,
            };
            if (currentTrack.title === '') currentTrack = null;
        }
    }
    return {
        currentTrack,
        deviceId: raw.deviceId.slice(0, 64),
        deviceName: raw.deviceName.slice(0, 100),
        isPlaying,
        mode: raw.mode === 'video' ? 'video' : 'audio',
        positionSec,
        updatedAt,
    };
}

/**
 * The echo/target filter — the single gate every inbound message passes.
 * Order matters: self-echo dies first (never act on your own broadcast),
 * then foreign targets (overheard but not addressed to us).
 */
export function shouldHandleMessage(message: ConnectMessage, selfDeviceId: string): boolean {
    if (message.fromDeviceId === selfDeviceId) return false;
    if (message.targetDeviceId !== selfDeviceId) return false;
    return true;
}

// ---------------------------------------------------------------------------
// Handoff chunking / reassembly
// ---------------------------------------------------------------------------

export const HANDOFF_CHUNK_SIZE = 25;
export const HANDOFF_MAX_TRACKS = 500;
export const HANDOFF_ASSEMBLY_TIMEOUT_MS = 10_000;

export interface HandoffAssembled {
    currentIndex: number;
    mode: 'audio' | 'video';
    positionSec: number;
    videoIds: string[];
}

/**
 * The receiver-side reassembler: collects chunks per transferId inside a
 * bounded window. Complete (all chunks + the chunk-0 handoff header) →
 * the assembled payload; the controller discards stale transfers on a
 * timer it drives (the assembler itself stays clock-free for the tests).
 */
export class HandoffAssembler {
    /** Active transfer count (diagnostics/tests). */
    get size(): number {
        return this.transfers.size;
    }

    private readonly transfers = new Map<
        string,
        {
            atMs: number;
            chunks: Map<number, string[]>;
            header: null | { currentIndex: number; mode: 'audio' | 'video'; positionSec: number };
            totalChunks: number;
        }
    >();

    /** Feed one chunk. Returns the assembled payload when complete. */
    push(
        message: Extract<ConnectMessage, { kind: 'handoff-chunk' }>,
        nowMs: number,
    ): HandoffAssembled | null {
        if (message.videoIds.length === 0 && message.chunkIndex !== 0) return null;
        let entry = this.transfers.get(message.transferId);
        if (entry === undefined) {
            entry = {
                atMs: nowMs,
                chunks: new Map(),
                header: null,
                totalChunks: message.totalChunks,
            };
            this.transfers.set(message.transferId, entry);
        }
        // A conflicting re-use of a transferId with a different shape is
        // ignored (the newest totalChunks wins only before any chunk lands).
        if (message.totalChunks !== entry.totalChunks) return null;
        if (message.handoff !== null) {
            entry.header = message.handoff;
        }
        entry.chunks.set(message.chunkIndex, message.videoIds);
        entry.atMs = nowMs;

        if (entry.header === null || entry.chunks.size < entry.totalChunks) return null;

        const videoIds: string[] = [];
        for (let i = 0; i < entry.totalChunks; i += 1) {
            const chunk = entry.chunks.get(i);
            if (chunk === undefined) return null; // a hole — wait for it
            videoIds.push(...chunk);
        }
        this.transfers.delete(message.transferId);
        return {
            currentIndex: Math.min(entry.header.currentIndex, Math.max(0, videoIds.length - 1)),
            mode: entry.header.mode,
            positionSec: entry.header.positionSec,
            videoIds,
        };
    }

    /** Drop transfers older than the window (the controller's timer calls). */
    sweep(nowMs: number, timeoutMs: number = HANDOFF_ASSEMBLY_TIMEOUT_MS): void {
        for (const [transferId, entry] of this.transfers) {
            if (nowMs - entry.atMs > timeoutMs) {
                this.transfers.delete(transferId);
            }
        }
    }
}

/** Split a queue's videoIds into wire-sized chunks. */
export function chunkHandoffQueue(
    videoIds: string[],
    chunkSize: number = HANDOFF_CHUNK_SIZE,
): string[][] {
    const capped = videoIds.slice(0, HANDOFF_MAX_TRACKS);
    if (capped.length === 0) return [];
    const out: string[][] = [];
    for (let i = 0; i < capped.length; i += chunkSize) {
        out.push(capped.slice(i, i + chunkSize));
    }
    return out;
}

// ---------------------------------------------------------------------------
// Device identity
// ---------------------------------------------------------------------------

export const CONNECT_DEVICE_STORAGE_KEY = 'streamify.connect.device';

/**
 * The human-readable device label off the user agent — coarse by design
 * (the label is PUBLISHED to the account's presence channel; it carries
 * no fingerprinting material beyond what a UA string already states).
 */
export function deviceLabelFromUserAgent(userAgent: string): string {
    const ua = userAgent.toLowerCase();
    let browser = 'Browser';
    if (ua.includes('edg/')) browser = 'Edge';
    else if (ua.includes('opr/') || ua.includes('opera')) browser = 'Opera';
    else if (ua.includes('chrome/') && !ua.includes('chromium')) browser = 'Chrome';
    else if (ua.includes('chromium')) browser = 'Chromium';
    else if (ua.includes('firefox/')) browser = 'Firefox';
    else if (ua.includes('safari/') && !ua.includes('chrome')) browser = 'Safari';

    let os = 'Web';
    if (ua.includes('windows')) os = 'Windows';
    else if (ua.includes('android')) os = 'Android';
    else if (ua.includes('iphone') || ua.includes('ipad') || ua.includes('ios')) {
        os = ua.includes('ipad') ? 'iPad' : 'iOS';
    } else if (ua.includes('mac os') || ua.includes('macintosh')) os = 'macOS';
    else if (ua.includes('linux')) os = 'Linux';

    return `${browser} on ${os}`;
}

/** Mint a stable per-browser-profile device id (the P3A jam pattern). */
export function mintConnectDeviceId(): string {
    return `dev-${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
}
