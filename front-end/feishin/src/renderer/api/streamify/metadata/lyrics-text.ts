/**
 * streamify/metadata/lyrics-text.ts — the LRC-line parser (client side).
 *
 * Mirrors the worker's parseLyricsText (the SCHEMA contract includes the
 * synced-detection rule; the 12 lines are duplicated across the runtimes
 * rather than shared — see the W1-A ADR §G). A line "[00:12.34] text"
 * becomes { atMs: 12340, text }; sync is only claimed when EVERY line
 * carries a timestamp.
 */

const LRC_LINE = /^\s*\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]\s*(.*)$/;

export interface ParsedLyrics {
    lines: Array<{ atMs: null | number; text: string }>;
    synced: boolean;
}

export function parseLyricsText(raw: string): ParsedLyrics {
    const lines: ParsedLyrics['lines'] = [];
    let syncedCount = 0;
    for (const rawLine of raw.split('\n')) {
        const m = LRC_LINE.exec(rawLine);
        if (m !== null) {
            const mins = Number(m[1]);
            const secs = Number(m[2]);
            const fracRaw = m[3] ?? '0';
            const frac = Number(fracRaw) / 10 ** fracRaw.length;
            const text = (m[4] ?? '').trim();
            if (text === '') continue;
            lines.push({ atMs: Math.round((mins * 60 + secs + frac) * 1000), text });
            syncedCount += 1;
            continue;
        }
        const text = rawLine.trim();
        if (text === '') continue;
        lines.push({ atMs: null, text });
    }
    return { lines, synced: lines.length > 0 && syncedCount === lines.length };
}
