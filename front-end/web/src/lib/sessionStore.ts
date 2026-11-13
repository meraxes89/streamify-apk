/**
 * lib/sessionStore.ts — P3a: the shared session/auth store (Zustand).
 *
 * One source of truth for the Supabase session across every surface —
 * the P2 bug was App and AuthPanel each holding a private session copy
 * (signing in via the panel left the app's likes/telemetry dark until a
 * full reload). The store boots once (AppShell), consumes OAuth
 * redirects, pulls cloud likes, and exposes the Google sign-in lane.
 */

import { create } from 'zustand';
import {
  fetchLikes,
  getSession,
  likeTrack,
  signInWithGoogle,
  signOut as supabaseSignOut,
  supabaseConfigured,
  takePendingOAuthError,
  takePendingOAuthSession,
  unlikeTrack,
  type CloudTrack,
  type SupabaseSession,
} from './supabase';

export interface LikeableTrack {
  videoId: string;
  title: string;
  artist: string;
  album?: string | null;
  durationSec?: number | null;
  thumbnailUrl?: string | null;
}

export interface SessionState {
  session: SupabaseSession | null;
  booted: boolean;
  authError: string | null;
  likes: CloudTrack[];
  likedVideoIds: Set<string>;
  likesBusy: boolean;
  configured: boolean;
  /** Boot: consume an OAuth redirect (if any) → persisted session → likes. */
  init(): Promise<void>;
  /** Google OAuth (full-page redirect; supabase credentials required). */
  signInWithGoogle(): boolean;
  signOut(): Promise<void>;
  /** Like/unlike toggle against the cloud library (signed-in only). */
  toggleLike(track: LikeableTrack): Promise<boolean>;
  isLiked(videoId: string): boolean;
}

export const useSessionStore = create<SessionState>((set, get) => ({
  session: null,
  booted: false,
  authError: null,
  likes: [],
  likedVideoIds: new Set<string>(),
  likesBusy: false,
  configured: supabaseConfigured(),

  async init() {
    // An OAuth redirect lands with the tokens pre-parsed (consumeOAuthRedirect
    // ran before render); surface its error, if any.
    const oauthError = takePendingOAuthError();
    const oauthSession = takePendingOAuthSession();
    let session: SupabaseSession | null = oauthSession;
    if (session === null && supabaseConfigured()) {
      session = await getSession();
    }
    set({ session, authError: oauthError, booted: true });
    if (session !== null) {
      void loadLikes(set, get);
    }
  },

  signInWithGoogle() {
    if (!supabaseConfigured()) {
      set({ authError: 'Supabase credentials not baked into this build.' });
      return false;
    }
    return signInWithGoogle();
  },

  async signOut() {
    await supabaseSignOut();
    set({ session: null, likes: [], likedVideoIds: new Set<string>() });
  },

  async toggleLike(track) {
    const { session, likes } = get();
    if (session === null) return false;
    const existing = likes.find(
      (l) =>
        l.streamUrl === track.videoId ||
        (l.title === track.title && l.artist === track.artist),
    );
    if (existing === undefined) {
      const ok = await likeTrack({
        title: track.title,
        artist: track.artist,
        album: track.album ?? null,
        durationSec: track.durationSec ?? null,
        coverUrl: track.thumbnailUrl ?? null,
        videoId: track.videoId,
      });
      if (ok) {
        set({
          likes: [
            {
              id: `${track.videoId}`,
              title: track.title,
              artist: track.artist,
              album: track.album ?? null,
              durationSec: track.durationSec ?? null,
              coverUrl: track.thumbnailUrl ?? null,
              streamUrl: track.videoId,
            },
            ...likes,
          ],
        });
        syncLikedIds(set, get);
      }
      return ok;
    }
    const ok = await unlikeTrack(existing.id);
    if (ok) {
      set({ likes: likes.filter((l) => l.id !== existing.id) });
      syncLikedIds(set, get);
    }
    return ok;
  },

  isLiked(videoId) {
    return get().likedVideoIds.has(videoId);
  },
}));

type SetState = (
  partial:
    | Partial<SessionState>
    | ((prev: SessionState) => Partial<SessionState>),
) => void;
type GetState = () => SessionState;

function syncLikedIds(set: SetState, get: GetState): void {
  set({ likedVideoIds: new Set(get().likes.map((l) => l.streamUrl ?? '')) });
}

function loadLikes(set: SetState, get: GetState): void {
  set({ likesBusy: true });
  void fetchLikes()
    .then((rows) => {
      set({ likes: rows, likesBusy: false });
      syncLikedIds(set, get);
    })
    .catch(() => {
      set({ likesBusy: false });
    });
}
