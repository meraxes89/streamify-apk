/**
 * store/streamify-jam.store.ts - the Jam live-queue UI state (P4B).
 *
 * A dumb state holder in the P3A pattern: every field is written by the
 * session controller (api/streamify/jam-live/jam-session-controller.ts) and
 * read by the React surface (features/streamify-jam/). No transport, no
 * Supabase, no player imports here - the store stays instantiable in the
 * node test environment and stays conflict-free against the upstream store
 * barrel.
 */

import type { JamTrackPayload } from '/@/renderer/api/streamify/jam-live/jam-live-protocol';

import { create } from 'zustand';

export interface JamHostPlaybackView {
    isPlaying: boolean;
    positionMs: number;
    trackName: null | string;
    updatedAtMs: number;
}

export interface JamParticipantView {
    deviceId: string;
    isHost: boolean;
    isSelf: boolean;
    lastSeenMs: number;
    name: null | string;
    userId: null | string;
}

export interface JamQueueItemView {
    addedBy: string;
    /** The element identity (ADD op id, decimal string). */
    addOpId: string;
    cadId: string;
    /** Resolved metadata (null until a meta/snapshot arrives). */
    track: JamTrackPayload | null;
    upvotes: number;
    /** W3-B: has THIS device already voted the element (+1 per sender). */
    voted: boolean;
}

/** One ephemeral floating reaction (W3-B — display-only, never journaled). */
export interface JamReactionView {
    atMs: number;
    emoji: string;
    /** Unique per-push key (animation identity; not a stable id). */
    key: number;
}

export interface JamSessionView {
    createdAtMs: number;
    /** 6-char uppercase room code. */
    roomCode: string;
    /** Supabase `listening_sessions.id`. */
    sessionId: string;
}

export interface StreamifyJamActions {
    /** Push one ephemeral reaction into the floating feed (capped + pruned). */
    pushReaction: (emoji: string, atMs: number) => void;
    requestEqPanel: () => void;
    reset: () => void;
    setHostDemoted: (demoted: boolean) => void;
    setHostPermissions: (hostOnlySkipSeek: boolean) => void;
    setHostPlayback: (view: JamHostPlaybackView | null) => void;
    setParticipants: (participants: JamParticipantView[]) => void;
    setQueue: (queue: JamQueueItemView[]) => void;
    setSession: (session: JamSessionView | null, isHost: boolean) => void;
    setStatus: (status: StreamifyJamStatus, error?: null | string) => void;
}

export interface StreamifyJamState {
    /** Monotonic counter: settings' EQ quick-link requests the drawer. */
    eqQuickLinkCounter: number;
    /** Latched when the host lease heartbeat returned DEMOTED. */
    hostDemoted: boolean;
    /** The room's permission posture, from the host's snapshot/ticks. */
    hostOnlySkipSeek: boolean;
    /** Drift-corrected mirror of the host playback (follower view). */
    hostPlayback: JamHostPlaybackView | null;
    isDesynced: boolean;
    /** Live participant roster (Supabase Realtime presence view). */
    participants: JamParticipantView[];
    /** Ordered live queue (metadata-resolved, voting-ordered since W3-B). */
    queue: JamQueueItemView[];
    /** W3-B: the ephemeral floating-reaction feed (newest last, capped). */
    reactions: JamReactionView[];
    session: JamSessionView | null;
    /** Connection-state error detail (status === 'error'). */
    sessionError: null | string;
    /** Am I the host (the session row's host_user_id is me). */
    sessionIsHost: boolean;
    status: StreamifyJamStatus;
}

export type StreamifyJamStatus = 'connecting' | 'ended' | 'error' | 'idle' | 'live';

const initialState: StreamifyJamState = {
    eqQuickLinkCounter: 0,
    hostDemoted: false,
    hostOnlySkipSeek: false,
    hostPlayback: null,
    isDesynced: false,
    participants: [],
    queue: [],
    reactions: [],
    session: null,
    sessionError: null,
    sessionIsHost: false,
    status: 'idle',
};

/** Reaction feed bounds: the float animation is ~2.4 s; 24 slots is ample. */
const REACTION_CAP = 24;
const REACTION_MAX_AGE_MS = 10_000;
let reactionKeySeq = 0;

export const useStreamifyJamStoreBase = create<StreamifyJamActions & StreamifyJamState>()(
    (set) => ({
        ...initialState,
        pushReaction: (emoji, atMs) => {
            if (typeof emoji !== 'string' || emoji === '') return;
            const key = (reactionKeySeq += 1);
            set((state) => ({
                reactions: [
                    ...state.reactions.filter((r) => atMs - r.atMs < REACTION_MAX_AGE_MS),
                    { atMs, emoji, key },
                ].slice(-REACTION_CAP),
            }));
        },
        requestEqPanel: () => {
            set((state) => ({ eqQuickLinkCounter: state.eqQuickLinkCounter + 1 }));
        },
        reset: () => set({ ...initialState, participants: [] }),
        setHostDemoted: (hostDemoted) => set({ hostDemoted }),
        setHostPermissions: (hostOnlySkipSeek) => set({ hostOnlySkipSeek }),
        setHostPlayback: (hostPlayback) => set({ hostPlayback }),
        setParticipants: (participants) => set({ participants }),
        setQueue: (queue) => set({ queue }),
        setSession: (session, sessionIsHost) => set({ session, sessionIsHost }),
        setStatus: (status, sessionError = null) => set({ sessionError, status }),
    }),
);

export const useStreamifyJamStore = useStreamifyJamStoreBase;
