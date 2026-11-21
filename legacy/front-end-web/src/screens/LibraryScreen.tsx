/**
 * screens/LibraryScreen.tsx — P2 browse port (ui/screens/LibraryScreen.kt,
 * 1,447 LOC of LazyColumns).
 *
 * The library surface, web lowering:
 *   • header (Library + New Playlist + refresh);
 *   • the filter chips rail (YtLibraryFilterChips) — Playlists / Songs /
 *     Albums / Artists. The Android Downloads / Device-files tabs are
 *     engine surfaces not exposed to JS yet (documented gap, not faked);
 *   • the multi-criteria sort menu (LibraryOrganizer sorts over the
 *     cloud library);
 *   • Playlists tab: the Liked Music hero card (synthetic gradient),
 *     the New playlist row, and the cloud playlist rows (rename/delete
 *     via dialogs — the real public.playlists tables);
 *   • Songs tab: the cloud library tracklist (long lists render
 *     incrementally; right-click/long-press opens the context menu);
 *   • Albums/Artists tabs: the groupings → /album/:name, /artist/:name;
 *   • in-place playlist detail (selectedPlaylistId) delegates to
 *     AlbumScreen exactly like the Kotlin PlaylistDetailScreen.
 */

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { TrackRow } from '../components/TrackRow';
import { TrackCoverArt } from '../components/TrackCoverArt';
import { EmptyStateView } from '../components/EmptyStateView';
import { GlobalTrackContextMenuHost, trackMenuGestures } from '../components/ContextMenuSheet';
import { AlbumScreen } from './AlbumScreen';
import {
  groupByAlbum,
  groupByArtist,
  sortTracks,
  toEngineTrack,
  toPlayable,
  useLibraryStore,
  type LibrarySort,
} from '../lib/libraryStore';
import { useSessionStore } from '../lib/sessionStore';
import { useTrackPlayer } from '../lib/trackPlayer';
import { useIncrementalList } from '../lib/incrementalList';
import { toast } from '../lib/toastStore';

type LibraryFilter = 'Playlists' | 'Songs' | 'Albums' | 'Artists';

const FILTERS: LibraryFilter[] = ['Playlists', 'Songs', 'Albums', 'Artists'];

const SORTS: Array<{ value: LibrarySort; label: string }> = [
  { value: 'RECENT', label: 'Recently added' },
  { value: 'TITLE', label: 'Title' },
  { value: 'ARTIST', label: 'Artist' },
];

