/// <reference types="vite/client" />
/**
 * audio/transport.ts — the main-thread edge of the sink link (W1.4).
 *
 * Owns the AudioContext + AudioWorkletNode and speaks the port
 * protocol defined in sink.worklet.ts:
 *
 *   • ZERO-COPY WRITES: every write's Float32Array is TRANSFERRED
 *     (postMessage transfer list) — the decoder produced a fresh
 *     buffer per frame batch, so ownership moves across the boundary
 *     with no copy. No SharedArrayBuffer (GitHub Pages: no COOP/COEP).
 *   • CREDIT BACKPRESSURE: the worklet reports consumed frames
 *     (epoch-tagged); the transport tracks
 *        budget = capacity − (sent − credited)
 *     and the spine's writer awaits `awaitCredit(n)` before pushing
 *     more. A flush (seek) resets the counters AND the epoch —
 *     in-flight credits from the dead epoch are ignored on arrival.
 *   • EPOCH FLUSH (MP-C10): `flush(offsetUs)` drops the ring on the
 *     audio thread IMMEDIATELY and rebases reported position.
 *   • STATS: the 96-byte SINK_STATS_WIRE image + positionUs arrive on
 *     the port; parsed here (statsFromWire) and handed to the store.
 */

// The worklet module URL: Vite's ?worker&url emits a BUNDLED ES-module
// chunk (statsWire import resolved) — audioWorklet.addModule requires
// a module; worker.format:'es' in vite.config keeps the chunk ESM.
import sinkWorkletUrl from './sink.worklet.ts?worker&url';
import { statsFromWire } from './statsWire';
import type { SinkStats } from './statsWire';

/** Parsed stats + position as the UI/store consumes them. */
export interface SinkStatsEvent {
  stats: SinkStats;
  positionUs: number;
}

interface WorkletOutMsg {
  t: 'ready' | 'stats' | 'credit';
  rateOut?: number;
  channels?: number;
  capacity?: number;
  wire?: Uint8Array;
  positionUs?: number;
  frames?: number;
  epoch?: number;
}

export interface SinkTransportOptions {
  /** Decoder output rate (ring input rate; SRC happens in the worklet). */
  rateIn: number;
  /** Decoder output channels (decoder upmixes mono → 2 already). */
  channels: number;
  onStats?: (ev: SinkStatsEvent) => void;
  /** Node output channel count (canonical stereo). */
  outputChannels?: number;
}

export class SinkTransport {
  private ctx: AudioContext | null = null;
  private node: AudioWorkletNode | null = null;
  private epochCounter = 0;
  private capacityFrames = 0;
  private sentFrames = 0;
  private creditedFrames = 0;
  private creditWaiters: Array<(frames: number) => void> = [];
  private closed = false;

  private constructor(
    private readonly opts: SinkTransportOptions,
    private readonly onStats: ((ev: SinkStatsEvent) => void) | null,
  ) {}

  /** Current epoch id (monotonic; flush bumps it). */
  get epoch(): number {
    return this.epochCounter;
  }
  get rateOut(): number {
    return this.ctx?.sampleRate ?? 48000;
  }
  get isClosed(): boolean {
    return this.closed;
  }
  /** Dev/diagnostic: the AudioContext state ('running' is healthy). */
  get contextState(): string {
    return this.ctx?.state ?? 'closed';
  }

