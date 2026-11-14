/**
 * lib/wasmEngine.ts — W2.1: the WasmEnginePort (TS side).
 *
 * The Kotlin EnginePort mirror over the wasm control plane
 * (front-end/crates/streamify-engine-wasm — see its crate docs for the
 * full semantic map):
 *
 *   snapshots: Flow<PlaybackSnapshot>   → subscribe(cb) (rev-guarded push)
 *   send(command): PlayerCommandResult  → 'APPLIED' | 'DEBOUNCED' | 'FAILED'
 *   currentSnapshot(): PlaybackSnapshot?→ currentSnapshot()
 *   close()                            → close() (documented no-op)
 *
 * plus the typed data lane (the iOS EngineGateway shape): enqueue /
 * getQueue / clearQueue / playTrack / stageStreamUrl — and the WEB glue
 * the platform ports own on every platform: the sink driver.
 *
 * THE SINK DRIVER (this is where W2.0 and W2.1 meet): the engine emits
 * PlayIntents (play / resolve / seek / stop); this port consumes them and
 * drives the DIRECT-EGRESS ElementPlayer (default, mobile) or the P1
 * WASM spine (opt-in engine path, desktop). The resolver ladder
 * (VISIONOS → WEB_SAFARI → WEB+POT — the P3b 3-Tier ladder) runs on
 * `resolve` intents and stale-URL
 * rotations — through the RELAY (tiny JSON legs), while every media byte
 * rides the user's own IP via the element.
 *
 * Media Session (lock-screen / notification controls) is wired here: the
 * port owns the track metadata via snapshots.
 */

import { ElementPlayer } from '../audio/elementPlayer';
import type { ElementTrackMeta } from '../audio/elementPlayer';
import { resolveAudioStream, markRungFailed, type ResolvedStream } from '../audio/resolve';
import { ensureMediaBridge, type MediaBridgeHandle } from './mediaBridge';
import { nativeHlsPlayback, remuxHlsForElement } from '../audio/hls/hlsRemux';
import {
  startSabrAudio,
  type SabrSessionHandle,
} from '../audio/sabr/sabrStreamPlayer';

// The wasm-pack output (generated territory — see src/wasm-engine/).
type EngineModule = typeof import('../wasm-engine/streamify_engine_wasm.js');
type WasmEngine = EngineModule['WasmEngine'] extends new () => infer T ? T : never;

// ---------------------------------------------------------------------------
// Vocabulary (mirrors the Rust DTOs — camelCase JSON bridge)
// ---------------------------------------------------------------------------

export interface TrackRow {
  id: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  thumbnailUrl: string | null;
  isExplicit: boolean;
}

export interface BufferStats {
  bufferedDurationMs: number;
  underrunCount: number;
  fillRatio: number;
}

export interface DacClock {
  dacFramePosition: number;
  sampleRate: number;
  monotonicNs: number;
}

export interface PlayIntent {
  kind: 'play' | 'resolve' | 'seek' | 'stop';
  trackId?: string;
  streamUrl?: string;
  startPositionMs?: number;
}

export interface EngineSnapshot {
  rev: number;
  status: 'Idle' | 'Buffering' | 'Playing' | 'Paused' | 'Error';
  volume: number;
  buffer: BufferStats;
  clock: DacClock | null;
  positionMs: number;
  currentTrack: TrackRow | null;
  queueLength: number;
  cursor: number | null;
  positionTargetMs: number;
  playIntent: PlayIntent | null;
}

export type PlayerCommand =
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'toggle' }
  | { type: 'next' }
  | { type: 'previous' }
  | { type: 'seek'; positionMs: number }
  | { type: 'setVolume'; volume: number }
  | { type: 'stop' };

export type PlayerCommandResult = 'APPLIED' | 'DEBOUNCED' | 'FAILED';

export interface QueueRead {
  rows: Array<{ index: number; enqueuedBy: string; track: TrackRow }>;
  cursor: number | null;
}

/** Which data plane drives playback. */
export type EgressMode = 'element' | 'spine';

export interface WasmEnginePortOptions {
  relayBase: string;
  /** Default 'element' (direct egress, mobile-first). */
  egressMode?: EgressMode;
}

// ---------------------------------------------------------------------------
// The port
// ---------------------------------------------------------------------------

let engineModulePromise: Promise<EngineModule> | null = null;

