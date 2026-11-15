/**
 * components/TrackListItem.tsx — P1 port of
 * app/…/ui/components/TrackListItem.kt, carrying the queue extensions of
 * yt/YtQueueTrackItem.kt (the web keeps ONE row chrome — both Kotlin rows
 * are 56dp art+title rows; §9.3's "one implementation, every platform").
 *
 * The shared TrackListItem surface: 56px row height, 48px art (4px
 * thumbnailSmall radius), the current-track art overlay + NowPlaying
 * equalizer (bars freeze when transport is paused), title/artist column,
 * options button. Queue extensions (from YtQueueTrackItem): a leading
 * position badge, dimmed history rows, a GPU transform (translateY)
 * driven by the parent's drag offset, and a drag handle for reorder —
 * the handle is the detectDragGesturesAfterLongPress equivalent: an
 * explicit grab affordance, so no long-press timer is needed.
 *
 * Web-adapted, documented: the BPM / pending-analysis / Streamify-source
 * chips have no web metadata source yet (the taste engine is a later
 * phase) — they are omitted rather than fabricated.
 */

import { useRef } from 'react';
import { Icon } from './icons';
import type { TrackRow } from '../lib/wasmEngine';

export interface TrackListItemProps {
  track: TrackRow;
  onClick: () => void;
  onOptionsClick?: () => void;
  /** The engine's current row (art overlay + equalizer). */
  isCurrentTrack?: boolean;
  /** Whether transport is actively playing (equalizer animation). */
  isActuallyPlaying?: boolean;
  isBuffering?: boolean;
  /** Queue position badge ("#01" — the Kotlin QueueScreen history slots). */
  leadingBadge?: string | null;
  /** History rows render at 0.62 alpha (Kotlin dimmed). */
  dimmed?: boolean;
  /**
   * Parent-owned GPU translation while dragging (the YtQueueTrackItem
   * dragOffset param): the host accumulates finger movement and RESETS
   * it to 0 when a boundary crossing commits the move — the row snaps
   * into its new slot exactly as on Android.
   */
  dragOffset?: number;
  /** Reorder (YtQueueTrackItem drag handle): grab → move(deltaY px) → drop. */
  showDragHandle?: boolean;
  onDragStart?: () => void;
  onDragMove?: (deltaY: number) => void;
  onDragEnd?: () => void;
}

/** The 3-bar now-playing equalizer (Kotlin NowPlayingIndicator). */
function NowPlayingIndicator({ isActive }: { isActive: boolean }): JSX.Element {
  return (
    <span className={`nowplaying ${isActive ? 'nowplaying--live' : ''}`} aria-hidden="true">
      <i className="nowplaying__bar" />
      <i className="nowplaying__bar" />
      <i className="nowplaying__bar" />
    </span>
  );
}

export function TrackListItem(props: TrackListItemProps): JSX.Element {
  const {
    track,
    onClick,
    onOptionsClick,
    isCurrentTrack = false,
    isActuallyPlaying = true,
    isBuffering = false,
    leadingBadge = null,
    dimmed = false,
    dragOffset = 0,
    showDragHandle = false,
    onDragStart,
    onDragMove,
    onDragEnd,
  } = props;

  // Only the pointer grab is local; the visual offset is parent-owned.
  const handleDrag = useRef<{ y0: number; active: boolean } | null>(null);

  const hasDrag = showDragHandle && onDragStart !== undefined && onDragMove !== undefined && onDragEnd !== undefined;

  const onHandlePointerDown = (e: React.PointerEvent<HTMLButtonElement>): void => {
    if (e.button !== 0) return;
    e.stopPropagation(); // the grab must not fall through to the row tap
    e.currentTarget.setPointerCapture(e.pointerId);
    handleDrag.current = { y0: e.clientY, active: true };
    onDragStart?.();
  };
  const onHandlePointerMove = (e: React.PointerEvent<HTMLButtonElement>): void => {
    const d = handleDrag.current;
    if (d === null || !d.active) return;
    onDragMove?.(e.clientY - d.y0);
    // Re-arm the capture on the next frame: a committed boundary crossing
    // re-orders the DOM nodes at the next commit, and that tree move
    // releases the pointer capture (Chrome drops it on mutation) —
    // without this, multi-crossing drags die at the first boundary.
    const el = e.currentTarget;
    requestAnimationFrame(() => {
      if (handleDrag.current !== null) {
        try {
          el.setPointerCapture(e.pointerId);
        } catch {
          /* the pointer went away mid-frame */
        }
      }
    });
  };
  const onHandlePointerUp = (): void => {
    if (handleDrag.current === null) return;
    handleDrag.current = null;
    onDragEnd?.();
  };

  return (
    <li
      className={`tracklistitem ${isCurrentTrack ? 'tracklistitem--current' : ''} ${dimmed ? 'tracklistitem--dimmed' : ''}`}
      style={dragOffset !== 0 ? { transform: `translateY(${dragOffset}px)` } : undefined}
    >
      <button type="button" className="tracklistitem__main" onClick={onClick}>
        {leadingBadge !== null && <span className="tracklistitem__badge">{leadingBadge}</span>}
        <span className="tracklistitem__art">
          {track.thumbnailUrl !== null ? (
            <img src={track.thumbnailUrl} alt="" loading="lazy" width={48} height={48} />
          ) : (
            <span className="tracklistitem__art-fallback" aria-hidden="true">
              <Icon name="musicNote" size={20} />
            </span>
          )}
          {isCurrentTrack && (
            <span className="tracklistitem__overlay">
              {isBuffering ? (
                <span className="fsheet__spinner fsheet__spinner--sm" role="img" aria-label="Buffering" />
              ) : (
                <NowPlayingIndicator isActive={isActuallyPlaying} />
              )}
            </span>
          )}
        </span>
        <span className="tracklistitem__titles">
          <span className="tracklistitem__title">{track.title}</span>
          <span className="tracklistitem__artist">
            {track.artist}
            {track.album !== '' && track.album !== 'Streamify' ? ` • ${track.album}` : ''}
          </span>
        </span>
      </button>
      {onOptionsClick !== undefined && (
        <button type="button" className="tracklistitem__options" aria-label="More options" onClick={onOptionsClick}>
          <Icon name="moreVert" size={18} />
        </button>
      )}
      {hasDrag && (
        <button
          type="button"
          className="tracklistitem__drag"
          aria-label={`Reorder ${track.title}`}
          onPointerDown={onHandlePointerDown}
          onPointerMove={onHandlePointerMove}
          onPointerUp={onHandlePointerUp}
          onPointerCancel={onHandlePointerUp}
        >
          <Icon name="dragHandle" size={20} />
        </button>
      )}
    </li>
  );
}
