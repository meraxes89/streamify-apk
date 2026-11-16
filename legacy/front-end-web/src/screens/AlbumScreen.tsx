/**
 * screens/AlbumScreen.tsx — P2 browse port (ui/screens/AlbumScreen.kt).
 *
 * The album/playlist detail: top toolbar (back + kind title + playlist
 * options when a playlistId rides along), the parallax hero (scroll-
 * driven scale/translate on the 180dp artwork — GPU transforms only),
 * Play/Shuffle pill controls, then the tracklist (context-menu gestures,
 * incremental rendering for long lists). Playlist mode (explicitTracks)
 * is the LibraryScreen in-place detail — the Kotlin PlaylistDetailScreen
 * literally delegates here, so the web keeps that shape.
 *
 * Skipped honestly (engine surfaces not exposed — see PR notes): bulk
 * edit + cover cropper, QR share card, radio discovery toggle. M3U8
 * export IS real (a client-side blob of the same rows).
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Icon } from '../components/icons';
import { TrackRow } from '../components/TrackRow';
import { TrackCoverArt } from '../components/TrackCoverArt';
import { EmptyStateView } from '../components/EmptyStateView';
import { GlobalTrackContextMenuHost, trackMenuGestures } from '../components/ContextMenuSheet';
import { toEngineTrack, toPlayable, useLibraryStore } from '../lib/libraryStore';
import type { CloudTrack } from '../lib/supabase';
import { useTrackPlayer } from '../lib/trackPlayer';
import { useIncrementalList } from '../lib/incrementalList';
import { useSessionStore } from '../lib/sessionStore';
import { toast } from '../lib/toastStore';

export interface AlbumScreenProps {
  albumName: string;
  /** The library (album mode filters by albumName) or the playlist rows. */
  allTracks: CloudTrack[];
  /** Playlist mode: the explicit row list (bypasses the album filter). */
  explicitTracks?: CloudTrack[] | null;
  headerTitle?: string | null;
  headerSubtitle?: string | null;
  /** Present in playlist mode (rename/delete menu + PLAYLIST menu origin). */
  playlistId?: string | null;
  /** Overrides back (the in-place detail); default: navigate(-1). */
  onBack?: () => void;
  onRenamed?: () => void;
  onDeleted?: () => void;
}

