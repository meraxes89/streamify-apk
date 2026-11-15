/**
 * screens/FullPlayerSheetPanes.tsx — P1 port of
 * app/…/ui/screens/FullPlayerSheetPanes.kt (the three tab panes:
 * LandscapeQueuePane / LandscapeLyricsPane / LandscapeRelatedPane).
 *
 * The queue pane ports the Kotlin slicing discipline verbatim: HISTORY
 * (rows before the cursor, 0.55 alpha), NOW PLAYING (the cursor row with
 * the equalizer overlay), UP NEXT (strictly-after rows). Rows are the
 * shared TrackListItem chrome; pane rows carry no drag handles (the
 * Kotlin pane hides the handle without reorder callbacks) — the
 * QueueScreen route owns drag-reorder.
 *
 * The lyrics pane is the engine activation site for the in-sheet surface:
 * useLyrics() (real resolver calls only) + the per-surface PLL clock +
 * FluidSyllableText karaoke rows + the 35% focal auto-scroll.
 *
 * The related pane renders its honest empty state: the web has no radio
 * broker yet (the taste engine is a later phase) — never a fabricated
 * list.
 */

import { useMemo, useRef } from 'react';
import { FluidSyllableText } from '../components/FluidSyllableText';
import { YtLyricsHeader } from '../components/YtLyricsHeader';
import { Icon } from '../components/icons';
import { playedRows, upNextRows, usePlayerStore } from '../lib/playerStore';
import { useLyrics, useLyricAutoScroll } from '../lib/useLyrics';
import type { LyricsSource } from '../lyrics';

/** The resolver lane → the attribution label (the Musixmatch slot on web). */
export function sourceLabel(source: LyricsSource | null): string {
  switch (source) {
    case 'ytm-pinned':
      return 'YouTube Music (pinned video)';
    case 'ytm-search':
      return 'YouTube Music';
    case 'lrclib':
      return 'LRCLIB';
    case 'synthesized':
      return 'LRCLIB (evenly timed)';
    default:
      return '—';
  }
}

/** LandscapePlayerTab — the landscape dual-pane tab vocabulary. */
export type LandscapePlayerTab = 'UP_NEXT' | 'LYRICS' | 'RELATED';

// ---------------------------------------------------------------------------
// LandscapeQueuePane
// ---------------------------------------------------------------------------

export interface LandscapeQueuePaneProps {
  onTrackClick: (trackId: string) => void;
}

export function LandscapeQueuePane(props: LandscapeQueuePaneProps): JSX.Element {
  const { onTrackClick } = props;
  const queue = usePlayerStore((s) => s.queue);
  const snapshot = usePlayerStore((s) => s.snapshot);
  const isPlaying = snapshot?.status === 'Playing';

  const cursor = queue.cursor;
  const played = useMemo(() => playedRows(queue), [queue]);
  const upNext = useMemo(() => upNextRows(queue), [queue]);
  const nowPlaying = useMemo(() => (cursor !== null ? (queue.rows[cursor] ?? null) : null), [queue, cursor]);

  return (
    <ul className="qpane" role="list">
      {played.length > 0 && (
        <>
          <li className="qpane__label qpane__label--history">HISTORY ({played.length})</li>
          {played.map((row) => (
            <QueueRow
              key={`hist-${row.track.id}`}
              trackId={row.track.id}
              title={row.track.title}
              artist={row.track.artist}
              album={row.track.album}
              thumbnailUrl={row.track.thumbnailUrl}
              isPlaying={false}
              dimmed
              onClick={() => onTrackClick(row.track.id)}
            />
          ))}
          <li className="qpane__spacer" aria-hidden="true" />
        </>
      )}

      {nowPlaying !== null && (
        <>
          <li className="qpane__label qpane__label--now">NOW PLAYING</li>
          <QueueRow
            key={`now-${nowPlaying.track.id}`}
            trackId={nowPlaying.track.id}
            title={nowPlaying.track.title}
            artist={nowPlaying.track.artist}
            album={nowPlaying.track.album}
            thumbnailUrl={nowPlaying.track.thumbnailUrl}
            isPlaying
            isActuallyPlaying={isPlaying}
            onClick={() => {
              /* already playing — the Kotlin no-op row */
            }}
          />
          <li className="qpane__spacer" aria-hidden="true" />
        </>
      )}

      {upNext.length > 0 ? (
        <>
          <li className="qpane__label">UP NEXT ({upNext.length})</li>
          {upNext.map((row) => (
            <QueueRow
              key={`next-${row.track.id}`}
              trackId={row.track.id}
              title={row.track.title}
              artist={row.track.artist}
              album={row.track.album}
              thumbnailUrl={row.track.thumbnailUrl}
              isPlaying={false}
              onClick={() => onTrackClick(row.track.id)}
            />
          ))}
        </>
      ) : (
        <li className="qpane__empty">End of queue. Queue more from search.</li>
      )}
    </ul>
  );
}

