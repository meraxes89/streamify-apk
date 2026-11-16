/**
 * streamify/jam-live/jam-session-controller.ts - the Jam session brain
 * (P4B): one controller instance owns the room lifecycle and glues
 *
 *   - the transport (Supabase realtime + RPC lane),
 *   - the queue CRDT document (convergent fold),
 *   - the metadata cache (out-of-band track payloads),
 *   - feishin's player store (through an injectable bridge),
 *   - the jam UI store (the dumb state holder).
 *
 * HOST / FOLLOWER SPLIT (the ADR contract):
 *
 *   HOST    - drives playback: player events broadcast as jam_sync deltas
 *             (track/play/pause/seek + a 5 s tick), the lease heartbeat
 *             runs every 15 s (HOST | DEMOTED), and presence-join diffs
 *             trigger a full queue snapshot for late joiners.
 *   FOLLOWER - applies jam_sync deltas: track changes reload the local
 *             queue, play/pause map straight through, and tick/seek
 *             observations run the threshold drift corrector (hard window
 *             2 s, soft window 750 ms x 3). Local control moves broadcast
 *             advisory `request` messages the host applies only when the
 *             room permissions allow it.
 *
 * Both roles share the queue mutation lane: local-first CRDT apply, then
 * the append-only journal via jam_mutation, with a small retry outbox (a
 * failed send replays on the next tick - at-least-once, journal-deduped).
 */

import type {
    JamMutationResult,
    JamPresenceEntry,
    JamSessionRow,
    JamTransportStatus,
} from './jam-live-transport';
import type { JamJournalRow, JamOp } from './jam-live-wire';

import { deviceNonceOf, generateCadIdU64 } from './jam-live-cad';
import { fracAfter, fracIndexForInsertAt } from './jam-live-frac';
import {
    computeDriftCorrection,
    decodeJamControlMessage,
    decodeJamMetaMessage,
    decodeJamSyncMessage,
    estimateHostPositionMs,
    generateJamRoomCode,
    JAM_CONTROL_EVENT,
    JAM_DRIFT_SOFT_MS,
    JAM_HOST_TICK_MS,
    JAM_META_EVENT,
    JAM_PRESENCE_INTERVAL_MS,
    JAM_SYNC_EVENT,
    type JamBroadcastMessage,
    type JamMetaMessage,
    type JamSnapshotMessage,
    type JamSyncKind,
    type JamSyncMessage,
    type JamTrackPayload,
    normalizeJamRoomCode,
} from './jam-live-protocol';
import {
    generateOpId,
    jamOpFromJournalRow,
    jamOpNew,
    OP_ADD,
    OP_REMOVE,
    OP_REORDER,
    OP_VOTE,
    POLICY_NONE,
} from './jam-live-wire';
import {
    applyJamQueueOp,
    applyJamQueueOps,
    canonicalJamQueueState,
    createJamQueueDoc,
    type JamQueueDoc,
    jamQueueOrderedLive,
    jamQueueRespacePlan,
    jamQueueUpvotes,
} from './jam-queue-crdt';

import { useStreamifyJamStoreBase } from '/@/renderer/store/streamify-jam.store';
import { useStreamifySettingsStoreBase } from '/@/renderer/store/streamify-settings.store';
import { logger } from '/@/renderer/utils/logger';

// ---------------------------------------------------------------------------
// Player bridge (injectable; the default binds feishin's player store)
// ---------------------------------------------------------------------------

export interface JamControllerDeps {
    bridge: JamPlayerBridge;
    deviceId: string;
    now?: () => number;
    transportFactory: JamTransportFactory;
    userId: null | string;
}

export interface JamPlayerBridge {
    /** Replace the local queue with one track and (optionally) seek. */
    applyTrack(track: JamTrackPayload, positionMs: number): void;
    getCurrentTrack(): JamTrackPayload | null;
    getPositionMs(): number;
    isPlaying(): boolean;
    next(): void;
    pause(): void;
    play(): void;
    seekTo(positionMs: number): void;
    subscribe(handler: (event: JamPlayerEvent) => void): () => void;
}

export type JamPlayerEvent =
    | { isPlaying: boolean; type: 'status' }
    | { positionMs: number; type: 'seek' }
    | { track: JamTrackPayload | null; type: 'track' };

export type JamTransportFactory = (
    code: string,
    events: {
        onBroadcast(event: string, raw: unknown): void;
        onOps(rows: JamJournalRow[]): void;
        onPresence(entries: JamPresenceEntry[]): void;
        onStatus(status: JamTransportStatus, detail?: string): void;
    },
) => JamTransportLike;