function loadEngineModule(): Promise<EngineModule> {
  if (engineModulePromise === null) {
    engineModulePromise = import('../wasm-engine/streamify_engine_wasm.js').then(
      async (mod) => {
        await mod.default();
        return mod;
      },
    );
    engineModulePromise.catch(() => {
      engineModulePromise = null;
    });
  }
  return engineModulePromise;
}

export class WasmEnginePort {
  private readonly engine: WasmEngine;
  private readonly relayBase: string;
  private readonly subscribers = new Set<(snap: EngineSnapshot) => void>();
  private lastRev = 0;
  private sink: ElementPlayer | null = null;
  private spinePlay: ((videoId: string) => Promise<void>) | null = null;
  private spineStop: (() => void) | null = null;
  private egressMode: EgressMode;
  private lastResolve: ResolvedStream | null = null;
  private resolving = new Map<string, Promise<ResolvedStream | null>>();
  /** The W2.0 SW media bridge (null when unavailable — direct-only). */
  private mediaBridge: MediaBridgeHandle | null = null;
  /** Media-session token counter (stable per video: swgv/<videoId>). */
  private static mediaToken(videoId: string): string {
    return `t-${videoId}`;
  }

  private constructor(engine: WasmEngine, options: WasmEnginePortOptions) {
    this.engine = engine;
    this.relayBase = options.relayBase;
    this.egressMode = options.egressMode ?? 'element';

    // The push lane: one wasm listener, rev-guarded (the Android bridge's
    // drop rule — stale/out-of-order snapshots never reach the UI).
    //
    // RE-ENTRANCY (the wasm-bindgen borrow guard): the Rust side invokes
    // this callback from INSIDE its own &mut borrow (commit() during
    // play_track/record_*). Calling back into the engine synchronously
    // (take_play_intent_json) trips "recursive use of an object detected
    // which would lead to unsafe aliasing in rust". The UDL engine has a
    // dedicated dispatch THREAD for exactly this reason — the web lowering
    // defers listener work to a microtask, releasing the borrow first.
    // Microtasks run in order, so snapshot ordering is preserved.
    this.engine.add_event_listener((raw: string) => {
      queueMicrotask(() => this.onSnapshot(raw));
    });
  }

  /** Load the wasm module + construct the port. */
  static async create(options: WasmEnginePortOptions): Promise<WasmEnginePort> {
    const mod = await loadEngineModule();
    const engine = new mod.WasmEngine();
    engine.initialize();
    return new WasmEnginePort(engine, options);
  }

  // -- EnginePort surface ----------------------------------------------------

  /** Push-subscribe (the Kotlin `snapshots: Flow` lowering). */
  subscribe(cb: (snap: EngineSnapshot) => void): () => void {
    this.subscribers.add(cb);
    return () => this.subscribers.delete(cb);
  }

  /** Send a media command (debounced in the engine, APPLIED/DEBOUNCED). */
  send(command: PlayerCommand): PlayerCommandResult {
    if (command.type === 'setVolume') {
      // The slider path bypasses the debounce (DesktopEnginePort rule).
      this.engine.set_volume(command.volume);
      this.sink?.setVolume(command.volume);
      return 'APPLIED';
    }
    // Gesture-derived commands bless the element for later programmatic
    // plays (mobile autoplay policy — see ElementPlayer.unlock).
    if (command.type === 'play' || command.type === 'toggle') {
      this.sink?.unlock();
    }
    const applied = this.engine.on_media_command(JSON.stringify(command));
    if (command.type === 'pause') {
      // The Rust command plane sets Paused WITHOUT emitting an intent
      // (pause is a pure state transition). The element-side half of the
      // handshake happens here: park it, or the audio keeps playing while
      // every UI surface shows Paused (the P2 pause bug).
      this.sink?.park();
    }
    return applied ? 'APPLIED' : 'DEBOUNCED';
  }

  /** Bless the element inside a user gesture (mobile autoplay unlock). */
  unlockSink(): void {
    this.sink?.unlock();
  }

  /** One-shot cold-start read. */
  currentSnapshot(): EngineSnapshot {
    return this.parseSnapshot(this.engine.get_engine_snapshot_json());
  }

  /** Documented no-op (the UDL has no listener removal; engine outlives page). */
  close(): void {
    this.engine.close();
  }

  // -- typed data lane ---------------------------------------------------------

  enqueue(track: TrackRow, enqueuedBy = 'web'): number {
    return this.engine.enqueue_track(JSON.stringify(track), enqueuedBy);
  }

  getQueue(): QueueRead {
    return JSON.parse(this.engine.get_queue_json()) as QueueRead;
  }

