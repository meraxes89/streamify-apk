/**
 * components/PlayerBar.tsx — the now-playing surface (W2.3, mobile-first).
 *
 * Renders the engine snapshot: artwork, title/artist, position/duration,
 * a seek slider, transport controls (44px+ touch targets), volume, and
 * the honest stat chips (state, buffered, egress mode, resolve rung).
 * Sticky at the bottom on small screens (safe-area aware), inline card
 * on wide ones.
 */

import { useCallback, useRef } from 'react';
import { usePlayerStore } from '../lib/playerStore';
import type { TrackRow } from '../lib/wasmEngine';

function fmt(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) ms = 0;
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export function PlayerBar({
  egressMode,
  onEgressModeChange,
}: {
  egressMode: 'element' | 'spine';
  onEgressModeChange: (mode: 'element' | 'spine') => void;
}): JSX.Element {
  const snapshot = usePlayerStore((s) => s.snapshot);
  const queue = usePlayerStore((s) => s.queue);
  const lastRung = usePlayerStore((s) => s.lastRung);
  const send = usePlayerStore((s) => s.send);
  const playOrToggle = usePlayerStore((s) => s.playOrToggle);
  const seekRef = useRef<HTMLInputElement | null>(null);

  // The honest display track: the cursor's row, falling back to the
  // HEAD of the queue — a queued-but-not-playing list must not read
  // "Nothing queued" (the P2 desync: the bar ignored the queue).
  const track: TrackRow | null =
    snapshot?.currentTrack ?? queue.rows[queue.cursor ?? 0]?.track ?? null;
  const status = snapshot?.status ?? 'Idle';
  const positionMs = snapshot?.positionMs ?? 0;
  const durationMs = track?.durationMs ?? 0;
  const playing = status === 'Playing';
  const buffering = status === 'Buffering';
  // The engine boots into Buffering (initialize: Idle→Buffering, the UDL
  // contract) — with no track anywhere that's rest, not buffering.
  const statusLabel =
    buffering && track === null && (queue.rows.length === 0 || queue.cursor === null) && snapshot?.clock === null
      ? 'idle'
      : status.toLowerCase();

  const onSeek = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const ms = Number(e.target.value);
      send({ type: 'seek', positionMs: ms });
    },
    [send],
  );

  const artwork =
    track?.thumbnailUrl !== null && track?.thumbnailUrl !== undefined
      ? track.thumbnailUrl
      : null;

  return (
    <section className="playerbar" aria-label="Now playing">
      <div className="playerbar__media">
        <div className={`playerbar__art ${artwork === null ? 'playerbar__art--empty' : ''}`}>
          {artwork !== null ? (
            <img src={artwork} alt="" loading="lazy" />
          ) : (
            <span aria-hidden>♪</span>
          )}
          {buffering && <span className="playerbar__spinner" aria-label="buffering" />}
        </div>
        <div className="playerbar__titles">
          <span className="playerbar__title">{track?.title ?? 'Nothing queued'}</span>
          <span className="playerbar__artist">{track?.artist ?? 'Search and tap Play'}</span>
          <span className="playerbar__chips">
            <span className={`chip chip--${statusLabel === 'idle' ? 'idle' : statusLabel}`}>{statusLabel}</span>
            {lastRung !== null && <span className="chip chip--rung">rung: {lastRung}</span>}
            <button
              type="button"
              className="chip chip--egress"
              onClick={() => onEgressModeChange(egressMode === 'element' ? 'spine' : 'element')}
              title="Toggle egress: direct (user IP) ↔ engine spine (relay bytes)"
            >
              egress: {egressMode === 'element' ? 'direct' : 'spine'}
            </button>
          </span>
        </div>
      </div>

      <div className="playerbar__progress">
        <span className="playerbar__time">{fmt(positionMs)}</span>
        <input
          ref={seekRef}
          className="playerbar__seek"
          type="range"
          min={0}
          max={durationMs > 0 ? durationMs : 1}
          step={1000}
          value={Math.min(positionMs, durationMs > 0 ? durationMs : 1)}
          onChange={onSeek}
          disabled={track === null || durationMs === 0}
          aria-label="Seek position"
        />
        <span className="playerbar__time">{fmt(durationMs)}</span>
      </div>

      <div className="playerbar__controls" role="group" aria-label="Transport controls">
        <button
          type="button"
          className="btn btn--transport"
          onClick={() => send({ type: 'previous' })}
          disabled={track === null}
          aria-label="Previous track"
        >
          ⏮
        </button>
        <button
          type="button"
          className="btn btn--transport btn--transport-main"
          onClick={playOrToggle}
          disabled={track === null}
          aria-label={playing ? 'Pause' : 'Play'}
        >
          {buffering ? '…' : playing ? '⏸' : '▶'}
        </button>
        <button
          type="button"
          className="btn btn--transport"
          onClick={() => send({ type: 'next' })}
          disabled={track === null}
          aria-label="Next track"
        >
          ⏭
        </button>
        <button
          type="button"
          className="btn btn--transport"
          onClick={() => send({ type: 'stop' })}
          disabled={status === 'Idle'}
          aria-label="Stop"
        >
          ⏹
        </button>
        <input
          className="playerbar__volume"
          type="range"
          min={0}
          max={100}
          step={1}
          value={Math.round((snapshot?.volume ?? 1) * 100)}
          onChange={(e) => send({ type: 'setVolume', volume: Number(e.target.value) / 100 })}
          aria-label="Volume"
        />
      </div>

      <div className="playerbar__stats" aria-label="Playback statistics">
        <span title="Buffered ahead">
          ▮ {fmt(snapshot?.buffer.bufferedDurationMs ?? 0)} buffered
        </span>
        <span title="Ring / buffer fill ratio">
          ◩ {Math.round((snapshot?.buffer.fillRatio ?? 0) * 100)}%
        </span>
        <span title="Underrun events this session">
          ⚠ {snapshot?.buffer.underrunCount ?? 0} underruns
        </span>
        <span title="Queue length">≡ {snapshot?.queueLength ?? 0} queued</span>
      </div>
    </section>
  );
}
