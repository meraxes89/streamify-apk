/**
 * streamify/metadata/provider.ts — the W1-A seam (typed MetadataProvider
 * + the default singleton).
 *
 * THE PIPELINE per call (the W1-A contract —
 * front-end/decisions/waves/W1A-edge-kv-metadata-importer.md):
 *
 *   snapshot lookup → Edge KV (/ytm/meta on the relay) → [on edge
 *   miss/unavailable/invalid] client-side InnerTube (youtubei.js via the
 *   resolver's getYt) → validate → Snapshot Upsert.
 *
 * FAILURE SEPARATION (three modes, never cross-contaminated):
 *   • not-found  — the edge answered missing:true, or both tiers
 *     normalized to an empty shelf. ZERO retries; remembered 15 min in
 *     the snapshot so repeat calls never re-hit.
 *   • rate-limited — 429 anywhere in the edge chain. The provider runs
 *     exponential backoff (3 attempts, 250 ms base, doubling) and then
 *     OPENS THE CIRCUIT for 60 s: every further edge call fails fast
 *     with the typed rate-limited result while the cooldown runs (the
 *     circuit guards the EDGE tier only — the client-side lane still
 *     gets its one attempt; if that also fails, rate-limited surfaces).
 *   • parse-error — a payload failed the client-side schema validator.
 *     The diagnostic is logged, a clean fallback runs; only a second
 *     failure surfaces the typed result.
 *
 * StreamifyController stays strictly snapshot-backed (Engineer B wires
 * W1-B against this interface; the controller never parses an innertube
 * node). Nothing in this module touches Supabase — the P1 boundary rule
 * now names the Cloudflare edge cache as THE global metadata cache.
 */

import type {
    LyricsPayload,
    MetadataPayload,
    MetadataProvider,
    MetadataResult,
    MetadataSearchFilter,
    PlaylistPayload,
    SearchPayload,
    SearchSuggestPayload,
} from './types';

import { type EdgeFetch, edgeMetaCall } from './edge';
import {
    fallbackAlbum,
    fallbackArtist,
    fallbackExplore,
    fallbackHomeFeed,
    fallbackLyrics,
    fallbackPlaylist,
    fallbackRelated,
    fallbackSearch,
    fallbackSearchSuggestions,
    fallbackUpNext,
} from './innertube-fallback';
import {
    validateAlbumPayload,
    validateArtistPayload,
    validateLyricsPayload,
    validatePlaylistPayload,
    validateRelatedPayload,
    validateSearchPayload,
    validateSearchSuggestPayload,
    validateShelvesPayload,
    validateUpNextPayload,
    type ValidationOutcome,
} from './normalize';
import { snapshotKey, snapshotLookup, snapshotUpsert } from './snapshot';

import { logger } from '/@/renderer/utils/logger';

// ---------------------------------------------------------------------------
// The circuit guard (edge tier only)
// ---------------------------------------------------------------------------

const BACKOFF_BASE_MS = 250;
const BACKOFF_ATTEMPTS = 3;
const CIRCUIT_COOLDOWN_MS = 60 * 1000;

interface CircuitState {
    openUntilMs: number;
}

const circuit: CircuitState = { openUntilMs: 0 };

/** Injectable clock + logging + edge fetch (the test lane); production
 * defaults below. */
export interface ProviderDeps {
    edgeFetch?: EdgeFetch;
    log?: (message: string) => void;
    now?: () => number;
    sleep?: (ms: number) => Promise<void>;
}

