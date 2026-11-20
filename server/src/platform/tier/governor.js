/**
 * Streamify Elastic Tiering - Stage 3: Runtime 1 Hz Feedback Governor
 * 
 * Inspects PAL memory pressure and adjusts resource residency dynamically:
 * - 'warn'     -> diskCache x0.75, pause/drop prefetch, demote pacing one step
 * - 'critical' -> bufferPool -> minSlots, teardown idle workers, pause prefetch, shed non-active clients
 * - 'recover'  -> step back up after 30s hysteresis (eliminates oscillation thrashing)
 * - activeStreams changes -> recompute bufferSlots within tier ceilings
 */

import { pal } from '../pal/index.js';
import { PRESSURE_LEVEL } from '../pal/types.js';
import { getDefaultBufferPool } from '../../lib/buffer-pool.js';

export class TierGovernor {
  /**
   * @param {object} [options]
   * @param {import('../pal/index.js').PlatformAbstractionLayer} [options.pal]
   * @param {object} [options.tierConfig]
   * @param {import('../../lib/buffer-pool.js').SlotBufferPool} [options.bufferPool]
   * @param {number} [options.hysteresisMs=30000] - 30 seconds recovery hysteresis
   * @param {object} [options.callbacks]
   */
  constructor(options = {}) {
    this.pal = options.pal || pal;
    this.tierConfig = options.tierConfig || null;
    this.bufferPool = options.bufferPool || getDefaultBufferPool();
    this.hysteresisMs = options.hysteresisMs ?? 30000;
    this.callbacks = options.callbacks || {};

    // Internal dynamic state
    this.state = PRESSURE_LEVEL.NORMAL;
    this.activeStreams = 0;
    this.lastPressureEventTime = 0;
    this.prefetchPaused = false;
    this.diskCacheScale = 1.0;
    this.pacingLevel = this.tierConfig?.network?.pacingPolicy || 'standard';

    const initialSlots = this.tierConfig
      ? this.tierConfig.bufferPool.computeSlots(0)
      : (this.bufferPool.numSlots || 64);
    this.currentBufferSlots = initialSlots;

    this.pollInterval = null;
    this.metrics = {
      warnTransitions: 0,
      criticalTransitions: 0,
      recoveries: 0,
      ticks: 0,
    };
  }

  /**
   * Start the 1 Hz feedback controller loop.
   */
  start() {
    if (this.pollInterval) return;

    // Apply initial buffer pool bounds if tierConfig was provided
    if (this.tierConfig) {
      this.bufferPool.setActiveSlots(this.currentBufferSlots);
    }

    this.pollInterval = setInterval(() => {
      this.tick();
    }, 1000);

    if (this.pollInterval.unref) {
      this.pollInterval.unref();
    }
  }

