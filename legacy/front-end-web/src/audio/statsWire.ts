/**
 * audio/statsWire.ts — the 96-byte SINK_STATS_WIRE, TypeScript mirror
 * of the frozen Rust layout (rust/crates/streamify-playback/src/sink.rs,
 * `SinkStats::to_wire`). Byte-for-byte alignment with the native image
 * is the contract: the worklet (W1.5) serializes into a shared
 * ArrayBuffer view and the main thread reads it with the same offsets,
 * so a layout drift between the two languages is a silent-corruption
 * bug. The magic + size fields exist precisely to catch that class of
 * drift at runtime.
 *
 * Wire layout v1 (little-endian, 96 bytes):
 *
 *   off  size  field
 *   ───  ────  ──────────────────────────────
 *    0    4    u32 layout_magic   = 0x51AB0001
 *    4    4    u32 layout_bytes   = 96
 *    8    4    u32 capacity_frames
 *   12    4    u32 sample_rate_in
 *   16    4    u32 sample_rate_out
 *   20    4    u32 channels_in
 *   24    4    u32 channels_out
 *   28    4    u32 current_fullness_frames
 *   32    8    u64 frames_written
 *   40    8    u64 frames_read
 *   48    8    u64 underrun_events
 *   56    8    u64 overrun_events
 *   64    8    u64 overrun_dropped_frames
 *   72    4    u32 high_watermark_frames
 *   76    4    u32 low_watermark_frames
 *   80    4    f32 fullness_percent
 *   84    4    f32 estimated_latency_ms
 *   88    4    u32 sequence
 *   92    4    u32 reserved (written 0)
 *
 * G1 gate metric: underrun_events / frames_read < 0.1% over a stream.
 */

/** Mirrors `SINK_STATS_WIRE_MAGIC` (sink.rs). */
export const SINK_STATS_WIRE_MAGIC = 0x51ab0001;
/** Mirrors `SINK_STATS_WIRE_BYTES` (sink.rs). */
export const SINK_STATS_WIRE_BYTES = 96;

/** The struct image of the wire — field names match the Rust 1:1. */
export interface SinkStats {
  layoutMagic: number;
  layoutBytes: number;
  capacityFrames: number;
  sampleRateIn: number;
  sampleRateOut: number;
  channelsIn: number;
  channelsOut: number;
  currentFullnessFrames: number;
  framesWritten: number;
  framesRead: number;
  underrunEvents: number;
  overrunEvents: number;
  overrunDroppedFrames: number;
  highWatermarkFrames: number;
  lowWatermarkFrames: number;
  fullnessPercent: number;
  estimatedLatencyMs: number;
  sequence: number;
}

/** Neutral zeroed image (magic + size pre-filled, watermarks at 0). */
export function zeroStats(): SinkStats {
  return {
    layoutMagic: SINK_STATS_WIRE_MAGIC,
    layoutBytes: SINK_STATS_WIRE_BYTES,
    capacityFrames: 0,
    sampleRateIn: 0,
    sampleRateOut: 0,
    channelsIn: 0,
    channelsOut: 0,
    currentFullnessFrames: 0,
    framesWritten: 0,
    framesRead: 0,
    underrunEvents: 0,
    overrunEvents: 0,
    overrunDroppedFrames: 0,
    highWatermarkFrames: 0,
    lowWatermarkFrames: 0,
    fullnessPercent: 0,
    estimatedLatencyMs: 0,
    sequence: 0,
  };
}

/**
 * Serialize `stats` at the frozen offsets into `dst` (byte 92 written
 * 0, reserved). Returns false when `dst` is shorter than the wire —
 * same contract as the Rust `to_wire`.
 */
