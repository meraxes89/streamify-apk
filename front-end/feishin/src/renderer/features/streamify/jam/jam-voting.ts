/**
 * features/streamify/jam/jam-voting.ts — W3-B: the Jam live-queue voting
 * policy + the ephemeral-reaction throttle, as pure functions over the
 * P4B CRDT's element shape.
 *
 * VOTING SEMANTICS (ADR'd in W3B-wrapped-jam-eq-presets.md):
 *   - +1 per sender: the CRDT fold (jam-queue-crdt.ts, byte-frozen and
 *     Rust-mirrored) already maps votes per senderNonce with max-op-id
 *     overwrite — a re-vote never doubles, so "toggle per userId /
 *     deviceId" is exactly the fold's register semantics. The client-side
 *     guard (upvoteTrack skipping an already-voted element) keeps the op
 *     journal free of no-op votes; removing a vote would need a new frozen
 *     op type and stays out of scope by design.
 *   - Queue presentation order: (voteCount DESC, addedAt ASC). addOpId is
 *     unix_ms<<16|counter, so ascending op id IS ascending added time —
 *     the deterministic tie-break. The CRDT's fractional storage order is
 *     NEVER rewritten by votes: this is the view order only.
 *   - The currently playing element (when known) is pinned first — voting
 *     never reorders what's already playing.
 *
 * REACTIONS: at most one emitted reaction per 500 ms per client (flood
 * control); the emoji whitelist lives on the wire contract
 * (JAM_REACTION_EMOJIS in jam-live-protocol.ts).
 */

import type { JamQueueElement } from '/@/renderer/api/streamify/jam-live/jam-queue-crdt';

import { JAM_REACTION_EMOJIS } from '/@/renderer/api/streamify/jam-live/jam-live-protocol';

export { JAM_REACTION_EMOJIS };

/** Client-side flood control: one emitted reaction per 500 ms. */
export const JAM_REACTION_MIN_INTERVAL_MS = 500;

const JAM_REACTION_EMOJIS_SET: ReadonlySet<string> = new Set(JAM_REACTION_EMOJIS);

export interface JamVotingOrderOptions {
    /** The currently playing element's ADD op id — pinned first when present. */
    pinnedAddOpId?: null | string;
}

/** May a reaction be emitted at nowMs, given the last emit timestamp? */
export function canSendJamReaction(lastSentAtMs: number, nowMs: number): boolean {
    if (!Number.isFinite(lastSentAtMs)) return true; // never sent
    return nowMs - lastSentAtMs >= JAM_REACTION_MIN_INTERVAL_MS;
}

/** Has this sender (device nonce hex) already voted the element? */
export function hasMyJamVote(
    element: Pick<JamQueueElement, 'votes'>,
    senderNonceHex: string,
): boolean {
    return element.votes.has(senderNonceHex);
}

export function isJamReactionEmoji(emoji: string): boolean {
    return JAM_REACTION_EMOJIS_SET.has(emoji);
}

/**
 * The pending-queue presentation order: (voteCount DESC, addedAt ASC).
 * Takes the CRDT's live elements in storage order and returns the voting
 * view. The pinned element (the currently playing track, when known)
 * always stays first — votes never reorder what's already playing.
 */
export function jamVotingOrder<T extends Pick<JamQueueElement, 'addOpId' | 'votes'>>(
    orderedLive: readonly T[],
    opts: JamVotingOrderOptions = {},
): T[] {
    const pinned = opts.pinnedAddOpId ?? null;
    const candidates = [...orderedLive];
    let pinnedElement: null | T = null;
    if (pinned !== null) {
        const index = candidates.findIndex((el) => el.addOpId.toString(10) === pinned);
        if (index !== -1) {
            pinnedElement = candidates.splice(index, 1)[0] ?? null;
        }
    }
    candidates.sort((a, b) => {
        const byVotes = b.votes.size - a.votes.size;
        if (byVotes !== 0) return byVotes;
        // addOpId = unix_ms<<16 | counter → ascending id is ascending addedAt.
        return a.addOpId < b.addOpId ? -1 : a.addOpId > b.addOpId ? 1 : 0;
    });
    return pinnedElement !== null ? [pinnedElement, ...candidates] : candidates;
}
