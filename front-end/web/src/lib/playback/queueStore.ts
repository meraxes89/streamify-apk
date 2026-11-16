/**
 * lib/playback/queueStore.ts — P2-B: the queue CONTROLLER.
 *
 * The single source of truth the kickoff asked for: queue order, current
 * index, repeat mode, and shuffle state live in ONE store (the machine
 * inside), and the players never hold canonical state — they read this
 * store's projections. The wasm engine below remains the TRANSPORT
 * (what is actually loaded/playing/paused, at what position — its
 * snapshot is the transport reality); this controller owns the SEMANTICS
 * and drives the engine through its existing interface:
 *
 *   resolveAndStage → stage_stream_url   (the getStreamUrl consumer —
 *                                         resolve.ts is never touched)
 *   playTrack(id)                        (latches the engine cursor)
 *   send({seek})                         (SEEK IS A COMMAND WE ISSUE;
 *                                         the mechanics are A's)
 *   clearQueue + enqueue                 (the mirror rebuild — silent
 *                                         at boundaries / idle, the P1
 *                                         discipline)
 *
 * THE ENGINE MIRROR, stated once: the engine queue is a staging area
 * that CONTAINS every machine row (enforced at append/insert/replace/
 * restore) in best-effort playOrder. The engine's own contiguous
 * advance is never used for queue movement — the machine computes every
 * target and play_track latches the cursor by id, wherever the row sits.
 * When the machine's order diverges (shuffle toggle, drag-reorder,
 * removal), `mirrorDirty` defers the rebuild to the next track boundary
 * — the one moment clearQueue is silent (the P1 proof).
 *
 * PAUSED ADVANCE: next/prev while paused moves the MACHINE cursor only;
 * the engine stays parked on the old row and catches up on resume
 * (resolveAndStage + playTrack for the machine's current). The UI reads
 * nowPlaying/displayStatus/displayPositionMs from THIS store, so it
 * never sees the engine's stale cursor — the handshake lives here.
 *
 * RESTORE: the persisted state rebuilds the machine + the engine
 * mirror, parked at the saved position (autoplay-honest: paused, no
 * gesture burned). The first play gesture resolves the current row
 * FRESH (never a persisted URL) and starts it AT the saved position
 * through the next→seek→play dance:
 *
 *   resolveAndStage(id)                  — fresh getStreamUrl
 *   rebuild mirror with the row FIRST    — cursor null + one 'next'
 *                                          selects it silently (Idle +
 *                                          staged URL emits no intent)
 *   send({seek, positionMs})             — latches position_target
 *                                          (the seek intent no-ops on
 *                                          the unloaded element — the
 *                                          latch is the point)
 *   send({play})                         — the play intent carries
 *                                          start = position_target, so
 *                                          the element loads AT the
 *                                          position (load() fast-
 *                                          forwards after metadata) —
 *                                          zero blip, no race
 *
 * THE SESSION RUNTIME (driven by the port's snapshot push): scrobble
 * accrual (the tracker), gapless scheduling (the T-15s preload + the
 * crossfade arm), and the persistence throttle. All failure paths are
 * honest and non-blocking — a scrobble that cannot send queues, a
 * preload that fails backs off, a save that cannot fit degrades.
 */

import { create } from 'zustand';
import type { EngineSnapshot, QueueRead, TrackRow, WasmEnginePort } from '../wasmEngine';
import {
  appendRow as mAppend,
  clearQueue as mClear,
  currentRow as mCurrentRow,
  currentTrack as mCurrentTrack,
  emptyQueueState,
  insertNext as mInsertNext,
  naturalEnd as mNaturalEnd,
  nextManual as mNext,
  prevManual as mPrev,
  removeRow as mRemove,
  reorderUpNext as mReorder,
  replaceQueue as mReplace,
  rowOfTrackId,
  setRepeat as mSetRepeat,
  startAt as mStartAt,
  toggleShuffle as mToggleShuffle,
  type MachineIntent,
  type QueueMachineState,
  type QueueRow as MachineQueueRow,
  type RepeatMode,
} from './queueMachine';
import { createPersistence, type PersistedQueue } from './persistence';
import { createOutboxStore, PlaySessionTracker, ScrobbleOutbox, type ScrobbleEvent } from './scrobbler';
import { GaplessScheduler, PRELOAD_LEAD_MS } from './gapless';
import { toast } from '../toastStore';
import { slog } from '../slog';
import { cloudTrackId, recordPlayEventStrict } from '../supabase';
import { recordTally } from '../playTally';
import { useSessionStore } from '../sessionStore';

