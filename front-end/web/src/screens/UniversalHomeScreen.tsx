/**
 * screens/UniversalHomeScreen.tsx — P2 browse port
 * (ui/screens/UniversalHomeScreen.kt).
 *
 * The YT-Music-style shelves feed: one LazyColumn of shelves (stable
 * keys), each a title + horizontal rail of 140px track cards (artwork,
 * title, artist — click plays through the engine, context menu on
 * right-click/long-press). Fed by lib/homeStore.ts (the HomeViewModel
 * port — Stage 1 local shelves render instantly, the cloud Trending
 * shelf lands progressively).
 *
 * The web HomeScreen.tsx (the P3a engine surface) stays untouched; this
 * feed mounts above it as the browse head of the home route.
 */

import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { TrackCard } from '../components/TrackCard';
import { EmptyStateView } from '../components/EmptyStateView';
import { GlobalTrackContextMenuHost, trackMenuGestures } from '../components/ContextMenuSheet';
import { useHomeStore, shelfTrackToPlayable } from '../lib/homeStore';
import { useTrackPlayer } from '../lib/trackPlayer';
import { usePlayerStore } from '../lib/playerStore';

export function UniversalHomeScreen(): JSX.Element {
  const navigate = useNavigate();
  const phase = useHomeStore((s) => s.phase);
  const shelves = useHomeStore((s) => s.shelves);
  const cloudLoading = useHomeStore((s) => s.cloudLoading);
  const error = useHomeStore((s) => s.error);
  const load = useHomeStore((s) => s.load);
  const player = useTrackPlayer();
  const currentId = usePlayerStore((s) => s.snapshot?.currentTrack?.id ?? null);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section className="unifeed" aria-label="Home feed">
      {phase === 'loading' && shelves.length === 0 && (
        // The YtHomeSkeleton port: shelf-shaped placeholders (title rail +
        // card rail) shimmer while the feed loads — the APK's Loading state.
        <div className="unifeed__skeleton" role="status" aria-label="Loading your feed">
          {[0, 1, 2].map((i) => (
            <div className="shelf" key={i} aria-hidden="true">
              <header className="shelf__head">
                <div className="sk sk--line" />
                <div className="sk sk--line sk--line--sub" />
              </header>
              <div className="shelf__rail">
                {Array.from({ length: 6 }, (_unused, j) => (
                  <div className="sk sk--card" key={j} />
                ))}
              </div>
            </div>
          ))}
          <span className="sr-only">Loading your feed…</span>
        </div>
      )}

      {phase === 'error' && shelves.length === 0 && error !== null && (
        <p className="unifeed__error" role="alert">{error}</p>
      )}

      {shelves.map((shelf) => (
        <div className="shelf" key={shelf.id}>
          <header className="shelf__head">
            <h2 className="shelf__title">{shelf.title}</h2>
            {shelf.subtitle !== null && <p className="shelf__subtitle">{shelf.subtitle}</p>}
          </header>
          <div className="shelf__rail" role="list" aria-label={shelf.title}>
            {shelf.tracks.map((track) => {
              const playable = shelfTrackToPlayable(track);
              const playableRow = playable.videoId !== '';
              return (
                <div
                  key={track.cadId}
                  {...(playableRow
                    ? trackMenuGestures(playable, 'HOME')
                    : {})}
                >
                  <TrackCard
                    title={track.title}
                    artist={track.artist}
                    artworkUrl={track.artworkUrl !== '' ? track.artworkUrl : null}
                    isCurrent={currentId === track.ytmVideoId}
                    onClick={() => {
                      if (!playableRow) return;
                      void player.play(playable);
                    }}
                  />
                </div>
              );
            })}
          </div>
        </div>
      ))}

      {cloudLoading && (
        <div className="unifeed__loading unifeed__loading--inline" role="status">
          <span className="spinner" aria-hidden="true" />
          <span className="sr-only">Searching Streamify Cloud…</span>
        </div>
      )}

      {phase === 'ready' && shelves.length === 0 && (
        <EmptyStateView
          className="unifeed__empty"
          title="Nothing to feed yet"
          subtitle="Like a few tracks and the feed builds from your library — everything stays honest and local until then."
          actionText="Explore music"
          onActionClick={() => navigate('/search')}
        />
      )}

      <GlobalTrackContextMenuHost />
    </section>
  );
}
