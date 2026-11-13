/**
 * audio/elementPlayer.ts — W2.0: the DIRECT-EGRESS playback sink.
 *
 * THE EGRESS ARCHITECTURE (measured 2026-11-13, see
 * front-end/decisions/W2.0-engine-egress.md):
 *
 *   • googlevideo AUTHORIZES cross-IP, cross-UA media fetches — a URL
 *     resolved through the relay (CF egress) serves 206 to ANY client IP
 *     (302 ipbypass → edge → 206, `ftyp` magic verified).
 *   • googlevideo's ACAO is ORIGIN-RESTRICTED to YouTube's own origins —
 *     a foreign page (github.io) can NEVER read media bytes via fetch(),
 *     on any client family, with or without pot.
 *   • THE `<audio>` ELEMENT DOES NOT NEED CORS. Media-element loads are
 *     no-cors by construction: the browser streams the bytes natively
 *     (its own range/redirect/buffer management) and plays them — exactly
 *     how every embedded YouTube player consumes googlevideo.
 *
 * So the mobile production path is: `<audio src={googlevideo-url}>` —
 * media bytes egress from the USER'S IP (mobile 5G / residential —
 * trusted), NOT the relay's datacenter IP (banned after ~5 plays).
 * The relay keeps only the tiny legs: player POST (~1/play) + mint
 * (rare). The WASM byte pipeline (fetch→demux→decode→worklet) remains
 * as the opt-in "engine quality" path (desktop) where relay egress is
 * acceptable — see the egress-mode switch in the UI.
 *
 * WHAT THE ELEMENT GIVES UP, HONESTLY: the WASM DSP chain (LUFS
 * normalization, the worklet ring, gapless). What it GAINS: native
 * buffering/seeking, hardware AAC decode, Media Session lock-screen
 * integration, and an egress profile identical to YouTube's own player.
 * Cross-origin element media TAINTS the graph — no MediaElementSource
 * DSP is possible on the direct path (measured: P3 of the egress suite).
 */

/**
 * A tiny silent WAV (44-byte header + 0.05 s of 8-bit mono silence at
 * 8 kHz), built at runtime — the classic iOS/Android mobile unlock:
 * `play()` called ON A REAL (silent) SOURCE inside the user gesture
 * blesses the element for later programmatic plays. Without this, the
 * mobile chain tap → resolve (seconds on 5G) → play() loses the gesture
 * context and the browser silently rejects the load (the P2 "button says
 * playing, no sound" bug).
 */
function buildSilentWavUrl(): string {
  const sampleRate = 8000;
  const samples = 400; // 50 ms
  const bytes = new Uint8Array(44 + samples);
  const view = new DataView(bytes.buffer);
  const ascii = (offset: number, text: string) => {
    for (let i = 0; i < text.length; i += 1) view.setUint8(offset + i, text.charCodeAt(i));
  };
  ascii(0, 'RIFF');
  view.setUint32(4, 36 + samples, true);
  ascii(8, 'WAVE');
  ascii(12, 'fmt ');
  view.setUint32(16, 16, true); // PCM chunk size
  view.setUint16(20, 1, true); // PCM format
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate, true); // byte rate
  view.setUint16(32, 1, true); // block align
  view.setUint16(34, 8, true); // bits per sample
  ascii(36, 'data');
  view.setUint32(40, samples, true);
  // samples stay zero = silence
  return URL.createObjectURL(new Blob([bytes], { type: 'audio/wav' }));
}

/** Track metadata the element surface needs (Media Session et al). */
export interface ElementTrackMeta {
  videoId: string;
  title: string;
  artist: string;
  album?: string;
  artworkUrl?: string | null;
  durationMs?: number;
}

/** Load options: the W2.0 dual-lane egress. */
export interface ElementLoadOptions {
  /**
   * The SW-bridge URL (same-origin /swgv/<token>) — when provided, a
   * direct-load failure falls back to it (the bridge serves bounded
   * ranges through the relay; measured to work on every IP class).
   */
  bridgeUrl?: string;
}

/** Events the sink emits upward — the glue wires these into the engine. */
export interface ElementPlayerHandlers {
  /** Natural end-of-track (drives the engine's auto-advance). */
  onEnded(): void;
  /** Periodic telemetry (throttled to ~4 Hz + progress events). */
  onStats(stats: {
    bufferedMs: number;
    underruns: number;
    fillRatio: number;
    positionMs: number;
  }): void;
  /** Transport reality mirror (element events → engine sink-state). */
  onSinkState(state: 'Buffering' | 'Playing' | 'Paused'): void;
  /**
   * Rotation hook: the URL went stale (element error / prolonged stall).
   * Return a FRESH URL (re-resolved) to rotate in-place, or null to give
   * up. The player preserves the position across the swap.
   */
  onStaleUrl(videoId: string, positionMs: number): Promise<string | null>;
}

