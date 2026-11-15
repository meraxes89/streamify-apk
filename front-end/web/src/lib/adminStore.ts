/**
 * lib/adminStore.ts — W-W admin port: the AdminDashboardScreen store
 * (the SupabaseAdminClient + screen-state port, store-per-screen §9.3).
 *
 * Owns the dashboard's data lane so the screen stays pure UI:
 *   • refreshAll — telemetry + jam rooms + comments + edge stats, with a
 *     silent mode for the 30s auto-refresh (the APK's never-stale rule);
 *   • the mutating actions (role toggle, room terminate, comment delete,
 *     broadcast) each refresh on success — verdicts land as toasts via
 *     the caller (the screen), exactly like the APK's Toast lanes;
 *   • top tracks load lazily on first visit of the Top Songs tab.
 */

import { create } from 'zustand';
import {
  adminDeleteComment,
  adminSetUserRole,
  adminTerminateJamSession,
  fetchAdminEdgeStats,
  fetchAdminJamSessions,
  fetchAdminRecentComments,
  fetchAdminTelemetry,
  fetchAdminTopTracks,
  postAdminBroadcast,
  type AdminCommentItem,
  type AdminEdgeMeshStats,
  type AdminJamSession,
  type AdminTelemetry,
  type AdminTopTrack,
  type AdminUserProfile,
} from './supabase';

export type AdminPhase = 'idle' | 'loading' | 'ready' | 'error';

interface AdminState {
  phase: AdminPhase;
  error: string | null;
  telemetry: AdminTelemetry | null;
  jamSessions: AdminJamSession[];
  recentComments: AdminCommentItem[];
  edgeStats: AdminEdgeMeshStats | null;
  topTracks: AdminTopTrack[] | null;
  topTracksError: string | null;
  lastUpdatedAt: number | null;
  /** The user detail sheet (the APK's selectedUserForDetail). */
  selectedUser: AdminUserProfile | null;

  refreshAll: (silent?: boolean) => Promise<void>;
  loadTopTracks: () => Promise<void>;
  selectUser: (user: AdminUserProfile | null) => void;
  toggleUserRole: (user: AdminUserProfile) => Promise<boolean>;
  terminateJam: (sessionId: string) => Promise<boolean>;
  deleteComment: (commentId: string) => Promise<boolean>;
  broadcast: (message: string) => Promise<boolean>;
}

export const useAdminStore = create<AdminState>((set, get) => ({
  phase: 'idle',
  error: null,
  telemetry: null,
  jamSessions: [],
  recentComments: [],
  edgeStats: null,
  topTracks: null,
  topTracksError: null,
  lastUpdatedAt: null,
  selectedUser: null,

  refreshAll: async (silent = false) => {
    if (!silent) set({ phase: 'loading' });
    try {
      const [telemetry, jamSessions, recentComments, edgeStats] = await Promise.all([
        fetchAdminTelemetry(),
        fetchAdminJamSessions(),
        fetchAdminRecentComments(50),
        fetchAdminEdgeStats(),
      ]);
      set({
        telemetry,
        jamSessions,
        recentComments,
        edgeStats,
        phase: 'ready',
        error: null,
        lastUpdatedAt: Date.now(),
      });
    } catch (err) {
      set({
        phase: 'error',
        error: err instanceof Error ? err.message : String(err),
      });
    }
  },

  loadTopTracks: async () => {
    if (get().topTracks !== null) return;
    try {
      const topTracks = await fetchAdminTopTracks(20);
      set({ topTracks, topTracksError: null });
    } catch (err) {
      set({ topTracksError: err instanceof Error ? err.message : String(err) });
    }
  },

  selectUser: (user) => set({ selectedUser: user }),

  toggleUserRole: async (user) => {
    const ok = await adminSetUserRole(user.id, !user.isAdmin);
    if (ok) {
      // Update the cached copy so the badge flips without a round-trip.
      const telem = get().telemetry;
      if (telem !== null) {
        set({
          telemetry: {
            ...telem,
            userList: telem.userList.map((u) => (u.id === user.id ? { ...u, isAdmin: !user.isAdmin } : u)),
          },
          selectedUser:
            get().selectedUser?.id === user.id ? { ...user, isAdmin: !user.isAdmin } : get().selectedUser,
        });
      }
      void get().refreshAll(true);
    }
    return ok;
  },

  terminateJam: async (sessionId) => {
    const ok = await adminTerminateJamSession(sessionId);
    if (ok) {
      set({ jamSessions: get().jamSessions.filter((s) => s.id !== sessionId) });
      void get().refreshAll(true);
    }
    return ok;
  },

  deleteComment: async (commentId) => {
    const ok = await adminDeleteComment(commentId);
    if (ok) {
      set({ recentComments: get().recentComments.filter((c) => c.id !== commentId) });
      void get().refreshAll(true);
    }
    return ok;
  },

  broadcast: async (message) => {
    const ok = await postAdminBroadcast(message);
    if (ok) void get().refreshAll(true);
    return ok;
  },
}));
