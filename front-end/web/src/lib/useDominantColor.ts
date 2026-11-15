/**
 * lib/useDominantColor.ts — P1: the player/lyrics ambient tint.
 *
 * The Kotlin surfaces receive `dominantColor: Color` from the palette
 * pipeline; the web lowerings (FullPlayerSheet's radial glow, LyricsScreen's
 * vocal glow) derive it from the track artwork: a CORS-anonymous image
 * (ytimg serves Access-Control-Allow-Origin: *) downsampled to 12×12 on a
 * canvas, averaged, then nudged toward the theme's near-black so the glow
 * never floods the OLED base. Every failure path (CORS refusal, decode
 * error, missing art) falls back to BgPlayer #0a0a0a — never a fake tint.
 */

import { useEffect, useState } from 'react';
import { colors } from '../theme/tokens';

const FALLBACK = colors.bgPlayer; // #0a0a0a — StreamifyColors.BgPlayer

const cache = new Map<string, string>();

function toHex(n: number): string {
  return Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0');
}

function averageOf(image: HTMLImageElement): string | null {
  try {
    const size = 12;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (ctx === null) return null;
    ctx.drawImage(image, 0, 0, size, size);
    const data = ctx.getImageData(0, 0, size, size).data;
    let r = 0;
    let g = 0;
    let b = 0;
    let count = 0;
    for (let i = 0; i < data.length; i += 4) {
      const a = data[i + 3];
      if (a < 32) continue; // transparent sample — skip
      r += data[i];
      g += data[i + 1];
      b += data[i + 2];
      count += 1;
    }
    if (count === 0) return null;
    r /= count;
    g /= count;
    b /= count;
    // Desaturate ~35% and sink toward the theme base so the glow reads as
    // ambience, not a colored flood (the palette-extracted Kotlin values
    // are similarly muted by the 0.24–0.35 alphas at their draw sites).
    const gray = 0.299 * r + 0.587 * g + 0.114 * b;
    r = gray + (r - gray) * 0.65;
    g = gray + (g - gray) * 0.65;
    b = gray + (b - gray) * 0.65;
    const sink = (v: number): number => v * 0.62 + 18; // toward #121212-ish
    return `#${toHex(sink(r))}${toHex(sink(g))}${toHex(sink(b))}`;
  } catch {
    return null; // tainted canvas / CORS — the fallback is honest
  }
}

function resolve(url: string, onDone: (hex: string) => void): () => void {
  const cached = cache.get(url);
  if (cached !== undefined) {
    onDone(cached);
    return () => undefined;
  }
  let cancelled = false;
  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.decoding = 'async';
  img.onload = (): void => {
    const hex = averageOf(img) ?? FALLBACK;
    cache.set(url, hex);
    if (!cancelled) onDone(hex);
  };
  img.onerror = (): void => {
    cache.set(url, FALLBACK);
    if (!cancelled) onDone(FALLBACK);
  };
  img.src = url;
  return () => {
    cancelled = true;
    img.onload = null;
    img.onerror = null;
  };
}

/** The ambient tint for the passed artwork URL (BgPlayer until resolved). */
export function useDominantColor(thumbnailUrl: string | null | undefined): string {
  const [hex, setHex] = useState<string>(FALLBACK);
  const url = typeof thumbnailUrl === 'string' && thumbnailUrl !== '' ? thumbnailUrl : null;
  useEffect(() => {
    setHex(FALLBACK);
    if (url === null) return undefined;
    return resolve(url, setHex);
  }, [url]);
  return hex;
}

export const DOMINANT_FALLBACK = FALLBACK;