  clearQueue(): void {
    this.engine.clear_queue();
  }

  /** Attach the resolved stream URL to a staged row. */
  stageStreamUrl(trackId: string, url: string): boolean {
    return this.engine.stage_stream_url(trackId, url);
  }

  /** Play a staged+resolved row (FAILED when the row lacks a URL). */
  playTrack(trackId: string): boolean {
    return this.engine.play_track(trackId);
  }

  reportTrackEnded(): void {
    this.engine.report_track_ended();
  }

  // -- sink binding --------------------------------------------------------------

  /** Bind the DIRECT-EGRESS element sink (W2.0 default). */
  attachElementSink(sink: ElementPlayer): void {
    this.sink = sink;
  }

  /** Bind the P1 spine as the (opt-in) engine-quality data plane. */
  attachSpineSink(play: (videoId: string) => Promise<void>, stop: () => void): void {
    this.spinePlay = play;
    this.spineStop = stop;
  }

  /** Bind the W2.0 SW media bridge (null = direct-only playback). */
  attachMediaBridge(bridge: MediaBridgeHandle | null): void {
    this.mediaBridge = bridge;
  }

  /** Swap a media-bridge session's upstream (URL rotation). */
  swapMediaSession(videoId: string, upstreamUrl: string): void {
    if (this.mediaBridge !== null) {
      void this.mediaBridge.swap(WasmEnginePort.mediaToken(videoId), upstreamUrl);
    }
  }

  /** Element-sink telemetry lane (clock + buffer stats in one shot). */
  reportElementStats(stats: {
    bufferedMs: number;
    underruns: number;
    fillRatio: number;
    positionMs: number;
  }): void {
    // Position → DAC clock lane (frames at the canonical 48 kHz).
    this.engine.record_dac_clock(
      stats.positionMs * 48,
      48_000,
      Math.round(performance.now() * 1e6),
    );
    this.engine.record_buffer_stats(stats.bufferedMs, stats.underruns, stats.fillRatio);
  }

  /** Element-sink transport mirror (the narrowing-only web extension). */
  reportSinkState(state: 'Buffering' | 'Playing' | 'Paused'): void {
    this.engine.report_sink_state(state);
  }

  setEgressMode(mode: EgressMode): void {
    this.egressMode = mode;
  }

  getEgressMode(): EgressMode {
    return this.egressMode;
  }

  /** The last successful resolve (diagnostics / the UI's rung chip). */
  get lastResolved(): ResolvedStream | null {
    return this.lastResolve;
  }

  /** The last resolve FAILURE verdict (surfaced to the UI's error lane). */
  get lastResolveError(): string | null {
    return this.resolveError;
  }

  /** Optional hook: resolve failures surfaced upward (the store's lane). */
  onResolveError: ((videoId: string, message: string) => void) | null = null;
  private resolveError: string | null = null;

  /**
   * The live SABR playback session (G4). Null when the current track is
   * not on the SABR lane; aborted + dropped on every track switch.
   */
  private sabrSession: SabrSessionHandle | null = null;

  // -- the resolver lane (async, relayed — the tiny legs) --------------------------

  /** Resolve + stage one track. Deduplicates in-flight resolves. */
  resolveAndStage(videoId: string): Promise<ResolvedStream | null> {
    const inFlight = this.resolving.get(videoId);
    if (inFlight !== undefined) return inFlight;
    const promise = resolveAudioStream(videoId, {
      relayBase: this.relayBase,
      autoMint: true,
      // The element path is AAC-first (Safari media elements cannot play
      // opus-in-webm); the spine path keeps the WebCodecs preference.
      elementCompat: this.egressMode === 'element',
    })
      .then((stream) => {
        this.lastResolve = stream;
        this.resolveError = null;
        // Element-compat: the element path prefers itag 140 (AAC) — see
        // pickFormat in resolve.ts (elementCompat flag). The URL we stage
        // is exactly what the resolver picked for this mode.
        this.engine.stage_stream_url(videoId, stream.url);
        return stream;
      })
      .catch((err: unknown) => {
        // The verdict (which rung refused, why) is preserved — the old
        // code swallowed it entirely, so a dead resolve left the row
        // button stuck on "playing…" with nothing honest anywhere.
        const message = err instanceof Error ? err.message : String(err);
        this.resolveError = message;
        this.onResolveError?.(videoId, message);
        return null;
      })
      .finally(() => {
        this.resolving.delete(videoId);
      });
    this.resolving.set(videoId, promise);
    return promise;
  }