/**
 * The direct-egress sink. One `<audio>` element, no crossOrigin attribute
 * (THE point — no CORS), preload auto, native redirect following.
 */
export class ElementPlayer {
  private readonly el: HTMLAudioElement;
  private readonly handlers: ElementPlayerHandlers;
  private meta: ElementTrackMeta | null = null;
  private rotations = 0;
  private underruns = 0;
  private stalledTimer: ReturnType<typeof setTimeout> | null = null;
  private statsTimer: ReturnType<typeof setInterval> | null = null;
  private disposed = false;
  /** The pending bridge fallback for the current load (direct failed). */
  private pendingBridge: { url: string; startMs: number } | null = null;
  /** Session-level lane preference: once the direct lane is refused on a
   * network (open-range 403s — datacenter egress), try the bridge first
   * for subsequent loads. A working direct lane stays preferred (user IP,
   * zero relay bytes). */
  private preferBridge = false;
  /** The bridge URL for the CURRENT track (rotations must stay on the
   * working lane — a fresh direct URL on a bridge-needing network just
   * 403s again and burns the rotation budget). */
  private currentBridgeUrl: string | null = null;
  /** Whether the gesture-unlock ran through (mobile autoplay policy). */
  private unlocked = false;
  /** Object URL of the silent unlock source (revoked after use). */
  private unlockUrl: string | null = null;

  /** Max in-place URL rotations per load before surfacing the failure. */
  private static readonly MAX_ROTATIONS = 3;

  constructor(handlers: ElementPlayerHandlers) {
    this.handlers = handlers;
    this.el = document.createElement('audio');
    this.el.preload = 'auto';
    // NO crossOrigin — media-element no-cors playback is the entire point.
    this.el.setAttribute('playsinline', '');
    this.el.style.display = 'none';
    document.body.appendChild(this.el);

    this.el.addEventListener('playing', () => {
      // Pre-load element events (the unlock silence, stray loads on the
      // empty element) must NEVER reach the engine's sink lane: the engine
      // boots into Buffering (initialize: Idle→Buffering, the UDL contract)
      // and report_sink_state ACCEPTS transitions from Buffering — an
      // unlock-time 'playing' would flip the engine to Playing with a null
      // cursor (the P3a ghost: "playing" chip, nothing loaded, dead
      // transport).
      if (this.meta === null) return;
      this.rotations = 0;
      this.handlers.onSinkState('Playing');
    });
    this.el.addEventListener('pause', () => {
      // Paused by the user/commands — only a transport mirror when we were
      // actually mid-playback (a loaded track, not the unlock cleanup's
      // pause of the silent wav — pre-load events stay in the sink).
      if (this.meta !== null && this.el.currentTime > 0 && !this.el.ended) {
        this.handlers.onSinkState('Paused');
      }
    });
    this.el.addEventListener('waiting', () => {
      if (this.meta === null) return; // pre-load events stay in the sink
      // Rebuffer: the honest element-side underrun signal.
      this.underruns += 1;
      this.handlers.onSinkState('Buffering');
    });
    this.el.addEventListener('stalled', () => {
      // Network stall: give it a grace window, then rotate the URL.
      if (this.stalledTimer === null && this.meta !== null) {
        this.stalledTimer = setTimeout(() => {
          this.stalledTimer = null;
          void this.rotate();
        }, 8000);
      }
    });
    this.el.addEventListener('progress', () => this.emitStats());
    this.el.addEventListener('timeupdate', () => this.emitStats());
    this.el.addEventListener('ended', () => {
      // The unlock silence must NEVER advance the queue: its 'ended'
      // fired while a cursor was latched would report_track_ended →
      // stop_pipeline — the unlock sabotaging its own gesture (measured:
      // first real tap on a parked track → wav ends → pipeline stops →
      // the tap's own toggle no-ops from Idle).
      if (this.meta === null) return;
      this.handlers.onEnded();
    });
    this.el.addEventListener('error', () => {
      if (this.el.error !== null && this.meta !== null) {
        // The direct lane was refused (open-range 403 → media error): the
        // bridge fallback takes over BEFORE any URL rotation.
        if (this.pendingBridge !== null) {
          const bridge = this.pendingBridge;
          this.pendingBridge = null;
          this.preferBridge = true; // session-wide: this network needs the bridge
          void this.load(bridge.url, this.meta, bridge.startMs);
          return;
        }
        void this.rotate();
      }
    });

    // Telemetry heartbeat (the progress/timeupdate events above are
    // irregular during pure playback; 500 ms keeps stats honest). Lives
    // for the player's LIFETIME — emitStats no-ops between loads (no meta).
    this.statsTimer = setInterval(() => this.emitStats(), 500);
  }

