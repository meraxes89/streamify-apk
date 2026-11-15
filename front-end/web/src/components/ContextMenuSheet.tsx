/**
 * components/ContextMenuSheet.tsx — P2 browse port
 * (ui/components/ContextMenuSheet.kt, 647 LOC).
 *
 * The track context menu: long-press (touch) / right-click (desktop)
 * opens the bottom sheet with the actions that have REAL web backing:
 *
 *   Add to Queue        → the engine queue (port.enqueue)
 *   Like / Unlike       → the cloud library (sessionStore.toggleLike)
 *   Add to Playlist     → cloud playlists (playlist_tracks, real)
 *   Remove from playlist→ playlist_tracks DELETE (PLAYLIST origin only)
 *   Go to Artist/Album  → the browse routes
 *   Share               → navigator.clipboard (the watch URL)
 *
 * NOT ported (engine/repository surfaces not exposed to JS yet — see the
 * PR description gap list; never faked): Start Radio / Jam (the radio
 * scorer; the Jam CRDT now rides lib/jamStore — its screens are the next
 * track), Download (OPFS phase), Edit Info (tagger), Exclude from Taste
 * Profile (taste graph).
 *
 * WS-W engine facade breadth, work item 2: Play Next IS ported now — the
 * engine grew insert_after_cursor (the UDL op), wired below.
 *
 * The sheet itself is a MINIMAL local implementation (scrim + slide-up
 * panel + drag handle + Escape). TODO: consolidate onto Sheet.tsx when
 * the parallel listen-loop PR (ws-w/ui-p1-listen-loop) merges it.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { create } from 'zustand';
import { TrackCoverArt } from './TrackCoverArt';
import { Icon } from './icons';
import { toast } from '../lib/toastStore';
import { useSessionStore } from '../lib/sessionStore';
import { useLibraryStore } from '../lib/libraryStore';
import { cloudTrackId } from '../lib/supabase';
import { useTrackPlayer, type PlayableTrack } from '../lib/trackPlayer';
import { usePlayerStore } from '../lib/playerStore';

// ---------------------------------------------------------------------------
// MenuOrigin + the controller store (TrackContextMenuController lowering)
// ---------------------------------------------------------------------------

export type MenuOrigin = 'HOME' | 'SEARCH' | 'LIBRARY' | 'PLAYLIST';

export interface ContextMenuTrack extends PlayableTrack {
  /** The cloud track id (trk_<hash>) for playlist membership ops. */
  trackId: string;
}

interface ContextMenuState {
  isOpen: boolean;
  track: ContextMenuTrack | null;
  origin: MenuOrigin;
  playlistId: string | null;
  show(track: ContextMenuTrack, origin?: MenuOrigin, playlistId?: string | null): void;
  dismiss(): void;
}

export const useContextMenuStore = create<ContextMenuState>((set) => ({
  isOpen: false,
  track: null,
  origin: 'HOME',
  playlistId: null,
  show(track, origin = 'HOME', playlistId = null) {
    set({ isOpen: true, track, origin, playlistId });
  },
  dismiss() {
    set({ isOpen: false });
  },
}));

/** Convenience builder: any playable track → the menu's track shape. */
export function toMenuTrack(track: PlayableTrack): ContextMenuTrack {
  return {
    ...track,
    trackId: cloudTrackId(track.title, track.artist),
  };
}

/**
 * The gesture spread for track rows: right-click (desktop) and long-press
 * (touch ≥500 ms; movement cancels — scroll must never trigger the menu).
 */
export function trackMenuGestures(
  track: PlayableTrack,
  origin: MenuOrigin,
  playlistId?: string | null,
): {
  onContextMenu: (event: React.MouseEvent) => void;
  onTouchStart: (event: React.TouchEvent) => void;
  onTouchEnd: () => void;
  onTouchMove: () => void;
} {
  const open = (): void => {
    useContextMenuStore.getState().show(toMenuTrack(track), origin, playlistId ?? null);
  };
  let timer = 0;
  return {
    onContextMenu: (event) => {
      event.preventDefault();
      open();
    },
    onTouchStart: (event) => {
      if (event.touches.length !== 1) return;
      timer = window.setTimeout(open, 500);
    },
    onTouchEnd: () => {
      window.clearTimeout(timer);
    },
    onTouchMove: () => {
      window.clearTimeout(timer);
    },
  };
}

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

export interface ContextMenuSheetProps {
  track: ContextMenuTrack;
  onDismissRequest: () => void;
}

