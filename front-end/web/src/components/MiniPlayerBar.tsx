/**
 * components/MiniPlayerBar.tsx — P3a: the docked mini player.
 *
 * The Android MiniPlayerBar.kt port, 1:1 geometry: 64px docked bar
 * (BgSurfaceElevated), 48px art, title/artist column, like heart,
 * play/pause (spinner while buffering), skip-next, and the 2px
 * micro progress line flush at the bottom edge. Gestures (web lowering):
 * tap → the full player surface (Home); horizontal swipe-to-skip with
 * the Android's 80px arm threshold + 75% disarm hysteresis and the
 * parallax peek overlay showing the destination track. Binds to the
 * Zustand player store — one source of truth with PlayerBar/TrackRow.
 */

import { useCallback, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from './icons';
import { usePlayerStore } from '../lib/playerStore';
import { useSessionStore } from '../lib/sessionStore';

/** Swipe arm threshold (Android: 80dp) and disarm hysteresis (75%). */
const SWIPE_ARM_PX = 80;
const SWIPE_DISARM_PX = SWIPE_ARM_PX * 0.75;

export function MiniPlayerBar(): JSX.Element | null {
  const navigate = useNavigate();
  const snapshot = usePlayerStore((s) => s.snapshot);
  const queue = usePlayerStore((s) => s.queue);
  const send = usePlayerStore((s) => s.send);
  const playOrToggle = usePlayerStore((s) => s.playOrToggle);
  const session = useSessionStore((s) => s.session);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);

  const track = snapshot?.currentTrack ?? queue.rows[queue.cursor ?? 0]?.track ?? null;
  const status = snapshot?.status ?? 'Idle';
  const playing = status === 'Playing';
  const buffering = status === 'Buffering';

  // -- swipe-to-skip -----------------------------------------------------
  const [dragX, setDragX] = useState(0);
  const [armed, setArmed] = useState(false);
  const drag = useRef<{ x0: number; y0: number; axis: 'x' | 'y' | null } | null>(null);

  const onPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (track === null) return;
    drag.current = { x0: e.clientX, y0: e.clientY, axis: null };
  }, [track]);

  const onPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (d === null) return;
    const dx = e.clientX - d.x0;
    const dy = e.clientY - d.y0;
    if (d.axis === null) {
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
      d.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
      if (d.axis === 'y') {
        drag.current = null; // vertical = scroll; abandon the gesture
        return;
      }
    }
    setDragX(dx);
    const nextArmed = Math.abs(dx) >= SWIPE_ARM_PX;
    setArmed((prev) => {
      if (nextArmed) return true;
      if (prev && Math.abs(dx) >= SWIPE_DISARM_PX) return true; // hysteresis
      return false;
    });
  }, []);

  const onPointerUp = useCallback(() => {
    const dx = dragX;
    drag.current = null;
    setDragX(0);
    if (armed && Math.abs(dx) >= SWIPE_DISARM_PX) {
      // Left swipe = next, right swipe = previous (the Android mapping).
      send({ type: dx < 0 ? 'next' : 'previous' });
    }
    setArmed(false);
  }, [armed, dragX, send]);

  if (track === null) {
    // The Android bar returns immediately when track == null — the dock
    // only exists once there is something playing/queued.
    return null;
  }

  const cursor = queue.cursor ?? 0;
  const nextTitle = queue.rows[cursor + 1]?.track.title ?? null;
  const prevTitle = cursor > 0 ? queue.rows[cursor - 1]?.track.title : null;
  const peekLeft = dragX > 0; // dragging right → previous track peek
  const peekTitle = peekLeft ? prevTitle : nextTitle;
  const peekAlpha = Math.min(1, Math.abs(dragX) / SWIPE_ARM_PX);

  const durationMs = track.durationMs > 0 ? track.durationMs : 0;
  const progress = durationMs > 0 ? Math.min(1, (snapshot?.positionMs ?? 0) / durationMs) : 0;
  const liked = likedVideoIds.has(track.id);

  return (
    <div
      className={`miniplayer ${armed ? 'miniplayer--armed' : ''}`}
      role="complementary"
      aria-label="Mini player"
      style={{ transform: `translateX(${dragX}px)` }}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
    >
      <button
        type="button"
        className="miniplayer__body"
        onClick={() => navigate('/')}
        aria-label={`Open player for ${track.title}`}
      >
        <span className="miniplayer__art">
          {track.thumbnailUrl !== null ? (
            <img src={track.thumbnailUrl} alt="" loading="lazy" />
          ) : (
            <Icon name="playArrow" size={24} className="miniplayer__art-fallback" />
          )}
        </span>
        <span className="miniplayer__titles">
          <span className="miniplayer__title">{track.title}</span>
          <span className="miniplayer__artist">{track.artist}</span>
        </span>
      </button>

      {session !== null && (
        <button
          type="button"
          className={`miniplayer__like ${liked ? 'miniplayer__like--on' : ''}`}
          aria-label={liked ? `Unlike ${track.title}` : `Like ${track.title}`}
          aria-pressed={liked}
          onClick={() => {
            void toggleLike({
              videoId: track.id,
              title: track.title,
              artist: track.artist,
              album: track.album,
              durationSec: track.durationMs / 1000,
              thumbnailUrl: track.thumbnailUrl,
            });
          }}
        >
          <Icon name={liked ? 'favorite' : 'favoriteBorder'} size={22} />
        </button>
      )}

      <button
        type="button"
        className="miniplayer__playpause"
        aria-label={playing ? 'Pause' : 'Play'}
        onClick={playOrToggle}
      >
        {buffering ? (
          <span className="miniplayer__spinner" aria-label="buffering" />
        ) : (
          <Icon name={playing ? 'pause' : 'playArrow'} size={28} />
        )}
      </button>

      <button
        type="button"
        className="miniplayer__next"
        aria-label="Next track"
        onClick={() => send({ type: 'next' })}
      >
        <Icon name="skipNext" size={24} />
      </button>

      {/* Swipe peek overlay (the Android parallax row) */}
      {dragX !== 0 && peekTitle !== null && (
        <span
          className="miniplayer__peek"
          aria-hidden="true"
          style={{ opacity: peekAlpha, transform: `translateX(${-dragX * 0.85}px)` }}
        >
          <Icon name={peekLeft ? 'skipPrevious' : 'skipNext'} size={18} />
          <span className="miniplayer__peek-title">{peekTitle}</span>
        </span>
      )}

      {/* 2px micro progress line (flush bottom edge) */}
      <span className="miniplayer__progress" aria-hidden="true">
        <span className="miniplayer__progress-fill" style={{ width: `${progress * 100}%` }} />
      </span>
    </div>
  );
}