  /** Convenience: enqueue + resolve + play (the "Play" button path).
   *
   * THROWS when the resolve fails — the caller surfaces the verdict on the
   * row (the engine never latches a cursor onto a URL-less row, so the
   * bar/queue stay honest: no more "playing…" buttons over dead tracks). */
  async playNow(track: TrackRow): Promise<void> {
    this.enqueue(track);
    const stream = await this.resolveAndStage(track.id);
    if (stream === null) {
      throw new Error(this.resolveError ?? `resolve failed for ${track.title}`);
    }
    this.engine.play_track(track.id);
  }

  // -- internals ------------------------------------------------------------------

  private onSnapshot(raw: string): void {
    let snap: EngineSnapshot;
    try {
      snap = this.parseSnapshot(raw);
    } catch (err) {
      console.error('[wasmEngine] snapshot parse failed', err);
      return;
    }
    if (snap.rev <= this.lastRev) return; // the Android drop-guard
    this.lastRev = snap.rev;

    // Consume + dispatch the latched intent (at-least-once; the ack clears).
    try {
      const intentRaw = this.engine.take_play_intent_json();
      if (intentRaw !== null && intentRaw !== undefined) {
        this.dispatchIntent(JSON.parse(intentRaw) as PlayIntent, snap);
      }
    } catch (err) {
      console.error('[wasmEngine] intent dispatch failed', err);
    }

    for (const cb of this.subscribers) {
      try {
        cb(snap);
      } catch {
        /* a throwing subscriber must not break the push lane */
      }
    }
    this.updateMediaSession(snap);
  }

