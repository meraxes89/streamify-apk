import { Transform } from 'node:stream';

export const TICK_MS = 100;
export const BURST_TOKENS = 256 * 1024; // 256 KiB instant playback burst (comm8.md Phase 1)
export const MAX_STEADY_TOKENS = 64 * 1024; // 64 KiB ceiling
export const WRITE_QUANTUM = 16 * 1024; // 16 KiB A-MPDU aligned frame quantum (comm8.md Phase 2)

/**
 * Shared-Tick Pacing Wheel (comm8.md Problem 4 / P1)
 * Single global coalesced pacing tick (setInterval at 100ms, auto-stopping when 0 streams).
 * Prevents timer churn and lets Android ARM cores enter low-power C-states.
 */
export class PacingWheel {
  constructor({ tickMs = TICK_MS } = {}) {
    this.tickMs = tickMs;
    /** @type {Set<PacedStreamTransform>} */
    this.streams = new Set();
    this.timer = null;
  }

  register(stream) {
    this.streams.add(stream);
    if (!this.timer) {
      this.timer = setInterval(() => this.tick(), this.tickMs);
      if (typeof this.timer.unref === 'function') {
        this.timer.unref();
      }
    }
  }

  unregister(stream) {
    this.streams.delete(stream);
    if (this.streams.size === 0 && this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  tick() {
    for (const stream of this.streams) {
      try {
        stream.onTick();
      } catch {}
    }
  }

  close() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.streams.clear();
  }
}

/**
 * PacedStreamTransform: Bitrate-aware 2-phase token bucket stream
 */
export class PacedStreamTransform extends Transform {
  /**
   * @param {Object} options
   * @param {PacingWheel} options.wheel
   * @param {number} [options.bitrateBps=160000] - 160 kbps default for Opus
   * @param {AbortSignal} [options.signal]
   */
  constructor({ wheel, bitrateBps = 160_000, signal, highWaterMark = 256 * 1024 } = {}) {
    super({ highWaterMark });
    this.wheel = wheel;
    this.signal = signal;

    // Phase 1: 256 KiB burst tokens for 0 TTFA delay
    this.tokens = BURST_TOKENS;
    this.isBurstPhase = true;

    // Phase 2: Steady state (1.75x bitrate)
    this.bytesPerSec = Math.max(32_768, Math.ceil((bitrateBps / 8) * 1.75));
    this.tokensPerTick = Math.ceil(this.bytesPerSec * (this.wheel.tickMs / 1000)); // ~3.5 KiB / 100ms tick for 160kbps

    /** @type {Buffer[]} */
    this.buffer = [];
    this.bufferedBytes = 0;
    this.pendingTransformCb = null;
    this.pendingFlushCb = null;

    this.wheel.register(this);

    if (signal) {
      signal.addEventListener(
        'abort',
        () => {
          this.destroy(signal.reason || new Error('Stream aborted'));
        },
        { once: true }
      );
    }
  }

  _transform(chunk, encoding, callback) {
    if (!chunk || chunk.length === 0) return callback();

    this.buffer.push(chunk);
    this.bufferedBytes += chunk.length;
    this.pendingTransformCb = callback;

    this.tryFlush();
  }

  tryFlush() {
    while (this.bufferedBytes > 0) {
      // In burst phase, push immediately up to available tokens
      if (this.isBurstPhase) {
        if (this.tokens > 0) {
          const toSend = Math.min(this.bufferedBytes, this.tokens);
          const chunk = this.sliceBuffer(toSend);
          this.tokens -= toSend;
          if (this.tokens <= 0) {
            this.isBurstPhase = false;
            this.tokens = 0;
          }
          this.push(chunk);
          continue;
        } else {
          this.isBurstPhase = false;
        }
      }

      // In steady state, only write once tokens >= WRITE_QUANTUM (16 KiB) or stream is ending
      const isEnding = Boolean(this.pendingFlushCb);
      if (this.tokens >= WRITE_QUANTUM || (isEnding && this.tokens > 0)) {
        const toSend = isEnding
          ? Math.min(this.bufferedBytes, this.tokens)
          : Math.min(this.bufferedBytes, WRITE_QUANTUM, this.tokens);

        if (toSend <= 0) break;

        const chunk = this.sliceBuffer(toSend);
        this.tokens -= toSend;
        this.push(chunk);
      } else {
        // Await next wheel tick
        break;
      }
    }

    // Release backpressure if buffer has drained below highWaterMark
    if (this.pendingTransformCb && this.bufferedBytes < 64 * 1024) {
      const cb = this.pendingTransformCb;
      this.pendingTransformCb = null;
      cb();
    }

    // Complete flush if buffer is empty
    if (this.pendingFlushCb && this.bufferedBytes === 0) {
      const cb = this.pendingFlushCb;
      this.pendingFlushCb = null;
      cb();
    }
  }

  sliceBuffer(size) {
    if (size >= this.bufferedBytes) {
      const full = Buffer.concat(this.buffer);
      this.buffer = [];
      this.bufferedBytes = 0;
      return full;
    }

    let needed = size;
    const parts = [];

    while (this.buffer.length > 0 && needed > 0) {
      const head = this.buffer[0];
      if (head.length <= needed) {
        parts.push(head);
        needed -= head.length;
        this.buffer.shift();
      } else {
        parts.push(head.subarray(0, needed));
        this.buffer[0] = head.subarray(needed);
        needed = 0;
      }
    }

    this.bufferedBytes -= size;
    return Buffer.concat(parts);
  }

  onTick() {
    if (!this.isBurstPhase) {
      // Replenish tokens up to MAX_STEADY_TOKENS ceiling
      this.tokens = Math.min(MAX_STEADY_TOKENS, this.tokens + this.tokensPerTick);
    }
    this.tryFlush();
  }

  _flush(callback) {
    if (this.bufferedBytes === 0) {
      return callback();
    }
    this.pendingFlushCb = callback;
    this.tryFlush();
  }

  _destroy(err, callback) {
    this.wheel.unregister(this);
    this.buffer = [];
    this.bufferedBytes = 0;
    if (this.pendingTransformCb) {
      const cb = this.pendingTransformCb;
      this.pendingTransformCb = null;
      cb(err);
    }
    if (this.pendingFlushCb) {
      const cb = this.pendingFlushCb;
      this.pendingFlushCb = null;
      cb(err);
    }
    callback(err);
  }
}

/**
 * StreamPacer provides gateway integration helper
 */
export class StreamPacer {
  constructor({ wheel = new PacingWheel() } = {}) {
    this.wheel = wheel;
  }

  pace(readableStream, { bitrateBps = 160_000, signal } = {}) {
    const transform = new PacedStreamTransform({
      wheel: this.wheel,
      bitrateBps,
      signal,
    });
    return readableStream.pipe(transform);
  }

  close() {
    this.wheel.close();
  }
}