export function LibraryScreen(): JSX.Element {
  const navigate = useNavigate();
  const phase = useLibraryStore((s) => s.phase);
  const error = useLibraryStore((s) => s.error);
  const tracks = useLibraryStore((s) => s.tracks);
  const playlists = useLibraryStore((s) => s.playlists);
  const playlistTracks = useLibraryStore((s) => s.playlistTracks);
  const load = useLibraryStore((s) => s.load);
  const createPlaylist = useLibraryStore((s) => s.createPlaylist);

  const session = useSessionStore((s) => s.session);
  const liveLikes = useSessionStore((s) => s.likes);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);
  const player = useTrackPlayer();

  const [selectedFilter, setSelectedFilter] = useState<LibraryFilter>('Playlists');
  const [selectedPlaylistId, setSelectedPlaylistId] = useState<string | null>(null);
  const [librarySort, setLibrarySort] = useState<LibrarySort>('RECENT');
  const [showSortMenu, setShowSortMenu] = useState(false);
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [newPlaylistDesc, setNewPlaylistDesc] = useState('');
  const [playlistToRename, setPlaylistToRename] = useState<{ id: string; name: string } | null>(null);
  const [renameText, setRenameText] = useState('');
  const [playlistToDelete, setPlaylistToDelete] = useState<{ id: string; name: string } | null>(null);
  const [busy, setBusy] = useState(false);

  // Load the cloud library on mount and again when the session flips
  // (sign-in/out while sitting here) — the Android loadLibrary() posture.
  const sessionKey = session?.user.id ?? 'signed-out';
  useEffect(() => {
    void load();
    setSelectedPlaylistId(null);
  }, [sessionKey, load]);

  // The groupings (remember(allTracks) derivations → useMemo).
  const albums = useMemo(() => groupByAlbum(tracks), [tracks]);
  const artists = useMemo(() => groupByArtist(tracks), [tracks]);
  const sortedTracks = useMemo(() => sortTracks(tracks, librarySort), [tracks, librarySort]);
  const songs = useIncrementalList(sortedTracks);

  // ---- In-place playlist detail (the PlaylistDetailScreen delegate) ----
  if (selectedPlaylistId !== null) {
    const isLiked = selectedPlaylistId === 'liked_songs';
    const currentPlaylist = playlists.find((p) => p.id === selectedPlaylistId);
    const detailTracks = isLiked
      ? liveLikes // live: a like from Search is visible immediately
      : (playlistTracks[selectedPlaylistId] ?? []);
    return (
      <AlbumScreen
        albumName={isLiked ? 'Liked Music' : (currentPlaylist?.name ?? 'Playlist')}
        allTracks={detailTracks}
        explicitTracks={detailTracks}
        headerTitle={isLiked ? 'Liked Music' : (currentPlaylist?.name ?? 'Playlist')}
        headerSubtitle={
          isLiked
            ? 'Auto playlist'
            : currentPlaylist?.description && currentPlaylist.description !== ''
              ? currentPlaylist.description
              : `${detailTracks.length} songs`
        }
        playlistId={selectedPlaylistId}
        onBack={() => setSelectedPlaylistId(null)}
        onRenamed={() => void load()}
        onDeleted={() => {
          setSelectedPlaylistId(null);
          void load();
        }}
      />
    );
  }

  const handleLike = (trackId: string): void => {
    const track = tracks.find((t) => t.id === trackId);
    if (track === undefined) return;
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

  const handleCreate = (): void => {
    const name = newPlaylistName.trim();
    if (name === '') return;
    setBusy(true);
    void createPlaylist(name, newPlaylistDesc.trim() === '' ? null : newPlaylistDesc.trim()).then(
      (created) => {
        setBusy(false);
        setShowCreateDialog(false);
        setNewPlaylistName('');
        setNewPlaylistDesc('');
        if (created !== null) {
          toast(`Created "${created.name}"`);
          setSelectedPlaylistId(created.id);
        }
      },
    );
  };

  return (
    <div className="libraryscreen">
      {/* 1. Header */}
      <div className="libraryscreen__header">
        <h1 className="libraryscreen__title">Library</h1>
        <div className="libraryscreen__actions">
          <button
            type="button"
            className="libaction"
            onClick={() => setShowCreateDialog(true)}
            disabled={session === null}
            title={session === null ? 'Sign in to create playlists' : undefined}
          >
            <Icon name="favoriteBorder" size={16} />
            <span>New Playlist</span>
          </button>
          <button
            type="button"
            className="libaction libaction--icon"
            aria-label="Refresh library"
            onClick={() => void load()}
          >
            <Icon name="refresh" size={20} />
          </button>
        </div>
      </div>

      {/* 2. Filter chips rail */}
      <div className="chipsrail" role="tablist" aria-label="Library sections">
        {FILTERS.map((label) => (
          <button
            key={label}
            type="button"
            role="tab"
            aria-selected={selectedFilter === label}
            className={`chipfilter ${selectedFilter === label ? 'chipfilter--on' : ''}`}
            onClick={() => setSelectedFilter(label)}
          >
            {label}
          </button>
        ))}
      </div>

      {/* 3. Sort selector (the multi-criteria sort menu) */}
      {selectedFilter !== 'Playlists' && (
        <div className="libraryscreen__sortrow">
          <div className="libraryscreen__sortwrap">
            <button
              type="button"
              className="libaction"
              aria-expanded={showSortMenu}
              onClick={() => setShowSortMenu((prev) => !prev)}
            >
              <span className="muted">Sort:</span>
              <span>{SORTS.find((s) => s.value === librarySort)?.label ?? 'Recently added'}</span>
            </button>
            {showSortMenu && (
              <>
                <button
                  type="button"
                  className="dropdownmenu__backdrop"
                  aria-label="Close sort menu"
                  tabIndex={-1}
                  onClick={() => setShowSortMenu(false)}
                />
                <div className="dropdownmenu dropdownmenu--up" role="menu">
                  {SORTS.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      role="menuitem"
                      className={`dropdownmenu__item ${librarySort === option.value ? 'dropdownmenu__item--on' : ''}`}
                      onClick={() => {
                        setLibrarySort(option.value);
                        setShowSortMenu(false);
                      }}
                    >
                      {librarySort === option.value && <Icon name="checkCircle" size={18} />}
                      {option.label}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* 4. Content */}
      {phase === 'loading' && tracks.length === 0 && playlists.length === 0 ? (
        <div className="libraryscreen__loading" role="status">
          <span className="spinner" aria-hidden="true" />
          <span className="sr-only">Loading library…</span>
        </div>
      ) : phase === 'error' ? (
        <EmptyStateView
          title="Something went wrong"
          subtitle={error ?? 'The library could not be loaded.'}
          actionText="Retry"
          onActionClick={() => void load()}
        />
      ) : session === null ? (
        <EmptyStateView
          title="Your library lives in the cloud"
          subtitle="Sign in (Settings → account, or the account card on Home) to sync your liked music and playlists."
          actionText="Go to Settings"
          onActionClick={() => navigate('/settings')}
        />
      ) : (
        <div className="libraryscreen__body">
          {selectedFilter === 'Playlists' && (
            <>
              {/* Liked Music hero card */}
              <button
                type="button"
                className="likedcard"
                onClick={() => setSelectedPlaylistId('liked_songs')}
              >
                <span className="likedcard__art" aria-hidden="true">
                  <Icon name="favorite" size={22} />
                </span>
                <span className="likedcard__body">
                  <span className="likedcard__title">Liked Music</span>
                  <span className="likedcard__sub">
                    Auto playlist • {liveLikes.length} songs
                  </span>
                </span>
              </button>

              {/* New playlist quick row */}
              <button
                type="button"
                className="newplaylistrow"
                onClick={() => setShowCreateDialog(true)}
              >
                <span className="newplaylistrow__art" aria-hidden="true">
                  <Icon name="favoriteBorder" size={24} />
                </span>
                <span className="newplaylistrow__body">
                  <span className="newplaylistrow__title">New playlist</span>
                  <span className="newplaylistrow__sub">Create custom playlist</span>
                </span>
              </button>

              {/* Cloud playlists */}
              {playlists.length === 0 ? (
                <EmptyStateView
                  title="No playlists yet"
                  subtitle="Create one above, or add songs from Search (right-click a result → Add to Playlist)."
                />
              ) : (
                <ul className="playlistrows" aria-label="Playlists">
                  {playlists.map((playlist) => (
                    <li key={playlist.id}>
                      <div className="playlistrow">
                        <button
                          type="button"
                          className="playlistrow__main"
                          onClick={() => setSelectedPlaylistId(playlist.id)}
                        >
                          <span className="playlistrow__art" aria-hidden="true">
                            <Icon name="libraryMusic" size={24} />
                          </span>
                          <span className="playlistrow__body">
                            <span className="playlistrow__title">{playlist.name}</span>
                            <span className="playlistrow__sub">
                              Playlist • {playlist.trackCount} songs
                            </span>
                          </span>
                        </button>
                        <button
                          type="button"
                          className="playlistrow__iconbtn"
                          aria-label={`Rename ${playlist.name}`}
                          onClick={() => {
                            setRenameText(playlist.name);
                            setPlaylistToRename({ id: playlist.id, name: playlist.name });
                          }}
                        >
                          <Icon name="edit" size={18} />
                        </button>
                        <button
                          type="button"
                          className="playlistrow__iconbtn"
                          aria-label={`Delete ${playlist.name}`}
                          onClick={() =>
                            setPlaylistToDelete({ id: playlist.id, name: playlist.name })
                          }
                        >
                          <Icon name="warning" size={18} />
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}

          {selectedFilter === 'Songs' &&
            (tracks.length === 0 ? (
              <EmptyStateView
                title="No songs yet"
                subtitle="Like tracks from Search (♡) — your cloud library builds from there. The scanned local library lands with the engine surface (see the port notes)."
                actionText="Search music"
                onActionClick={() => navigate('/search')}
              />
            ) : (
              <ul className="results libraryscreen__tracks" aria-label="Songs">
                {songs.visible.map((track) => {
                  const playable = toPlayable(track);
                  return (
                    <TrackRow
                      key={`lib_${track.id}`}
                      track={toEngineTrack(track)}
                      playback={player.playbackFor(playable)}
                      onPlay={() => void player.play(playable)}
                      onQueue={() => player.queue(playable)}
                      onLike={() => handleLike(track.id)}
                      liked={likedVideoIds.has(track.streamUrl ?? '')}
                      isCurrent={player.playbackFor(playable)?.status === 'playing'}
                      gestures={trackMenuGestures(playable, 'LIBRARY')}
                    />
                  );
                })}
                {songs.hasMore && (
                  <li ref={songs.sentinelRef} className="libraryscreen__sentinel" aria-hidden="true">
                    <span className="spinner" />
                  </li>
                )}
              </ul>
            ))}

          {selectedFilter === 'Albums' &&
            (albums.length === 0 ? (
              <EmptyStateView
                title="No albums found"
                subtitle="Albums show up automatically when your liked tracks carry album tags."
                actionText="Rescan"
                onActionClick={() => void load()}
              />
            ) : (
              <ul className="grouprows" aria-label="Albums">
                {albums.map((group) => {
                  const first = group.tracks[0];
                  return (
                    <li key={group.key}>
                      <button
                        type="button"
                        className="grouprrow"
                        onClick={() => navigate(`/album/${encodeURIComponent(group.key)}`)}
                      >
                        <TrackCoverArt
                          coverArtPath={first?.coverUrl}
                          title={group.key}
                          artist={first?.artist ?? ''}
                          sizePx={56}
                          radiusPx={4}
                        />
                        <span className="grouprrow__body">
                          <span className="grouprrow__title">{group.key}</span>
                          <span className="grouprrow__sub">
                            Album • {first?.artist ?? 'Unknown Artist'} • {group.tracks.length}{' '}
                            songs
                          </span>
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            ))}

          {selectedFilter === 'Artists' &&
            (artists.length === 0 ? (
              <EmptyStateView
                title="No artists found"
                subtitle="Artists appear automatically from your liked tracks."
                actionText="Rescan"
                onActionClick={() => void load()}
              />
            ) : (
              <ul className="grouprows" aria-label="Artists">
                {artists.map((group) => {
                  const first = group.tracks[0];
                  return (
                    <li key={group.key}>
                      <button
                        type="button"
                        className="grouprrow"
                        onClick={() => navigate(`/artist/${encodeURIComponent(group.key)}`)}
                      >
                        <TrackCoverArt
                          coverArtPath={first?.coverUrl}
                          title={group.key}
                          artist={group.key}
                          sizePx={56}
                          radiusPx={999}
                        />
                        <span className="grouprrow__body">
                          <span className="grouprrow__title">{group.key}</span>
                          <span className="grouprrow__sub">
                            Artist • {group.tracks.length} songs
                          </span>
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            ))}
        </div>
      )}

      {/* Create playlist dialog */}
      {showCreateDialog && (
        <div className="dlgscrim" onClick={() => setShowCreateDialog(false)} role="presentation">
          <div
            className="dialog"
            role="dialog"
            aria-label="New playlist"
            onClick={(event) => event.stopPropagation()}
          >
            <h3 className="dialog__title">New Playlist</h3>
            <input
              className="dialog__input"
              type="text"
              placeholder="e.g. Acoustic Vibes"
              aria-label="Playlist name"
              maxLength={80}
              value={newPlaylistName}
              onChange={(event) => setNewPlaylistName(event.target.value)}
              autoFocus
            />
            <input
              className="dialog__input"
              type="text"
              placeholder="Description (optional)"
              aria-label="Playlist description"
              maxLength={200}
              value={newPlaylistDesc}
              onChange={(event) => setNewPlaylistDesc(event.target.value)}
            />
            <div className="dialog__actions">
              <button type="button" onClick={() => setShowCreateDialog(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="button--primary"
                disabled={busy || newPlaylistName.trim() === ''}
                onClick={handleCreate}
              >
                Create
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Rename dialog */}
      {playlistToRename !== null && (
        <div
          className="dlgscrim"
          onClick={() => setPlaylistToRename(null)}
          role="presentation"
        >
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
              aria-label="Playlist name"
              maxLength={80}
              value={renameText}
              onChange={(event) => setRenameText(event.target.value)}
              autoFocus
            />
            <div className="dialog__actions">
              <button type="button" onClick={() => setPlaylistToRename(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="button--primary"
                disabled={busy || renameText.trim() === ''}
                onClick={() => {
                  const target = playlistToRename;
                  if (target === null) return;
                  setBusy(true);
                  void useLibraryStore
                    .getState()
                    .renamePlaylist(target.id, renameText.trim())
                    .then((ok) => {
                      setBusy(false);
                      setPlaylistToRename(null);
                      toast(ok ? 'Playlist renamed' : 'Rename failed', ok ? 'success' : 'error');
                    });
                }}
              >
                Save
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete confirm dialog */}
      {playlistToDelete !== null && (
        <div
          className="dlgscrim"
          onClick={() => setPlaylistToDelete(null)}
          role="presentation"
        >
          <div
            className="dialog"
            role="alertdialog"
            aria-label="Delete playlist"
            onClick={(event) => event.stopPropagation()}
          >
            <h3 className="dialog__title">Delete Playlist?</h3>
            <p className="dialog__text">
              Are you sure you want to delete &ldquo;{playlistToDelete.name}&rdquo;? Songs will
              remain in your library.
            </p>
            <div className="dialog__actions">
              <button type="button" onClick={() => setPlaylistToDelete(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="dialog__danger"
                disabled={busy}
                onClick={() => {
                  const target = playlistToDelete;
                  if (target === null) return;
                  setBusy(true);
                  void useLibraryStore
                    .getState()
                    .deletePlaylist(target.id)
                    .then((ok) => {
                      setBusy(false);
                      setPlaylistToDelete(null);
                      toast(ok ? 'Playlist deleted' : 'Delete failed', ok ? 'success' : 'error');
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
