/**
 * features/streamify-badges/quality-badge-model.ts — the honest
 * adaptive-format transparency model (W1-B).
 *
 * THE GUARDRAILS (locked on the discussion page, see the W1-B ADR):
 *   - ZERO extra network RPCs: the badges read ONLY the active
 *     StreamUrlResolution (#185/#186 — peekStreamUrl, the resolve that
 *     already happened for playback) and P4-A's OPFS manifests (#192).
 *     No getInfo, no supplemental InnerTube calls, ever.
 *   - TRUTHFUL FIELDS: codec and bitrate come from the live rung exactly
 *     as negotiated; unknown stays unrendered (never a guess).
 *   - STRICT SPATIAL RULE: a Spatial / 5.1 badge renders ONLY when
 *     audioChannels > 2 in the negotiated stream metadata — never
 *     inferred from track titles, codecs or itags.
 */

import type { StreamUrlResolution } from '/@/renderer/api/streamify/playback/stream-source';

export interface QualityBadge {
    kind: QualityBadgeKind;
    /** The i18n label, already formatted (data-driven: codec names and
     * numbers are universal; only kind titles localize). */
    label: string;
}

export interface QualityBadgeFacts {
    /** The OPFS download sidecar exists for this track (P4-A manifest). */
    offline: boolean;
    /** The negotiated resolution (peekStreamUrl of the live videoId). */
    resolution: null | Pick<
        StreamUrlResolution,
        'audioChannels' | 'codecs' | 'ladder' | 'protocol'
    >;
}

export type QualityBadgeKind = 'codec' | 'hls' | 'offline' | 'spatial';

/** Codec descriptors that render as acronyms (the conventional forms —
 *  "Opus" is deliberately absent, it capitalizes). */
const CODEC_ACRONYMS = new Set(['aac', 'ac-3', 'ac-4', 'alac', 'ec-3', 'flac', 'mp3']);

/**
 * Compute the player-bar badges from the honest facts. PURE — no network,
 * no store reads; the component feeds it, the tests lock it.
 */
export function computeQualityBadges(facts: QualityBadgeFacts): QualityBadge[] {
    const badges: QualityBadge[] = [];

    if (facts.offline) {
        // OPFS Offline: the P4-A zero-egress lane is serving this track.
        badges.push({ kind: 'offline', label: 'offline' });
    }

    const resolution = facts.resolution;
    if (resolution !== null) {
        if (resolution.protocol === 'hls') {
            badges.push({ kind: 'hls', label: 'HLS' });
        }

        const liveRung = resolution.ladder[0];
        const codec = codecLabel(resolution.codecs);
        if (codec !== '') {
            const kbps = liveRung !== undefined ? formatKbps(liveRung.bitrate) : null;
            badges.push({
                kind: 'codec',
                label: kbps !== null ? `${codec} · ${kbps}` : codec,
            });
        }

        // THE STRICT SPATIAL RULE: audioChannels > 2 in the negotiated
        // metadata, or nothing. null/2/absent → suppressed, always.
        if (resolution.audioChannels !== null && resolution.audioChannels > 2) {
            badges.push({
                kind: 'spatial',
                label: resolution.audioChannels >= 6 ? '5.1' : `${resolution.audioChannels}ch`,
            });
        }
    }

    return badges;
}

/** Capitalize the codec descriptor exactly as convention writes it
 *  (opus → Opus, aac → AAC, ec-3 → EC-3, video+opus → Video+Opus). */
function codecLabel(codec: string): string {
    if (codec === '') return '';
    return codec
        .split('+')
        .map((segment) =>
            CODEC_ACRONYMS.has(segment.toLowerCase())
                ? segment.toUpperCase()
                : segment.charAt(0).toUpperCase() + segment.slice(1),
        )
        .join('+');
}

/** kbps from the live rung's bitrate (rounded; unknown bitrate → codec only). */
function formatKbps(bitrate: number): null | string {
    if (!Number.isFinite(bitrate) || bitrate <= 0) return null;
    const kbps = Math.round(bitrate / 1000);
    return kbps > 0 ? `${kbps} kbps` : null;
}
