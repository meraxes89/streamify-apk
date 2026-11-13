/**
 * audio/webcodecsDecoder.ts — the WebCodecs AudioDecoder backend (W1.1).
 *
 * Wraps the platform decoder into the AudioDecoderLike contract:
 *
 *   • configure(): aac → 'mp4a.40.2' + ASC description; opus → 'opus'
 *     + OpusHead description (Chrome reads pre-skip from it).
 *   • decode(): EncodedAudioChunk in → ordered Promise<DecodeFrame[]>
 *     out. Outputs are f32-PLANAR per the WebCodecs spec; this wrapper
 *     INTERLEAVES into f32 and upmixes mono → canonical stereo (the
 *     rate stays native — the sink worklet owns rate conversion, see
 *     sink.worklet.ts: sample_rate_in ≠ sample_rate_out is a designed
 *     wire field).
 *   • reset(): epoch flush hook — pending decode()s resolve [] (the
 *     transport drops epoch-stale frames by epoch id, not by error).
 *
 * ORDERING CONTRACT: the platform decoder emits outputs strictly in
 * input order. Per-chunk attribution can drift by at most the decoder
 * delay (AAC priming swallows the first chunk's output) — the spine
 * treats decode output as one continuous PCM stream and never slices
 * per-packet, so the drift is harmless by construction.
 */

import type {
  AudioDecoderLike,
  AudioFormat,
  DecodeFrame,
  DemuxedPacket,
} from './types';
import { CANONICAL_CHANNELS } from './types';

/** A decode() promise resolver. */
type PendingDecode = {
  resolve: (frames: DecodeFrame[]) => void;
  reject: (err: Error) => void;
};

interface EncodedAudioChunkCtor {
  new (init: {
    type: 'key' | 'delta';
    timestamp: number; // µs
    duration?: number; // µs
    data: BufferSource;
  }): { close(): void };
}

interface AudioDataLike {
  numberOfFrames: number;
  numberOfChannels: number;
  sampleRate: number;
  timestamp: number;
  format: string;
  close(): void;
  copyTo(dest: ArrayBufferView, opts?: { planeIndex?: number }): void;
}

interface PlatformAudioDecoder {
  decodeQueueSize: number;
  configure(config: Record<string, unknown>): void;
  decode(chunk: unknown): void;
  flush(): Promise<void>;
  reset(): void;
  close(): void;
}

interface AudioDecoderCtor {
  new (init: {
    output: (data: AudioDataLike) => void;
    error: (err: DOMException) => void;
  }): PlatformAudioDecoder;
  isConfigSupported(config: Record<string, unknown>): Promise<{
    supported: boolean;
  }>;
}

/** Typed accessor for the (lib-dom-less) WebCodecs globals. */
function webCodecsGlobal(): AudioDecoderCtor | null {
  const g = globalThis as unknown as { AudioDecoder?: AudioDecoderCtor };
  return g.AudioDecoder ?? null;
}

/** True when the platform exposes the WebCodecs AudioDecoder. */
export function webCodecsAvailable(): boolean {
  return webCodecsGlobal() !== null;
}

/** Probe a codec config without instantiating (factory ladder rung). */
export async function webCodecsSupports(
  format: AudioFormat,
): Promise<boolean> {
  const AD = webCodecsGlobal();
  if (AD === null) return false;
  try {
    const { supported } = await AD.isConfigSupported({
      codec: codecStringOf(format),
      sampleRate: format.sampleRate,
      numberOfChannels: format.channels,
      description: format.description,
    });
    return supported;
  } catch {
    return false;
  }
}

function codecStringOf(format: AudioFormat): string {
  switch (format.codec) {
    case 'aac':
      return 'mp4a.40.2';
    case 'opus':
      return 'opus';
  }
}

export class WebCodecsAudioDecoder implements AudioDecoderLike {
  /** Dev diagnosis: last output formats seen (Chrome build variance). */
  static lastFormats: string[] = [];
  readonly backend: string;
  private decoder: PlatformAudioDecoder | null = null;
  private pending: PendingDecode[] = [];
  private _ready = false;
  private failed: string | null = null;

  constructor(format: AudioFormat | null = null) {
    this.backend = `webcodecs:${format?.codec ?? '?'}`;
  }

  get ready(): boolean {
    return this._ready;
  }

  async configure(format: AudioFormat): Promise<void> {
    if (this.failed !== null) throw new Error(this.failed);
    const AD = webCodecsGlobal();
    if (AD === null) throw new Error('webcodecs: AudioDecoder absent');
    const decoder = new AD({
      output: (data: AudioDataLike) => this.onOutput(data),
      error: (err: DOMException) => {
        this.failed = err.message;
        // Fail everything queued — the transport surfaces this to the
        // spine, which trips to 'errored'.
        for (const p of this.pending.splice(0)) {
          p.reject(new Error(`webcodecs: ${err.message}`));
        }
      },
    });
    decoder.configure({
      codec: codecStringOf(format),
      sampleRate: format.sampleRate,
      numberOfChannels: format.channels,
      description: format.description,
    });
    this.decoder = decoder;
    this._ready = true;
  }