/** How often the persisted state refreshes while things change. */
const PERSIST_INTERVAL_MS = 3_000;
/** The outbox drain heartbeat (online/visibility events drain too). */
const DRAIN_INTERVAL_MS = 60_000;

// ---------------------------------------------------------------------------
// Store shape
// ---------------------------------------------------------------------------

export interface QueueState {
  port: WasmEnginePort | null;
  /** The canonical machine (rendered projections derive from it). */
  machine: QueueMachineState;
  /** The QueueRead-shaped projection every queue surface renders. */
  queue: QueueRead;
  shuffle: boolean;
  repeat: RepeatMode;
  /** A restore landed this boot (the "picked up where you left off" hint). */
  restored: boolean;
  /**
   * A position parked for a row that has not started (restore, or a
   * seek issued while staged) — consumed when the row begins playing.
   */
  pendingPositionMs: number | null;
}

export interface QueueActions {
  bind(port: WasmEnginePort): void;
  unbind(): void;

  // -- transport-facing (the surfaces' verbs) ------------------------------
  playOrToggle(): void;
  next(): void;
  previous(): void;
  seekTo(positionMs: number): void;
  toggleShuffle(): void;
  cycleRepeat(): void;
  /** Crossfade config (seconds; 0 = off) — pushed by the settings surface. */
  setCrossfadeSec(sec: number): void;

  // -- queue-facing ----------------------------------------------------------
  jumpToRow(trackId: string): void;
  append(track: TrackRow, enqueuedBy?: string): boolean;
  insertNext(track: TrackRow, enqueuedBy?: string): boolean;
  removeById(trackId: string): void;
  reorderUpNext(fromPos: number, toPos: number): boolean;
  replaceAndPlay(rows: MachineQueueRow[], startTrackId: string, shuffle: boolean): Promise<void>;
  clear(): void;
}

// ---------------------------------------------------------------------------
// Derived selectors (the read model)
// ---------------------------------------------------------------------------

/** The now-playing row (the machine's cursor — never the engine's). */
export function nowPlaying(s: QueueState): TrackRow | null {
  return mCurrentTrack(s.machine);
}

/**
 * The transport status the UI should show: the engine's truth, except
 * an Idle engine with a staged machine row reads as Paused (restored /
 * paused-advanced / finished-parked — a row is there to play).
 */
export function displayStatus(engine: EngineSnapshot | null, s: QueueState): EngineSnapshot['status'] {
  const engineStatus = engine?.status ?? 'Idle';
  if (engineStatus === 'Idle' && mCurrentTrack(s.machine) !== null) return 'Paused';
  return engineStatus;
}

/** The playhead the UI should show (engine truth when it matches). */
export function displayPositionMs(engine: EngineSnapshot | null, s: QueueState): number {
  const engineTrack = engine?.currentTrack?.id ?? null;
  const machineTrack = mCurrentTrack(s.machine)?.id ?? null;
  if (engineTrack !== null && engineTrack === machineTrack) {
    return engine?.positionMs ?? 0;
  }
  return s.pendingPositionMs ?? 0;
}

/** The row naturalEnd would advance to (repeat/shuffle aware) — the
 * gapless scheduler's "next". */
function machineNextTrackId(s: QueueMachineState): string | null {
  if (s.finished) return null;
  if (s.cursor === null) return null;
  if (s.repeat === 'one') return mCurrentTrack(s)?.id ?? null;
  const target = s.cursor + 1;
  if (target < s.playOrder.length) {
    return s.rows[s.playOrder[target] as number]?.track.id ?? null;
  }
  if (s.repeat === 'all') return s.rows[s.playOrder[0] as number]?.track.id ?? null;
  return null;
}