  /**
   * Unlock the element inside a user gesture (mobile autoplay policy).
   *
   * MUST be called synchronously from a real gesture handler (before any
   * await). Plays 50 ms of silence on the element, which blesses it for
   * the later programmatic play() after the (slow, network-bound) resolve.
   * Best-effort and idempotent: a rejection simply retries on the next
   * gesture; a real load() racing it overrides the unlock source safely.
   */
  unlock(): void {
    if (this.disposed || this.unlocked) return;
    if (this.unlockUrl === null) this.unlockUrl = buildSilentWavUrl();
    const gen = ++this.unlockGeneration;
    this.el.src = this.unlockUrl;
    const p = this.el.play();
    if (p !== undefined) {
      p.then(() => {
        this.unlocked = true;
        // A real load() may have already swapped the source — only clean
        // up when the element is still on the unlock source.
        if (gen === this.unlockGeneration && this.meta === null) {
          this.el.pause();
          this.el.removeAttribute('src');
          this.el.load();
        }
      }).catch(() => {
        /* still locked — the next gesture retries */
      });
    }
  }

  private unlockGeneration = 0;

  /** The videoId currently loaded (null when stopped/idle). */
  get currentVideoId(): string | null {
    return this.meta?.videoId ?? null;
  }

  /** The element's current playhead (ms). */
  get currentPositionMs(): number {
    return this.el.currentTime * 1000;
  }

  /**
   * Whether resume() can honestly continue the current track: parked
   * mid-track with a live source (NOT ended, NOT at the tail, NOT the
   * pre-unlock silence). The engine's resume-vs-reload decision reads
   * this — a 'play' intent for the SAME parked track resumes in place;
   * anything else is a fresh load (the old code reloaded from 0:00).
   */
  get canResume(): boolean {
    if (this.meta === null || !this.el.paused) return false;
    return this.canResumeBody();
  }

  private canResumeBody(): boolean {
    if (this.el.src === '' || this.el.ended) return false;
    if (this.unlockUrl !== null && this.el.src === this.unlockUrl) return false;
    const t = this.el.currentTime;
    const d = this.el.duration;
    if (t <= 0 || !Number.isFinite(d) || d <= 0) return false;
    return t < d - 0.25; // at-tail: a wrap-around replay must restart
  }

  /**
   * Load a (resolved) media URL and start playback. DUAL-LANE (W2.0):
   * the direct googlevideo URL rides the user's IP (the default lane —
   * zero relay bytes); when the network refuses open-ended element ranges
   * (the measured datacenter behavior), the same-origin SW bridge URL
   * takes over mid-load, position-preserving.
   */
  async load(
    url: string,
    meta: ElementTrackMeta,
    startMs = 0,
    options: ElementLoadOptions = {},
  ): Promise<void> {
    if (this.disposed) return;
    this.unlockGeneration += 1; // any real load supersedes an unlock race
    // Rotation bookkeeping is PER TRACK: a fresh videoId resets the
    // counters, a rotation (same track, fresh URL) PRESERVES them —
    // otherwise the rotate→load→error loop never exhausts MAX_ROTATIONS
    // and spins forever (measured: 5 generations in 10 ms).
    if (this.meta === null || this.meta.videoId !== meta.videoId) {
      this.rotations = 0;
      this.underruns = 0;
      this.pendingBridge = null;
      this.currentBridgeUrl = null;
    }
    this.meta = meta;
    if (options.bridgeUrl !== undefined) this.currentBridgeUrl = options.bridgeUrl;
    const bridgeUrl = this.currentBridgeUrl ?? undefined;
    const useBridge = this.preferBridge && bridgeUrl !== undefined;
    this.el.src = useBridge ? (bridgeUrl as string) : url;
    if (!useBridge && bridgeUrl !== undefined) {
      // Arm the fallback: a direct-lane failure on THIS load switches to
      // the bridge (once; the lane preference then flips session-wide).
      this.pendingBridge = { url: bridgeUrl, startMs };
    } else {
      this.pendingBridge = null;
    }
    this.el.load();
    this.handlers.onSinkState('Buffering');
    if (startMs > 0) {
      await this.once('loadedmetadata', 10_000);
      if (this.disposed || this.el.src === '') return;
      this.el.currentTime = startMs / 1000;
    }
    await this.el.play().catch(() => {
      // Autoplay policy: the FIRST load must ride a user gesture; the
      // auto-advance chain does (each advance originates from an 'ended'
      // event the browser treats as gesture-continued in practice; where
      // it does not, the surface shows the paused state and the user's
      // next tap resumes — surfaced, never swallowed).
      this.handlers.onSinkState('Paused');
    });
  }