export function statsToWire(stats: SinkStats, dst: Uint8Array): boolean {
  if (dst.length < SINK_STATS_WIRE_BYTES) return false;
  const v = new DataView(dst.buffer, dst.byteOffset, dst.byteLength);
  v.setUint32(0, stats.layoutMagic >>> 0, true);
  v.setUint32(4, stats.layoutBytes >>> 0, true);
  v.setUint32(8, stats.capacityFrames >>> 0, true);
  v.setUint32(12, stats.sampleRateIn >>> 0, true);
  v.setUint32(16, stats.sampleRateOut >>> 0, true);
  v.setUint32(20, stats.channelsIn >>> 0, true);
  v.setUint32(24, stats.channelsOut >>> 0, true);
  v.setUint32(28, stats.currentFullnessFrames >>> 0, true);
  v.setBigUint64(32, BigInt(stats.framesWritten) & 0xffffffffffffffffn, true);
  v.setBigUint64(40, BigInt(stats.framesRead) & 0xffffffffffffffffn, true);
  v.setBigUint64(48, BigInt(stats.underrunEvents) & 0xffffffffffffffffn, true);
  v.setBigUint64(56, BigInt(stats.overrunEvents) & 0xffffffffffffffffn, true);
  v.setBigUint64(
    64,
    BigInt(stats.overrunDroppedFrames) & 0xffffffffffffffffn,
    true,
  );
  v.setUint32(72, stats.highWatermarkFrames >>> 0, true);
  v.setUint32(76, stats.lowWatermarkFrames >>> 0, true);
  v.setFloat32(80, stats.fullnessPercent, true);
  v.setFloat32(84, stats.estimatedLatencyMs, true);
  v.setUint32(88, stats.sequence >>> 0, true);
  v.setUint32(92, 0, true);
  return true;
}

/** Field offsets for direct (worklet-side) writers. */
export const SINK_STATS_OFFSETS = {
  layoutMagic: 0,
  layoutBytes: 4,
  capacityFrames: 8,
  sampleRateIn: 12,
  sampleRateOut: 16,
  channelsIn: 20,
  channelsOut: 24,
  currentFullnessFrames: 28,
  framesWritten: 32,
  framesRead: 40,
  underrunEvents: 48,
  overrunEvents: 56,
  overrunDroppedFrames: 64,
  highWatermarkFrames: 72,
  lowWatermarkFrames: 76,
  fullnessPercent: 80,
  estimatedLatencyMs: 84,
  sequence: 88,
  reserved: 92,
} as const;

/**
 * Parse `src` into a fresh `SinkStats`. Returns null when the buffer is
 * short, the magic mismatches, or the size field disagrees — a wire the
 * native side never would have produced (layout drift → fail loud, not
 * corrupt).
 */
export function statsFromWire(src: Uint8Array): SinkStats | null {
  if (src.length < SINK_STATS_WIRE_BYTES) return null;
  const v = new DataView(src.buffer, src.byteOffset, src.byteLength);
  const magic = v.getUint32(0, true);
  if (magic !== SINK_STATS_WIRE_MAGIC) return null;
  const size = v.getUint32(4, true);
  if (size !== SINK_STATS_WIRE_BYTES) return null;
  return {
    layoutMagic: magic,
    layoutBytes: size,
    capacityFrames: v.getUint32(8, true),
    sampleRateIn: v.getUint32(12, true),
    sampleRateOut: v.getUint32(16, true),
    channelsIn: v.getUint32(20, true),
    channelsOut: v.getUint32(24, true),
    currentFullnessFrames: v.getUint32(28, true),
    framesWritten: Number(v.getBigUint64(32, true)),
    framesRead: Number(v.getBigUint64(40, true)),
    underrunEvents: Number(v.getBigUint64(48, true)),
    overrunEvents: Number(v.getBigUint64(56, true)),
    overrunDroppedFrames: Number(v.getBigUint64(64, true)),
    highWatermarkFrames: v.getUint32(72, true),
    lowWatermarkFrames: v.getUint32(76, true),
    fullnessPercent: v.getFloat32(80, true),
    estimatedLatencyMs: v.getFloat32(84, true),
    sequence: v.getUint32(88, true),
  };
}

/**
 * G1 underrun ratio over a stats snapshot: underrun events per frame
 * the consumer drained. Gate: < 0.1% (0.001). `framesRead` 0 → null
 * (nothing drained yet — not a failure, not a pass).
 */
export function underrunRatio(stats: SinkStats): number | null {
  if (stats.framesRead <= 0) return null;
  return stats.underrunEvents / stats.framesRead;
}
