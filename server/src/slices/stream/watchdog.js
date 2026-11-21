import { EventEmitter } from 'node:events';

export const THROTTLE_THRESHOLD_BPS = 65_000; // 65 kbps ceiling (YouTube throttles to ~50-55 kbps)
export const CONSECUTIVE_WINDOWS_REQUIRED = 2; // Must sustain throttle for 2 seconds to trigger
export const SAMPLE_INTERVAL_MS = 1000; // Sample every 1 second

/**
 * StreamWatchdog: Real-time throughput monitor (comm9.1.md Phase 1)
 * Detects YouTube's silent attestation enforcement (~50 kbps throttle)
 * and signals the health ladder to demote the responsible client persona.
 */
export class StreamWatchdog extends EventEmitter {
  /**
   * @param {Object} [options]
   * @param {import('./health.js').ClientHealthManager} [options.healthManager]
   * @param {number} [options.throttleBps=65000]
   * @param {number} [options.sampleIntervalMs=1000]
   */
  constructor({
    healthManager,
    throttleBps = THROTTLE_THRESHOLD_BPS,
    sampleIntervalMs = SAMPLE_INTERVAL_MS,
  } = {}) {
    super();
    this.healthManager = healthManager;
    this.throttleBps = throttleBps;
    this.sampleIntervalMs = sampleIntervalMs;

    /** @type {Map<string, { clientName: string, videoId: string, bytesTransferred: number, lastBytes: number, consecutiveThrottles: number, startedAt: number }>} */
    this.activeStreams = new Map();
    this.timer = null;
    this.totalThrottlesDetected = 0;
  }

  /**
   * Registers a stream for throughput monitoring
   * @param {string} streamId - Unique identifier (e.g. videoId or connectionId)
   * @param {string} clientName - Persona used to resolve stream (e.g. 'ANDROID', 'IOS')
   * @param {string} videoId
   */
  watch(streamId, clientName, videoId) {
    this.activeStreams.set(streamId, {
      clientName,
      videoId,
      bytesTransferred: 0,
      lastBytes: 0,
      consecutiveThrottles: 0,
      startedAt: Date.now(),
    });

    if (!this.timer) {
      this.timer = setInterval(() => this.sample(), this.sampleIntervalMs);
      if (typeof this.timer.unref === 'function') {
        this.timer.unref();
      }
    }
  }

  /**
   * Records bytes transferred for a watched stream
   */
  recordBytes(streamId, bytes) {
    const s = this.activeStreams.get(streamId);
    if (s) {
      s.bytesTransferred += bytes;
    }
  }

  /**
   * Unregisters a completed or closed stream
   */
  unwatch(streamId) {
    this.activeStreams.delete(streamId);
    if (this.activeStreams.size === 0 && this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  sample() {
    const now = Date.now();

    for (const [streamId, s] of this.activeStreams) {
      const ageMs = now - s.startedAt;
      if (ageMs < 1500) {
        // Skip first 1.5 seconds for initial TCP ramp-up
        s.lastBytes = s.bytesTransferred;
        continue;
      }

      const deltaBytes = s.bytesTransferred - s.lastBytes;
      s.lastBytes = s.bytesTransferred;

      // Calculate instantaneous bitrate in bits per second
      const bps = (deltaBytes * 8 * 1000) / this.sampleIntervalMs;

      if (bps > 0 && bps <= this.throttleBps) {
        s.consecutiveThrottles++;
        if (s.consecutiveThrottles >= CONSECUTIVE_WINDOWS_REQUIRED) {
          // Attestation throttle detected!
          this.totalThrottlesDetected++;
          this.emit('throttled', {
            streamId,
            clientName: s.clientName,
            videoId: s.videoId,
            bps,
          });

          if (this.healthManager) {
            this.healthManager.recordThrottle(s.clientName);
          }

          // Reset counter so we don't spam alerts on same stream
          s.consecutiveThrottles = 0;
        }
      } else {
        s.consecutiveThrottles = 0;
      }
    }
  }

  close() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.activeStreams.clear();
  }
}
