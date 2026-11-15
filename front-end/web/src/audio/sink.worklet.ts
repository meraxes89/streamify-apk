/**
 * audio/sink.worklet.ts — THE SINK (W1.5). AudioWorkletProcessor on
 * the real-time audio thread; everything else in the spine exists to
 * keep this ring fed.
 *
 * THREADING MODEL — message-passed SPSC by construction:
 *   The ring lives ONLY on the audio thread. The main thread never
 *   touches it; writes arrive as port messages whose payloads are
 *   TRANSFERABLE Float32Arrays (zero-copy across the boundary, the
 *   W1.4 contract). No SharedArrayBuffer — GitHub Pages cannot send
 *   COOP/COEP, so SAB is off the table by hosting constraint.
 *
 * RING GEOMETRY: one flat Float32Array of interleaved samples;
 * head/tail cursors count SAMPLES (frame-aligned by construction —
 * writers and the resampler move whole frames); the mask wraps.
 *
 * MESSAGE PROTOCOL (main → worklet):
 *   {t:'configure', capacityFrames, channels, rateIn, prefillFrames}
 *   {t:'write', pcm: Float32Array (interleaved @rateIn), epoch}
 *   {t:'flush', epoch}          — MP-C10 epoch flush: drop the ring
 *                                 IMMEDIATELY (seek); stale never plays.
 *   {t:'stop'}
 * (worklet → main):
 *   {t:'ready', rateOut, channels, capacity}
 *   {t:'stats', wire: Uint8Array(96), positionUs} — SINK_STATS_WIRE
 *         image (offsets imported from statsWire.ts — the 96-byte
 *         layout has ONE source of truth across TS and Rust).
 *   {t:'credit', frames}        — input frames consumed; the sender
 *                                 may push (capacity − outstanding).
 *
 * RESAMPLING: rateIn ≠ rateOut (AAC 44.1 k → device 48 k is the
 * common case) via linear interpolation with carry state.
 *
 * EPOCH RAMP: the first 64 output samples after an epoch boundary (or
 * an underrun recovery) get a raised-cosine fade-in (~1.3 ms @ 48 k) —
 * a seek lands mid-phase and the ramp kills the pop.
 *
 * UNDERRUN ACCOUNTING (G1: events / framesRead < 0.1%):
 *   • priming (startup + post-flush + post-underrun): ring below
 *     prefill → silence out, NO underrun counted — startup is not
 *     starvation;
 *   • running: ring exhausted mid-stream → ONE event per episode.
 */

import { SINK_STATS_OFFSETS as O, SINK_STATS_WIRE_BYTES } from './statsWire';

// -- AudioWorkletGlobalScope ambients (no DOM lib types here) --------
declare const sampleRate: number;
declare class AudioWorkletProcessor {
  readonly port: MessagePort;
  constructor(...args: unknown[]);
  process(
    inputs: Float32Array[][],
    outputs: Float32Array[][],
    parameters: Record<string, Float32Array>,
  ): boolean;
}
declare function registerProcessor(name: string, ctor: new () => object): void;

/** Default ring capacity (frames, power of two). */
const DEFAULT_CAPACITY = 16384; // ~341 ms @ 48 k stereo
/** Prefill before playback starts (frames). */
const DEFAULT_PREFILL = 2048; // ~43 ms @ 48 k
/** Raised-cosine ramp after epoch boundaries (output frames). */
const RAMP_FRAMES = 64;
/** Post stats every N process quanta (128-frame) ≈ 12 Hz @ 48 k. */
const STATS_EVERY = 32;
/** Post credits after consuming ≥ this many input frames. */
const CREDIT_BATCH = 1024;

interface WriteMsg {
  t: 'write';
  pcm: Float32Array;
  epoch: number;
}
interface ConfigureMsg {
  t: 'configure';
  capacityFrames?: number;
  channels: number;
  rateIn: number;
  prefillFrames?: number;
}
interface FlushMsg {
  t: 'flush';
  epoch: number;
  /** Stream offset the NEW epoch starts at (µs) — position rebasing. */
  offsetUs?: number;
}
interface StopMsg {
  t: 'stop';
}
type InMsg = WriteMsg | ConfigureMsg | FlushMsg | StopMsg;

class StreamifySinkProcessor extends AudioWorkletProcessor {
  // -- ring (audio-thread private; interleaved samples) ------------------
  private ring = new Float32Array(DEFAULT_CAPACITY * 2);
  private mask = DEFAULT_CAPACITY * 2 - 1;
  private head = 0; // write cursor (samples, unbounded)
  private tail = 0; // read cursor (samples, unbounded)
  private channels = 2;
  private rateIn = sampleRate;
  private capacityFrames = DEFAULT_CAPACITY;
  private prefillFrames = DEFAULT_PREFILL;
  private configured = false;
  private stopped = false;

  // -- lifecycle ----------------------------------------------------------
  private running = false; // false = priming
  private inUnderrun = false;

