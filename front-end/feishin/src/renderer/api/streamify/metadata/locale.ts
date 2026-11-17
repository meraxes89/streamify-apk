/**
 * api/streamify/metadata/locale.ts — the hl/gl hint helpers the W1-B
 * catalog/lyrics wiring passes to the MetadataProvider (W1-A owns the
 * actual region defaults; these are the caller-side hints only).
 *
 * hl comes from the active i18next language (the user's UI locale); gl is
 * left to the provider unless a region setting exists. Both are read
 * lazily and defensively — in node tests i18next is not initialized and
 * the helpers honestly resolve undefined.
 */

import i18next from 'i18next';

/** The UI language hint (BCP-47), when i18next is initialized. */
export function currentHl(): string | undefined {
    try {
        if (!i18next.isInitialized) return undefined;
        const lang = i18next.language;
        return typeof lang === 'string' && lang.length > 0 ? lang : undefined;
    } catch {
        return undefined;
    }
}