  decode(packet: DemuxedPacket): Promise<DecodeFrame[]> {
    if (this.decoder === null || !this._ready) {
      return Promise.reject(new Error('webcodecs: decode before configure'));
    }
    if (this.failed !== null) {
      return Promise.reject(new Error(this.failed));
    }
    return new Promise<DecodeFrame[]>((resolve, reject) => {
      const Chunk = (globalThis as unknown as {
        EncodedAudioChunk: EncodedAudioChunkCtor;
      }).EncodedAudioChunk;
      const chunk = new Chunk({
        type: packet.keyframe === false ? 'delta' : 'key',
        timestamp: packet.timestampUs,
        duration: packet.durationUs,
        data: packet.data,
      });
      this.pending.push({ resolve, reject });
      this.decoder?.decode(chunk);
    });
  }

  /** Epoch flush: platform reset + drain the queue with empty frames. */
  reset(): void {
    if (this.decoder === null) return;
    try {
      this.decoder.reset();
    } catch {
      /* already closed — nothing to flush */
    }
    for (const p of this.pending.splice(0)) p.resolve([]);
  }

  close(): void {
    for (const p of this.pending.splice(0)) p.resolve([]);
    try {
      this.decoder?.close();
    } catch {
      /* close is idempotent */
    }
    this.decoder = null;
    this._ready = false;
  }

  /**
   * AudioData → interleaved (upmixed) DecodeFrame.
   *
   * FORMAT REALITY (verified Chrome 157): AudioDecoder outputs
   * INTERLEAVED 'f32' (the WebCodecs spec changed; older builds spoke
   * 'f32-planar'). Both are handled — and the first output usually
   * arrives pre-skip-adjusted (n=648 for a 960-frame Opus packet with
   * preSkip=312), which is why the OpusHead must ride along as the
   * decoder `description`.
   */
  private onOutput(data: AudioDataLike): void {
    // A decoder-callback exception must NEVER deadlock the serial
    // chain (one lost frame beats a frozen pipeline) — resolve the
    // oldest pending with [] on any throw.
    const p = this.pending.shift();
    try {
      this.onOutputInner(data, p);
    } catch (err) {
      try {
        data.close();
      } catch {
        /* already closed */
      }
      p?.resolve([]);
      this.failed = null; // not fatal — record for diagnosis
      if (WebCodecsAudioDecoder.lastFormats.length < 5) {
        WebCodecsAudioDecoder.lastFormats.push(
          `ERR:${err instanceof Error ? err.message : String(err)}`,
        );
      }
    }
  }

  private onOutputInner(data: AudioDataLike, p: PendingDecode | undefined): void {
    if (WebCodecsAudioDecoder.lastFormats.length < 3) {
      WebCodecsAudioDecoder.lastFormats.push(
        `${data.format}?ch=${data.numberOfChannels}?sr=${data.sampleRate}?n=${data.numberOfFrames}`,
      );
    }
    const frames = data.numberOfFrames;
    const channels = data.numberOfChannels;
    const timestampUs = data.timestamp; // capture BEFORE close() clears

    if (data.format === 'f32') {
      // Interleaved: one copy, then mono→stereo upmix when needed.
      const interleaved = new Float32Array(frames * channels);
      data.copyTo(interleaved, { planeIndex: 0 }); // Chrome: options REQUIRED
      data.close();
      p?.resolve([
        {
          samples: this.toCanonical(interleaved, channels),
          timestampUs,
          frames,
        },
      ]);
      return;
    }
    if (data.format === 'f32-planar') {
      const planes: Float32Array[] = [];
      for (let c = 0; c < channels; c++) {
        const plane = new Float32Array(frames);
        data.copyTo(plane, { planeIndex: c });
        planes.push(plane);
      }
      data.close();
      const interleaved = new Float32Array(frames * channels);
      for (let c = 0; c < channels; c++) {
        const plane = planes[c];
        for (let i = 0; i < frames; i++) {
          interleaved[i * channels + c] = plane[i];
        }
      }
      p?.resolve([
        {
          samples: this.toCanonical(interleaved, channels),
          timestampUs,
          frames,
        },
      ]);
      return;
    }
    data.close();
    p?.resolve([]);
  }

  /** Mono → canonical stereo upmix (already-interleaved input). */
  private toCanonical(interleaved: Float32Array, channels: number): Float32Array {
    const frames = Math.floor(interleaved.length / channels);
    if (channels >= CANONICAL_CHANNELS || channels < 1) return interleaved;
    const out = new Float32Array(frames * CANONICAL_CHANNELS);
    for (let i = 0; i < frames; i++) {
      const v = interleaved[i];
      out[i * 2] = v;
      out[i * 2 + 1] = v;
    }
    return out;
  }
}