  // -- epoch ---------------------------------------------------------------
  private epoch = 0;
  private rampPos = RAMP_FRAMES; // start open; dropRing/configure reset it

  // -- resampler (linear, carry state) --------------------------------------
  private frac = 0; // input frames to advance per output frame accumulates here
  private step = 1; // rateIn / sampleRate
  private prev: Float32Array = new Float32Array(2);

  // -- stats -----------------------------------------------------------------
  private framesWritten = 0;
  private framesRead = 0;
  private underruns = 0;
  private overruns = 0;
  private overrunDropped = 0;
  private highWater = 0;
  private lowWater = 0;
  private sequence = 0;
  private quantum = 0;
  private creditPending = 0;
  /** Position rebasing: stream µs where the current epoch starts. */
  private epochBaseUs = 0;
  private framesReadAtEpoch = 0;

  constructor() {
    super();
    this.port.onmessage = (ev: MessageEvent<InMsg>) => this.onMessage(ev.data);
  }

  // -----------------------------------------------------------------------
  // message edge (the ONLY writer besides process())
  // -----------------------------------------------------------------------
  private onMessage(msg: InMsg): void {
    switch (msg.t) {
      case 'configure': {
        this.channels = msg.channels;
        this.rateIn = msg.rateIn;
        this.step = this.rateIn / sampleRate;
        this.capacityFrames =
          1 << Math.round(Math.log2(Math.max(1024, msg.capacityFrames ?? DEFAULT_CAPACITY)));
        this.ring = new Float32Array(this.capacityFrames * this.channels);
        this.mask = this.ring.length - 1; // frames pow2 × ch keeps pow2
        this.prefillFrames = Math.min(
          msg.prefillFrames ?? DEFAULT_PREFILL,
          this.capacityFrames >> 1,
        );
        this.prev = new Float32Array(this.channels);
        this.resetRingState();
        this.configured = true;
        this.port.postMessage({
          t: 'ready',
          rateOut: sampleRate,
          channels: this.channels,
          capacity: this.capacityFrames,
        });
        break;
      }
      case 'write': {
        if (this.stopped || !this.configured) return;
        if (msg.epoch !== this.epoch) {
          // A new epoch riding on a write: implicit flush (the sender
          // normally flushes explicitly first; offset base unchanged).
          this.resetRingState();
          this.epoch = msg.epoch;
        }
        const ch = this.channels;
        const frames = (msg.pcm.length / ch) | 0;
        if (frames <= 0) return;
        const freeFrames = this.freeFrames();
        if (frames > freeFrames) {
          // Overrun: credit backpressure makes this a should-never;
          // accept the OLDEST frames (continuity) and count the drop.
          const accept = Math.max(0, freeFrames);
          this.overruns++;
          this.overrunDropped += frames - accept;
          this.copyIn(msg.pcm, accept);
          this.framesWritten += accept;
        } else {
          this.copyIn(msg.pcm, frames);
          this.framesWritten += frames;
        }
        const f = this.frameFullness();
        if (f > this.highWater) this.highWater = f;
        break;
      }
      case 'flush': {
        if (msg.epoch >= this.epoch) {
          this.resetRingState();
          this.epochBaseUs = msg.offsetUs ?? 0;
          this.epoch = msg.epoch;
        }
        break;
      }
      case 'stop':
        this.stopped = true;
        this.resetRingState();
        break;
    }
  }

  /** Drop everything + back to priming + fade the next start in. */
  private resetRingState(): void {
    this.head = 0;
    this.tail = 0;
    this.frac = 0;
    this.running = false;
    this.inUnderrun = false;
    this.rampPos = 0;
    this.lowWater = this.capacityFrames;
    // Credits pending against the OLD epoch are void — the transport
    // resets its budget counters on flush (credits are epoch-tagged).
    this.creditPending = 0;
    this.framesReadAtEpoch = this.framesRead;
  }

  /** Interleaved samples buffered (may be mid-frame never: frame-aligned). */
  private sampleFullness(): number {
    return this.head - this.tail;
  }
  private frameFullness(): number {
    return Math.floor(this.sampleFullness() / this.channels);
  }
  private freeFrames(): number {
    return this.capacityFrames - this.frameFullness();
  }

  /** Copy `frames` frames (oldest first) from pcm into the ring. */
  private copyIn(pcm: Float32Array, frames: number): void {
    const n = frames * this.channels;
    for (let i = 0; i < n; i++) {
      this.ring[this.head & this.mask] = pcm[i];
      this.head++;
    }
  }