export function AlbumScreen(props: AlbumScreenProps): JSX.Element {
  const {
    albumName,
    allTracks,
    explicitTracks,
    headerTitle,
    headerSubtitle,
    playlistId,
    onBack,
    onRenamed,
    onDeleted,
  } = props;
  const navigate = useNavigate();
  const player = useTrackPlayer();
  const session = useSessionStore((s) => s.session);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);
  const renamePlaylist = useLibraryStore((s) => s.renamePlaylist);
  const deletePlaylist = useLibraryStore((s) => s.deletePlaylist);

  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [showOptionsMenu, setShowOptionsMenu] = useState(false);
  const [showRenameDialog, setShowRenameDialog] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [renameText, setRenameText] = useState(headerTitle ?? albumName);
  const [busy, setBusy] = useState(false);

  // remember(albumName, allTracks, explicitTracks) — the album filter.
  const albumTracks = useMemo(
    () =>
      explicitTracks ??
      allTracks.filter((track) => (track.album ?? '').toLowerCase() === albumName.toLowerCase()),
    [albumName, allTracks, explicitTracks],
  );

  const displayTitle = headerTitle ?? albumName;
  const isPlaylist = explicitTracks != null;
  const firstTrack = albumTracks[0];
  const displaySubtitle =
    headerSubtitle ??
    (isPlaylist
      ? `${albumTracks.length} songs`
      : `Album • ${firstTrack?.artist ?? 'Unknown Artist'} • ${albumTracks.length} songs`);

  const { visible, sentinelRef, hasMore } = useIncrementalList(albumTracks);

  // The 120fps parallax lowering: transform-only, scroll-event driven.
  const heroScale = Math.max(0.7, 1 - Math.min(scrollTop, 300) / 1000);
  const heroTranslateY = -Math.min(scrollTop, 300) * 0.4;

  const handleBack = (): void => {
    if (onBack !== undefined) {
      onBack();
      return;
    }
    navigate(-1);
  };

  const handleLike = (track: CloudTrack): void => {
    if (session === null) {
      toast('Sign in to save likes to your cloud library', 'error');
      return;
    }
    void toggleLike({
      videoId: track.streamUrl ?? '',
      title: track.title,
      artist: track.artist,
      album: track.album,
      durationSec: track.durationSec,
      thumbnailUrl: track.coverUrl,
    }).then((ok) => {
      if (ok) toast(`♥ ${track.title}`);
    });
  };

  const exportM3u = (): void => {
    setShowOptionsMenu(false);
    if (albumTracks.length === 0) return;
    const lines = ['#EXTM3U'];
    for (const track of albumTracks) {
      const duration = track.durationSec ?? -1;
      lines.push(`#EXTINF:${duration},${track.artist} - ${track.title}`);
      lines.push(
        track.streamUrl != null && track.streamUrl !== ''
          ? `https://www.youtube.com/watch?v=${track.streamUrl}`
          : `# (no stream source) ${track.title}`,
      );
    }
    const blob = new Blob([`${lines.join('\n')}\n`], { type: 'audio/x-mpegurl' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${displayTitle.replace(/[^\w-]+/g, '_')}.m3u8`;
    anchor.click();
    URL.revokeObjectURL(url);
    toast(`Exported: ${anchor.download}`);
  };

  const menuOrigin = playlistId != null ? 'PLAYLIST' : 'HOME';

  return (
    <div className="albumscreen">
      {/* Top toolbar */}
      <div className="screen-topbar albumscreen__topbar">
        <button
          type="button"
          className="screen-topbar__back"
          aria-label="Back"
          onClick={handleBack}
        >
          <Icon name="arrowBack" size={24} />
        </button>
        <h1 className="screen-topbar__title">{isPlaylist ? 'Playlist' : 'Album'}</h1>
        {playlistId != null && playlistId !== 'liked_songs' && (
          <div className="albumscreen__optionswrap">
            <button
              type="button"
              className="albumscreen__optionsbtn"
              aria-label="Playlist options"
              aria-expanded={showOptionsMenu}
              onClick={() => setShowOptionsMenu((prev) => !prev)}
            >
              <Icon name="settings" size={22} />
            </button>
            {showOptionsMenu && (
              <>
                <button
                  type="button"
                  className="dropdownmenu__backdrop"
                  aria-label="Close menu"
                  tabIndex={-1}
                  onClick={() => setShowOptionsMenu(false)}
                />
                <div className="dropdownmenu" role="menu">
                <button
                  type="button"
                  role="menuitem"
                  className="dropdownmenu__item"
                  onClick={() => {
                    setShowOptionsMenu(false);
                    setRenameText(displayTitle);
                    setShowRenameDialog(true);
                  }}
                >
                  <Icon name="edit" size={18} /> Rename Playlist
                </button>
                <button
                  type="button"
                  role="menuitem"
                  className="dropdownmenu__item"
                  onClick={exportM3u}
                >
                  <Icon name="download" size={18} /> Export to M3U8
                </button>
                <button
                  type="button"
                  role="menuitem"
                  className="dropdownmenu__item dropdownmenu__item--danger"
                  onClick={() => {
                    setShowOptionsMenu(false);
                    setShowDeleteConfirm(true);
                  }}
                >
                  <Icon name="warning" size={18} /> Delete Playlist
                </button>
                </div>
              </>
            )}
          </div>
        )}
      </div>

      <div
        className="albumscreen__scroll"
        ref={scrollRef}
        onScroll={(event) => setScrollTop((event.target as HTMLDivElement).scrollTop)}
      >
        {/* Hero header */}
        <header className="albumhero">
          <div
            className="albumhero__art"
            style={{
              transform: `translateY(${heroTranslateY}px) scale(${heroScale})`,
            }}
          >
            <TrackCoverArt
              coverArtPath={firstTrack?.coverUrl}
              title={displayTitle}
              artist={firstTrack?.artist ?? ''}
              sizePx={180}
              radiusPx={8}
            />
          </div>
          <h2 className="albumhero__title">{displayTitle}</h2>
          <p className="albumhero__subtitle">{displaySubtitle}</p>
          <div className="albumhero__controls">
            <button
              type="button"
              className="pillbtn pillbtn--primary"
              disabled={albumTracks.length === 0}
              onClick={() => void player.playCollection(albumTracks.map(toPlayable))}
            >
              <Icon name="playArrow" size={26} /> Play
            </button>
            <button
              type="button"
              className="pillbtn pillbtn--outline"
              disabled={albumTracks.length === 0}
              onClick={() => void player.playCollection(albumTracks.map(toPlayable), true)}
            >
              <Icon name="radio" size={20} /> Shuffle
            </button>
            <button
              type="button"
              className="pillbtn pillbtn--outline"
              disabled={albumTracks.length === 0}
              onClick={exportM3u}
              aria-label="Export to M3U8"
            >
              <Icon name="download" size={20} /> Export
            </button>
          </div>
        </header>

        {/* Tracklist */}
        {albumTracks.length === 0 ? (
          <EmptyStateView
            title={isPlaylist ? 'Empty playlist' : 'No songs in this album yet'}
            subtitle={
              isPlaylist
                ? 'Add songs from Search (long-press or right-click a result → Add to Playlist).'
                : 'Like tracks from this album (Search → ♡) and they will show up here.'
            }
          />
        ) : (
          <ul className="results albumscreen__tracks" aria-label="Tracks">
            {visible.map((track) => {
              const playable = toPlayable(track);
              return (
                <TrackRow
                  key={`${playlistId ?? 'album'}_${track.id}`}
                  track={toEngineTrack(track)}
                  playback={player.playbackFor(playable)}
                  onPlay={() => void player.play(playable)}
                  onQueue={() => player.queue(playable)}
                  onLike={session !== null ? () => handleLike(track) : undefined}
                  liked={likedVideoIds.has(track.streamUrl ?? '')}
                  isCurrent={player.playbackFor(playable)?.status === 'playing'}
                  gestures={trackMenuGestures(playable, menuOrigin, playlistId)}
                />
              );
            })}
            {hasMore && (
              <li ref={sentinelRef} className="albumscreen__sentinel" aria-hidden="true">
                <span className="spinner" />
              </li>
            )}
          </ul>
        )}
      </div>

      {/* Rename dialog */}
      {showRenameDialog && playlistId != null && (
        <div className="dlgscrim" onClick={() => setShowRenameDialog(false)} role="presentation">
          <div
            className="dialog"
            role="dialog"
            aria-label="Rename playlist"
            onClick={(event) => event.stopPropagation()}
          >
            <h3 className="dialog__title">Rename Playlist</h3>
            <input
              className="dialog__input"
              type="text"
              value={renameText}
              maxLength={80}
              aria-label="Playlist name"
              onChange={(event) => setRenameText(event.target.value)}
              autoFocus
            />
            <div className="dialog__actions">
              <button type="button" onClick={() => setShowRenameDialog(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="button--primary"
                disabled={busy || renameText.trim() === ''}
                onClick={() => {
                  setBusy(true);
                  void renamePlaylist(playlistId, renameText.trim()).then((ok) => {
                    setBusy(false);
                    setShowRenameDialog(false);
                    if (ok) {
                      toast('Playlist renamed');
                      onRenamed?.();
                    } else {
                      toast('Rename failed', 'error');
                    }
                  });
                }}
              >
                Save
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete confirm */}
      {showDeleteConfirm && playlistId != null && (
        <div className="dlgscrim" onClick={() => setShowDeleteConfirm(false)} role="presentation">
          <div
            className="dialog"
            role="alertdialog"
            aria-label="Delete playlist"
            onClick={(event) => event.stopPropagation()}
          >
            <h3 className="dialog__title">Delete Playlist?</h3>
            <p className="dialog__text">
              Are you sure you want to delete &ldquo;{displayTitle}&rdquo;? Songs will remain in
              your library.
            </p>
            <div className="dialog__actions">
              <button type="button" onClick={() => setShowDeleteConfirm(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="dialog__danger"
                disabled={busy}
                onClick={() => {
                  setBusy(true);
                  void deletePlaylist(playlistId).then((ok) => {
                    setBusy(false);
                    setShowDeleteConfirm(false);
                    if (ok) {
                      toast('Playlist deleted');
                      onDeleted?.();
                    } else {
                      toast('Delete failed', 'error');
                    }
                  });
                }}
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}

      <GlobalTrackContextMenuHost />
    </div>
  );
}

/**
 * The route wrapper: /album/:albumName — URL-param driven (deep-linkable:
 * a cold refresh loads the library on mount, then filters by album).
 */
export function AlbumRoute(): JSX.Element {
  const params = useParams<{ albumName: string }>();
  const albumName = decodeURIComponent(params.albumName ?? '');
  const phase = useLibraryStore((s) => s.phase);
  const tracks = useLibraryStore((s) => s.tracks);
  const load = useLibraryStore((s) => s.load);
  const booted = useSessionStore((s) => s.booted);

  useEffect(() => {
    if (booted && phase === 'idle') {
      void load();
    }
  }, [booted, phase, load]);

  if (albumName === '') {
    return (
      <EmptyStateView
        title="No album"
        subtitle="This URL is missing the album name — open an album from your Library."
      />
    );
  }

  return <AlbumScreen albumName={albumName} allTracks={tracks} />;
}
