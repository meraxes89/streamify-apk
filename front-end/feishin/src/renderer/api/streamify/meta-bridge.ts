/**
 * api/streamify/meta-bridge.ts — the W2-B consumer seam over the W1
 * metadata provider (see front-end/decisions/waves/
 * W2B-credits-smartsync-discover.md).
 *
 * WHY A BRIDGE (the merge-order problem): Wave 1 lands the provider in two
 * PRs with two honest shapes — W1-A ships the live implementation behind a
 * `metadataProvider` singleton whose methods resolve the typed
 * `MetadataResult<T>` envelope, W1-B ships the `getMetadataProvider()` /
 * `installMetadataProvider()` installer API whose methods resolve `null |
 * YtmXxx` directly. L2 reconciles the two onto `main`; W2-B (this branch)
 * starts before that reconciliation lands and must rebase onto it with zero
 * structural rewrite. So this module is the ONE place that knows both
 * shapes: it resolves whichever provider module is installed at runtime,
 * duck-types the result forms, and normalizes everything into the flat
 * DTOs below. W2-B's features (credits, discover) consume only
 * `W2bMetaSource` — never the provider module directly.
 *
 * DEGRADATION IS HONEST: before the Wave 1 provider exists (this branch
 * pre-rebase, stock deployments), `resolveMetaSource()` resolves null and
 * every consumer renders its honest empty state — the same doctrine as
 * W1-B's stub provider. No fabricated data, ever.
 *
 * The module specifier is read through a variable + `@vite-ignore` so the
 * pre-rebase tree (where `metadata/provider` does not exist yet) still
 * typechecks and vitest-transforms; at runtime the import only runs inside
 * try/catch and a miss degrades to null.
 */

// ---------------------------------------------------------------------------
// The flat W2-B DTOs (what the features consume)
// ---------------------------------------------------------------------------

/** Structured song credits (the Liner Notes surface). */
export interface MetaCredits {
    hl: string;
    roleGroups: Array<{
        credits: Array<{ name: string; subtitle: null | string }>;
        title: string;
    }>;
    videoId: string;
}

export interface MetaShelfItem {
    browseId: null | string;
    subtitle: null | string;
    thumbnail: null | string;
    title: string;
    videoId: null | string;
}

/** A shelf page (Explore / Home feed cold-start). */
export interface MetaShelfPage {
    shelves: Array<{ items: MetaShelfItem[]; title: string }>;
}

/** One candidate track for the Discover Mix / radio surfaces. */
export interface MetaSongRef {
    artist: null | string;
    durationMs: null | number;
    title: string;
    videoId: string;
}

/** The narrowed provider surface W2-B needs (nothing more). */
export interface W2bMetaSource {
    getCredits(videoId: string, hl?: string): Promise<MetaCredits | null>;
    getExplore(gl?: string, hl?: string): Promise<MetaShelfPage | null>;
    getHomeFeed(gl?: string, hl?: string): Promise<MetaShelfPage | null>;
    getRelated(seedVideoId: string): Promise<MetaSongRef[] | null>;
    getUpNext(seedVideoId: string, continuation?: string): Promise<MetaSongRef[] | null>;
}

// ---------------------------------------------------------------------------
// The shape-agnostic adapter (pure — the vitest lane pins every arm)
// ---------------------------------------------------------------------------

/** Minimal structural views over the two W1 payload families. */
type AnyPayload = Record<string, unknown>;
type AnyProvider = Record<string, unknown>;
type MaybePromise<T> = Promise<T> | T;

/**
 * The MetadataResult envelope's discriminator values (W1-A). The PAYLOADS
 * also carry a `kind` ('credits', 'lyrics', …) — the collision is resolved
 * by checking for the RESULT kinds only: a 'credits'-kind object is a
 * direct payload (the W1-B form), an 'ok'-kind object is an envelope.
 */
const RESULT_KINDS: ReadonlySet<string> = new Set([
    'not-found',
    'ok',
    'parse-error',
    'rate-limited',
]);

/**
 * Adapt any W1 provider shape into the W2bMetaSource. Methods the installed
 * provider lacks resolve their honest miss (null) — e.g. `getCredits`
 * before the W2-B provider exposure lands.
 */