  // -----------------------------------------------------------------------
  // the real-time edge
  // -----------------------------------------------------------------------
  process(_inputs: Float32Array[][], outputs: Float32Array[][]): boolean {
    const out = outputs[0];
    const outFrames = out[0] !== undefined ? out[0].length : 0;
    if (this.stopped || !this.configured || outFrames === 0) {
      for (const plane of out ?? []) plane?.fill(0);
      return !this.stopped;
    }

    // -- priming gate ------------------------------------------------------
    if (!this.running) {
      if (this.frameFullness() >= this.prefillFrames) {
        this.running = true;
        this.inUnderrun = false;
        this.rampPos = 0; // fade in on every (re)start
      } else {
        for (const plane of out) plane.fill(0);
        this.quantum++;
        return true; // priming silence — not an underrun
      }
    }

    const ch = this.channels;
    for (let i = 0; i < outFrames; i++) {
      // Consume whole input frames the fractional cursor passed over.
      while (this.frac >= 1) {
        if (this.sampleFullness() < ch) break;
        for (let c = 0; c < ch; c++) this.prev[c] = this.ring[(this.tail + c) & this.mask];
        this.tail += ch;
        this.framesRead++;
        this.creditPending++;
        this.frac -= 1;
      }
      // A frame must be buffered to interpolate toward.
      if (this.sampleFullness() < ch) {
        if (!this.inUnderrun) {
          this.underruns++;
          this.inUnderrun = true;
          this.running = false; // back to priming until refilled
        }
        for (const plane of out) plane.fill(0, i);
        break;
      }
      const gain = this.rampGain(i);
      for (let c = 0; c < out.length; c++) {
        const ci = c < ch ? c : ch - 1;
        const next = this.ring[(this.tail + ci) & this.mask];
        const v = this.prev[ci] + this.frac * (next - this.prev[ci]);
        out[c][i] = v * gain;
      }
      this.frac += this.step;
    }
    // Advance the epoch ramp by the frames this quantum actually
    // produced (an early underrun break resumes where it left off).
    this.rampPos = Math.min(RAMP_FRAMES, this.rampPos + outFrames);

    // -- watermarks ----------------------------------------------------------
    const f = this.frameFullness();
    if (f < this.lowWater) this.lowWater = f;
    if (f > this.highWater) this.highWater = f;

    // -- credits (backpressure release; epoch-tagged so a flush's
    //    reset budget can't be corrupted by in-flight old credits) ----
    if (this.creditPending >= CREDIT_BATCH) {
      this.port.postMessage({
        t: 'credit',
        frames: this.creditPending,
        epoch: this.epoch,
      });
      this.creditPending = 0;
    }

    // -- stats wire (throttled + on underrun) ----------------------------------
    this.quantum++;
    if (this.quantum % STATS_EVERY === 0 || this.inUnderrun) {
      this.postStats();
    }
    return true;
  }

  /** Raised-cosine gain across the epoch fade-in ramp (read-only here;
   *  the position advances at the end of each quantum). */
  private rampGain(frameInQuantum: number): number {
    const pos = this.rampPos + frameInQuantum;
    if (pos >= RAMP_FRAMES) return 1;
    return 0.5 - 0.5 * Math.cos((Math.PI * pos) / RAMP_FRAMES);
  }

  /** Serialize + post the 96-byte wire (fresh copy — postMessage clones). */
  private postStats(): void {
    const wire = new Uint8Array(SINK_STATS_WIRE_BYTES);
    const v = new DataView(wire.buffer);
    const fullness = this.frameFullness();
    v.setUint32(O.layoutMagic, 0x51ab0001, true);
    v.setUint32(O.layoutBytes, SINK_STATS_WIRE_BYTES, true);
    v.setUint32(O.capacityFrames, this.capacityFrames, true);
    v.setUint32(O.sampleRateIn, this.rateIn, true);
    v.setUint32(O.sampleRateOut, sampleRate, true);
    v.setUint32(O.channelsIn, this.channels, true);
    v.setUint32(O.channelsOut, this.channels, true);
    v.setUint32(O.currentFullnessFrames, fullness, true);
    v.setBigUint64(O.framesWritten, BigInt(this.framesWritten), true);
    v.setBigUint64(O.framesRead, BigInt(this.framesRead), true);
    v.setBigUint64(O.underrunEvents, BigInt(this.underruns), true);
    v.setBigUint64(O.overrunEvents, BigInt(this.overruns), true);
    v.setBigUint64(O.overrunDroppedFrames, BigInt(this.overrunDropped), true);
    v.setUint32(O.highWatermarkFrames, this.highWater, true);
    v.setUint32(O.lowWatermarkFrames, this.lowWater, true);
    v.setFloat32(O.fullnessPercent, (100 * fullness) / this.capacityFrames, true);
    v.setFloat32(
      O.estimatedLatencyMs,
      (1000 * fullness) / this.rateIn,
      true,
    );
    v.setUint32(O.sequence, ++this.sequence, true);
    v.setUint32(O.reserved, 0, true);
    this.port.postMessage({
      t: 'stats',
      wire,
      positionUs:
        this.epochBaseUs +
        Math.round(((this.framesRead - this.framesReadAtEpoch) / this.rateIn) * 1e6),
    });
  }
}

registerProcessor('streamify-sink', StreamifySinkProcessor);