/**
 * The pane row: TrackListItem's shape inlined as a light wrapper (the
 * pane's rows have no options button and no handle — the Kotlin pane
 * passes both as dead slots).
 */
function QueueRow(props: {
  trackId: string;
  title: string;
  artist: string;
  album: string;
  thumbnailUrl: string | null;
  isPlaying: boolean;
  isActuallyPlaying?: boolean;
  dimmed?: boolean;
  onClick: () => void;
}): JSX.Element {
  const { isPlaying, isActuallyPlaying = true, dimmed = false, onClick } = props;
  return (
    <li
      className={`qpane__row ${isPlaying ? 'qpane__row--playing' : ''} ${dimmed ? 'qpane__row--dimmed' : ''}`}
    >
      <button type="button" className="qpane__main" onClick={onClick}>
        <span className="qpane__art">
          {props.thumbnailUrl !== null ? (
            <img src={props.thumbnailUrl} alt="" loading="lazy" width={48} height={48} />
          ) : (
            <Icon name="musicNote" size={20} />
          )}
          {isPlaying && (
            <span className={`qpane__eq ${isActuallyPlaying ? 'qpane__eq--live' : ''}`} aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
          )}
        </span>
        <span className="qpane__titles">
          <span className="qpane__title">{props.title}</span>
          <span className="qpane__artist">
            {props.artist}
            {props.album !== '' && props.album !== 'Streamify' ? ` • ${props.album}` : ''}
          </span>
        </span>
      </button>
    </li>
  );
}

// ---------------------------------------------------------------------------
// LandscapeLyricsPane
// ---------------------------------------------------------------------------

export interface LandscapeLyricsPaneProps {
  /** Seek target in absolute ms (the sheet maps it through the transport). */
  onSeek: (positionMs: number) => void;
}

export function LandscapeLyricsPane(props: LandscapeLyricsPaneProps): JSX.Element {
  const { onSeek } = props;
  const lyrics = useLyrics();
  const listRef = useRef<HTMLDivElement | null>(null);
  useLyricAutoScroll(listRef, lyrics.activeLineIndex, lyrics.isSynced && lyrics.phase === 'ready');

  return (
    <div className="lpane">
      <YtLyricsHeader
        source={sourceLabel(lyrics.source)}
        isSynced={lyrics.isSynced}
        userOffsetMs={lyrics.userOffsetMs}
        onAdjustOffset={lyrics.adjustOffset}
        onResetOffset={lyrics.resetOffset}
        onClose={null}
      />
      {lyrics.phase === 'loading' || lyrics.phase === 'idle' ? (
        <div className="lpane__center" role="status">
          <span className="fsheet__spinner" aria-label="Loading lyrics" />
        </div>
      ) : lyrics.phase === 'missing' || lyrics.lines.length === 0 ? (
        <div className="lpane__center">
          <p className="lpane__hint">No synchronized lyrics found for this track.</p>
        </div>
      ) : !lyrics.isSynced ? (
        <div className="lpane__static" role="list">
          {lyrics.lines.map((line, i) => (
            <p className="lpane__line" role="listitem" key={`unsynced-${i}-${line.timeMs}`}>
              {line.text}
            </p>
          ))}
        </div>
      ) : (
        <div className="lpane__scroll" ref={listRef}>
          {lyrics.lines.map((line, i) => {
            const nextStart = i + 1 < lyrics.lines.length ? lyrics.lines[i + 1].timeMs : line.timeMs + 3500;
            const isActive = i === lyrics.activeLineIndex;
            const isPast = i < lyrics.activeLineIndex;
            return (
              <div className="lpane__lyricrow" data-lyric-index={i} key={`${i}-${line.timeMs}`}>
                <FluidSyllableText
                  text={line.text}
                  lineStartMs={line.timeMs}
                  lineEndMs={nextStart}
                  syllables={line.syllables}
                  playbackMsProvider={() => lyrics.clock.interpolatedPosMs}
                  isActive={isActive}
                  isPast={isPast}
                  onClick={() => onSeek(line.timeMs)}
                />
              </div>
            );
          })}
          <p className="lpane__attribution">
            Lyrics by {sourceLabel(lyrics.source)} • Real-Time Fluid Sync
          </p>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// LandscapeRelatedPane
// ---------------------------------------------------------------------------

export function LandscapeRelatedPane(): JSX.Element {
  // No web radio broker yet (the taste engine ships in a later phase):
  // the honest empty state, never a fabricated list.
  return (
    <div className="lpane__center lpane__center--tall">
      <p className="lpane__hint">No related tracks discovered yet.</p>
      <p className="lpane__hint lpane__hint--sub">Radio builds arrive with the taste engine.</p>
    </div>
  );
}