  /**
   * Create the context, load the worklet module, connect the node and
   * resolve once the worklet reported `ready`.
   */
  static async open(opts: SinkTransportOptions): Promise<SinkTransport> {
    const transport = new SinkTransport(opts, opts.onStats ?? null);
    const ctx = new AudioContext({ latencyHint: 'playback' });
    if (ctx.state === 'suspended') await ctx.resume();
    await ctx.audioWorklet.addModule(sinkWorkletUrl);
    const node = new AudioWorkletNode(ctx, 'streamify-sink', {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [opts.outputChannels ?? 2],
    });
    node.connect(ctx.destination);
    transport.ctx = ctx;
    transport.node = node;

    const ready = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error('sink worklet ready timeout')),
        5000,
      );
      node.port.onmessage = (ev: MessageEvent<WorkletOutMsg>) => {
        const msg = ev.data;
        if (msg.t === 'ready') {
          transport.capacityFrames = msg.capacity ?? 0;
          clearTimeout(timer);
          // Rebind the handler for the steady-state protocol.
          node.port.onmessage = (ev2: MessageEvent<WorkletOutMsg>) =>
            transport.onWorkletMessage(ev2.data);
          resolve();
        }
      };
      node.port.postMessage({
        t: 'configure',
        channels: opts.channels,
        rateIn: opts.rateIn,
      });
      // A configure failure surfaces as the ready timeout.
      void reject;
    });
    await ready;
    return transport;
  }

  /** Steady-state port messages: stats + credits. */
  private onWorkletMessage(msg: WorkletOutMsg): void {
    if (msg.t === 'stats' && msg.wire !== undefined) {
      const stats = statsFromWire(msg.wire);
      if (stats !== null && this.onStats !== null) {
        this.onStats({ stats, positionUs: msg.positionUs ?? 0 });
      }
      return;
    }
    if (msg.t === 'credit') {
      if (msg.epoch === this.epochCounter) {
        this.creditedFrames += msg.frames ?? 0;
        // Release waiters that now fit.
        while (this.creditWaiters.length > 0 && this.availableFrames() > 0) {
          const w = this.creditWaiters.shift();
          w?.(this.availableFrames());
        }
      }
      // Old-epoch credits are void by design (see flush()).
    }
  }

  /** Frame budget the ring can still absorb right now. */
  availableFrames(): number {
    return Math.max(0, this.capacityFrames - (this.sentFrames - this.creditedFrames));
  }

  /**
   * Await at least `frames` of budget (or `timeoutMs`, then whatever
   * is available). Resolves immediately when the budget already fits.
   */
  async awaitCredit(frames: number, timeoutMs = 2000): Promise<number> {
    if (this.closed) return 0;
    if (this.availableFrames() >= frames) return this.availableFrames();
    const avail = new Promise<number>((resolve) => {
      this.creditWaiters.push(resolve);
    });
    const timeout = new Promise<number>((resolve) =>
      setTimeout(() => resolve(this.availableFrames()), timeoutMs),
    );
    return Promise.race([avail, timeout]);
  }

  /**
   * Zero-copy write: the pcm buffer is TRANSFERRED (the caller must
   * not touch it afterwards). Frames = pcm.length / channels.
   */
  write(pcm: Float32Array): void {
    if (this.closed || this.node === null) return;
    const frames = Math.floor(pcm.length / this.opts.channels);
    if (frames <= 0) return;
    const buffer = pcm.buffer;
    this.node.port.postMessage(
      { t: 'write', pcm, epoch: this.epochCounter },
      [buffer],
    );
    this.sentFrames += frames;
  }

  /**
   * MP-C10 epoch flush: drop the ring on the audio thread, bump the
   * epoch (voiding in-flight credits), rebase position to offsetUs.
   */
  flush(offsetUs: number): void {
    if (this.closed || this.node === null) return;
    this.epochCounter++;
    this.sentFrames = 0;
    this.creditedFrames = 0;
    this.node.port.postMessage({
      t: 'flush',
      epoch: this.epochCounter,
      offsetUs,
    });
  }

  /** Tear down (worklet stop + context close). Idempotent. */
  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    for (const w of this.creditWaiters.splice(0)) w(0);
    try {
      this.node?.port.postMessage({ t: 'stop' });
    } catch {
      /* port may be gone */
    }
    try {
      this.node?.disconnect();
    } catch {
      /* already disconnected */
    }
    await this.ctx?.close().catch(() => undefined);
    this.node = null;
    this.ctx = null;
  }
}
