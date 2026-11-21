/**
 * screens/ArtistScreen.tsx — P2 browse port (ui/screens/ArtistScreen.kt).
 *
 * The artist drill-down: top bar (back + name), the centered circle
 * avatar hero (180dp, TrackCoverArt pipeline), the song count, Play /
 * Shuffle pills, "Popular Songs" and the tracklist (context-menu
 * gestures, incremental rendering).
 *
 * Skipped honestly (out of scope / not exposed — see PR notes): the
 * vertical Clips rail + immersive feed (video pieces are deferred while
 * the SABR lane is audio-only), FollowButton + follower count (the
 * follow-graph store is an engine surface), the upcoming-release
 * countdown banner + Pre-Save (ReleaseWatcherApi not ported).
 *
 * Deep-linkable: /artist/:artistName survives refresh — the library
 * loads from the cloud on mount (libraryStore.load in AppShell wiring
 * or here via the load effect).
 */

import { useEffect, useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Icon } from '../components/icons';
import { TrackRow } from '../components/TrackRow';
import { TrackCoverArt } from '../components/TrackCoverArt';
import { EmptyStateView } from '../components/EmptyStateView';
import { GlobalTrackContextMenuHost, trackMenuGestures } from '../components/ContextMenuSheet';
import { toEngineTrack, toPlayable, useLibraryStore } from '../lib/libraryStore';
import { useTrackPlayer } from '../lib/trackPlayer';
import { useIncrementalList } from '../lib/incrementalList';
import { useSessionStore } from '../lib/sessionStore';

export interface ArtistScreenProps {
  artistName: string;
  allTracks: import('../lib/supabase').CloudTrack[];
  onBack?: () => void;
}

export function ArtistScreen(props: ArtistScreenProps): JSX.Element {
  const { artistName, allTracks, onBack } = props;
  const navigate = useNavigate();
  const player = useTrackPlayer();
  const session = useSessionStore((s) => s.session);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);

  // remember(artistName, allTracks) — the artist filter (contains, CI).
  const artistTracks = useMemo(
    () => allTracks.filter((track) => track.artist.toLowerCase().includes(artistName.toLowerCase())),
    [artistName, allTracks],
  );

  const firstWithCover = artistTracks.find((track) => (track.coverUrl ?? '') !== '');
  const { visible, sentinelRef, hasMore } = useIncrementalList(artistTracks);

  const handleBack = (): void => {
    if (onBack !== undefined) {
      onBack();
      return;
    }
    navigate(-1);
  };

  return (
    <div className="artistscreen">
      {/* Top app bar */}
      <div className="screen-topbar">
        <button type="button" className="screen-topbar__back" aria-label="Back" onClick={handleBack}>
          <Icon name="arrowBack" size={24} />
        </button>
        <h1 className="screen-topbar__title">{artistName}</h1>
      </div>

      <div className="artistscreen__scroll">
        {/* Artist hero */}
        <header className="artisthero">
          <TrackCoverArt
            coverArtPath={firstWithCover?.coverUrl}
            title={artistName}
            artist=""
            sizePx={180}
            radiusPx={999}
            className="artisthero__avatar"
          />
          <h2 className="artisthero__name">{artistName}</h2>
          <p className="artisthero__count">{artistTracks.length} Songs</p>
          <div className="artisthero__controls">
            <button
              type="button"
              className="pillbtn pillbtn--primary"
              disabled={artistTracks.length === 0}
              onClick={() => void player.playCollection(artistTracks.map(toPlayable))}
            >
              <Icon name="playArrow" size={26} /> Play
            </button>
            <button
              type="button"
              className="pillbtn pillbtn--outline"
              disabled={artistTracks.length === 0}
              onClick={() => void player.playCollection(artistTracks.map(toPlayable), true)}
            >
              <Icon name="radio" size={20} /> Shuffle
            </button>
          </div>
        </header>

        {/* Popular songs */}
        <h2 className="artistscreen__popular">Popular Songs</h2>
        {artistTracks.length === 0 ? (
          <EmptyStateView
            title="No songs by this artist yet"
            subtitle="Like their tracks from Search (♡) — the artist page groups your cloud library by artist."
          />
        ) : (
          <ul className="results artistscreen__tracks" aria-label="Popular songs">
            {visible.map((track) => {
              const playable = toPlayable(track);
              return (
                <TrackRow
                  key={track.id}
                  track={toEngineTrack(track)}
                  playback={player.playbackFor(playable)}
                  onPlay={() => void player.play(playable)}
                  onQueue={() => player.queue(playable)}
                  onLike={
                    session !== null
                      ? () => {
                          void toggleLike({
                            videoId: track.streamUrl ?? '',
                            title: track.title,
                            artist: track.artist,
                            album: track.album,
                            durationSec: track.durationSec,
                            thumbnailUrl: track.coverUrl,
                          });
                        }
                      : undefined
                  }
                  liked={likedVideoIds.has(track.streamUrl ?? '')}
                  isCurrent={player.playbackFor(playable)?.status === 'playing'}
                  gestures={trackMenuGestures(playable, 'HOME')}
                />
              );
            })}
            {hasMore && (
              <li ref={sentinelRef} className="artistscreen__sentinel" aria-hidden="true">
                <span className="spinner" />
              </li>
            )}
          </ul>
        )}
      </div>

      <GlobalTrackContextMenuHost />
    </div>
  );
}

/**
 * The route wrapper: /artist/:artistName — pulls the artist name from
 * the URL and feeds the library tracks (loads them on mount if the
 * store is still idle, so a cold refresh deep-link works).
 */
export function ArtistRoute(): JSX.Element {
  const params = useParams<{ artistName: string }>();
  const artistName = decodeURIComponent(params.artistName ?? '');
  const phase = useLibraryStore((s) => s.phase);
  const tracks = useLibraryStore((s) => s.tracks);
  const load = useLibraryStore((s) => s.load);
  const booted = useSessionStore((s) => s.booted);

  // Cold deep-link: the library loads when the session has booted and
  // the store is still idle (AppShell already booted the session).
  useEffect(() => {
    if (booted && phase === 'idle') {
      void load();
    }
  }, [booted, phase, load]);

  if (artistName === '') {
    return (
      <EmptyStateView
        title="No artist"
        subtitle="This URL is missing the artist name — open an artist from your Library or Search."
      />
    );
  }

  return <ArtistScreen artistName={artistName} allTracks={tracks} />;
}