export function ContextMenuSheet(props: ContextMenuSheetProps): JSX.Element {
  const { track, onDismissRequest } = props;
  const navigate = useNavigate();
  const player = useTrackPlayer();
  const session = useSessionStore((s) => s.session);
  const likes = useSessionStore((s) => s.likes);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);
  const playlists = useLibraryStore((s) => s.playlists);
  const createPlaylist = useLibraryStore((s) => s.createPlaylist);
  const addTrackToPlaylist = useLibraryStore((s) => s.addTrackToPlaylist);
  const removeTrackFromPlaylist = useLibraryStore((s) => s.removeTrackFromPlaylist);

  const [showPlaylistDialog, setShowPlaylistDialog] = useState(false);
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [busy, setBusy] = useState(false);
  const nameInputRef = useRef<HTMLInputElement | null>(null);

  const isLiked =
    (track.videoId !== '' && likedVideoIds.has(track.videoId)) ||
    likes.some((l) => l.title === track.title && l.artist === track.artist);

  const albumNavigable =
    typeof track.album === 'string' &&
    track.album.trim() !== '' &&
    track.album !== 'Single' &&
    track.album !== 'Local Import' &&
    track.album !== 'Streamify Cloud';
  const artistNavigable =
    track.artist.trim() !== '' && track.artist !== 'Unknown Artist';

  // Escape closes (the BackHandler lowering); the scrim click too.
  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onDismissRequest();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onDismissRequest]);

  useEffect(() => {
    if (showPlaylistDialog) nameInputRef.current?.focus();
  }, [showPlaylistDialog]);

  const shareUrl = useMemo(
    () =>
      track.videoId !== ''
        ? `https://www.youtube.com/watch?v=${track.videoId}`
        : `${window.location.origin}${window.location.pathname}#/search`,
    [track.videoId],
  );

  const goToArtist = (): void => {
    onDismissRequest();
    navigate(`/artist/${encodeURIComponent(track.artist)}`);
  };

  const goToAlbum = (): void => {
    if (typeof track.album !== 'string') return;
    onDismissRequest();
    navigate(`/album/${encodeURIComponent(track.album)}`);
  };

  const handleLike = (): void => {
    onDismissRequest();
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
      if (ok) toast(isLiked ? `Removed ${track.title} from Liked Music` : `♥ ${track.title}`);
    });
  };

  const handleAddToPlaylist = (playlistId: string, playlistName: string): void => {
    setBusy(true);
    void addTrackToPlaylist(playlistId, track).then((ok) => {
      setBusy(false);
      if (ok) {
        toast(`Added to ${playlistName}`);
        setShowPlaylistDialog(false);
        onDismissRequest();
      } else {
        toast(`Could not add to ${playlistName}`, 'error');
      }
    });
  };

  const handleCreateAndAdd = (): void => {
    const name = newPlaylistName.trim();
    if (name === '') return;
    setBusy(true);
    void createPlaylist(name, null)
      .then(async (created) => {
        if (created === null) {
          toast('Could not create the playlist', 'error');
          return;
        }
        const ok = await addTrackToPlaylist(created.id, track);
        if (ok) {
          toast(`Created "${name}" & added track`);
          setShowPlaylistDialog(false);
          onDismissRequest();
        } else {
          toast(`Created "${name}" (track add failed)`, 'error');
        }
      })
      .finally(() => setBusy(false));
  };

  const menuOrigin = useContextMenuStore((s) => s.origin);
  const originPlaylistId = useContextMenuStore((s) => s.playlistId);

  return (
    <div className="cmscrim" onClick={onDismissRequest} role="presentation">
      <section
        className="cmsheet"
        role="dialog"
        aria-label={`Actions for ${track.title}`}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="cmsheet__handle" aria-hidden="true" />
        <header className="cmsheet__head">
          <TrackCoverArt
            coverArtPath={track.thumbnailUrl}
            title={track.title}
            artist={track.artist}
            sizePx={52}
            radiusPx={8}
          />
          <div className="cmsheet__titles">
            <h3 className="cmsheet__title">{track.title}</h3>
            <p className="cmsheet__artist">{track.artist}</p>
          </div>
        </header>
        <div className="cmsheet__divider" />

        {!showPlaylistDialog ? (
          <div className="cmsheet__actions">
            <button type="button" className="cmaction" onClick={() => { onDismissRequest(); void player.queue(track); }}>
              <Icon name="libraryMusicOutline" size={22} className="cmaction__icon" />
              <span>Add to Queue</span>
            </button>

            <button
              type="button"
              className="cmaction"
              onClick={() => {
                onDismissRequest();
                const ok = usePlayerStore.getState().playNext(track);
                toast(ok ? `Playing next: ${track.title}` : 'Engine busy — try again', ok ? 'success' : 'error');
              }}
            >
              <Icon name="playArrow" size={22} className="cmaction__icon" />
              <span>Play Next</span>
            </button>

            <button type="button" className="cmaction" onClick={handleLike}>
              <Icon
                name={isLiked ? 'favorite' : 'favoriteBorder'}
                size={22}
                className={`cmaction__icon ${isLiked ? 'cmaction__icon--liked' : ''}`}
              />
              <span>{isLiked ? 'Remove from Liked Music' : 'Like'}</span>
            </button>

            <button
              type="button"
              className="cmaction"
              disabled={session === null}
              onClick={() => setShowPlaylistDialog(true)}
            >
              <Icon name="link" size={22} className="cmaction__icon" />
              <span>Add to Playlist</span>
            </button>

            {menuOrigin === 'PLAYLIST' && originPlaylistId !== null && (
              <button
                type="button"
                className="cmaction cmaction--danger"
                onClick={() => {
                  if (originPlaylistId === 'liked_songs') {
                    // Liked Music: removing = unliking (the auto playlist).
                    onDismissRequest();
                    void toggleLike({
                      videoId: track.videoId,
                      title: track.title,
                      artist: track.artist,
                      album: track.album ?? null,
                      durationSec: track.durationSec ?? null,
                      thumbnailUrl: track.thumbnailUrl ?? null,
                    }).then((ok) => {
                      toast(ok ? `Removed ${track.title} from Liked Music` : 'Remove failed', ok ? 'success' : 'error');
                    });
                    return;
                  }
                  const pid = originPlaylistId;
                  onDismissRequest();
                  setBusy(true);
                  void removeTrackFromPlaylist(pid, track.trackId).then((ok) => {
                    setBusy(false);
                    toast(ok ? `Removed ${track.title}` : 'Remove failed', ok ? 'success' : 'error');
                  });
                }}
              >
                <Icon name="warning" size={22} className="cmaction__icon" />
                <span>
                  {originPlaylistId === 'liked_songs'
                    ? 'Remove from Liked Music'
                    : 'Remove from this playlist'}
                </span>
              </button>
            )}

            {artistNavigable && (
              <button type="button" className="cmaction" onClick={goToArtist}>
                <Icon name="stars" size={22} className="cmaction__icon" />
                <span>Go to Artist</span>
              </button>
            )}

            {albumNavigable && (
              <button type="button" className="cmaction" onClick={goToAlbum}>
                <Icon name="album" size={22} className="cmaction__icon" />
                <span>Go to Album</span>
              </button>
            )}

            <button
              type="button"
              className="cmaction"
              onClick={() => {
                void navigator.clipboard
                  .writeText(shareUrl)
                  .then(() => toast('Link copied'))
                  .catch(() => toast('Copy failed — clipboard unavailable', 'error'));
                onDismissRequest();
              }}
            >
              <Icon name="link" size={22} className="cmaction__icon" />
              <span>Share</span>
            </button>
          </div>
        ) : (
          <div className="cmplaylists">
            <button
              type="button"
              className="cmplaylists__create"
              onClick={handleCreateAndAdd}
              disabled={busy || newPlaylistName.trim() === ''}
            >
              <Icon name="favoriteBorder" size={20} />
              <span>Create &ldquo;{newPlaylistName.trim() || 'New Playlist'}&rdquo; &amp; add</span>
            </button>
            <input
              ref={nameInputRef}
              className="cmplaylists__input"
              type="text"
              placeholder="New playlist name"
              value={newPlaylistName}
              maxLength={80}
              onChange={(event) => setNewPlaylistName(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') handleCreateAndAdd();
              }}
            />
            <div className="cmplaylists__divider" />
            {playlists.length === 0 ? (
              <p className="cmplaylists__empty muted">No playlists created yet.</p>
            ) : (
              <ul className="cmplaylists__list">
                {playlists.map((playlist) => (
                  <li key={playlist.id}>
                    <button
                      type="button"
                      className="cmplaylists__row"
                      disabled={busy}
                      onClick={() => handleAddToPlaylist(playlist.id, playlist.name)}
                    >
                      <span className="cmplaylists__name">{playlist.name}</span>
                      <span className="cmplaylists__count">{playlist.trackCount} songs</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <button
              type="button"
              className="cmplaylists__cancel"
              onClick={() => setShowPlaylistDialog(false)}
            >
              Cancel
            </button>
          </div>
        )}
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// The global host (GlobalTrackContextMenuHost lowering — one per screen)
// ---------------------------------------------------------------------------

export function GlobalTrackContextMenuHost(): JSX.Element | null {
  const isOpen = useContextMenuStore((s) => s.isOpen);
  const track = useContextMenuStore((s) => s.track);
  const dismiss = useContextMenuStore((s) => s.dismiss);
  if (!isOpen || track === null) return null;
  return <ContextMenuSheet track={track} onDismissRequest={dismiss} />;
}
