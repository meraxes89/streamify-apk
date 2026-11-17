/**
 * features/streamify/connect/connect-transport.ts — the W3-A Supabase
 * Realtime channel wrapper (the jam-live-transport pattern, slimmer).
 *
 * ONE user-scoped channel `streamify:connect:<userId>`:
 *   • presence keyed by deviceId — the live roster + now-playing state;
 *   • broadcast `self: false` — the command lane (transport controls and
 *     handoff chunks);
 *
 * The client is INJECTED (the tests drive a fake) and the module holds no
 * network of its own beyond the channel API the caller supplies.
 */

import type { RealtimeChannel } from '@supabase/supabase-js';

import type { ConnectMessage, ConnectPresenceState } from './connect-protocol';

import { parseConnectPresence } from './connect-protocol';

/** The supabase-js surface this transport consumes (injectable; the
 *  option/removeChannel types are deliberately loose — supabase-js's own
 *  signatures (a generic channel(), RealtimeRemoveChannelResponse)
 *  structurally satisfy them). */
export interface ConnectSupabaseLike {
    channel(name: string, options?: unknown): RealtimeChannel;
    removeChannel(channel: RealtimeChannel): Promise<unknown> | unknown;
}

export interface ConnectTransportEvents {
    /** A validated inbound broadcast command (already echo/target filtered
     *  by the controller — the transport only parses). */
    onMessage: (message: ConnectMessage) => void;
    /** The full presence roster (this device included; the caller filters). */
    onPresence: (states: ConnectPresenceState[]) => void;
    onStatus: (status: 'connected' | 'connecting' | 'error' | 'offline') => void;
}

export const CONNECT_PRESENCE_EVENT = 'state';
export const CONNECT_COMMAND_EVENT = 'cmd';

export class StreamifyConnectTransport {
    private readonly channel: RealtimeChannel;

    private closed = false;

    private readonly events: ConnectTransportEvents;

    constructor(
        client: ConnectSupabaseLike,
        userId: string,
        deviceId: string,
        events: ConnectTransportEvents,
    ) {
        this.events = events;
        // The channel name mirrors the jam lane's convention: supabase-js
        // prefixes its realtime namespace itself.
        this.channel = client.channel(`streamify:connect:${userId}`, {
            config: {
                broadcast: { self: false },
                presence: { key: deviceId },
            },
        });
    }

    /** Subscribe + join presence. Resolves on SUBSCRIBED; rejects on error. */
    async connect(): Promise<void> {
        this.events.onStatus('connecting');
        this.channel
            .on('broadcast', { event: CONNECT_COMMAND_EVENT }, (payload: { payload: unknown }) => {
                if (this.closed) return;
                this.events.onMessage(payload.payload as ConnectMessage);
            })
            .on('presence', { event: 'sync' }, () => {
                if (this.closed) return;
                this.events.onPresence(this.readPresence());
            });

        await new Promise<void>((resolve, reject) => {
            this.channel.subscribe((status: string) => {
                if (this.closed) return;
                if (status === 'SUBSCRIBED') {
                    this.events.onStatus('connected');
                    resolve();
                } else if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT') {
                    this.events.onStatus('error');
                    reject(new Error(`connect channel ${status}`));
                }
            });
        });
    }

    /** Remove the channel from the client (the caller owns the client). */
    async dispose(client: ConnectSupabaseLike): Promise<void> {
        await this.leave();
        try {
            await client.removeChannel(this.channel);
        } catch {
            // best-effort
        }
    }

    /** Cleanly leave: untrack, unsubscribe, remove the channel. */
    async leave(): Promise<void> {
        if (this.closed) return;
        this.closed = true;
        this.events.onStatus('offline');
        try {
            await this.channel.untrack();
        } catch {
            // best-effort
        }
        try {
            await this.channel.unsubscribe();
        } catch {
            // best-effort
        }
    }

    /** Publish the presence state (replaces the device's roster entry). */
    async publishPresence(state: ConnectPresenceState): Promise<void> {
        if (this.closed) return;
        try {
            await this.channel.track(state);
        } catch {
            // best-effort — the next keepalive re-publishes
        }
    }

    /** Broadcast one command-lane message. */
    send(message: ConnectMessage): void {
        if (this.closed) return;
        void this.channel.send({
            event: CONNECT_COMMAND_EVENT,
            payload: message,
            type: 'broadcast',
        });
    }

    /** Read + validate the full presence roster. */
    private readPresence(): ConnectPresenceState[] {
        let raw: Record<string, unknown> = {};
        try {
            raw = (this.channel.presenceState?.() ?? {}) as unknown as Record<string, unknown>;
        } catch {
            return [];
        }
        const out: ConnectPresenceState[] = [];
        for (const metas of Object.values(raw)) {
            if (!Array.isArray(metas)) continue;
            for (const meta of metas) {
                const state = parseConnectPresence(meta);
                if (state !== null) out.push(state);
            }
        }
        return out;
    }
}