  /** Resume (Play command). */
  async resume(): Promise<void> {
    if (this.disposed) return;
    await this.el.play().catch(() => this.handlers.onSinkState('Paused'));
  }

  /** Park (Pause command) — buffered data retained by the element.
   * Reports Paused explicitly: the engine's command plane emits no pause
   * intent, so the park is the element-side half of the pause handshake
   * (the pre-play guard in the 'pause' listener suppresses the redundant
   * event report when currentTime is still 0). */
  park(): void {
    if (this.meta === null && this.el.src === '') return;
    this.el.pause();
    if (this.meta !== null && this.el.currentTime > 0 && !this.el.ended) {
      this.handlers.onSinkState('Paused');
    }
  }

  /** Seek within the current track. */
  seek(positionMs: number): void {
    if (Number.isFinite(this.el.duration)) {
      this.el.currentTime = Math.min(positionMs / 1000, Math.max(0, this.el.duration));
    }
  }

  /** Volume scalar [0,1]. */
  setVolume(volume: number): void {
    this.el.volume = Math.min(1, Math.max(0, volume));
  }

  /** Release the pipeline (Stop) — keeps the element for the next load. */
  stop(): void {
    this.el.pause();
    this.el.removeAttribute('src');
    this.el.load();
    this.meta = null;
  }

  /** Tear down entirely (page unload). */
  dispose(): void {
    this.disposed = true;
    this.stop();
    this.stopStatsTimer();
    if (this.stalledTimer !== null) clearTimeout(this.stalledTimer);
    if (this.unlockUrl !== null) {
      URL.revokeObjectURL(this.unlockUrl);
      this.unlockUrl = null;
    }
    this.el.remove();
  }

  /** Whether the element is currently mid-playback. */
  get isPlaying(): boolean {
    return !this.el.paused && !this.el.ended && this.el.currentTime > 0;
  }

  // -- internals -------------------------------------------------------------

  private async rotate(): Promise<void> {
    const meta = this.meta;
    if (meta === null || this.disposed) return;
    if (this.rotations >= ElementPlayer.MAX_ROTATIONS) {
      // Surface honestly: rotations exhausted — the track is NOT coming.
      // Paused (not Buffering) so the UI stops spinning and the user's
      // next gesture retries through the normal play path.
      this.handlers.onSinkState('Paused');
      return;
    }
    this.rotations += 1;
    const positionMs = this.el.currentTime * 1000;
    const fresh = await this.handlers.onStaleUrl(meta.videoId, positionMs);
    if (this.disposed || fresh === null || this.meta === null) return;
    // Position-preserving swap; the rotation STAYS ON THE WORKING LANE
    // (currentBridgeUrl survives — load() reuses it when preferBridge).
    await this.load(fresh, meta, positionMs);
  }

  private emitStats(): void {
    const meta = this.meta;
    if (meta === null || this.disposed) return;
    const duration = this.el.duration;
    const buffered = this.el.buffered;
    let bufferedMs = 0;
    let fillRatio = 0;
    if (Number.isFinite(duration) && duration > 0) {
      // The range containing the playhead is the honest "ahead" buffer.
      const t = this.el.currentTime;
      for (let i = 0; i < buffered.length; i += 1) {
        if (t >= buffered.start(i) && t < buffered.end(i)) {
          bufferedMs = (buffered.end(i) - t) * 1000;
          break;
        }
      }
      if (buffered.length > 0) {
        fillRatio = (buffered.end(buffered.length - 1) / duration) * 1;
      }
    }
    this.handlers.onStats({
      bufferedMs,
      underruns: this.underruns,
      fillRatio,
      positionMs: this.el.currentTime * 1000,
    });
  }

  private once(event: string, timeoutMs: number): Promise<void> {
    return new Promise((resolve) => {
      const onDone = () => done();
      const done = () => {
        clearTimeout(timer);
        this.el.removeEventListener(event, onDone);
        resolve();
      };
      const timer = setTimeout(done, timeoutMs);
      this.el.addEventListener(event, onDone);
    });
  }

  private stopStatsTimer(): void {
    if (this.statsTimer !== null) {
      clearInterval(this.statsTimer);
      this.statsTimer = null;
    }
  }
}
