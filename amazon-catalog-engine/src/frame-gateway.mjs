/**
 * Frame-Aligned HTTP 206 Gateway & Time-Based Audio Pacer
 *
 * Implements docs/nano/comm21.md specifications:
 * 1. Frame-Aligned Seek: Maps target timestamp 't' to exact sample and frame
 *    sync boundary via the REAL frame index (FlacIntrospector.scanFrameSyncs
 *    now decodes true per-frame sample numbers from UTF-8-coded headers).
 *    Binary search O(log n) over sorted frameIndex.
 * 2. Time-Based Two-Phase Pacing:
 *    - Phase 1: Burst = MetadataBytes + (TrackBitrate * 1.5s) at line rate (< 250ms cold start).
 *    - Phase 2: Steady token bucket paced at 1.2x track bitrate.
 * 3. Never transcodes; bit-perfect delivery always.
 * 4. Decouples backpressure using a monotonic emit-time scheduler (no delay
 *    capping that would defeat pacing on large chunks).
 */

import { Readable, Transform } from 'stream';

export class FrameAlignedGateway {
  constructor({
    frameIndex = [],
    sampleRate = 96000,
    totalBytes = 0,
    metadataBytes = 42,
    bitrate = 4608000 // bits/sec
  } = {}) {
    // Frame index entries: { frameIndex, sampleNumber, byteOffset, dataOffset,
    // headerLength, blockSize } - sorted by sampleNumber (scan order is ascending).
    // Only CRC-8 validated frames with real sample numbers participate in seek
    // geometry; unvalidated sync markers (null sampleNumber) are excluded.
    this.frameIndex = (frameIndex || []).filter(f => f && f.sampleNumber != null);
    this.sampleRate = sampleRate;
    this.totalBytes = totalBytes;
    this.metadataBytes = metadataBytes;
    this.bitrate = bitrate;
  }

  /**
   * Resolves a seek timestamp to an exact FLAC frame boundary.
   * Prevents decoder sync errors by never serving mid-frame slices.
   *
   * @param {number} seekSeconds - Seek target in seconds
   * @param {number} [endSeconds] - Optional range end (for HTTP 206 windows)
   * @returns {Object} { startByte, endByte, frameOffset, isFrameAligned, ... }
   */
  resolveSeekRange(seekSeconds, endSeconds = null) {
    if (!seekSeconds || seekSeconds <= 0) {
      return {
        startByte: 0,
        endByte: this.totalBytes - 1,
        frameOffset: 0,
        isFrameAligned: true
      };
    }

    const targetSample = Math.floor(seekSeconds * this.sampleRate);

    // Binary search: last frame whose sampleNumber <= targetSample
    const frame = this._findFrameForSample(targetSample);

    const startByte = frame ? frame.byteOffset : this.metadataBytes;
    const result = {
      startByte,
      endByte: this.totalBytes - 1,
      targetSample,
      resolvedSample: frame ? frame.sampleNumber : 0,
      isFrameAligned: true
    };

    if (frame) {
      result.frameOffset = frame.frameIndex;
      result.frameByteOffset = frame.byteOffset;
      result.frameHeaderLength = frame.headerLength;
      // Sub-frame precision: samples the decoder must skip inside the frame
      result.skipSamples = Math.max(0, targetSample - frame.sampleNumber);
    }

    // Optional window end: resolve end boundary to a frame too
    if (endSeconds != null && endSeconds > seekSeconds) {
      const endSample = Math.floor(endSeconds * this.sampleRate);
      const endFrame = this._findFrameForSample(endSample);
      result.endByte = endFrame ? Math.max(startByte, endFrame.byteOffset - 1) : this.totalBytes - 1;
      if (result.endByte < startByte) result.endByte = this.totalBytes - 1;
    }

    return result;
  }

  /**
   * Binary search over the frame index (sorted ascending by sampleNumber).
   * Falls back to metadata boundary when the index is empty or before first frame.
   */
  _findFrameForSample(sample) {
    const idx = this.frameIndex;
    if (!idx || idx.length === 0) return null;

    // Before first frame: align to frame 0 (never mid-stream metadata-only slices)
    if (sample <= idx[0].sampleNumber) return idx[0];

    let lo = 0;
    let hi = idx.length - 1;
    let best = idx[0];
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (idx[mid].sampleNumber <= sample) {
        best = idx[mid];
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }

  /**
   * Computes exact time-based burst and pacing configuration.
   * Phase 1 burst = metadata + 1.5s of audio.
   * Phase 2 rate = 1.2x of track's true bitrate.
   */
  computePacingConfig() {
    const bytesPerSecond = Math.ceil(this.bitrate / 8);
    const burstBytes = this.metadataBytes + Math.ceil(bytesPerSecond * 1.5);
    const steadyBytesPerSec = Math.ceil(bytesPerSecond * 1.2);

    return {
      bytesPerSecond,
      burstBytes,
      steadyBytesPerSec,
      targetColdStartMs: 250
    };
  }

  /**
   * Creates a time-based pacer transform stream.
   *
   * Phase 1 (burst): bytes flow immediately at line rate until burstBytes sent.
   * Phase 2 (steady): monotonic emit-time scheduler at steadyBytesPerSec —
   * each chunk is delayed to keep long-run average <= steady rate, with no
   * artificial per-chunk cap that would allow rate overshoot.
   */
  createTimeBasedPacerStream() {
    const { burstBytes, steadyBytesPerSec } = this.computePacingConfig();
    let sentBytes = 0;
    let burstComplete = false;
    let nextEmitTime = 0; // monotonic ms watermark

    return new Transform({
      transform(chunk, encoding, callback) {
        sentBytes += chunk.length;
        if (!burstComplete) {
          if (sentBytes >= burstBytes) {
            burstComplete = true;
            nextEmitTime = Date.now();
          }
          // Deliver burst immediately with zero delay
          return callback(null, chunk);
        }

        // Phase 2 steady pacing: throttle to 1.2x bitrate via monotonic scheduler
        const now = Date.now();
        const emitAt = Math.max(now, nextEmitTime);
        nextEmitTime = emitAt + (chunk.length / steadyBytesPerSec) * 1000;

        const delay = emitAt - now;
        if (delay > 0) {
          setTimeout(() => callback(null, chunk), delay);
        } else {
          callback(null, chunk);
        }
      }
    });
  }

  /**
   * Total stream duration implied by the frame index (last frame sample + block).
   */
  get durationSeconds() {
    if (!this.frameIndex || this.frameIndex.length === 0) return 0;
    const last = this.frameIndex[this.frameIndex.length - 1];
    const blockSamples = last.blockSize || 0;
    return (last.sampleNumber + blockSamples) / (this.sampleRate || 1);
  }
}