export function createMetaSourceFromProvider(provider: AnyProvider): W2bMetaSource {
    const callSongs = async (method: string, ...args: unknown[]): Promise<MetaSongRef[] | null> => {
        const fn = provider[method];
        if (typeof fn !== 'function') return null;
        try {
            return toSongRefs(
                unwrap(await (fn as (...a: unknown[]) => MaybePromise<unknown>)(...args)),
            );
        } catch {
            return null;
        }
    };
    const callPage = async (method: string, ...args: unknown[]): Promise<MetaShelfPage | null> => {
        const fn = provider[method];
        if (typeof fn !== 'function') return null;
        try {
            return toShelfPage(
                unwrap(await (fn as (...a: unknown[]) => MaybePromise<unknown>)(...args)),
            );
        } catch {
            return null;
        }
    };
    return {
        getCredits: async (videoId, hl) => {
            const fn = provider.getCredits;
            if (typeof fn !== 'function') return null;
            try {
                return toCredits(
                    unwrap(await (fn as (...a: unknown[]) => MaybePromise<unknown>)(videoId, hl)),
                );
            } catch {
                return null;
            }
        },
        getExplore: (gl, hl) => callPage('getExplore', gl, hl),
        getHomeFeed: (gl, hl) => callPage('getHomeFeed', gl, hl),
        getRelated: (seedVideoId) => callSongs('getRelated', seedVideoId),
        getUpNext: (seedVideoId, continuation) => callSongs('getUpNext', seedVideoId, continuation),
    };
}