/** The structural surface the controller needs from a transport. */
export interface JamTransportLike {
    broadcastControl(message: JamBroadcastMessage): void;
    broadcastMeta(message: JamMetaMessage): void;
    broadcastSync(message: JamSyncMessage): void;
    connect(): Promise<void>;
    fetchJournal(): Promise<JamJournalRow[]>;
    fetchSessionRow(): Promise<JamSessionRow | null>;
    heartbeat(
        sessionId: string,
        positionMs: number,
        monoMs: number,
    ): Promise<'DEMOTED' | 'HOST' | null>;
    insertSessionRow(hostUserId: null | string): Promise<JamSessionRow>;
    leave(): Promise<void>;
    sendOp(op: JamOp): Promise<JamMutationResult>;
    touchPresence(sessionId: string, userId: null | string): Promise<void>;
}

export type StreamifyJamControllerError = {
    code: 'insert-failed' | 'invalid-code' | 'session-not-found' | 'subscribe-failed';
    message: string;
};

const ROOM_CODE_ATTEMPTS = 3;

export class StreamifyJamSessionController {
    private bridgeUnsub: (() => void) | null = null;

    private code: null | string = null;

    private readonly deps: JamControllerDeps;

    private readonly doc: JamQueueDoc = createJamQueueDoc();

    private isHost = false;

    private readonly knownDevices = new Set<string>();

    private lastHostEpoch = 0;

    private readonly metaCache = new Map<string, JamTrackPayload>();

    private readonly outbox: JamOp[] = [];

    /** Events received mid-bootstrap (the channel is live before the
     * journal fold finishes); flushed once the room goes live. */
    private pendingBroadcasts: Array<[string, unknown]> = [];

    private pendingOps: JamJournalRow[] = [];

    private presenceTimer: null | ReturnType<typeof setInterval> = null;

    private sessionRow: JamSessionRow | null = null;

    private softOffences = 0;

    private state: 'idle' | 'live' | 'starting' | 'stopping' = 'idle';

    private tickTimer: null | ReturnType<typeof setInterval> = null;

    private transport: JamTransportLike | null = null;

    private get now(): number {
        return this.deps.now?.() ?? Date.now();
    }

    private get store() {
        return useStreamifyJamStoreBase.getState();
    }

