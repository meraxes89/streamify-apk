/**
 * audio/types.ts — shared contracts of the P1 audio spine (WS-W1).
 *
 * Everything the demuxers, decoders, transport and sink agree on lives
 * here. Two container families are in scope for P1 (see the P1 task
 * book, "Media formats"):
 *
 *   • itag 140 — MPEG-4 / m4a container, AAC-LC (mp4a.40.2), 44.1 or
 *     48 kHz, ~129 kbps. PRIMARY for the WASM decoder ladder because
 *     symphonia 0.6 decodes AAC natively (the crate set has no Opus
 *     decoder — documented W1.2 deviation).
 *   • itag 251 — WebM / Matroska container, Opus, 48 kHz, ~160 kbps.
 *     PRIMARY for the WebCodecs path (Chrome decodes Opus natively).
 *
 * The demuxer contract is PULL-IN / PUSH-BYTES, deliberately
 * container-agnostic:
 *
 *      new Mp4Demuxer(onPacket) / new WebmDemuxer(onPacket)
 *        .push(bytes)        — feed arbitrary chunks (any size, any
 *                              alignment); the demuxer buffers and
 *                              parses incrementally, emitting complete
 *                              elementary packets as they become whole
 *        .format             — set after enough header bytes parsed;
 *                              null until then (poll or listen via
 *                              onFormat)
 *
 * No SharedArrayBuffer, no Worker coupling: the demuxer is a pure byte
 * consumer so the transport (W1.4) can run it wherever the caller
 * pleases and move packets as transferable ArrayBuffers (the GitHub
 * Pages constraint — no COOP/COEP headers → SAB is unavailable).
 */

/** Elementary audio codecs the P1 spine produces. */
export type AudioCodec = 'aac' | 'opus';

/** Format facts every demuxer publishes once its header is parsed. */
export interface AudioFormat {
  /** Codec of the elementary packets. */
  codec: AudioCodec;
  /**
   * Codec configuration blob the WebCodecs AudioDecoder wants as
   * `description`:
   *   • aac  — the AudioSpecificConfig (ASC) lifted from the mp4 esds
   *            box (typically 2 bytes: 5-bit profile, 4-bit index,
   *            4-bit channel config, plus extension bits).
   *   • opus — the OpusHead identification header (19+ bytes) lifted
   *            from the WebM CodecPrivate element.
   * WASM decoders receive the same blob (symphonia takes the raw ASC
   * for its aac reader).
   */
  description: Uint8Array;
  /** Source sample rate as muxed (Opus always 48000). */
  sampleRate: number;
  /** Channel count as muxed (1 or 2 for YouTube audio). */
  channels: number;
  /**
   * Opus only: container-declared codec delay in nanoseconds (WebM
   * CodecDelay element = preSkip × 1e9 / 48000). 0 when absent.
   */
  codecDelayNs?: number;
  /**
   * Opus only: pre-skip in 48 kHz samples from the OpusHead. 0 when
   * absent. WebCodecs handles the skip internally when the OpusHead is
   * passed as `description` — the field is informational for stats.
   */
  preSkip?: number;
}

/**
 * One elementary packet, ready for the decoder. `data` is always a
 * fresh copy owned by the packet (never a view into demuxer scratch),
 * so it can be transferred zero-copy across a MessagePort.
 */
export interface DemuxedPacket {
  data: Uint8Array;
  /** Presentation timestamp in microseconds — monotonic per stream. */
  timestampUs: number;
  /**
   * Best-effort packet duration in microseconds (Opus TOC / AAC frame
   * math). WebCodecs ignores it; the WASM path uses it for pacing.
   */
  durationUs?: number;
  /** True when the packet starts a cluster (AAC: a sample-table run). */
  keyframe?: boolean;
  /**
   * Opus/WebM only: DiscardPadding of the block in nanoseconds (the
   * encoder-side padding trimmed at the end of the packet). 0/absent
   * for SimpleBlocks without it.
   */
  discardPaddingNs?: number;
}

/** Constructor callback: packets stream out as bytes stream in. */
export type PacketSink = (packet: DemuxedPacket) => void;

/** Fired exactly once, when the header parsed and `format` is valid. */
export type FormatSink = (format: AudioFormat) => void;

/**
 * The incremental demuxer both containers implement.
 *
 * Implementations MUST be tolerant of chunk boundaries — `push` is fed
 * raw 256 KiB range chunks (see chunkFetcher.ts) with zero alignment
 * guarantees. Partial elements at the tail are retained until the next
 * push completes them. Pushing after `endOfStream` is a no-op.
 */
export interface AudioDemuxer {
  /** Format facts; null until the container header fully parsed. */
  readonly format: AudioFormat | null;
  /** Feed bytes; parses as far as the buffered data allows. */
  push(chunk: Uint8Array): void;
  /** No more bytes will arrive; flush any fully-formed tail state. */
  endOfStream(): void;
  /** True after endOfStream. */
  readonly ended: boolean;
}

/**
 * Decoded PCM frame batch — interleaved f32 at the CANONICAL geometry
 * (48 kHz, 2ch; mono sources are upmixed by the decoder wrapper per
 * the project canonical-geometry rule). Interleaved (not planar) so a
 * single transferable ArrayBuffer carries it across the MessagePort.
 */
export interface DecodeFrame {
  /** Interleaved f32 samples (frames × channels floats). */
  samples: Float32Array;
  /** Presentation timestamp of the FIRST frame, microseconds. */
  timestampUs: number;
  /** Frame count = samples.length / channels. */
  frames: number;
}

/** The decoder contract both the WebCodecs and WASM backends satisfy. */
export interface AudioDecoderLike {
  /** Human-readable backend id ('webcodecs:opus', 'wasm:aac', …). */
  readonly backend: string;
  /** True once configure() succeeded. */
  readonly ready: boolean;
  /**
   * Configure for a format. Resolves when the backend accepts the
   * config (WebCodecs: immediate; WASM: module may lazy-load first).
   */
  configure(format: AudioFormat): Promise<void>;
  /** Decode one packet; resolves with the PCM it produced. */
  decode(packet: DemuxedPacket): Promise<DecodeFrame[]>;
  /** Drop queued work (seek epoch). Backends must tolerate this. */
  reset(): void;
  /** Release all resources. */
  close(): void;
}

/** Milliseconds in one microsecond-basis timestamp unit. */
export const US_PER_MS = 1000;
/** Opus is ALWAYS 48 kHz output, whatever the OpusHead input rate says. */
export const OPUS_OUTPUT_RATE = 48000;
/** Canonical spine geometry (project-wide, matches the Rust HAL). */
export const CANONICAL_RATE = 48000;
export const CANONICAL_CHANNELS = 2;
