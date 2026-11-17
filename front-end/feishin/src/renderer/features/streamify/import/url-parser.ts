/**
 * features/streamify/import/url-parser.ts — the pure YTM URL / raw-ID
 * parser (W1-A's 1-Click importer entry).
 *
 * Accepted forms (the task contract):
 *   • raw ids: PL…, OLAK5uy_…, RDCLAK5uy_…, VL…
 *   • public URLs on youtube.com / music.youtube.com / m.youtube.com —
 *     the `list=` parameter is the import target (a watch URL with a list
 *     imports the LIST, not the single video; a watch URL without one is
 *     not an import target).
 *
 * Normalization: everything becomes a listId for provider.getPlaylist
 * (the worker VL-normalizes on browse — verified live for every form;
 * OLAK5uy_ included). kind='album' exactly on the OLAK5uy_ prefix (the
 * album-URL form; the import title comes from the tracks' album column).
 *
 * Pure and network-free by construction — locked by the importer test
 * suite.
 */

export type ParsedYtmTarget =
    | { kind: 'album'; listId: string }
    | { kind: 'playlist'; listId: string };

export type ParseYtmResult =
    | { error: 'empty'; ok: false }
    | { error: 'unsupported'; input: string; ok: false }
    | { ok: true; target: ParsedYtmTarget };

const ID_PREFIXES = ['PL', 'OLAK5uy_', 'RDCLAK5uy_', 'VL'] as const;

const ALLOWED_HOSTS = new Set([
    'm.youtube.com',
    'music.youtube.com',
    'www.youtube.com',
    'youtube.com',
]);

/** Parse one user-supplied line (URL or raw id). */
export function parseYtmInput(raw: string): ParseYtmResult {
    const input = raw.trim();
    if (input === '') {
        return { error: 'empty', ok: false };
    }

    // Raw id form.
    if (looksLikeListId(input) && !input.includes('/')) {
        return { ok: true, target: classify(input) };
    }

    // URL form.
    let url: URL;
    try {
        url = new URL(input.startsWith('http') ? input : `https://${input}`);
    } catch {
        return { error: 'unsupported', input, ok: false };
    }
    if (!ALLOWED_HOSTS.has(url.hostname.toLowerCase())) {
        return { error: 'unsupported', input, ok: false };
    }

    const list = url.searchParams.get('list');
    if (list !== null && list !== '' && looksLikeListId(list)) {
        return { ok: true, target: classify(list.trim()) };
    }

    // music.youtube.com/browse/VL… path form (the share-sheet browse URL).
    const pathMatch = /^\/browse\/(VL[\w-]+)$/i.exec(url.pathname);
    if (pathMatch !== null) {
        return { ok: true, target: classify(pathMatch[1]) };
    }

    return { error: 'unsupported', input, ok: false };
}

/** Human-facing id for progress/log lines (no scheme noise). */
export function ytmTargetLabel(target: ParsedYtmTarget): string {
    return target.listId;
}

function classify(listId: string): ParsedYtmTarget {
    return listId.startsWith('OLAK5uy_') ? { kind: 'album', listId } : { kind: 'playlist', listId };
}

function looksLikeListId(value: string): boolean {
    return ID_PREFIXES.some((prefix) => value.startsWith(prefix));
}
