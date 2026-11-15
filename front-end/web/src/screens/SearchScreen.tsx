/**
 * screens/SearchScreen.tsx — P2 browse port (ui/screens/SearchScreen.kt).
 *
 * The full search UX over the G4 session lane:
 *   1. the omnibar (rounded search field, clear button);
 *   2. the category filter chips (query-active only);
 *   3. the suggestions rail with 1-tap quick-play;
 *   4. STATE A (blank query) — the Explore & Browse hub: recent searches
 *      (Clear action) + the Moods & Genres card grid;
 *   5. STATE B — results: "From your library" (cloud likes matching,
 *      3-row preview), artist rows (→ /artist/:name), playlist rows
 *      (→ re-search the shelf, the Android behavior), track rows with
 *      play-on-click + the context-menu gestures (right-click/long-press).
 *
 * Pillar 1 (deterministic unwinding): clearing the query resets to the
 * hub (the BackHandler lowering); a 150 ms debounce cancels rapid
 * keystroke searches (the LaunchedEffect delay).
 */

import { useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { TrackRow } from '../components/TrackRow';
import { CategoryCard } from '../components/CategoryCard';
import { TrackCoverArt } from '../components/TrackCoverArt';
import { EmptyStateView } from '../components/EmptyStateView';
import { GlobalTrackContextMenuHost, trackMenuGestures } from '../components/ContextMenuSheet';
import { useSearchStore, matchLibraryTracks } from '../lib/searchStore';
import type { SearchFilterLabel } from '../lib/searchStore';
import { useSessionStore } from '../lib/sessionStore';
import { useTrackPlayer } from '../lib/trackPlayer';
import { toPlayable } from '../lib/libraryStore';
import { toast } from '../lib/toastStore';
import type { Track } from '../lib/engine';

/** The genre grid (SearchScreen.kt's genres list, verbatim colors). */
const GENRES: Array<{ title: string; color: string }> = [
  { title: 'Chill & Lofi', color: '#3EA6FF' },
  { title: 'Energy & Gym', color: '#FF0000' },
  { title: 'Deep Focus', color: '#8D67AB' },
  { title: 'Indie & Rock', color: '#E91E63' },
  { title: 'Hip-Hop', color: '#FF9800' },
  { title: 'Electronic', color: '#00E676' },
  { title: 'Pop', color: '#FF4081' },
  { title: 'Acoustic & Folk', color: '#8D6E63' },
];

const FILTERS: SearchFilterLabel[] = ['All', 'Songs', 'Artists', 'Playlists'];

export function SearchScreen(): JSX.Element {
  const navigate = useNavigate();
  const query = useSearchStore((s) => s.query);
  const phase = useSearchStore((s) => s.phase);
  const filter = useSearchStore((s) => s.filter);
  const results = useSearchStore((s) => s.results);
  const artists = useSearchStore((s) => s.artists);
  const playlists = useSearchStore((s) => s.playlists);
  const suggestions = useSearchStore((s) => s.suggestions);
  const history = useSearchStore((s) => s.history);
  const error = useSearchStore((s) => s.error);
  const setQuery = useSearchStore((s) => s.setQuery);
  const setFilter = useSearchStore((s) => s.setFilter);
  const search = useSearchStore((s) => s.search);
  const updateSuggestions = useSearchStore((s) => s.updateSuggestions);
  const clearHistory = useSearchStore((s) => s.clearHistory);
  const reset = useSearchStore((s) => s.reset);

  const session = useSessionStore((s) => s.session);
  const likes = useSessionStore((s) => s.likes);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);
  const player = useTrackPlayer();
  const inputRef = useRef<HTMLInputElement | null>(null);

  // The 150 ms keystroke debounce (LaunchedEffect(query) — the filter is
  // a client-side section filter here, so it never re-fires the search).
  useEffect(() => {
    if (query.trim() === '') {
      updateSuggestions('');
      return;
    }
    const timer = window.setTimeout(() => {
      void search(query);
      updateSuggestions(query);
    }, 150);
    return () => window.clearTimeout(timer);
  }, [query, search, updateSuggestions]);

  const queryActive = query.trim() !== '';

  // "From your library" — the local-matches preview (max 3, count kicker).
  const libraryMatches = useMemo(
    () => (session !== null ? matchLibraryTracks(query, likes) : []),
    [query, session, likes],
  );
  const libraryPreview = useMemo(() => libraryMatches.slice(0, 3), [libraryMatches]);

  const showLibrarySection =
    phase === 'results' && (filter === 'All' || filter === 'Songs') && libraryMatches.length > 0;

  const handlePlay = (track: Track): void => {
    void player.play(track);
  };

  const handleLike = (track: Track): void => {
    if (session === null) {
      toast('Sign in to save likes to your cloud library', 'error');
      return;
    }
    void toggleLike({
      videoId: track.videoId,
      title: track.title,
      artist: track.artist,
      album: track.album ?? null,
      durationSec: track.durationSec ?? null,
      thumbnailUrl: track.thumbnailUrl ?? null,
    }).then((ok) => {
      if (ok) toast(`♥ ${track.title}`);
    });
  };

  const openSuggestion = (value: string): void => {
    setQuery(value);
    void search(value);
    useSearchStore.setState({ suggestions: [] });
  };

  return (
    <div className="searchscreen">
      {/* 1. Omnibar */}
      <div className="searchscreen__barrow">
        <form
          className="omnibar"
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            void search(query);
            inputRef.current?.blur();
          }}
        >
          <Icon name="search" size={20} className="omnibar__icon" />
          <input
            ref={inputRef}
            className="omnibar__input"
            type="search"
            name="q"
            autoComplete="off"
            placeholder="Search YouTube Music"
            aria-label="Search YouTube Music"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          {query !== '' && (
            <button
              type="button"
              className="omnibar__clear"
              aria-label="Clear search"
              onClick={() => {
                reset();
                inputRef.current?.focus();
              }}
            >
              &times;
            </button>
          )}
        </form>
      </div>

      {/* 2. Filter chips (query-active only) */}
      {queryActive && (
        <div className="chipsrail" role="tablist" aria-label="Result filters">
          {FILTERS.map((label) => (
            <button
              key={label}
              type="button"
              role="tab"
              aria-selected={filter === label}
              className={`chipfilter ${filter === label ? 'chipfilter--on' : ''}`}
              onClick={() => setFilter(label)}
            >
              {label}
            </button>
          ))}
        </div>
      )}

      {/* 3. Suggestions rail */}
      {queryActive && suggestions.length > 0 && (
        <div className="suggrail" aria-label="Search suggestions">
          {suggestions.map((suggestion) => (
            <span className="suggchip" key={suggestion}>
              <button
                type="button"
                className="suggchip__text"
                onClick={() => openSuggestion(suggestion)}
              >
                <Icon name="search" size={14} className="suggchip__icon" />
                {suggestion}
              </button>
              <button
                type="button"
                className="suggchip__play"
                aria-label={`Quick play ${suggestion}`}
                onClick={() => openSuggestion(suggestion)}
              >
                <Icon name="playArrow" size={16} />
              </button>
            </span>
          ))}
        </div>
      )}

      {/* 4/5. The main viewport */}
      {!queryActive ? (
        // STATE A — Explore & Browse hub
        <div className="searchscreen__hub">
          {history.length > 0 && (
            <section className="searchhub__recent" aria-label="Recent searches">
              <div className="searchhub__recenthead">
                <h2 className="searchhub__recenttitle">Recent searches</h2>
                <button
                  type="button"
                  className="searchhub__clear"
                  onClick={clearHistory}
                >
                  Clear
                </button>
              </div>
              <ul className="searchhub__history">
                {history.slice(0, 5).map((pastQuery) => (
                  <li key={pastQuery}>
                    <button
                      type="button"
                      className="searchhub__historyrow"
                      onClick={() => {
                        setQuery(pastQuery);
                        void search(pastQuery);
                      }}
                    >
                      <Icon name="explore" size={20} className="searchhub__historyicon" />
                      <span>{pastQuery}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section aria-label="Moods and genres">
            <header className="secthead">
              <span className="secthead__kicker">Explore Categories</span>
              <h2 className="secthead__title">Moods &amp; Genres</h2>
            </header>
            <div className="genregrid">
              {GENRES.map((genre) => (
                <CategoryCard
                  key={genre.title}
                  title={genre.title}
                  accentColor={genre.color}
                  onClick={() => {
                    setQuery(genre.title);
                    void search(genre.title);
                  }}
                />
              ))}
            </div>
          </section>
        </div>
      ) : phase === 'loading' ? (
        <div className="searchscreen__loading" role="status" aria-live="polite">
          <span className="spinner" aria-hidden="true" />
          <span className="sr-only">Searching…</span>
        </div>
      ) : phase === 'error' && error !== null ? (
        <EmptyStateView
          title="Search failed"
          subtitle={error}
          actionText="Retry"
          onActionClick={() => void search(query)}
        />
      ) : (
        <div className="searchscreen__results">
          {/* From your library (compact preview) */}
          {showLibrarySection && libraryPreview.length > 0 && (
            <section aria-label="Matches from your library">
              <header className="secthead">
                <span className="secthead__kicker">
                  {libraryMatches.length > 3
                    ? `${libraryMatches.length} Matches • Showing top 3`
                    : `${libraryMatches.length} Tracks Found`}
                </span>
                <h2 className="secthead__title">From your library</h2>
              </header>
              <ul className="results" aria-label="Library matches">
                {libraryPreview.map((track) => (
                  <TrackRow
                    key={`local_${track.id}`}
                    track={{
                      videoId: track.streamUrl ?? '',
                      title: track.title,
                      artist: track.artist,
                      album: track.album ?? undefined,
                      durationSec: track.durationSec ?? undefined,
                      thumbnailUrl: track.coverUrl ?? undefined,
                    }}
                    playback={player.playbackFor(toPlayable(track))}
                    onPlay={(t) => void player.play(t)}
                    onQueue={(t) => player.queue(t)}
                    onLike={session !== null ? handleLike : undefined}
                    liked={likedVideoIds.has(track.streamUrl ?? '')}
                    isCurrent={player.playbackFor(toPlayable(track))?.status === 'playing'}
                    gestures={trackMenuGestures(toPlayable(track), 'SEARCH')}
                  />
                ))}
              </ul>
            </section>
          )}

          {/* Artist results (channels → /artist/:name) */}
          {phase === 'results' && (filter === 'All' || filter === 'Artists') && artists.length > 0 && (
            <section aria-label="Artist results">
              <header className="secthead">
                <span className="secthead__kicker">Artists</span>
                <h2 className="secthead__title">Artists</h2>
              </header>
              <ul className="artistresults">
                {artists.map((artist) => (
                  <li key={artist.id}>
                    <button
                      type="button"
                      className="artistresult"
                      onClick={() => navigate(`/artist/${encodeURIComponent(artist.name)}`)}
                    >
                      <TrackCoverArt
                        coverArtPath={artist.thumbnailUrl}
                        title={artist.name}
                        artist=""
                        sizePx={54}
                        radiusPx={999}
                      />
                      <span className="artistresult__body">
                        <span className="artistresult__name">{artist.name}</span>
                        <span className="artistresult__sub">Artist • Tap to explore songs</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* Playlist / album results (re-search the shelf — Android behavior) */}
          {phase === 'results' && (filter === 'All' || filter === 'Playlists') && playlists.length > 0 && (
            <section aria-label="Playlist results">
              <header className="secthead">
                <span className="secthead__kicker">Featured &amp; Community Playlists</span>
                <h2 className="secthead__title">Albums &amp; Playlists</h2>
              </header>
              <ul className="plresults">
                {playlists.map((playlist) => (
                  <li key={playlist.id}>
                    <button
                      type="button"
                      className="plresult"
                      onClick={() => {
                        const shelfQuery = `${playlist.title} ${playlist.channelName}`.trim();
                        setQuery(shelfQuery);
                        setFilter('Songs');
                        void search(shelfQuery);
                      }}
                    >
                      <TrackCoverArt
                        coverArtPath={playlist.thumbnailUrl}
                        title={playlist.title}
                        artist={playlist.channelName}
                        sizePx={54}
                        radiusPx={6}
                      />
                      <span className="plresult__body">
                        <span className="plresult__name">{playlist.title}</span>
                        <span className="plresult__sub">Playlist • {playlist.channelName}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* Online track results */}
          {phase === 'results' && (filter === 'All' || filter === 'Songs') && results.length > 0 && (
            <section aria-label="Online results">
              <header className="secthead">
                <span className="secthead__kicker">Instant High-Fidelity Stream</span>
                <h2 className="secthead__title">Online Results</h2>
              </header>
              <ul className="results" aria-label="Search results">
                {results.map((track) => (
                  <TrackRow
                    key={track.videoId}
                    track={track}
                    playback={player.playbackFor(track)}
                    onPlay={handlePlay}
                    onQueue={(t) => player.queue(t)}
                    onLike={session !== null ? handleLike : undefined}
                    liked={likedVideoIds.has(track.videoId)}
                    isCurrent={player.playbackFor(track)?.status === 'playing'}
                    gestures={trackMenuGestures(track, 'SEARCH')}
                  />
                ))}
              </ul>
            </section>
          )}

          {/* Empty result state */}
          {phase === 'results' &&
            results.length === 0 &&
            artists.length === 0 &&
            playlists.length === 0 &&
            libraryMatches.length === 0 && (
              <div className="searchscreen__empty">
                <h3 className="searchscreen__emptytitle">No results found for &ldquo;{query}&rdquo;</h3>
                <p className="muted">Try searching for a different song or artist</p>
              </div>
            )}
        </div>
      )}

      <GlobalTrackContextMenuHost />
    </div>
  );
}