const defaultDeps: ProviderDeps = {
    // Direct module import (not the store barrel): the barrel drags the
    // player store's chain in, which is fatal for the network-free node
    // test lane (same discipline as the P5-A canary panel).
    log: (message) => logger.warn(`[streamify/metadata] ${message}`),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

type EdgeAttemptOutcome =
    | { detail: string; kind: 'edge-unavailable' }
    | { kind: 'edge-not-found' }
    | { kind: 'edge-ok'; payload: unknown; state: 'fill' | 'fresh' | 'stale' }
    | { kind: 'edge-rate-limited' };

interface RunOptions<TPayload> {
    continuation?: string;
    /** The edge endpoint name. */
    endpoint: string;
    /** The fallback runner (client-side InnerTube lane). */
    fallback: () => Promise<null | TPayload>;
    filter?: MetadataSearchFilter;
    /** The id half of the key identity (absent on query endpoints). */
    id?: string;
    query?: string;
    signal?: AbortSignal;
    /** Client-side guard for the FALLBACK payload too (double failure → parse-error). */
    validateEdge: (payload: unknown) => ValidationOutcome<TPayload>;
    validateFallback?: (payload: TPayload) => boolean;
}

function createProvider(deps: ProviderDeps = {}): MetadataProvider {
    const resolve = (gl?: string, hl?: string) => ({
        gl: (gl ?? 'US').trim() || 'US',
        hl: (hl ?? 'en').trim() || 'en',
    });

    return {
        async getAlbum(browseId, gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'album',
                    fallback: () => fallbackAlbum(browseId, d.gl, d.hl),
                    id: browseId,
                    validateEdge: validateAlbumPayload,
                },
                deps,
                d,
            );
        },
        async getArtist(browseId, gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'artist',
                    fallback: () => fallbackArtist(browseId, d.gl, d.hl),
                    id: browseId,
                    validateEdge: validateArtistPayload,
                },
                deps,
                d,
            );
        },
        async getExplore(gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'explore',
                    fallback: () => fallbackExplore(d.gl, d.hl),
                    id: 'FEmusic_explore',
                    validateEdge: (payload) => validateShelvesPayload(payload, 'explore'),
                },
                deps,
                d,
            );
        },
        async getHomeFeed(gl, hl) {
            const d = resolve(gl, hl);
            return run(
                {
                    endpoint: 'home',
                    fallback: () => fallbackHomeFeed(d.gl, d.hl),
                    id: 'FEmusic_home',
                    validateEdge: (payload) => validateShelvesPayload(payload, 'home'),
                },
                deps,
                d,
            );
        },
        async getLyrics(videoId, hl) {
            const d = resolve(undefined, hl);
            return run<LyricsPayload>(
                {
                    endpoint: 'lyrics',
                    fallback: () => fallbackLyrics(videoId, d.hl),
                    id: videoId,
                    validateEdge: validateLyricsPayload,
                    validateFallback: (payload) =>
                        payload.lines.length > 0 && typeof payload.synced === 'boolean',
                },
                deps,
                d,
            );
        },
        async getPlaylist(browseId, continuation, signal) {
            const d = resolve();
            return run<PlaylistPayload>(
                {
                    continuation,
                    endpoint: 'playlist',
                    fallback: () => fallbackPlaylist(browseId, continuation ?? null),
                    id: browseId,
                    signal,
                    validateEdge: validatePlaylistPayload,
                    validateFallback: (payload) => payload.tracks.length > 0,
                },
                deps,
                d,
            );
        },
        async getRelated(seedVideoId) {
            const d = resolve();
            return run(
                {
                    endpoint: 'related',
                    fallback: () => fallbackRelated(seedVideoId),
                    id: seedVideoId,
                    validateEdge: validateRelatedPayload,
                },
                deps,
                d,
            );
        },
        async getSearchSuggestions(input, gl, hl) {
            const d = resolve(gl, hl);
            return run<SearchSuggestPayload>(
                {
                    endpoint: 'search-suggest',
                    fallback: () => fallbackSearchSuggestions(input),
                    query: input,
                    validateEdge: validateSearchSuggestPayload,
                    validateFallback: (payload) => payload.suggestions.length > 0,
                },
                deps,
                d,
            );
        },
        async getUpNext(seedVideoId) {
            const d = resolve();
            return run(
                {
                    endpoint: 'up-next',
                    fallback: () => fallbackUpNext(seedVideoId),
                    id: seedVideoId,
                    validateEdge: validateUpNextPayload,
                },
                deps,
                d,
            );
        },
        async search(query, filter, continuation, gl, hl) {
            const d = resolve(gl, hl);
            return run<SearchPayload>(
                {
                    continuation,
                    endpoint: 'search',
                    fallback: () => fallbackSearch(query, filter ?? null),
                    filter,
                    query,
                    validateEdge: validateSearchPayload,
                    validateFallback: (payload) => payload.results.length > 0,
                },
                deps,
                d,
            );
        },
    };
}

// ---------------------------------------------------------------------------
// The edge call with backoff + circuit
// ---------------------------------------------------------------------------

async function edgeWithBackoff(
    call: Parameters<typeof edgeMetaCall>[0],
    deps: ProviderDeps,
): Promise<EdgeAttemptOutcome> {
    const now = deps.now ?? Date.now;
    if (circuit.openUntilMs > now()) {
        return { kind: 'edge-rate-limited' };
    }
    let delay = BACKOFF_BASE_MS;
    for (let attempt = 1; attempt <= BACKOFF_ATTEMPTS; attempt += 1) {
        if (signalAborted(call.signal)) {
            return { detail: 'aborted', kind: 'edge-unavailable' };
        }
        const outcome = await edgeMetaCall(call, deps.edgeFetch);
        if (outcome.kind === 'ok') {
            return { kind: 'edge-ok', payload: outcome.payload, state: outcome.state };
        }
        if (outcome.kind === 'not-found') {
            return { kind: 'edge-not-found' };
        }
        if (outcome.kind === 'rate-limited') {
            if (attempt < BACKOFF_ATTEMPTS) {
                // Retry-After is a FLOOR on the exponential ramp, never a
                // replacement — the doubling still climbs underneath it.
                const waitMs = Math.max(delay, outcome.retryAfterMs ?? 0);
                await sleep(deps, waitMs);
                delay *= 2;
                continue;
            }
            break;
        }
        // unavailable — one shot is enough (network/5xx/absent relay is not
        // transient enough to burn the caller's latency budget on).
        return { detail: outcome.detail, kind: 'edge-unavailable' };
    }
    // Backoff exhausted on 429s — open the circuit.
    circuit.openUntilMs = now() + CIRCUIT_COOLDOWN_MS;
    return { kind: 'edge-rate-limited' };
}

