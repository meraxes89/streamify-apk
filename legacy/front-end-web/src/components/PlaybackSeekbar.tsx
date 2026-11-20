/**
 * components/PlaybackSeekbar.tsx — P1 port of
 * shared/…/components/PlaybackSeekbar.kt (the platform-agnostic seek bar).
 *
 * Renders the engine-pushed position with drag-to-seek:
 *
 *  • position + duration known — an interactive slider; while the user
 *    scrubs, the bar renders the SCRUB position (the engine keeps playing
 *    untouched — onSeek fires exactly once, on release);
 *  • duration unknown — the quiet baseline plus "--:--" labels (the shared
 *    degraded-mode discipline: absence is never dressed up as zero; the
 *    strip keeps the slot so layout never jumps between modes).
 *
 * Round-trip discipline (the VolumeSlider contract, applied to position):
 * the bar never owns authoritative state — it renders what the engine
 * last pushed and lowers onSeek onto the host, which dispatches the seek
 * command; the engine's next push repaints the bar. Two surfaces sharing
 * a session can therefore never disagree.
 *
 * The scrub latch (from YtPlayerSeekBar.kt): after release the bar holds
 * the scrubbed value locally until the pushed position CONVERGES within
 * 400 ms of it — the engine's seek is async and the 4 Hz push would
 * otherwise snap the thumb back to the stale position mid-flight.
 */

import { useEffect, useRef, useState } from 'react';

/** shared/…/model/PlaybackFormatting.kt formatPlaybackTime. */
export function formatPlaybackTime(positionMs: number): string {
  const total = Math.max(0, Math.round(positionMs)) / 1000;
  const totalSeconds = Math.floor(total);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

export interface PlaybackSeekbarProps {
  /** Engine-pushed position (ms); null = not yet known. */
  positionMs: number | null;
  /** Track duration (ms); null/<=0 = unknown → quiet baseline. */
  durationMs: number | null;
  /** Fires once, on scrub release (or keyboard commit). */
  onSeek: (positionMs: number) => void;
  enabled?: boolean;
}

/** The convergence window that unlatches a finished scrub (Kotlin: 400ms). */
const UNLATCH_MS = 400;

export function PlaybackSeekbar(props: PlaybackSeekbarProps): JSX.Element {
  const { positionMs, durationMs, onSeek, enabled = true } = props;
  const knownDuration = durationMs !== null && durationMs > 0 ? durationMs : null;

  const [scrubbing, setScrubbing] = useState(false);
  const [scrubPositionMs, setScrubPositionMs] = useState(0);
  // The post-release latch: the value held until the engine converges.
  const [latchedMs, setLatchedMs] = useState<number | null>(null);
  const latchedRef = useRef<number | null>(null);
  latchedRef.current = latchedMs;

  // Track-identity reset: a stale latch must never leak across tracks.
  // Keying on the duration makes a track change drop in-flight scrub
  // state deterministically (the Kotlin LaunchedEffect(knownDuration)).
  useEffect(() => {
    setScrubbing(false);
    setLatchedMs(null);
  }, [knownDuration]);

  // Unlatch when the pushed position converges near the latched target.
  useEffect(() => {
    if (positionMs === null) return;
    const latched = latchedRef.current;
    if (latched !== null && Math.abs(positionMs - latched) < UNLATCH_MS) {
      setLatchedMs(null);
    }
  }, [positionMs]);

  const displayPosition =
    scrubbing ? scrubPositionMs : latchedMs !== null ? latchedMs : positionMs;

  const fillPct =
    knownDuration !== null && displayPosition !== null
      ? Math.min(100, Math.max(0, (displayPosition / knownDuration) * 100))
      : 0;

  const commitScrub = (raw: number): void => {
    if (!scrubbing) return;
    const target = Math.max(0, Math.min(knownDuration ?? 0, Math.round(raw)));
    setScrubbing(false);
    if (knownDuration !== null && target > 0) setLatchedMs(target);
    onSeek(target);
  };

  return (
    <div className="seekbar">
      <input
        type="range"
        className={`seekbar__slider ${scrubbing ? 'seekbar__slider--scrubbing' : ''}`}
        min={0}
        max={knownDuration ?? 1}
        step={250}
        disabled={!enabled || knownDuration === null}
        value={displayPosition !== null ? Math.min(displayPosition, knownDuration ?? 1) : 0}
        aria-label="Seek position"
        aria-valuetext={
          displayPosition !== null ? formatPlaybackTime(displayPosition) : 'not playing'
        }
        style={{ '--seekbar-fill': `${fillPct}%` } as React.CSSProperties}
        onPointerDown={() => {
          if (knownDuration === null) return;
          setScrubbing(true);
        }}
        onInput={(e) => {
          if (!scrubbing) return;
          setScrubPositionMs(Number((e.target as HTMLInputElement).value));
        }}
        onPointerUp={(e) => commitScrub(Number((e.target as HTMLInputElement).value))}
        onPointerCancel={() => setScrubbing(false)}
        onKeyUp={(e) => {
          if (e.key === 'ArrowLeft' || e.key === 'ArrowRight' || e.key === 'Home' || e.key === 'End') {
            commitScrub(Number((e.target as HTMLInputElement).value));
          }
        }}
        onBlur={() => setScrubbing(false)}
      />
      <div className="seekbar__labels" aria-hidden="true">
        <span className={`seekbar__label ${scrubbing ? 'seekbar__label--active' : ''}`}>
          {displayPosition !== null ? formatPlaybackTime(displayPosition) : '--:--'}
        </span>
        <span className="seekbar__label">
          {knownDuration !== null ? formatPlaybackTime(knownDuration) : '--:--'}
        </span>
      </div>
    </div>
  );
}
