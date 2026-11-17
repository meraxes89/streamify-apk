/**
 * features/streamify/wrapped/components/wrapped-share-card.tsx — W3-B: the
 * client-side Wrapped share card.
 *
 * Renders a crisp 1200×630 summary card (devicePixelRatio-scaled) on an
 * offscreen HTML5 canvas — Top 5 tracks, top artists, total minutes,
 * longest streak, Streamify branding — and triggers a LOCAL PNG download
 * (canvas.toBlob → object URL → anchor click). Zero network calls, zero
 * external fonts: system-ui stack only. The data contract is the pure
 * formatWrappedShareCardData output, so the numbers are exactly the
 * ledger's (pinned by w3b-wrapped.test.ts).
 */

import type { WrappedShareCardData } from '/@/renderer/features/streamify/wrapped/wrapped-engine';

import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { Button } from '/@/shared/components/button/button';
import { toast } from '/@/shared/components/toast/toast';

const CARD_W = 1200;
const CARD_H = 630;
const ACCENT_FALLBACK = '#f76707';

/**
 * The draw pass — a pure function of (ctx, data, colors) so the geometry
 * stays reviewable and the data layer stays dumb.
 */
export function drawWrappedShareCard(
    ctx: CanvasRenderingContext2D,
    data: WrappedShareCardData,
    accent: string,
): void {
    const bg = ctx.createLinearGradient(0, 0, CARD_W, CARD_H);
    bg.addColorStop(0, '#161618');
    bg.addColorStop(1, '#1f1c24');
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, CARD_W, CARD_H);

    // Accent spine down the left edge.
    ctx.fillStyle = accent;
    ctx.fillRect(0, 0, 10, CARD_H);

    ctx.textBaseline = 'alphabetic';

    // Header.
    ctx.fillStyle = accent;
    ctx.font = '700 64px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillText('Streamify Wrapped', 64, 108);
    ctx.fillStyle = 'rgba(255, 255, 255, 0.65)';
    ctx.font = '400 30px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillText(data.windowLabel, 66, 152);

    // Hero stats.
    ctx.fillStyle = '#ffffff';
    ctx.font = '700 78px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillText(data.minutesLabel, 64, 268);
    ctx.fillStyle = 'rgba(255, 255, 255, 0.75)';
    ctx.font = '400 32px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillText(data.playsLabel, 66, 310);
    if (data.streakLabel !== null) {
        ctx.fillStyle = accent;
        ctx.font = '600 32px system-ui, -apple-system, "Segoe UI", sans-serif';
        ctx.fillText(data.streakLabel, 66, 356);
    }

    // Top artists line.
    if (data.topArtistsLabel !== '') {
        ctx.fillStyle = 'rgba(255, 255, 255, 0.85)';
        ctx.font = '400 30px system-ui, -apple-system, "Segoe UI", sans-serif';
        ctx.fillText(data.topArtistsLabel, 64, 420);
    }

    // Top tracks (right column).
    const listX = 640;
    ctx.fillStyle = 'rgba(255, 255, 255, 0.65)';
    ctx.font = '600 26px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillText('Top tracks', listX, 200);
    ctx.font = '400 27px system-ui, -apple-system, "Segoe UI", sans-serif';
    data.topTracks.forEach((track, i) => {
        const y = 248 + i * 58;
        ctx.fillStyle = accent;
        ctx.font = '700 27px system-ui, -apple-system, "Segoe UI", sans-serif';
        ctx.fillText(String(track.rank), listX, y);
        ctx.fillStyle = '#ffffff';
        ctx.font = '400 27px system-ui, -apple-system, "Segoe UI", sans-serif';
        const label = `${track.title} — ${track.artist}`;
        ctx.fillText(ellipsize(ctx, label, CARD_W - listX - 96), listX + 34, y);
        ctx.fillStyle = 'rgba(255, 255, 255, 0.55)';
        ctx.fillText(`×${track.plays}`, CARD_W - 64, y);
    });

    // Footer branding.
    ctx.fillStyle = 'rgba(255, 255, 255, 0.45)';
    ctx.font = '400 24px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillText('Streamify — verified listens only (skips never count)', 64, CARD_H - 48);
}

/** Resolve the live theme accent (best-effort; fallback stays on-brand). */
function accentColor(): string {
    try {
        const value = getComputedStyle(document.documentElement)
            .getPropertyValue('--theme-colors-primary')
            .trim();
        return value !== '' ? value : ACCENT_FALLBACK;
    } catch {
        return ACCENT_FALLBACK;
    }
}

function ellipsize(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string {
    if (ctx.measureText(text).width <= maxWidth) return text;
    let out = text;
    while (out.length > 1 && ctx.measureText(`${out}…`).width > maxWidth) {
        out = out.slice(0, -1);
    }
    return `${out}…`;
}

export const WrappedShareCardButton = ({ data }: { data: WrappedShareCardData }) => {
    const { t } = useTranslation();
    const [busy, setBusy] = useState(false);

    const fileName = useMemo(
        () => `streamify-wrapped-${data.windowLabel.toLowerCase().replace(/[^a-z0-9]+/g, '-')}.png`,
        [data.windowLabel],
    );

    const download = () => {
        setBusy(true);
        try {
            const canvas = document.createElement('canvas');
            const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
            canvas.width = CARD_W * dpr;
            canvas.height = CARD_H * dpr;
            const ctx = canvas.getContext('2d');
            if (ctx === null) {
                toast.error({ message: t('streamifyWrapped.shareCardFailed') });
                return;
            }
            ctx.scale(dpr, dpr);
            drawWrappedShareCard(ctx, data, accentColor());
            canvas.toBlob((blob) => {
                if (blob === null) {
                    toast.error({ message: t('streamifyWrapped.shareCardFailed') });
                    return;
                }
                const url = URL.createObjectURL(blob);
                const anchor = document.createElement('a');
                anchor.href = url;
                anchor.download = fileName;
                anchor.click();
                window.setTimeout(() => URL.revokeObjectURL(url), 5_000);
                toast.success({ message: t('streamifyWrapped.shareCardDone') });
            }, 'image/png');
        } catch {
            toast.error({ message: t('streamifyWrapped.shareCardFailed') });
        } finally {
            setBusy(false);
        }
    };

    return (
        <Button loading={busy} onClick={download} size="sm" variant="outline">
            {t('streamifyWrapped.shareCard')}
        </Button>
    );
};