  /**
   * Stop the feedback controller loop.
   */
  stop() {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
      this.pollInterval = null;
    }
  }

  /**
   * Notify governor that active stream count has changed.
   * @param {number} count
   */
  setActiveStreams(count) {
    this.activeStreams = Math.max(0, count);

    // If currently under critical pressure, force buffer pool to remain at minSlots
    if (this.state === PRESSURE_LEVEL.CRITICAL) {
      if (this.tierConfig) {
        this.currentBufferSlots = this.tierConfig.bufferPool.minSlots;
        this.bufferPool.setActiveSlots(this.currentBufferSlots);
      }
      return;
    }

    if (this.tierConfig) {
      const targetSlots = this.tierConfig.bufferPool.computeSlots(this.activeStreams);
      if (targetSlots !== this.currentBufferSlots) {
        this.currentBufferSlots = targetSlots;
        this.bufferPool.setActiveSlots(targetSlots);
        if (this.callbacks.onSlotsChange) {
          this.callbacks.onSlotsChange({ slots: targetSlots });
        }
      }
    }
  }

  /**
   * Demote pacing by one level: off -> standard -> aggressive
   */
  demotePacing() {
    if (this.pacingLevel === 'off') {
      this.pacingLevel = 'standard';
    } else if (this.pacingLevel === 'standard') {
      this.pacingLevel = 'aggressive';
    }
    if (this.callbacks.onPacingLevelChange) {
      this.callbacks.onPacingLevelChange({ level: this.pacingLevel });
    }
  }

  /**
   * Core 1 Hz feedback tick.
   * @param {import('../pal/types.js').PressureSample} [injectedSample]
   */
  tick(injectedSample = null) {
    this.metrics.ticks++;
    const sample = injectedSample || this.pal.memory.sample();
    const now = Date.now();
    const previousState = this.state;

    if (sample.level === PRESSURE_LEVEL.CRITICAL) {
      this.lastPressureEventTime = now;

      if (this.state !== PRESSURE_LEVEL.CRITICAL) {
        this.state = PRESSURE_LEVEL.CRITICAL;
        this.metrics.criticalTransitions++;

        // 1. Force buffer pool to minSlots
        if (this.tierConfig) {
          this.currentBufferSlots = this.tierConfig.bufferPool.minSlots;
          this.bufferPool.setActiveSlots(this.currentBufferSlots);
        }

        // 2. Pause prefetch
        this.prefetchPaused = true;

        // 3. Scale disk cache down
        this.diskCacheScale = 0.5;

        // 4. Force aggressive pacing
        this.pacingLevel = 'aggressive';

        // 5. Notify subsystems
        if (this.callbacks.onTeardownIdleWorkers) {
          this.callbacks.onTeardownIdleWorkers();
        }
        if (this.callbacks.onPrefetchStateChange) {
          this.callbacks.onPrefetchStateChange({ paused: true });
        }
        if (this.callbacks.onPacingLevelChange) {
          this.callbacks.onPacingLevelChange({ level: 'aggressive' });
        }
        if (this.callbacks.onDiskCacheScaleChange) {
          this.callbacks.onDiskCacheScaleChange({
            scale: 0.5,
            effectiveBytes: this.getEffectiveDiskCacheBytes()
          });
        }
        if (this.callbacks.onPressureChange) {
          this.callbacks.onPressureChange({ from: previousState, to: PRESSURE_LEVEL.CRITICAL, sample });
        }
      }
    } else if (sample.level === PRESSURE_LEVEL.WARN) {
      this.lastPressureEventTime = now;

      if (this.state !== PRESSURE_LEVEL.WARN) {
        this.state = PRESSURE_LEVEL.WARN;
        this.metrics.warnTransitions++;

        // 1. Pause/drop prefetch
        this.prefetchPaused = true;

        // 2. Scale disk cache to 0.75
        this.diskCacheScale = 0.75;

        // 3. Demote pacing one step
        this.demotePacing();

        // 4. Notify subsystems
        if (this.callbacks.onPrefetchStateChange) {
          this.callbacks.onPrefetchStateChange({ paused: true });
        }
        if (this.callbacks.onDiskCacheScaleChange) {
          this.callbacks.onDiskCacheScaleChange({
            scale: 0.75,
            effectiveBytes: this.getEffectiveDiskCacheBytes()
          });
        }
        if (this.callbacks.onPressureChange) {
          this.callbacks.onPressureChange({ from: previousState, to: PRESSURE_LEVEL.WARN, sample });
        }
      }
    } else {
      // sample.level === NORMAL
      if (this.state !== PRESSURE_LEVEL.NORMAL) {
        // Evaluate hysteresis window to prevent oscillation
        const elapsedSincePressure = now - this.lastPressureEventTime;
        if (elapsedSincePressure >= this.hysteresisMs) {
          this.state = PRESSURE_LEVEL.NORMAL;
          this.metrics.recoveries++;

          // Restore normal parameters
          this.prefetchPaused = false;
          this.diskCacheScale = 1.0;
          this.pacingLevel = this.tierConfig?.network?.pacingPolicy || 'standard';

          // Recompute buffer slots for active streams
          if (this.tierConfig) {
            this.currentBufferSlots = this.tierConfig.bufferPool.computeSlots(this.activeStreams);
            this.bufferPool.setActiveSlots(this.currentBufferSlots);
          }

          if (this.callbacks.onPrefetchStateChange) {
            this.callbacks.onPrefetchStateChange({ paused: false });
          }
          if (this.callbacks.onPacingLevelChange) {
            this.callbacks.onPacingLevelChange({ level: this.pacingLevel });
          }
          if (this.callbacks.onDiskCacheScaleChange) {
            this.callbacks.onDiskCacheScaleChange({
              scale: 1.0,
              effectiveBytes: this.getEffectiveDiskCacheBytes()
            });
          }
          if (this.callbacks.onPressureChange) {
            this.callbacks.onPressureChange({ from: previousState, to: PRESSURE_LEVEL.NORMAL, sample });
          }
        }
      }
    }
  }

  /**
   * Compute effective disk cache bytes accounting for current scale.
   * @returns {number}
   */
  getEffectiveDiskCacheBytes() {
    const baseBytes = this.tierConfig?.storage?.effectiveDiskCacheBytes || (512 * 1024 * 1024);
    return Math.floor(baseBytes * this.diskCacheScale);
  }

  /**
   * Diagnostic telemetry and governor state.
   */
  getStatus() {
    const now = Date.now();
    const elapsedSincePressure = now - this.lastPressureEventTime;
    const inHysteresis = this.state !== PRESSURE_LEVEL.NORMAL && elapsedSincePressure < this.hysteresisMs;

    return {
      tier: this.tierConfig?.tier || 'Balanced',
      state: this.state,
      activeStreams: this.activeStreams,
      currentBufferSlots: this.currentBufferSlots,
      prefetchPaused: this.prefetchPaused,
      diskCacheScale: this.diskCacheScale,
      effectiveDiskCacheBytes: this.getEffectiveDiskCacheBytes(),
      pacingLevel: this.pacingLevel,
      inHysteresis,
      hysteresisRemainingMs: inHysteresis ? (this.hysteresisMs - elapsedSincePressure) : 0,
      metrics: { ...this.metrics }
    };
  }
}
