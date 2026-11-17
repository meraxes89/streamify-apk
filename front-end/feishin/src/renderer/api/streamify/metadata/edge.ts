/**
 * streamify/metadata/edge.ts — the Edge KV client (the "Edge KV Cache"
 * stage of the W1-A pipeline).
 *
 * One GET /ytm/meta call against the resolver's own relay knob
 * (relayBaseCandidate() — the relay production playback rides, so the
 * metadata seam and the playback seam share ONE deployment knob), with:
 *   • an 10 s abort timeout per attempt;
 *   • typed outcomes: ok / not-found (200 + missing) / rate-limited (429,
 *     Retry-After honored) / unavailable (network, 5xx, absent relay —
 *     the caller falls back to the client-side InnerTube lane).
 *
 * The relay being absent ('' — local dev, stock deployments) is NOT an
 * error: it maps to 'unavailable' and the provider falls back.
 */

import type { MetadataSearchFilter } from './types';

import { relayBaseCandidate } from '../playback/resolver/lib/relayBase';

export type EdgeCall = {
    continuation?: string;
    endpoint: string;
    filter?: MetadataSearchFilter;
    gl?: string;
    hl?: string;
    id?: string;
    query?: string;
    signal?: AbortSignal;
};

export type EdgeOutcome =
    | { detail: string; kind: 'unavailable' }
    | { kind: 'not-found' }
    | {
          kind: 'ok';
          /** 200 + missing:true — the honest not-found form. */
          missing: boolean;
          payload: unknown;
          state: 'fill' | 'fresh' | 'stale';
      }
    | { kind: 'rate-limited'; retryAfterMs?: number };

const EDGE_TIMEOUT_MS = 10_000;

/** Injectable fetch (tests); defaults to the ambient fetch. */
export type EdgeFetch = (
    url: string,
    init: { signal: AbortSignal },
) => Promise<{ header(name: string): null | string; json: () => Promise<unknown>; status: number }>;

export const defaultEdgeFetch: EdgeFetch = async (url, init) => {
    const res = await fetch(url, init);
    return {
        header: (name) => res.headers.get(name),
        json: () => res.json(),
        status: res.status,
    };
};

export function edgeBaseUrl(): string {
    return relayBaseCandidate();
}

export async function edgeMetaCall(
    call: EdgeCall,
    fetchImpl: EdgeFetch = defaultEdgeFetch,
): Promise<EdgeOutcome> {
    const base = edgeBaseUrl();
    if (base === '') {
        return { detail: 'relay base is not configured', kind: 'unavailable' };
    }
    const qs = new URLSearchParams();
    qs.set('endpoint', call.endpoint);
    if (call.id !== undefined) qs.set('id', call.id);
    if (call.query !== undefined) qs.set('q', call.query);
    if (call.gl !== undefined) qs.set('gl', call.gl);
    if (call.hl !== undefined) qs.set('hl', call.hl);
    if (call.continuation !== undefined) qs.set('continuation', call.continuation);
    if (call.filter !== undefined) qs.set('filter', call.filter);
    const url = `${base.replace(/\/+$/, '')}/ytm/meta?${qs.toString()}`;

    // The timeout controller composes with the caller's signal (importer
    // cancellation aborts immediately; the 10 s cap bounds hangs).
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), EDGE_TIMEOUT_MS);
    const onCallerAbort = () => timeout.abort();
    call.signal?.addEventListener('abort', onCallerAbort);
    try {
        const res = await fetchImpl(url, { signal: timeout.signal });
        if (res.status === 429) {
            const retryAfterRaw = res.header('retry-after');
            const retryAfterMs =
                retryAfterRaw !== null && Number.isFinite(Number(retryAfterRaw))
                    ? Number(retryAfterRaw) * 1000
                    : undefined;
            return { kind: 'rate-limited', retryAfterMs };
        }
        if (res.status >= 500) {
            return { detail: `edge answered ${res.status}`, kind: 'unavailable' };
        }
        if (res.status >= 400) {
            return { detail: `edge rejected the request (${res.status})`, kind: 'unavailable' };
        }
        let body: unknown;
        try {
            body = await res.json();
        } catch (err) {
            return { detail: `edge body is not JSON (${String(err)})`, kind: 'unavailable' };
        }
        const parsed = body as {
            cache?: { state?: unknown };
            missing?: unknown;
            payload?: unknown;
        };
        if (parsed === null || typeof parsed !== 'object') {
            return { detail: 'edge body is not an object', kind: 'unavailable' };
        }
        if (parsed.missing === true) {
            return { kind: 'not-found' };
        }
        if (parsed.payload === undefined) {
            return { detail: 'edge 200 without payload and without missing', kind: 'unavailable' };
        }
        const state = parsed.cache?.state;
        return {
            kind: 'ok',
            missing: false,
            payload: parsed.payload,
            state: state === 'fill' || state === 'stale' ? state : 'fresh',
        };
    } catch (err) {
        return {
            detail: `edge fetch failed (${err instanceof Error ? err.message : String(err)})`,
            kind: 'unavailable',
        };
    } finally {
        clearTimeout(timer);
        call.signal?.removeEventListener('abort', onCallerAbort);
    }
}