  private async dispatchIntent(intent: PlayIntent, snap: EngineSnapshot): Promise<void> {
    // Both data planes get seek/stop; the load paths differ.
    if (intent.kind === 'seek') {
      if (this.egressMode === 'element') {
        this.sink?.seek(intent.startPositionMs ?? 0);
      }
      // The spine path: seek bumps the epoch via its own stop/play cycle —
      // the P1 spine has no in-place seek (documented); a seek in spine
      // mode re-plays from the target via the play intent below.
      return;
    }
    if (intent.kind === 'stop') {
      if (this.egressMode === 'element') {
        this.sink?.stop();
      } else {
        this.spineStop?.();
      }
      return;
    }
    if (intent.kind === 'resolve') {
      const videoId = intent.trackId;
      if (videoId === undefined) return;
      void this.resolveAndStage(videoId).then((stream) => {
        if (stream === null) {
          // Resolve failed: the verdict already surfaced through
          // onResolveError; the queue stays for a user retry. (No silent
          // skipping.)
          return;
        }
        this.engine.play_track(videoId);
      });
      return;
    }
    // 'play': the engine guarantees a staged URL on this intent.
    let url = intent.streamUrl;
    if (url === undefined || url === '') return;
    if (this.egressMode === 'element' && this.sink !== null) {
      const meta: ElementTrackMeta = {
        videoId: snap.currentTrack?.id ?? intent.trackId ?? '',
        title: snap.currentTrack?.title ?? 'Unknown',
        artist: snap.currentTrack?.artist ?? '',
        album: snap.currentTrack?.album || undefined,
        artworkUrl: snap.currentTrack?.thumbnailUrl ?? null,
        durationMs: snap.currentTrack?.durationMs,
      };
      // RESUME vs RELOAD (the P2 un-pause bug): a 'play' intent for the
      // SAME track that is parked mid-track resumes IN PLACE. The old
      // path always did a full sink.load() with the engine's stale
      // position_target_ms (usually 0) — un-pausing restarted the song
      // from 0:00. A startPositionMs within 1.5 s of the parked playhead
      // (a paused seek already moved the element) still resumes; anything
      // else (fresh track, ended, at-tail, stopped, real jump) takes the
      // full dual-lane load below.
      const startMs = intent.startPositionMs ?? 0;
      const parkedMs = this.sink.currentPositionMs;
      const nearParked = startMs === 0 || Math.abs(startMs - parkedMs) < 1500;
      if (this.sink.currentVideoId === meta.videoId && this.sink.canResume && nearParked) {
        void this.sink.resume();
        return;
      }
      // G3b: HLS rungs — Chromium-class engines cannot play m3u8 in a
      // media element. Remux the manifest into ONE element-playable file
      // (audio/hls/hlsRemux.ts: fMP4 byte-concat fast path, ffmpeg.wasm
      // remux lane for TS segments) BEFORE any load. A remux failure is
      // a definitive rung rejection: ledger it (the rotation must
      // ESCALATE the ladder, never re-pick the dead manifest) and leave
      // the track parked for an honest retry.
      const last = this.lastResolve;
      if (
        last !== null &&
        last.videoId === meta.videoId &&
        last.isHls &&
        !nativeHlsPlayback()
      ) {
        try {
          const remuxed = await remuxHlsForElement(url, {
            relayBase: this.relayBase,
          });
          url = remuxed.url; // blob: — element-playable, never goes stale
        } catch (err) {
          const message = err instanceof Error ? err.message : String(err);
          console.error('[wasmEngine] HLS remux failed', { rung: last.rung, message });
          markRungFailed(meta.videoId, last.rung);
          this.resolveError = `HLS remux failed (${last.rung}): ${message}`;
          this.onResolveError?.(meta.videoId, this.resolveError);
          this.sink.park(); // parked, not spinning — an honest retry surface
          return;
        }
      }

      // G4: the SABR lane — URL-less format descriptors (the modern
      // IOS-client reality) played through googlevideo's SabrStream into
      // an MSE-backed object URL. The staged url was the
      // serverAbrStreamingUrl (a placeholder the intent machinery could
      // carry); it is swapped for the object URL BEFORE any load. A
      // failure is a definitive rung rejection: ledger + park, exactly
      // like the HLS remux lane.
      if (
        last !== null &&
        last.videoId === meta.videoId &&
        last.isSabr &&
        last.sabr !== undefined
      ) {
        // One session at a time: a track switch aborts the previous one.
        this.sabrSession?.abort();
        this.sabrSession = null;
        try {
          const handle = await startSabrAudio(last.sabr, true, this.relayBase);
          this.sabrSession = handle;
          handle.onFail((message) => {
            console.error('[wasmEngine] SABR session failed', { rung: last.rung, message });
            markRungFailed(meta.videoId, last.rung);
            this.resolveError = `SABR stream failed (${last.rung}): ${message}`;
            this.onResolveError?.(meta.videoId, this.resolveError);
            // G4.2: a failed SABR session must not leave the element
            // spinning in Buffering on its partial buffer forever — park
            // it (the partial ~62 KB burst keeps the playhead; the UI shows
            // the error and the user's retry escalates the ladder past the
            // ledgered rung).
            this.sink?.park();
          });
          url = handle.objectUrl; // blob: — MSE-fed, bridge lane skipped
        } catch (err) {
          const message = err instanceof Error ? err.message : String(err);
          console.error('[wasmEngine] SABR start failed', { rung: last.rung, message });
          markRungFailed(meta.videoId, last.rung);
          this.resolveError = `SABR start failed (${last.rung}): ${message}`;
          this.onResolveError?.(meta.videoId, this.resolveError);
          this.sink.park();
          return;
        }
      } else if (this.sabrSession !== null && this.sabrSession !== undefined) {
        // A non-SABR load: tear the previous session down.
        this.sabrSession.abort();
        this.sabrSession = null;
      }

      // Dual-lane (W2.0): direct URL first, the SW bridge URL armed as the
      // automatic fallback (open-range-hostile networks flip to it). The
      // session registration is AWAITED — the element must not fetch the
      // /swgv/ URL before the worker holds the token (the 404 would burn
      // a rotation and strand the sink on the dead direct lane).
      let bridgeUrl: string | undefined;
      const videoId = meta.videoId;
      if (
        !url.startsWith('blob:') &&
        this.mediaBridge !== null &&
        this.mediaBridge.live &&
        videoId !== ''
      ) {
        try {
          await this.mediaBridge.register(WasmEnginePort.mediaToken(videoId), url);
        } catch {
          /* best-effort: the bridge lane is armed anyway; a 404 falls back
           * to rotation */
        }
        bridgeUrl = this.mediaBridge.urlFor(WasmEnginePort.mediaToken(videoId));
      }
      void this.sink.load(url, meta, intent.startPositionMs ?? 0, { bridgeUrl });
    } else if (this.egressMode === 'spine' && this.spinePlay !== null) {
      const videoId = snap.currentTrack?.id ?? intent.trackId;
      if (videoId !== undefined) void this.spinePlay(videoId);
    }
  }