    constructor(deps: JamControllerDeps) {
        this.deps = deps;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Test seam: the room code of the active session. */
    activeCode(): null | string {
        return this.code;
    }

    addTrack(track: JamTrackPayload): void {
        if (this.state !== 'live') return;
        const cadId = generateCadIdU64(track.name, track.artistName, track.duration);
        this.cacheTrackMeta(track, cadId);
        this.transport?.broadcastMeta({ cadId: cadId.toString(10), track, v: 1 });
        const ordered = jamQueueOrderedLive(this.doc);
        const tail = ordered.length > 0 ? ordered[ordered.length - 1].frac : null;
        const frac = fracAfter(tail);
        this.mintAndSend(OP_ADD, cadId, frac.value, 0n);
        if (frac.needsRebalance && this.isHost) {
            this.respace();
        }
        this.publishQueue();
    }

    /** Test seam: the convergence fingerprint of the current document. */
    canonicalState(): string {
        return canonicalJamQueueState(this.doc);
    }

    /** Create a room (host). Retries on room-code collisions. */
    async createRoom(): Promise<null | StreamifyJamControllerError> {
        if (this.state !== 'idle') {
            return { code: 'subscribe-failed', message: 'session already active' };
        }
        this.state = 'starting';
        let lastError: null | string = null;
        for (let attempt = 0; attempt < ROOM_CODE_ATTEMPTS; attempt += 1) {
            const candidate = generateJamRoomCode();
            try {
                const transport = this.makeTransport(candidate);
                const row = await transport.insertSessionRow(this.deps.userId);
                await this.start(transport, candidate, row, true);
                return null;
            } catch (error) {
                lastError = error instanceof Error ? error.message : String(error);
            }
        }
        this.state = 'idle';
        this.store.setStatus('error', lastError ?? 'insert-failed');
        return { code: 'insert-failed', message: lastError ?? 'insert-failed' };
    }

    // -------------------------------------------------------------------------
    // Host wiring
    // -------------------------------------------------------------------------

    /** Join an existing room by code (follower, or host re-joining). */
    async joinRoom(input: string): Promise<null | StreamifyJamControllerError> {
        if (this.state !== 'idle') {
            return { code: 'subscribe-failed', message: 'session already active' };
        }
        const code = normalizeJamRoomCode(input);
        if (!code) {
            return { code: 'invalid-code', message: 'invalid room code' };
        }
        this.state = 'starting';
        try {
            const transport = this.makeTransport(code);
            const row = await transport.fetchSessionRow();
            if (!row) {
                this.state = 'idle';
                this.store.setStatus('error', 'session_not_found');
                return { code: 'session-not-found', message: 'session_not_found' };
            }
            const isHost = this.deps.userId !== null && row.hostUserId === this.deps.userId;
            await this.start(transport, code, row, isHost);
            return null;
        } catch (error) {
            this.state = 'idle';
            const message = error instanceof Error ? error.message : String(error);
            this.store.setStatus('error', message);
            return { code: 'subscribe-failed', message };
        }
    }

    /**
     * Leave the session. Local playback is never touched: leaving a Jam
     * room means "stop syncing", which is exactly the desync escape hatch.
     */
    async leave(): Promise<void> {
        if (this.state === 'idle' || this.state === 'stopping') return;
        this.state = 'stopping';
        this.stopTimers();
        this.bridgeUnsub?.();
        this.bridgeUnsub = null;
        const transport = this.transport;
        this.transport = null;
        this.sessionRow = null;
        this.code = null;
        this.isHost = false;
        this.outbox.length = 0;
        this.knownDevices.clear();
        this.metaCache.clear();
        this.pendingOps = [];
        this.pendingBroadcasts = [];
        this.doc.appliedOps.clear();
        this.doc.elements.clear();
        this.doc.needsRebalance = false;
        this.softOffences = 0;
        this.lastHostEpoch = 0;
        await transport?.leave().catch(() => undefined);
        this.state = 'idle';
        const wasSynced = useStreamifyJamStoreBase.getState().status === 'live';
        useStreamifyJamStoreBase.getState().reset();
        useStreamifyJamStoreBase.setState({ isDesynced: wasSynced });
    }

    // -------------------------------------------------------------------------
    // Transport event handlers
    // -------------------------------------------------------------------------

    moveTrack(addOpId: string, position: number): void {
        if (this.state !== 'live') return;
        const target = this.doc.elements.get(BigInt(addOpId));
        if (!target || target.removedAtOpId !== null) return;
        const others = jamQueueOrderedLive(this.doc)
            .filter((element) => element.addOpId !== target.addOpId)
            .map((element) => element.frac);
        const frac = fracIndexForInsertAt(position, others);
        this.mintAndSend(OP_REORDER, target.cadId, frac.value, target.addOpId);
        if (frac.needsRebalance && this.isHost) {
            this.respace();
        }
        this.publishQueue();
    }

    removeTrack(addOpId: string): void {
        if (this.state !== 'live') return;
        const target = this.doc.elements.get(BigInt(addOpId));
        if (!target || target.removedAtOpId !== null) return;
        this.mintAndSend(OP_REMOVE, target.cadId, target.frac, target.addOpId);
        this.publishQueue();
    }

    /** The follower's advisory control surface (seek / skip suggestions). */
    requestControl(request: 'seek' | 'skip', positionMs?: number): void {
        if (this.state !== 'live' || !this.transport || this.isHost) return;
        this.transport.broadcastControl({
            kind: 'request',
            positionMs: positionMs ?? this.deps.bridge.getPositionMs(),
            request,
            v: 1,
        });
    }

    upvoteTrack(addOpId: string): void {
        if (this.state !== 'live') return;
        const target = this.doc.elements.get(BigInt(addOpId));
        if (!target || target.removedAtOpId !== null) return;
        this.mintAndSend(OP_VOTE, target.cadId, target.frac, target.addOpId);
        this.publishQueue();
    }

    private attachHost(): void {
        this.bridgeUnsub = this.deps.bridge.subscribe((event) => {
            if (this.state !== 'live' || !this.isHost || !this.transport) return;
            switch (event.type) {
                case 'seek':
                    this.broadcastSync({ kind: 'seek', positionMs: event.positionMs });
                    break;
                case 'status':
                    this.broadcastSync({
                        kind: this.deps.bridge.isPlaying() ? 'play' : 'pause',
                        positionMs: this.deps.bridge.getPositionMs(),
                    });
                    break;
                case 'track':
                    if (event.track) {
                        this.cacheTrackMeta(event.track);
                        this.broadcastSync({
                            kind: 'track',
                            positionMs: this.deps.bridge.getPositionMs(),
                            track: event.track,
                        });
                    }
                    break;
            }
        });

        this.tickTimer = setInterval(() => {
            if (this.state !== 'live' || !this.isHost) return;
            this.flushOutbox();
            this.broadcastSync({ kind: 'tick', positionMs: this.deps.bridge.getPositionMs() });
        }, JAM_HOST_TICK_MS);
    }

    private broadcastSnapshot(): void {
        if (!this.transport) return;
        const ordered = jamQueueOrderedLive(this.doc);
        const snapshot: JamSnapshotMessage = {
            hostOnlySkipSeek: useStreamifySettingsStoreBase.getState().settings.jamHostOnlySkipSeek,
            kind: 'snapshot',
            ordered: ordered.map((element) => ({
                addOpId: element.addOpId.toString(10),
                cadId: element.cadId.toString(10),
                frac: element.frac,
                track: this.metaCache.get(element.cadId.toString(10)) ?? null,
            })),
            playback: {
                currentAddOpId: null,
                currentTrack: this.deps.bridge.getCurrentTrack(),
                isPlaying: this.deps.bridge.isPlaying(),
                positionMs: this.deps.bridge.getPositionMs(),
            },
            v: 1,
        };
        this.transport.broadcastControl(snapshot);
    }

    private broadcastSync(input: {
        kind: JamSyncKind;
        positionMs: number;
        track?: JamTrackPayload | null;
    }): void {
        if (!this.transport) return;
        const { jamHostOnlySkipSeek } = useStreamifySettingsStoreBase.getState().settings;
        this.transport.broadcastSync({
            emittedAt: this.now,
            hostEpoch: Math.max(this.lastHostEpoch, 1),
            hostOnlySkipSeek: jamHostOnlySkipSeek,
            hostUserId: this.deps.userId ?? '',
            kind: input.kind,
            positionMs: Math.max(0, Math.round(input.positionMs)),
            track: input.track ?? null,
            trackAddOpId: null,
            v: 1,
        });
    }

    private cacheTrackMeta(track: JamTrackPayload, cadId?: bigint): void {
        const cad = cadId ?? generateCadIdU64(track.name, track.artistName, track.duration);
        this.metaCache.set(cad.toString(10), track);
    }

    // -------------------------------------------------------------------------
    // Queue mutations (local-first + journal + outbox retry)
    // -------------------------------------------------------------------------

    private enqueueOp(op: JamOp): void {
        if (!this.transport) return;
        void this.transport
            .sendOp(op)
            .then((result) => {
                if (!result.ok) {
                    this.outbox.push(op);
                }
            })
            .catch(() => {
                this.outbox.push(op);
            });
    }

    private flushOutbox(): void {
        if (!this.transport || this.outbox.length === 0) return;
        const pending = [...this.outbox];
        this.outbox.length = 0;
        for (const op of pending) {
            this.enqueueOp(op);
        }
    }

    private handleBroadcast(event: string, raw: unknown): void {
        if (this.state === 'starting') {
            this.pendingBroadcasts.push([event, raw]);
            return;
        }
        if (this.state !== 'live' || !this.transport) return;
        if (event === JAM_SYNC_EVENT) {
            const message = decodeJamSyncMessage(raw);
            if (message) this.handleSync(message);
            return;
        }
        if (event === JAM_META_EVENT) {
            const message = decodeJamMetaMessage(raw);
            if (message) {
                this.metaCache.set(message.cadId, message.track);
                this.publishQueue();
            }
            return;
        }
        if (event === JAM_CONTROL_EVENT) {
            const message = decodeJamControlMessage(raw);
            if (!message) return;
            if (message.kind === 'request') {
                this.handleRequest(message);
            } else if (message.kind === 'snapshot') {
                this.handleSnapshot(message);
            }
        }
    }

    private handleOps(rows: JamJournalRow[]): void {
        if (this.state === 'starting') {
            this.pendingOps.push(...rows);
            return;
        }
        if (this.state !== 'live') return;
        for (const row of rows) {
            const op = jamOpFromJournalRow(row);
            if (op) applyJamQueueOp(this.doc, op);
        }
        this.publishQueue();
        if (this.doc.needsRebalance && this.isHost) {
            this.respace();
        }
    }

    private handlePresence(entries: JamPresenceEntry[]): void {
        const hostUserId = this.sessionRow?.hostUserId ?? null;
        this.store.setParticipants(
            entries.map((entry) => ({
                deviceId: entry.deviceId,
                isHost:
                    (entry.userId !== null && entry.userId === hostUserId) ||
                    (this.isHost && entry.deviceId === this.deps.deviceId),
                isSelf: entry.deviceId === this.deps.deviceId,
                lastSeenMs: entry.lastSeenMs,
                name: entry.name,
                userId: entry.userId,
            })),
        );

        // Late-joiner catch-up: the host answers any new device with a full
        // snapshot (idempotent to apply for everyone else).
        const hasNew = entries.some((entry) => !this.knownDevices.has(entry.deviceId));
        for (const entry of entries) {
            this.knownDevices.add(entry.deviceId);
        }
        if (hasNew && this.isHost && this.state === 'live' && this.transport) {
            this.broadcastSnapshot();
        }
    }

    /** Follower advisory requests (the host applies when allowed). */
    private handleRequest(message: { positionMs: number; request: 'seek' | 'skip' }): void {
        if (!this.isHost || this.state !== 'live') return;
        const { jamHostOnlySkipSeek } = useStreamifySettingsStoreBase.getState().settings;
        if (jamHostOnlySkipSeek) {
            logger.debug('Jam follower request ignored (host-only skip/seek)', {
                request: message.request,
            });
            return;
        }
        if (message.request === 'seek') {
            this.deps.bridge.seekTo(message.positionMs);
            this.broadcastSync({ kind: 'seek', positionMs: message.positionMs });
        } else {
            this.deps.bridge.next();
        }
    }

    private handleSnapshot(message: JamSnapshotMessage): void {
        for (const entry of message.ordered) {
            if (entry.track) {
                this.metaCache.set(entry.cadId, entry.track);
            }
        }
        this.publishQueue();
        this.store.setHostPermissions(message.hostOnlySkipSeek === true);
        if (!this.isHost && message.playback.isPlaying && message.playback.currentTrack) {
            if (!this.deps.bridge.isPlaying()) {
                this.deps.bridge.applyTrack(
                    message.playback.currentTrack,
                    message.playback.positionMs,
                );
            }
        }
    }

    private handleSync(message: JamSyncMessage): void {
        // Epoch fencing: stale-epoch deltas are ignored.
        if (message.hostEpoch < this.lastHostEpoch) return;
        this.lastHostEpoch = Math.max(this.lastHostEpoch, message.hostEpoch);

        if (this.isHost) return; // the host drives; it never follows.

        const mirror = (isPlaying: boolean, trackName: null | string) => {
            this.store.setHostPlayback({
                isPlaying,
                positionMs: message.positionMs,
                trackName,
                updatedAtMs: this.now,
            });
        };

        switch (message.kind) {
            case 'end':
                this.store.setStatus('ended');
                void this.teardownTransportOnly();
                break;
            case 'pause':
                this.deps.bridge.pause();
                mirror(false, null);
                break;
            case 'play':
                this.deps.bridge.play();
                mirror(true, null);
                break;
            case 'seek':
            case 'tick': {
                const estimated = estimateHostPositionMs(message, this.now);
                const local = this.deps.bridge.getPositionMs();
                const correction = computeDriftCorrection(local, estimated, this.softOffences);
                if (correction.action === 'seek') {
                    this.deps.bridge.seekTo(correction.seekToMs);
                    this.softOffences = 0;
                } else if (Math.abs(local - estimated) >= JAM_DRIFT_SOFT_MS) {
                    this.softOffences += 1;
                } else {
                    this.softOffences = 0;
                }
                mirror(this.deps.bridge.isPlaying(), null);
                break;
            }
            case 'track':
                if (message.track) {
                    this.cacheTrackMeta(message.track);
                    this.store.setHostPermissions(message.hostOnlySkipSeek === true);
                    this.deps.bridge.applyTrack(message.track, message.positionMs);
                    mirror(true, message.track.name);
                }
                break;
        }
    }

    private makeTransport(code: string): JamTransportLike {
        const events = {
            onBroadcast: (event: string, raw: unknown) => this.handleBroadcast(event, raw),
            onOps: (rows: JamJournalRow[]) => this.handleOps(rows),
            onPresence: (entries: JamPresenceEntry[]) => this.handlePresence(entries),
            onStatus: (status: JamTransportStatus, detail?: string) => {
                if (status === 'error') {
                    this.store.setStatus('error', detail ?? 'channel error');
                }
            },
        };
        return this.deps.transportFactory(code, events);
    }

    // -------------------------------------------------------------------------
    // Shared internals
    // -------------------------------------------------------------------------

    private mintAndSend(opType: number, cadId: bigint, frac: number, target: bigint): void {
        const op = jamOpNew(
            generateOpId(),
            deviceNonceOf(this.deps.deviceId),
            opType,
            POLICY_NONE,
            cadId,
            frac,
            target,
        );
        const result = applyJamQueueOp(this.doc, op);
        if (!result.applied) return;
        this.enqueueOp(op);
    }

    private publishQueue(): void {
        this.store.setQueue(
            jamQueueOrderedLive(this.doc).map((element) => ({
                addedBy: element.addedBy,
                addOpId: element.addOpId.toString(10),
                cadId: element.cadId.toString(10),
                track: this.metaCache.get(element.cadId.toString(10)) ?? null,
                upvotes: jamQueueUpvotes(element),
            })),
        );
    }

    /** Host compaction: re-space the live queue on the 0.5 + i grid. */
    private respace(): void {
        for (const step of jamQueueRespacePlan(this.doc)) {
            const target = this.doc.elements.get(step.addOpId);
            if (!target || target.removedAtOpId !== null) continue;
            this.mintAndSend(OP_REORDER, target.cadId, step.newFrac, target.addOpId);
        }
        this.doc.needsRebalance = false;
        this.publishQueue();
    }

    private async start(
        transport: JamTransportLike,
        code: string,
        row: JamSessionRow,
        isHost: boolean,
    ): Promise<void> {
        this.transport = transport;
        this.code = code;
        this.sessionRow = row;
        this.isHost = isHost;
        this.lastHostEpoch = row.hostEpoch;
        this.store.setSession({ createdAtMs: this.now, roomCode: code, sessionId: row.id }, isHost);
        this.store.setStatus('connecting');

        await transport.connect();

        // Bootstrap: fold the full journal before treating the room as live.
        const rows = await transport.fetchJournal();
        const ops = rows
            .map((journalRow) => jamOpFromJournalRow(journalRow))
            .filter((op): op is JamOp => op !== null);
        applyJamQueueOps(this.doc, ops);
        this.publishQueue();

        await transport.touchPresence(row.id, this.deps.userId);
        this.startPresenceTimer();

        if (isHost) {
            this.attachHost();
        }

        // The channel went live BEFORE the journal fold completed, so early
        // realtime traffic was buffered - flush it now that the document is
        // bootstrapped (appliedOps dedupes against the journal).
        const bufferedOps = this.pendingOps;
        const bufferedBroadcasts = this.pendingBroadcasts;
        this.pendingOps = [];
        this.pendingBroadcasts = [];
        this.state = 'live';
        this.store.setStatus('live');
        if (bufferedOps.length > 0) {
            this.handleOps(bufferedOps);
        }
        for (const [event, raw] of bufferedBroadcasts) {
            this.handleBroadcast(event, raw);
        }
    }

    private startPresenceTimer(): void {
        this.presenceTimer = setInterval(() => {
            if (this.state !== 'live' || !this.transport || !this.sessionRow) return;
            void this.transport
                .touchPresence(this.sessionRow.id, this.deps.userId)
                .catch(() => undefined);
            if (this.isHost) {
                void this.transport
                    .heartbeat(this.sessionRow.id, this.deps.bridge.getPositionMs(), monotonicMs())
                    .then((verdict) => {
                        if (verdict === 'DEMOTED') {
                            this.store.setHostDemoted(true);
                        }
                    })
                    .catch(() => undefined);
            }
        }, JAM_PRESENCE_INTERVAL_MS);
    }

    private stopTimers(): void {
        if (this.tickTimer) {
            clearInterval(this.tickTimer);
            this.tickTimer = null;
        }
        if (this.presenceTimer) {
            clearInterval(this.presenceTimer);
            this.presenceTimer = null;
        }
    }

    private async teardownTransportOnly(): Promise<void> {
        this.stopTimers();
        this.bridgeUnsub?.();
        this.bridgeUnsub = null;
        const transport = this.transport;
        this.transport = null;
        await transport?.leave().catch(() => undefined);
    }
}

/** Stable per-install device id (the P3A pattern). */
export function getStreamifyJamDeviceId(): string {
    try {
        const existing = window.localStorage.getItem('streamify.jam.device');
        if (existing) return existing;
        const minted = `dev-${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
        window.localStorage.setItem('streamify.jam.device', minted);
        return minted;
    } catch {
        return 'dev-ephemeral';
    }
}

function monotonicMs(): number {
    if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
        return performance.now();
    }
    return Date.now();
}
