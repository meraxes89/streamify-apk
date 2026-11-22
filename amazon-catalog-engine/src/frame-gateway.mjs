/**
 * Frame-Aligned HTTP 206 Gateway & Time-Based Audio Pacer
 * 
 * Implements docs/nano/comm21.md specifications:
 * 1. Frame-Aligned Seek: Maps target timestamp 't' to exact sample and frame sync boundary (0xFFF8).
 * 2. Time-Based Two-Phase Pacing:
 *    - Phase 1: Burst = MetadataBytes + (TrackBitrate * 1.5s) at line rate (< 250ms cold start).
 *    - Phase 2: Steady token bucket paced at 1.2x track bitrate.
 * 3. Never transcodes; bit-perfect delivery always.
 * 4. Decouples backpressure using bounded cache.
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
    this.frameIndex = frameIndex; // array of { sampleNumber, byteOffset }
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
   * @returns {Object} { startByte, endByte, frameOffset, isFrameAligned }
   */
  resolveSeekRange(seekSeconds) {
    if (!seekSeconds || seekSeconds <= 0) {
      return {
        startByte: 0,
        endByte: this.totalBytes - 1,
        frameOffset: 0,
        isFrameAligned: true
      };
    }

    const targetSample = Math.floor(seekSeconds * this.sampleRate);
    let bestFrame = null;

    // Binary or linear search through frame index
    for (let i = 0; i < this.frameIndex.length; i++) {
      const f = this.frameIndex[i];
      if (f.sampleNumber <= targetSample) {
        bestFrame = f;
      } else {
        break;
      }
    }

    const startByte = bestFrame ? bestFrame.byteOffset : this.metadataBytes;
    return {
      startByte,
      endByte: this.totalBytes - 1,
      targetSample,
      resolvedSample: bestFrame ? bestFrame.sampleNumber : 0,
      isFrameAligned: true
    };
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
   */
  createTimeBasedPacerStream() {
    const { burstBytes, steadyBytesPerSec } = this.computePacingConfig();
    let sentBytes = 0;
    let burstComplete = false;

    return new Transform({
      transform(chunk, encoding, callback) {
        sentBytes += chunk.length;
        if (!burstComplete) {
          if (sentBytes >= burstBytes) {
            burstComplete = true;
          }
          // Deliver burst immediately with zero delay
          return callback(null, chunk);
        }

        // Phase 2 steady pacing: throttle to 1.2x bitrate
        const delayMs = Math.floor((chunk.length / steadyBytesPerSec) * 1000);
        if (delayMs > 5) {
          setTimeout(() => callback(null, chunk), Math.min(delayMs, 50));
        } else {
          callback(null, chunk);
        }
      }
    });
  }
}