  private updateMediaSession(snap: EngineSnapshot): void {
    const ms = navigator.mediaSession;
    if (ms === undefined) return;
    if (snap.currentTrack === null) return;
    if (this.mediaSessionTrackId === snap.currentTrack.id) {
      ms.playbackState =
        snap.status === 'Playing' ? 'playing' : snap.status === 'Paused' ? 'paused' : 'none';
      return;
    }
    this.mediaSessionTrackId = snap.currentTrack.id;
    try {
      ms.metadata = new MediaMetadata({
        title: snap.currentTrack.title,
        artist: snap.currentTrack.artist,
        album: snap.currentTrack.album || '',
        artwork:
          snap.currentTrack.thumbnailUrl !== null
            ? [{ src: snap.currentTrack.thumbnailUrl, sizes: '512x512' }]
            : [],
      });
    } catch {
      /* MediaMetadata is best-effort */
    }
    ms.playbackState = snap.status === 'Playing' ? 'playing' : 'paused';
  }

  private mediaSessionTrackId: string | null = null;

  private parseSnapshot(raw: string): EngineSnapshot {
    return JSON.parse(raw) as EngineSnapshot;
  }
}

// ---------------------------------------------------------------------------
// The stack builder — engine + element sink + media session, fully wired
// ---------------------------------------------------------------------------

export interface EngineStack {
  port: WasmEnginePort;
  sink: ElementPlayer;
}

/**
 * Build the whole W2.0/W2.1 stack: the wasm control plane + the direct
 * element sink, with every event lane cross-wired:
 *
 *   element events ──▶ engine telemetry (stats / clock / sink-state / ended)
 *   engine intents ──▶ element loads (play / seek / stop)
 *   media session  ──▶ engine commands (play/pause/next/prev/seek/stop)
 *   stale URLs     ──▶ resolver rotation (position-preserving)
 */
export async function createEngineStack(
  options: WasmEnginePortOptions,
): Promise<EngineStack> {
  const port = await WasmEnginePort.create(options);
  // The W2.0 SW media bridge (best-effort; null → direct-only playback).
  port.attachMediaBridge(await ensureMediaBridge(options.relayBase));

  const sink = new ElementPlayer({
    onEnded: () => port.reportTrackEnded(),
    onStats: (s) => port.reportElementStats(s),
    onSinkState: (state) => port.reportSinkState(state),
    onStaleUrl: async (videoId, positionMs) => {
      // P3b rotation loop guard: when the element rejects the LAST stream
      // and it was an HLS manifest (Chromium-class engines cannot play
      // m3u8 in a media element), ledger that rung — the re-resolve must
      // ESCALATE the ladder (Tier 2), never hand back the same manifest.
      const last = port.lastResolved;
      if (last !== null && last.videoId === videoId && last.isHls) {
        markRungFailed(videoId, last.rung);
      }
      const stream = await port.resolveAndStage(videoId);
      if (stream === null) return null;
      // G4.2: the rotation contract is an ELEMENT-LOADABLE URL — a fresh
      // SABR (serverAbrStreamingUrl placeholder) or HLS (m3u8 manifest)
      // stream is NOT one; loading either into the element would burn the
      // rotation budget on a guaranteed media error. Ledger the rung and
      // park honestly instead; the user's next tap goes through the full
      // play-intent path (which owns the SABR/HLS swap machinery).
      if (stream.isSabr || stream.isHls) {
        markRungFailed(videoId, stream.rung);
        return null;
      }
      // Rotation through the bridge: swap the upstream in-place — the
      // element's same-origin URL never changes.
      void port.swapMediaSession(videoId, stream.url);
      void positionMs; // the element player handles position preservation
      return stream.url;
    },
  });
  port.attachElementSink(sink);

  // Media Session action handlers → engine commands (once; the port owns
  // metadata updates via snapshots).
  const ms = navigator.mediaSession;
  if (ms !== undefined && typeof ms.setActionHandler === 'function') {
    const send = (cmd: PlayerCommand) => () => void port.send(cmd);
    for (const [action, cmd] of [
      ['play', { type: 'play' }],
      ['pause', { type: 'pause' }],
      ['previoustrack', { type: 'previous' }],
      ['nexttrack', { type: 'next' }],
      ['stop', { type: 'stop' }],
    ] as Array<[MediaSessionAction, PlayerCommand]>) {
      try {
        ms.setActionHandler(action, send(cmd));
      } catch {
        /* unsupported action on this browser */
      }
    }
    try {
      ms.setActionHandler('seekto', (details) => {
        if (typeof details.seekTime === 'number') {
          void port.send({ type: 'seek', positionMs: details.seekTime * 1000 });
        }
      });
    } catch {
      /* optional */
    }
  }

  return { port, sink };
}
