/**
 * components/QueueList.tsx — P1 port of
 * shared/…/components/QueueList.kt (the platform-agnostic queue list),
 * replacing the P0-era engine-queue surface per §9.3 ("bring them to the
 * Kotlin shape").
 *
 * DATA-SOURCE DISCIPLINE (the Kotlin one): the component is pure
 * presentation — it renders exactly the state it is given (the store's
 * PROJECTED queue rows lowered into QueueListState) and an honest empty
 * state when there is nothing queued. Rows are the shared TrackListItem
 * chrome; tapping a row reports the QUEUE INDEX, which the host lowers
 * onto its own play-at-index surface.
 *
 * Sections: "Now playing" (the cursor row, the equalizer overlay) and
 * "Up next" (strictly-after rows, queue-index badges) — the QueueList.kt
 * derivations; history is the QueueScreen's surface (not this list's).
 */

import { Icon } from './icons';
import { TrackListItem } from './TrackListItem';
import type { QueueRead } from '../lib/wasmEngine';

/** QueueListState — the Kotlin data class, store-shaped. */
export interface QueueListState {
  /** Queue rows in play order (the projection). */
  rows: QueueRead['rows'];
  /** Index of the playing row; -1 when nothing is active. */
  currentIndex: number;
  /** Whether the current row is actively playing (drives the equalizer). */
  isPlaying: boolean;
}

export const QUEUE_LIST_EMPTY: QueueListState = { rows: [], currentIndex: -1, isPlaying: false };

/** Builds the state from the store's queue read (the host lowering). */
export function queueListStateOf(queue: QueueRead, isPlaying: boolean): QueueListState {
  return {
    rows: queue.rows,
    currentIndex: queue.cursor ?? -1,
    isPlaying,
  };
}

export interface QueueListProps {
  state: QueueListState;
  /** Tap → the QUEUE INDEX (never the track — the Kotlin contract). */
  onTrackClick?: (queueIndex: number) => void;
  emptyStateText?: string;
}

export function QueueList(props: QueueListProps): JSX.Element {
  const { state, onTrackClick, emptyStateText } = props;

  if (state.rows.length === 0) {
    return (
      <section className="queuelist" aria-label="Play queue">
        <div className="queuelist__empty">
          <Icon name="queueMusic" size={28} />
          <p>{emptyStateText ?? 'Nothing queued — search a track and tap Play or Queue.'}</p>
        </div>
      </section>
    );
  }

  const current = state.currentIndex >= 0 ? state.rows[state.currentIndex] : undefined;
  const upNext = state.rows.filter((_r, i) => i > state.currentIndex);

  return (
    <section className="queuelist" aria-label="Play queue">
      {current !== undefined && (
        <>
          <QueueSectionLabel text="Now playing" />
          <ul className="queuelist__rows">
            <TrackListItem
              track={current.track}
              isCurrentTrack
              isActuallyPlaying={state.isPlaying}
              onClick={() => onTrackClick?.(state.currentIndex)}
            />
          </ul>
        </>
      )}
      {upNext.length > 0 && (
        <>
          <QueueSectionLabel text="Up next" />
          <ul className="queuelist__rows">
            {upNext.map((row) => (
              <TrackListItem
                key={`${row.index}-${row.track.id}`}
                track={row.track}
                onClick={() => onTrackClick?.(row.index)}
              />
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

function QueueSectionLabel({ text }: { text: string }): JSX.Element {
  return (
    <div className="queuelist__label">
      <Icon name="queueMusic" size={16} />
      <span>{text}</span>
    </div>
  );
}
