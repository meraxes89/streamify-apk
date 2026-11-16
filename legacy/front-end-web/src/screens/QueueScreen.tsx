/**
 * screens/QueueScreen.tsx — P1 port of
 * app/…/ui/screens/QueueScreen.kt (the Up-next route screen).
 *
 * The three slices in the Kotlin discipline — HISTORY (most-recent-first,
 * dimmed, position badges, collapse/expand), NOW PLAYING (the equalizer
 * row), UP NEXT (reorderable) — over the store's PROJECTED queue.
 *
 * Reorder (the P1 web adaptation): the engine has no move verb, so the
 * drag reorders the projection instantly (every surface re-renders) and
 * the engine queue is rebuilt at the next track boundary via
 * playerStore.reorderQueue + the port's onTrackEndedHook — silent by
 * construction. The drag math is the Kotlin one verbatim: the row
 * translates with the finger; crossing a 56px row boundary moves the
 * row (target = index + round(offset / 56px)) and re-anchors the offset
 * to zero; the drop is a no-op (the move already committed).
 *
 * Web-adapted, documented omissions (no backing verb on the web engine —
 * never a dead control): the Autoplay switch, the Smart Shuffle surface,
 * the radio build telemetry, the clear-history button, and the
 * long-press context menu.
 */

import { useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { TrackListItem } from '../components/TrackListItem';
import { usePlayerStore } from '../lib/playerStore';
// P2-B: the queue state machine is the truth — order, history, up next.
import { playedRows, upNextRows, useQueueStore } from '../lib/playback/queueStore';
import { toast } from '../lib/toastStore';

/** The row height for drag-index math (Kotlin: 56.dp). */
const ROW_H = 56;
/** Visible history rows before expansion (Kotlin: take(3)). */
const HISTORY_PREVIEW = 3;

export interface QueueScreenProps {
  onClose?: () => void;
}

export function QueueScreen(props: QueueScreenProps): JSX.Element {
  const { onClose } = props;
  const navigate = useNavigate();
  const queue = useQueueStore((s) => s.queue);
  const jumpToRow = useQueueStore((s) => s.jumpToRow);
  const reorderUpNext = useQueueStore((s) => s.reorderUpNext);
  const clearQueue = useQueueStore((s) => s.clear);
  const snapshot = usePlayerStore((s) => s.snapshot);

  const isPlaying = snapshot?.status === 'Playing';
  const cursor = queue.cursor;

  // The shared slice helpers (null-cursor = nothing selected yet: the
  // whole queue is up next — the QueueList.kt derivations).
  const played = useMemo(() => playedRows(queue), [queue]);
  const upNext = useMemo(() => upNextRows(queue), [queue]);
  const nowPlaying = useMemo(() => (cursor !== null ? (queue.rows[cursor] ?? null) : null), [queue, cursor]);

  // -- history surface -----------------------------------------------------
  const [historyExpanded, setHistoryExpanded] = useState(false);
  const history = useMemo(() => played.slice().reverse(), [played]); // most-recent-first
  const visibleHistory = historyExpanded || history.length <= HISTORY_PREVIEW ? history : history.slice(0, HISTORY_PREVIEW);

  // -- drag reorder (the Kotlin state pair: index + offset, host-owned) ---
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dragMoved, setDragMoved] = useState(false);
  const dragOffsetRef = useRef(0);
  const [dragOffset, setDragOffset] = useState(0);

  const onDragStart = (): void => {
    dragOffsetRef.current = 0;
    setDragOffset(0);
    setDragIndex(null);
    setDragMoved(false);
  };

  const onDragMove = (index: number, deltaY: number): void => {
    dragOffsetRef.current += deltaY;
    setDragOffset(dragOffsetRef.current);
    if (dragIndex === null) setDragIndex(index);
    // The Kotlin math: crossing a row boundary moves + re-anchors.
    const rel = Math.round(dragOffsetRef.current / ROW_H);
    if (rel === 0) return;
    const from = index;
    const to = Math.min(upNext.length - 1, Math.max(0, index + rel));
    if (from === to) return;
    if (reorderUpNext(cursor !== null ? cursor + 1 + from : from, cursor !== null ? cursor + 1 + to : to)) {
      dragOffsetRef.current = 0; // re-anchor: the row snaps into its new slot
      setDragOffset(0);
      setDragIndex(to);
      setDragMoved(true);
    }
  };

  const onDragEnd = (): void => {
    if (dragMoved) {
      // The engine queue rebuilds at the next track boundary — say so.
      toast('Order saved — it applies when this track ends.');
    }
    setDragIndex(null);
    setDragOffset(0);
    dragOffsetRef.current = 0;
    setDragMoved(false);
  };

  /** Tap-to-play: the machine's jump (the lazy resolve + play shape). */
  const playAt = (id: string): void => {
    jumpToRow(id);
  };

  const close = (): void => {
    if (onClose !== undefined) onClose();
    else navigate(-1);
  };

  const hasQueueItems = upNext.length > 0 || played.length > 0;
  const sourceName = nowPlaying !== null ? `${nowPlaying.track.artist} queue` : 'Library Queue';

  return (
    <div className="queuescreen">
      <header className="queuescreen__head">
        <div className="queuescreen__head-lead">
          <button type="button" className="queuescreen__close" aria-label="Close queue" onClick={close}>
            <Icon name="close" size={24} />
          </button>
          <h1 className="queuescreen__title">Up next</h1>
        </div>
        <div className="queuescreen__head-actions">
          {hasQueueItems && (
            <button
              type="button"
              className="queuescreen__clear"
              aria-label="Clear queue"
              onClick={() => clearQueue()}
            >
              Clear
            </button>
          )}
        </div>
        <p className="queuescreen__source">Playing from {sourceName}</p>
      </header>

      <ul className="queuescreen__list" role="list">
        {played.length > 0 && (
          <>
            <li className="queuescreen__section">
              <span className="queuescreen__section-label">HISTORY ({played.length})</span>
              <button
                type="button"
                className="queuescreen__expand"
                aria-label={historyExpanded ? 'Collapse history' : 'Expand history'}
                aria-expanded={historyExpanded}
                onClick={() => setHistoryExpanded((v) => !v)}
              >
                <Icon name={historyExpanded ? 'expandLess' : 'expandMore'} size={20} />
              </button>
            </li>
            {visibleHistory.map((row, i) => {
              const absolutePosition = played.length - i; // queue slot #1..
              return (
                <TrackListItem
                  key={`played-${row.track.id}`}
                  track={row.track}
                  leadingBadge={`#${String(absolutePosition).padStart(2, '0')}`}
                  dimmed
                  onClick={() => playAt(row.track.id)}
                />
              );
            })}
            {history.length > HISTORY_PREVIEW && !historyExpanded && (
              <li>
                <button type="button" className="queuescreen__showall" onClick={() => setHistoryExpanded(true)}>
                  SHOW {history.length - HISTORY_PREVIEW} EARLIER
                </button>
              </li>
            )}
          </>
        )}

        {nowPlaying !== null && (
          <>
            <li className="queuescreen__section queuescreen__section--now">
              <span className="queuescreen__section-label queuescreen__section-label--now">NOW PLAYING</span>
            </li>
            <TrackListItem
              track={nowPlaying.track}
              isCurrentTrack
              isActuallyPlaying={isPlaying}
              isBuffering={snapshot?.status === 'Buffering'}
              onClick={() => {
                /* already playing — the Kotlin no-op row */
              }}
            />
          </>
        )}

        {upNext.length > 0 ? (
          <>
            <li className="queuescreen__section queuescreen__section--upnext">
              <span className="queuescreen__section-label">UP NEXT ({upNext.length})</span>
            </li>
            {upNext.map((row, i) => {
              const isDragged = dragIndex === i;
              return (
                <TrackListItem
                  key={`queue-${row.track.id}`}
                  track={row.track}
                  showDragHandle
                  dragOffset={isDragged ? dragOffset : 0}
                  onDragStart={onDragStart}
                  onDragMove={(dy) => onDragMove(i, dy)}
                  onDragEnd={onDragEnd}
                  onClick={() => playAt(row.track.id)}
                />
              );
            })}
          </>
        ) : (
          nowPlaying === null && (
            <li className="queuescreen__empty">
              <p>No songs in queue</p>
              <p className="queuescreen__empty-sub">Search a track and tap Play or Queue.</p>
            </li>
          )
        )}
      </ul>
    </div>
  );
}
