/// <reference types="vite/client" />
/**
 * lyrics/playbackClock.ts — the phase-locked lyric sync clock (port of
 * app/…/media/lyrics/LyricPlaybackController.kt).
 *
 * The Android original runs a 120 FPS withFrameNanos loop with hardware
 * VSYNC extrapolation; requestAnimationFrame is its direct web equivalent
 * (same monotonic per-frame callback contract, typically 60–120 Hz).
 * The PLL discipline is preserved EXACTLY:
 *
 *   • CONTINUOUS EXTRAPOLATION — the media element's position property
 *     updates in coarse ticks (ExoPlayer position ticks / the audio
 *     element's currentTime quantization); between ticks the clock
 *     advances real elapsed wall time from the frame timestamps, so the
 *     interpolated position is smooth instead of steppy.
 *   • SEEK / NUDGE SNAPPING — a target jump > 400 ms arms an instant
 *     snap on the next frame (no glide across seeks); manual nudges arm
 *     it too.
 *   • PHASE-LOCKED CONVERGENCE — otherwise the interpolated position
 *     converges toward the extrapolated truth with a 0.25 proportional
 *     step per frame whenever drift exceeds 500 ms, minimum ±1 ms steps
 *     so tiny errors still close.
 *   • PAUSE FREEZE — paused output holds the last observed target plus
 *     the user offset, never extrapolating.
 *
 * The public surface mirrors the Kotlin class: bindTrack / adjustOffset /
 * resetOffset / the targetPositionMs+isPlaying inputs the player writes,
 * and the interpolatedPosMs output the lyric view reads each frame.
 */

import { getOffset, keyOf, setOffset } from './offsetStore';

/** Seek/nudge gap that arms an instant snap (Kotlin: 400 ms). */
const SNAP_GAP_MS = 400;
/** Drift beyond which the PLL re-converges (Kotlin: 500 ms). */
const CONVERGE_DRIFT_MS = 500;
/** Proportional convergence step per frame (Kotlin: 0.25). */
const CONVERGE_STEP_RATIO = 0.25;

export class LyricPlaybackClock {
  /** The player-written media position (ms) — update every tick. */
  targetPositionMs = 0;
  /** The player-written playing state. */
  isPlaying = false;
  /** The interpolated, offset-adjusted output (ms) — read by the view. */
  interpolatedPosMs = 0;
  /** The user's manual per-track offset (ms). */
  userOffsetMs = 0;

  /** Stable identity of the bound track (offsetStore key). */
  trackKey: string | null = null;

  private lastObservedTargetMs = -1;
  private lastTargetTimeMs = 0;
  private lastFrameTimeMs = 0;
  private snapPending = false;
  private rafHandle: number | null = null;
  private boundFrame: FrameRequestCallback | null = null;

  constructor(private readonly raf: (cb: FrameRequestCallback) => number = (cb) =>
    requestAnimationFrame(cb)) {}

  /**
   * Attaches the clock to a track: restores the user's saved offset and
   * routes every subsequent nudge into persistent storage.
   */
  bindTrack(title: string | null | undefined, artist: string | null | undefined): void {
    this.trackKey = title == null && artist == null ? null : keyOf(title, artist);
    this.userOffsetMs = this.trackKey !== null ? getOffset(this.trackKey) : 0;
    this.snapPending = true; // re-anchor rendering to the restored offset
  }

  /** Nudges the user offset (persisted per track) and re-anchors. */
  adjustOffset(deltaMs: number): void {
    this.userOffsetMs += deltaMs;
    this.snapPending = true;
    if (this.trackKey !== null) setOffset(this.trackKey, this.userOffsetMs);
  }

  /** Clears the user offset (persisted) and re-anchors. */
  resetOffset(): void {
    this.userOffsetMs = 0;
    this.snapPending = true;
    if (this.trackKey !== null) setOffset(this.trackKey, 0);
  }

  /** Starts the frame loop (idempotent). */
  start(): void {
    if (this.rafHandle !== null) return;
    this.boundFrame = this.frameLoop.bind(this);
    this.rafHandle = this.raf(this.boundFrame);
  }

  /** Stops the frame loop (idempotent; state is preserved). */
  stop(): void {
    if (this.rafHandle !== null && this.boundFrame !== null) {
      cancelAnimationFrame(this.rafHandle);
    }
    this.rafHandle = null;
    this.boundFrame = null;
  }

  private frameLoop(frameTimeMs: number): void {
    this.rafHandle = this.raf(this.boundFrame!);
    this.tick(frameTimeMs);
  }

  /** One PLL frame — also callable directly (tests / custom loops). */
  tick(frameTimeMs: number): void {
    if (this.lastFrameTimeMs === 0) {
      this.lastFrameTimeMs = frameTimeMs;
      this.lastTargetTimeMs = frameTimeMs;
      this.lastObservedTargetMs = this.targetPositionMs;
      this.interpolatedPosMs = this.targetPositionMs + this.userOffsetMs;
      return;
    }

    // When the player reports a new position tick…
    if (this.targetPositionMs !== this.lastObservedTargetMs) {
      const gap = Math.abs(this.targetPositionMs - this.lastObservedTargetMs);
      this.lastObservedTargetMs = this.targetPositionMs;
      this.lastTargetTimeMs = frameTimeMs;
      if (gap > SNAP_GAP_MS) {
        this.snapPending = true; // a seek — snap, don't glide
      }
    }

    if (!this.isPlaying) {
      this.interpolatedPosMs = Math.max(
        0,
        this.lastObservedTargetMs + this.userOffsetMs,
      );
      this.snapPending = false;
      return;
    }

    // Phase-Locked Loop: extrapolate continuous real-time advancement
    // using the frame timestamps (the VSYNC-analog monotonic clock).
    const elapsedSinceTargetMs =
      this.lastTargetTimeMs > 0
        ? frameTimeMs - this.lastTargetTimeMs
        : frameTimeMs - this.lastFrameTimeMs;
    this.lastFrameTimeMs = frameTimeMs;

    const exactTarget = Math.round(
      this.lastObservedTargetMs + elapsedSinceTargetMs + this.userOffsetMs,
    );
    const diff = exactTarget - this.interpolatedPosMs;

    if (this.snapPending || Math.abs(diff) > CONVERGE_DRIFT_MS || this.interpolatedPosMs <= 0) {
      // Instant snap on seek, initial load, or user nudge.
      this.interpolatedPosMs = exactTarget;
      this.snapPending = false;
    } else {
      // Smooth phase-locked tracking.
      const step = Math.trunc(diff * CONVERGE_STEP_RATIO);
      this.interpolatedPosMs +=
        step !== 0 ? step : diff > 0 ? 1 : diff < 0 ? -1 : 0;
    }
  }
}