function projectQueue(m: QueueMachineState): QueueRead {
  return {
    rows: m.playOrder.map((rowIdx, pos) => {
      const r = m.rows[rowIdx];
      return { index: pos, enqueuedBy: r?.enqueuedBy ?? 'web', track: r?.track } as QueueRead['rows'][number];
    }),
    cursor: m.cursor,
  };
}

// ---------------------------------------------------------------------------
// The store
// ---------------------------------------------------------------------------

export const useQueueStore = create<QueueState & QueueActions>((set, get) => {
  // -- private runtime (closures; the store holds the projections) ------
  const persistence = createPersistence();
  const tracker = new PlaySessionTracker();
  const outbox = new ScrobbleOutbox(createOutboxStore('streamify.scrobble.queue.v1'));
  const scheduler = new GaplessScheduler({ preloadLeadMs: PRELOAD_LEAD_MS, crossfadeMs: 0 });

  /** Guards async play chains against a newer command (the skip race). */
  let playEpoch = 0;
  /** The engine mirror no longer matches the machine order — rebuild at
   * the next boundary (the silent moment). */
  let mirrorDirty = false;
  let lastPersistAt = 0;
  let persistDirty = false;
  let lastTickAt = 0;
  let unsubscribeSnapshots: (() => void) | null = null;
  let drainTimer: ReturnType<typeof setInterval> | null = null;
  let crossfadeFiredFor: string | null = null;
  /** Snapshot cache for the controller's own decisions. */
  let lastSnapshot: EngineSnapshot | null = null;

  const currentEngineTrackId = (): string | null => lastSnapshot?.currentTrack?.id ?? null;
  const enginePlaying = (): boolean => lastSnapshot?.status === 'Playing';

  /** Re-render projections after any machine change. */
  const commit = (machine: QueueMachineState): void => {
    set({ machine, queue: projectQueue(machine), shuffle: machine.shuffle, repeat: machine.repeat });
    persistDirty = true;
  };

  // -- persistence ---------------------------------------------------------

  const persistNow = (): void => {
    const s = get();
    if (s.machine.rows.length === 0) {
      void persistence.clear();
      persistDirty = false;
      return;
    }
    const session = tracker.current;
    const machineTrack = mCurrentTrack(s.machine)?.id ?? null;
    const payload: PersistedQueue = {
      v: 2,
      savedAt: Date.now(),
      rows: s.machine.rows.map((r) => ({ track: r.track, enqueuedBy: r.enqueuedBy })),
      playOrder: s.machine.playOrder,
      cursor: s.machine.cursor,
      repeat: s.machine.repeat,
      shuffle: s.machine.shuffle,
      positionMs: displayPositionMs(lastSnapshot, s),
      session:
        session !== null && session.trackId === machineTrack
          ? {
              trackId: session.trackId,
              startedAt: session.startedAt,
              playedMs: session.playedMs,
              scrobbled: session.fired,
            }
          : null,
    };
    void persistence.save(payload);
    persistDirty = false;
    lastPersistAt = Date.now();
  };

  const maybePersist = (): void => {
    if (!persistDirty) return;
    if (Date.now() - lastPersistAt < PERSIST_INTERVAL_MS) return;
    persistNow();
  };

  // -- the scrobble fire path (local tally always; cloud via the outbox) --

  const fireScrobble = (event: ScrobbleEvent): void => {
    const trackSig = cloudTrackId(event.title, event.artist);
    recordTally({
      trackSig,
      title: event.title,
      artist: event.artist,
      durationPlayedSec: event.playedMs / 1000,
    });
    slog.i(
      'scrobble',
      `${event.title} · ${event.artist} — played ${Math.round(event.playedMs / 1000)}s of ${Math.round(event.durationMs / 1000)}s (rung ${event.rung ?? 'n/a'})`,
    );
    outbox.push(event);
    drainOutbox();
  };

  const drainOutbox = (): void => {
    void outbox.drain(
      async (e) =>
        recordPlayEventStrict({
          trackSig: cloudTrackId(e.title, e.artist),
          title: e.title,
          artist: e.artist,
          durationPlayedSec: e.playedMs / 1000,
          completionRatio: e.durationMs > 0 ? Math.min(1, e.playedMs / e.durationMs) : 0,
        }),
      () => useSessionStore.getState().session !== null && navigator.onLine,
    );
  };

  // -- engine execution ------------------------------------------------------

  /**
   * Rebuild the engine mirror (clearQueue + enqueue in playOrder).
   * Callers own the silence proof: Idle, or a track boundary (the
   * element already ended — the P1 discipline), or an intentional
   * stop-and-switch (collection replace / the restore dance).
   */
  const rebuildMirror = (machine: QueueMachineState, rotateCurrentFirst = false): void => {
    const port = get().port;
    if (port === null) return;
    port.clearQueue();
    let order = machine.playOrder;
    if (rotateCurrentFirst && machine.cursor !== null && machine.cursor > 0) {
      order = [...machine.playOrder.slice(machine.cursor), ...machine.playOrder.slice(0, machine.cursor)];
    }
    for (const rowIdx of order) {
      const r = machine.rows[rowIdx];
      if (r !== undefined) port.enqueue(r.track, r.enqueuedBy);
    }
    mirrorDirty = false;
  };

  /**
   * Execute a machine intent against the engine. The transport state
   * decides HOW: a playing engine takes resolve+play (or the prepared
   * handoff); a paused/idle engine takes the machine-only path (cursor
   * moves, engine catches up on resume) — UNLESS the command is a
   * user-gesture play (row tap, collection play), which starts playback
   * from whatever state the engine is in.
   */
  const executeIntent = (
    intent: MachineIntent,
    machine: QueueMachineState,
    opts: { playNow?: boolean } = {},
  ): void => {
    const port = get().port;
    if (port === null) return;
    if (intent.type === 'stop') {
      // The engine's own default end path releases the pipeline — the
      // hook returns false so reportTrackEnded runs it.
      tracker.abandon();
      scheduler.reset();
      return;
    }
    if (intent.type === 'restart') {
      const row = mCurrentRow(machine);
      const id = row === null ? null : (machine.rows[row]?.track.id ?? null);
      if (id === null) return;
      tracker.start(id, Date.now()); // a repeat-one loop / prev-restart is a fresh play
      const epoch = ++playEpoch;
      // The row's staged URL is warm (it just played) — play directly; a
      // stale URL rotates through the sink's own lane.
      if (!port.playTrack(id)) {
        void port.resolveAndStage(id).then((stream) => {
          if (epoch === playEpoch && stream !== null) port.playTrack(id);
        });
      }
      return;
    }
    // intent 'play'
    const id = machine.rows[intent.row]?.track.id;
    if (id === undefined) return;
    if (enginePlaying() || opts.playNow === true) {
      tracker.start(id, Date.now());
      const epoch = ++playEpoch;
      const play = (): void => {
        if (epoch !== playEpoch) return; // a newer command superseded this chain
        if (mirrorDirty) rebuildMirror(machine);
        port.playTrack(id);
      };
      // The gapless handoff: a healthy prepared stream for THIS target
      // plays directly — the resolve wait at the boundary is the gap we
      // are killing. Anything else resolves fresh, epoch-guarded.
      const prepared = scheduler.currentPrepared;
      if (prepared !== null && !prepared.failed && prepared.trackId === id) {
        scheduler.reset();
        play();
        return;
      }
      void port.resolveAndStage(id).then((stream) => {
        if (stream !== null) play();
      });
      return;
    }
    // Paused / Idle / Buffering: machine-only advance.
    if (currentEngineTrackId() === id) {
      tracker.start(id, Date.now()); // the engine is already bringing THIS row up
    } else {
      tracker.abandon(); // a skip is not a scrobble
    }
  };

  // -- the session runtime (the snapshot push lane) -------------------------

  const handleSnapshot = (snap: EngineSnapshot): void => {
    lastSnapshot = snap;
    const s = get();
    const machine = s.machine;
    const now = Date.now();
    const dt = lastTickAt === 0 ? 0 : Math.max(0, now - lastTickAt);
    lastTickAt = now;

    // -- scrobble accrual (only while the transport really plays the
    //    session's track) ----------------------------------------------
    const engineId = snap.currentTrack?.id ?? null;
    if (snap.status === 'Playing' && engineId !== null && tracker.isFor(engineId)) {
      const row = machine.rows[rowOfTrackId(machine, engineId)];
      const event = tracker.accrueTrack(engineId, dt, now, {
        title: row?.track.title ?? snap.currentTrack?.title ?? '',
        artist: row?.track.artist ?? snap.currentTrack?.artist ?? '',
        album: row?.track.album ?? '',
        durationMs: row?.track.durationMs ?? snap.currentTrack?.durationMs ?? 0,
        rung: null,
      });
      if (event !== null) {
        // The resolved-stream metadata comes from the ENGINE, not
        // re-derived: the last resolve, when it belongs to this track.
        const last = get().port?.lastResolved;
        if (last !== null && last !== undefined && last.videoId === engineId) event.rung = last.rung;
        fireScrobble(event);
      }
    }

    // -- gapless scheduling ------------------------------------------------
    const machineId = mCurrentTrack(machine)?.id ?? null;
    // Only the machine's current row drives the schedule (the engine may
    // briefly lag a paused advance; its position is not the future).
    const scheduling = snap.status === 'Playing' && engineId !== null && engineId === machineId;
    const ops = scheduler.tick({
      now,
      playing: scheduling,
      currentTrackId: machineId,
      positionMs: snap.positionMs,
      durationMs: machine.rows[rowOfTrackId(machine, machineId ?? '')]?.track.durationMs ?? 0,
      nextTrackId: machineNextTrackId(machine),
    });
    if (ops.preload !== null) {
      const target = ops.preload;
      slog.d('gapless', `T-${Math.round(PRELOAD_LEAD_MS / 1000)}s — preloading ${target}`);
      void get()
        ?.port?.resolveAndStage(target)
        .then((stream) => {
          scheduler.reportResult(target, stream !== null);
        });
    }
    if (ops.invalidated !== null) {
      slog.d('gapless', `prepared ${ops.invalidated} is no longer next — dropped`);
    }
    if (ops.crossfadeArmed && ops.crossfadeTarget !== null) {
      const pairKey = `${machineId}→${ops.crossfadeTarget}`;
      if (crossfadeFiredFor !== pairKey) {
        crossfadeFiredFor = pairKey;
        // THE DUAL-ENGINE SEAM (Engineer A's contract, P2): when the
        // port grows crossfadeTo(trackId, crossfadeMs), the arm event
        // hands the fade to the dual engine here. Until then the flag is
        // inert and the boundary takes the staged-URL fallback.
        const port = get().port as (WasmEnginePort & {
          crossfadeTo?: (id: string, ms: number) => boolean;
        }) | null;
        if (port !== null && typeof port.crossfadeTo === 'function') {
          port.crossfadeTo(ops.crossfadeTarget, scheduler.currentConfig.crossfadeMs);
        }
      }
    } else {
      crossfadeFiredFor = null;
    }

    // -- parked position landing --------------------------------------------
    // A pending position resolves once its row is really rolling: within
    // 1.5 s of the target the dance already landed it (consume); far
    // away means the fallback path started at 0 — one seek carries it.
    const pending = s.pendingPositionMs;
    if (
      pending !== null &&
      snap.status === 'Playing' &&
      engineId !== null &&
      engineId === machineId &&
      snap.positionMs > 0
    ) {
      set({ pendingPositionMs: null });
      if (Math.abs(snap.positionMs - pending) > 1_500) {
        get().port?.send({ type: 'seek', positionMs: pending });
        slog.i('restore', `seeking to the parked position (${Math.round(pending / 1000)}s)`);
      }
    }

    maybePersist();
  };

  // -- restore ---------------------------------------------------------------

  const restore = (port: WasmEnginePort): void => {
    void persistence.load().then((loaded) => {
      if (get().port !== port) return; // unbound mid-load
      // A queue the user started during the load window WINS — the
      // restore must never overwrite live state.
      if (get().machine.rows.length > 0) return;
      if (loaded === null) return;
      const machine: QueueMachineState = {
        rows: loaded.rows.map((r) => ({ track: r.track, enqueuedBy: r.enqueuedBy })),
        playOrder: loaded.playOrder,
        cursor: loaded.cursor,
        repeat: loaded.repeat,
        shuffle: loaded.shuffle,
        finished: false,
      };
      // The engine mirror: every row staged, current FIRST (the resume
      // dance's 'next' selects row 0).
      rebuildMirror(machine, true);
      const rowIdx = loaded.cursor === null ? null : machine.playOrder[loaded.cursor] ?? null;
      const row = rowIdx === null ? null : machine.rows[rowIdx] ?? null;
      const positionMs =
        row !== null && row.track.durationMs > 0
          ? Math.min(loaded.positionMs, row.track.durationMs - 1_000)
          : loaded.positionMs;
      if (loaded.session !== null && row !== null && loaded.session.trackId === row.track.id) {
        tracker.continue(loaded.session);
      }
      commit(machine);
      set({ restored: true, pendingPositionMs: positionMs > 0 ? positionMs : null });
      toast(`Picked up where you left off — ${row?.track.title ?? 'your queue'}`);
      slog.i(
        'restore',
        `queue restored — ${machine.rows.length} rows, cursor ${loaded.cursor}, position ${Math.round(positionMs / 1000)}s`,
      );
    });
  };

  // -- lifecycle listeners -----------------------------------------------------

  const onOnline = (): void => drainOutbox();
  const onVisible = (): void => {
    if (document.visibilityState === 'visible') drainOutbox();
  };
  const onHide = (): void => {
    if (document.visibilityState === 'hidden') persistNow();
  };

  return {
    port: null,
    machine: emptyQueueState(),
    queue: { rows: [], cursor: null },
    shuffle: false,
    repeat: 'off',
    restored: false,
    pendingPositionMs: null,

    bind(port) {
      set({ port });
      lastSnapshot = port.currentSnapshot();
      // Crossfade config from the settings surface's persisted prefs
      // (crossfadeSec; the P2-B default is ~3 s — see SettingsScreen).
      try {
        const raw = localStorage.getItem('streamify.settings.v1');
        if (raw !== null) {
          const prefs = JSON.parse(raw) as { crossfadeSec?: number };
          if (typeof prefs.crossfadeSec === 'number') {
            scheduler.setConfig({
              preloadLeadMs: PRELOAD_LEAD_MS,
              crossfadeMs: Math.max(0, Math.round(prefs.crossfadeSec * 1000)),
            });
          }
        }
      } catch {
        /* prefs are best-effort — the scheduler default stands */
      }
      // The boundary interceptor: the machine owns every natural advance
      // (repeat/shuffle aware). 'stop' intents fall through to the
      // engine's default end path (reportTrackEnded) by returning false.
      port.onTrackEndedHook = () => {
        const machine = get().machine;
        if (machine.cursor === null) return false;
        const tr = mNaturalEnd(machine);
        commit(tr.state);
        if (tr.intent === null || tr.intent.type === 'stop') return false; // the engine's release path
        executeIntent(tr.intent, tr.state);
        return true;
      };
      unsubscribeSnapshots = port.subscribe(handleSnapshot);
      window.addEventListener('online', onOnline);
      window.addEventListener('pagehide', persistNow);
      document.addEventListener('visibilitychange', onVisible);
      document.addEventListener('visibilitychange', onHide);
      drainTimer = setInterval(drainOutbox, DRAIN_INTERVAL_MS);
      drainOutbox(); // yesterday's outbox drains on boot when possible
      restore(port);
    },

    unbind() {
      const { port } = get();
      if (port !== null) port.onTrackEndedHook = null;
      unsubscribeSnapshots?.();
      unsubscribeSnapshots = null;
      window.removeEventListener('online', onOnline);
      window.removeEventListener('pagehide', persistNow);
      document.removeEventListener('visibilitychange', onVisible);
      document.removeEventListener('visibilitychange', onHide);
      if (drainTimer !== null) clearInterval(drainTimer);
      drainTimer = null;
      persistNow();
      set({ port: null });
    },

    playOrToggle() {
      const port = get().port;
      if (port === null) return;
      port.unlockSink(); // the gesture blesses the element
      const machine = get().machine;
      const machineId = mCurrentTrack(machine)?.id ?? null;
      if (machineId === null) return;
      const engineStatus = lastSnapshot?.status ?? 'Idle';
      const engineId = currentEngineTrackId();
      if ((engineStatus === 'Playing' || engineStatus === 'Paused') && engineId === machineId) {
        port.send({ type: 'toggle' });
        return;
      }
      // The engine is elsewhere (Idle, or parked on a row the machine
      // has moved past): (re)start the machine's current row.
      const epoch = ++playEpoch;
      const parkedPosition = get().pendingPositionMs;
      const fromIdle = engineStatus === 'Idle';
      slog.d(
        'queue',
        `playOrToggle — engine ${engineStatus}/${engineId ?? 'none'} vs machine ${machineId}, parked ${parkedPosition ?? 'none'}`,
      );
      void port.resolveAndStage(machineId).then((stream) => {
        if (epoch !== playEpoch || stream === null) return;
        if (parkedPosition !== null && parkedPosition > 0 && fromIdle && engineId === null) {
          // THE RESTORE DANCE (the module doc): the boot-time mirror is
          // current-first, so one 'next' selects the row silently (cursor
          // null + Idle + staged URL = no intent); the seek latches
          // position_target; the play intent carries the start position.
          // Zero blip, no race — and NO rebuild here: a rebuild would
          // wipe the just-staged URL off the engine row.
          port.send({ type: 'next' });
          port.send({ type: 'seek', positionMs: parkedPosition });
          port.send({ type: 'play' });
          slog.i('restore', `resume dance at ${Math.round(parkedPosition / 1000)}s`);
          return;
        }
        tracker.start(machineId, Date.now()); // a deliberate (re)start of this row
        port.playTrack(machineId);
      });
    },

    next() {
      const tr = mNext(get().machine);
      if (!tr.changed) return;
      commit(tr.state);
      if (tr.intent !== null) executeIntent(tr.intent, tr.state);
    },

    previous() {
      const position = displayPositionMs(lastSnapshot, get());
      const tr = mPrev(get().machine, position);
      if (!tr.changed) return;
      commit(tr.state);
      if (tr.intent !== null) executeIntent(tr.intent, tr.state);
    },

    seekTo(positionMs) {
      const port = get().port;
      if (port === null) return;
      const machineId = mCurrentTrack(get().machine)?.id ?? null;
      const engineId = currentEngineTrackId();
      const target = Math.max(0, Math.round(positionMs));
      if (engineId !== null && engineId === machineId) {
        port.send({ type: 'seek', positionMs: target });
        return;
      }
      // Staged (paused-advanced / restored, not yet rolling): park the
      // position — it lands when the row starts.
      set({ pendingPositionMs: target });
    },

    toggleShuffle() {
      const tr = mToggleShuffle(get().machine);
      commit(tr.state);
      // Order-only: the engine mirror catches up at the next boundary.
      mirrorDirty = true;
      scheduler.reset(); // the next row may have changed — re-derive
      slog.d('queue', `shuffle ${tr.state.shuffle ? 'on' : 'off'}`);
    },

    cycleRepeat() {
      const machine = get().machine;
      const order: RepeatMode[] = ['off', 'all', 'one'];
      const next = order[(order.indexOf(machine.repeat) + 1) % 3] as RepeatMode;
      const tr = mSetRepeat(machine, next);
      commit(tr.state);
      slog.d('queue', `repeat ${next}`);
    },

    setCrossfadeSec(sec) {
      scheduler.setConfig({
        preloadLeadMs: PRELOAD_LEAD_MS,
        crossfadeMs: Math.max(0, Math.round(sec * 1000)),
      });
    },

    jumpToRow(trackId) {
      const port = get().port;
      if (port === null) return;
      const machine = get().machine;
      const row = rowOfTrackId(machine, trackId);
      if (row < 0) return;
      port.unlockSink();
      const tr = mStartAt(machine, row);
      if (!tr.changed) return;
      commit(tr.state);
      // A row tap is a gesture play: it starts playback from ANY
      // engine state (idle, paused-on-another-row, playing).
      if (tr.intent !== null) executeIntent(tr.intent, tr.state, { playNow: true });
    },

    append(track, enqueuedBy = 'web') {
      const port = get().port;
      const machine = get().machine;
      const tr = mAppend(machine, { track, enqueuedBy });
      if (!tr.changed) return false;
      commit(tr.state);
      port?.enqueue(track, enqueuedBy); // mirror append (tombstone-free)
      return true;
    },

    insertNext(track, enqueuedBy = 'web') {
      const port = get().port;
      const machine = get().machine;
      const tr = mInsertNext(machine, { track, enqueuedBy });
      if (!tr.changed) return false;
      commit(tr.state);
      // The engine's insert lands at ITS cursor+1 — matches the machine
      // while the engine row is the machine row (the common case).
      port?.insertAfterCursor(track, enqueuedBy);
      return true;
    },

    removeById(trackId) {
      const machine = get().machine;
      const tr = mRemove(machine, trackId);
      if (!tr.changed) return;
      commit(tr.state);
      // No engine remove verb: the tombstone row stays in the engine
      // queue (harmless — play is by id) and the mirror rebuilds at the
      // next boundary.
      mirrorDirty = true;
    },

    reorderUpNext(fromPos, toPos) {
      const machine = get().machine;
      const tr = mReorder(machine, fromPos, toPos);
      if (!tr.changed) return false;
      commit(tr.state);
      mirrorDirty = true;
      return true;
    },

    async replaceAndPlay(rows, startTrackId, shuffle) {
      const port = get().port;
      if (port === null) return;
      const machine = get().machine;
      const tr = mReplace(machine, rows, startTrackId, shuffle);
      if (!tr.changed) return;
      port.unlockSink();
      // Mirror rebuild from scratch: clear + enqueue in the new order.
      // clearQueue stops any playing pipeline — correct for a
      // collection replace (the user asked for a new thing to play).
      port.clearQueue();
      for (const rowIdx of tr.state.playOrder) {
        const r = tr.state.rows[rowIdx];
        if (r !== undefined) port.enqueue(r.track, r.enqueuedBy);
      }
      mirrorDirty = false;
      commit(tr.state);
      set({ pendingPositionMs: null });
      // A collection play is a gesture play — it starts from any state.
      if (tr.intent !== null) executeIntent(tr.intent, tr.state, { playNow: true });
    },

    clear() {
      const port = get().port;
      mClear(get().machine);
      port?.clearQueue();
      mirrorDirty = false;
      scheduler.reset();
      tracker.abandon();
      commit(emptyQueueState());
      set({ pendingPositionMs: null });
      void persistence.clear();
    },
  };
});

// ---------------------------------------------------------------------------
// The QueueRead slice helpers (the QueueList.kt derivations — same code,
// re-homed from playerStore; the projections are QueueRead-shaped so the
// surfaces' math is unchanged)
// ---------------------------------------------------------------------------

/** Rows before the cursor (play history), in play order. */
export function playedRows(queue: QueueRead): QueueRead['rows'] {
  const c = queue.cursor;
  if (c === null || c <= 0 || queue.rows.length === 0) return [];
  return queue.rows.slice(0, Math.min(c, queue.rows.length));
}

/** Rows strictly after the cursor (up next), in play order. */
export function upNextRows(queue: QueueRead): QueueRead['rows'] {
  const c = queue.cursor;
  if (c === null || c < 0) return queue.rows;
  if (c + 1 >= queue.rows.length) return [];
  return queue.rows.slice(c + 1);
}

/** The row at the cursor (now playing), when one is selected. */
export function currentRow(queue: QueueRead): QueueRead['rows'][number] | null {
  const c = queue.cursor;
  if (c === null || c < 0 || c >= queue.rows.length) return null;
  return queue.rows[c] ?? null;
}