function logDiagnostic(deps: ProviderDeps, message: string): void {
    (deps.log ?? defaultDeps.log)?.(message);
}

// ---------------------------------------------------------------------------
// The generic pipeline runner
// ---------------------------------------------------------------------------

async function run<TPayload>(
    options: RunOptions<TPayload>,
    deps: ProviderDeps,
    defaults: { gl: string; hl: string },
): Promise<MetadataResult<TPayload>> {
    const now = deps.now ?? Date.now;
    const key = snapshotKey(
        options.endpoint,
        options.query ?? options.id ?? '',
        defaults.gl,
        defaults.hl,
        options.continuation,
    );

    // Stage 0: the snapshot (last-known-good, or a fresh negative entry).
    const cached = snapshotLookup(key, now());
    if (cached !== null) {
        if (cached.negative) return { kind: 'not-found' };
        return { kind: 'ok', payload: cached.payload as TPayload, source: 'snapshot' };
    }

    if (signalAborted(options.signal)) {
        return { detail: 'aborted before the edge call', kind: 'parse-error' };
    }

    // Stage 1: the edge (backoff + circuit).
    const edgeOutcome = await edgeWithBackoff(
        {
            continuation: options.continuation,
            endpoint: options.endpoint,
            filter: options.filter,
            gl: defaults.gl,
            hl: defaults.hl,
            id: options.id === '' ? undefined : options.id,
            query: options.query,
            signal: options.signal,
        },
        deps,
    );

    if (edgeOutcome.kind === 'edge-not-found') {
        // The 404 class: remember it 15 min, zero retries, no fallback — a
        // missing entity is not transient.
        snapshotUpsert(key, null, now());
        return { kind: 'not-found' };
    }

    if (edgeOutcome.kind === 'edge-ok') {
        const validated = options.validateEdge(edgeOutcome.payload);
        if (validated.ok) {
            snapshotUpsert(key, validated.payload as MetadataPayload, now());
            return { kind: 'ok', payload: validated.payload, source: 'edge' };
        }
        // Parse-error class: diagnostic + clean fallback below.
        logDiagnostic(
            deps,
            `edge payload failed validation (${options.endpoint}): ${validated.reason}`,
        );
    } else if (edgeOutcome.kind === 'edge-unavailable' && edgeOutcome.detail !== 'aborted') {
        logDiagnostic(deps, `edge unavailable (${options.endpoint}): ${edgeOutcome.detail}`);
    }

    if (signalAborted(options.signal)) {
        return { detail: 'aborted before the fallback', kind: 'parse-error' };
    }

    // Stage 2: the client-side InnerTube fallback.
    try {
        const payload = await options.fallback();
        if (payload !== null) {
            if (options.validateFallback !== undefined && !options.validateFallback(payload)) {
                return {
                    detail: `fallback payload failed validation (${options.endpoint})`,
                    kind: 'parse-error',
                };
            }
            snapshotUpsert(key, payload as MetadataPayload, now());
            return { kind: 'ok', payload, source: 'fallback' };
        }
    } catch (err) {
        logDiagnostic(
            deps,
            `fallback threw (${options.endpoint}): ${err instanceof Error ? err.message : String(err)}`,
        );
    }

    // Stage 3: the honest terminal results.
    if (edgeOutcome.kind === 'edge-rate-limited') {
        return { kind: 'rate-limited' };
    }
    if (edgeOutcome.kind === 'edge-unavailable' && edgeOutcome.detail === 'aborted') {
        return { detail: 'aborted', kind: 'parse-error' };
    }
    if (edgeOutcome.kind === 'edge-ok') {
        return {
            detail: `payload failed validation on both tiers (${options.endpoint})`,
            kind: 'parse-error',
        };
    }
    // Edge unavailable + fallback empty: the entity simply does not resolve
    // — the not-found form (zero retry storm; the snapshot remembers).
    snapshotUpsert(key, null, now());
    return { kind: 'not-found' };
}

/** Fresh abort read — a function boundary per call: the signal can fire
 * mid-await, so a narrowed `aborted === false` from an earlier check must
 * never suppress a later one. */
function signalAborted(signal: AbortSignal | undefined): boolean {
    return signal?.aborted === true;
}

// ---------------------------------------------------------------------------
// The default singleton (the seam W1-B wires against)
// ---------------------------------------------------------------------------

async function sleep(deps: ProviderDeps, ms: number): Promise<void> {
    await (deps.sleep ?? defaultDeps.sleep)?.(ms);
}

/** The default singleton — the seam W1-B's controller wiring consumes. */
export const metadataProvider: MetadataProvider = createProvider();

/** Test seam: build an isolated provider with injected deps. */
export function createMetadataProviderForTests(deps: ProviderDeps): MetadataProvider {
    return createProvider(deps);
}

/** Test seam: reset the circuit + snapshot state between cases. */
export function resetMetadataProviderForTests(): void {
    circuit.openUntilMs = 0;
}