function asNumber(value: unknown): null | number {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function asString(value: unknown): null | string {
    return typeof value === 'string' && value !== '' ? value : null;
}

/** Normalize a credits payload (W1-A's CreditsPayload shape is canonical). */
function toCredits(payload: unknown): MetaCredits | null {
    if (payload === null || typeof payload !== 'object') return null;
    const record = payload as AnyPayload;
    const roleGroupsRaw = record.roleGroups;
    if (!Array.isArray(roleGroupsRaw)) return null;
    const roleGroups: MetaCredits['roleGroups'] = [];
    for (const groupRaw of roleGroupsRaw) {
        if (groupRaw === null || typeof groupRaw !== 'object') continue;
        const group = groupRaw as AnyPayload;
        const title = asString(group.title) ?? '';
        const creditsRaw = Array.isArray(group.credits) ? group.credits : [];
        const credits = creditsRaw
            .map((creditRaw): null | { name: string; subtitle: null | string } => {
                if (creditRaw === null || typeof creditRaw !== 'object') return null;
                const credit = creditRaw as AnyPayload;
                const name = asString(credit.name);
                if (name === null) return null;
                return { name, subtitle: asString(credit.subtitle) };
            })
            .filter(
                (credit): credit is { name: string; subtitle: null | string } => credit !== null,
            );
        if (credits.length > 0) roleGroups.push({ credits, title });
    }
    if (roleGroups.length === 0) return null;
    return {
        hl: asString(record.hl) ?? 'en',
        roleGroups,
        videoId: asString(record.videoId) ?? '',
    };
}

/** Normalize a shelves page (Explore/Home in both families). */
function toShelfPage(payload: unknown): MetaShelfPage | null {
    if (payload === null || typeof payload !== 'object') return null;
    const record = payload as AnyPayload;
    const shelvesRaw = record.shelves;
    if (Array.isArray(shelvesRaw)) {
        const shelves: MetaShelfPage['shelves'] = [];
        for (const shelfRaw of shelvesRaw) {
            if (shelfRaw === null || typeof shelfRaw !== 'object') continue;
            const shelf = shelfRaw as AnyPayload;
            const title = asString(shelf.title) ?? '';
            const itemsRaw = Array.isArray(shelf.items) ? shelf.items : [];
            const items = itemsRaw
                .map((itemRaw): MetaShelfItem | null => {
                    if (itemRaw === null || typeof itemRaw !== 'object') return null;
                    const item = itemRaw as AnyPayload;
                    const itemTitle = asString(item.title);
                    if (itemTitle === null) return null;
                    return {
                        browseId: asString(item.browseId),
                        subtitle: asString(item.subtitle),
                        thumbnail: asString(item.thumbnail) ?? asString(item.thumbnailUrl),
                        title: itemTitle,
                        videoId: asString(item.videoId),
                    };
                })
                .filter((item): item is MetaShelfItem => item !== null);
            if (items.length > 0) shelves.push({ items, title });
        }
        return { shelves };
    }
    return null;
}

/**
 * Normalize one track-ish record into a MetaSongRef. Reads both families'
 * field spellings: `tracks[].videoId` (W1-A UpNextPayload) vs `songs[]`
 * (W1-B YtmUpNext), `artistText` vs `artist`.
 */
function toSongRef(raw: unknown): MetaSongRef | null {
    if (raw === null || typeof raw !== 'object') return null;
    const record = raw as AnyPayload;
    const videoId = asString(record.videoId) ?? asString(record.id);
    const title = asString(record.title) ?? asString(record.name);
    if (videoId === null || title === null) return null;
    return {
        artist:
            asString(record.artistText) ??
            asString(record.artist) ??
            asString(record.subtitle) ??
            null,
        durationMs:
            asNumber(record.durationMs) ??
            (typeof record.durationSec === 'number' && record.durationSec > 0
                ? Math.round(record.durationSec * 1000)
                : null),
        title,
        videoId,
    };
}

function toSongRefs(rawList: unknown): MetaSongRef[] | null {
    if (!Array.isArray(rawList)) return null;
    const out = rawList.map(toSongRef).filter((t): t is MetaSongRef => t !== null);
    return out;
}

/** Unwrap a provider result: MetadataResult envelope → payload, direct → as-is. */
function unwrap(result: unknown): unknown {
    if (result === null || result === undefined) return null;
    if (typeof result === 'object' && 'kind' in (result as AnyPayload)) {
        const kind = (result as AnyPayload).kind;
        if (typeof kind === 'string' && RESULT_KINDS.has(kind)) {
            if (kind === 'ok' && 'payload' in (result as AnyPayload)) {
                return (result as AnyPayload).payload ?? null;
            }
            // not-found / rate-limited / parse-error → the honest miss.
            return null;
        }
    }
    return result;
}

// ---------------------------------------------------------------------------
// The runtime resolver (lazy, tolerant, cached)
// ---------------------------------------------------------------------------

/** Read through a variable so pre-rebase typecheck/vite never resolve it. */
const PROVIDER_MODULE_SPECIFIER = '../metadata/provider';

let cachedSource: null | W2bMetaSource = null;
let resolved = false;
let testSource: null | W2bMetaSource = null;

/**
 * The production resolver: lazily import the W1 provider module, adapt
 * whatever shape it exports, and cache. Resolves null (honestly) when the
 * module does not exist yet or exposes no provider.
 */
export async function resolveMetaSource(): Promise<null | W2bMetaSource> {
    if (testSource !== null) return testSource;
    if (resolved) return cachedSource;
    resolved = true;
    try {
        const mod = (await import(
            /* @vite-ignore */ PROVIDER_MODULE_SPECIFIER
        )) as unknown as AnyPayload;
        const provider = providerFromModule(mod);
        cachedSource = provider === null ? null : createMetaSourceFromProvider(provider);
    } catch {
        cachedSource = null;
    }
    return cachedSource;
}

/** Test seam: inject a fake source (null restores the runtime resolver). */
export function setMetaSourceForTests(source: null | W2bMetaSource): void {
    testSource = source;
    if (source === null) {
        cachedSource = null;
        resolved = false;
    }
}

/** Resolve the provider object out of whichever module shape Wave 1 shipped. */
function providerFromModule(module: AnyPayload): AnyProvider | null {
    const installer = module.getMetadataProvider;
    if (typeof installer === 'function') {
        try {
            const provider = (installer as () => AnyProvider)();
            if (provider !== null && typeof provider === 'object') return provider;
        } catch {
            /* fall through to the singleton form */
        }
    }
    const singleton = module.metadataProvider;
    if (singleton !== null && typeof singleton === 'object') {
        return singleton as AnyProvider;
    }
    return null;
}
