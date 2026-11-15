import type { Track } from '../lib/engine';
import { TrackRow } from './TrackRow';
import type { RowPlayback } from './TrackRow';

export type SearchPhase = 'idle' | 'loading' | 'error' | 'results';

export interface SearchPanelProps {
  phase: SearchPhase;
  query: string;
  onQueryChange: (value: string) => void;
  onSubmit: () => void;
  error: string | null;
  results: Track[];
  /** Per-row playback state resolver (snapshot-derived — one truth). */
  playback: (track: Track) => RowPlayback | undefined;
  onPlay: (track: Track) => void;
  onQueue?: (track: Track) => void;
  onLike?: (track: Track) => void;
  likedIds?: Set<string>;
  currentId?: string | null;
}

const SKELETON_ROWS = 6;
const PROOF_QUERY_HINT = 'Blinding Lights The Weeknd';

export function SearchPanel(props: SearchPanelProps): JSX.Element {
  const {
    phase,
    query,
    onQueryChange,
    onSubmit,
    error,
    results,
    playback,
    onPlay,
    onQueue,
    onLike,
    likedIds,
    currentId,
  } = props;

  return (
    <section className="card search" aria-label="YouTube Music search">
      <form
        className="search-form"
        aria-label="Search YouTube Music"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit();
        }}
      >
        <label className="field-label field-label-tight" htmlFor="search-input">
          Search YouTube Music
        </label>
        <div className="field-row">
          <input
            id="search-input"
            name="q"
            type="search"
            autoComplete="off"
            placeholder="Blinding Lights The Weeknd"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
          />
          <button
            type="submit"
            className="button--primary"
            disabled={phase === 'loading' || query.trim() === ''}
          >
            Search
          </button>
        </div>
      </form>

      {phase === 'idle' && (
        <p className="muted idle-hint">
          Idle — try &quot;{PROOF_QUERY_HINT}&quot;. Tap <strong>Play</strong> to resolve + queue
          through the engine (direct egress on mobile), <strong>Queue</strong> to append,{' '}
          <strong>♡</strong> to like (signed in).
        </p>
      )}

      {phase === 'loading' && (
        <div>
          <p className="sr-only" role="status">
            Searching…
          </p>
          <ul className="skeleton-list" aria-hidden="true">
            {Array.from({ length: SKELETON_ROWS }, (_, index) => (
              <li className="skeleton" key={index} />
            ))}
          </ul>
        </div>
      )}

      {phase === 'error' && error !== null && (
        <div className="error-box" role="alert">
          <span>Search failed: {error}</span>
          <button type="button" onClick={onSubmit}>
            Retry
          </button>
        </div>
      )}

      {phase === 'results' &&
        (results.length > 0 ? (
          <div>
            <h3 className="results-heading">Results ({results.length})</h3>
            <p className="sr-only" role="status">
              {results.length} results found
            </p>
            <ul className="results" aria-label="Search results">
              {results.map((track) => (
                <TrackRow
                  key={track.videoId}
                  track={track}
                  playback={playback(track)}
                  onPlay={onPlay}
                  onQueue={onQueue}
                  onLike={onLike}
                  liked={likedIds?.has(track.videoId)}
                  isCurrent={currentId === track.videoId}
                />
              ))}
            </ul>
          </div>
        ) : (
          <p className="muted">No results — the engine returned an empty list.</p>
        ))}
    </section>
  );
}
