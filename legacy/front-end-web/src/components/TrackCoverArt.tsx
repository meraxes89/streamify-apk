/**
 * components/TrackCoverArt.tsx — P2 browse port (ui/components/TrackCoverArt.kt).
 *
 * The three-stage cover pipeline, web lowering:
 *   1. the primary artwork URL (engine-provided thumbnail);
 *   2. the derived fallback — `https://i.ytimg.com/vi/<id>/hqdefault.jpg`
 *      when a videoId is derivable from the URL (the Kotlin
 *      buildThumbnailPipeline secondary rung);
 *   3. the generative cover — a seeded two-stop gradient (the Kotlin
 *      fallbackColorSeed hues) with the title/artist set in the middle,
 *      painted as a PERMANENT layer beneath the <img> so pixels simply
 *      land on top when they arrive (no subcomposition churn).
 *
 * Modifier → className; Color.kt values arrive via the --sf-* tokens.
 */

import { useMemo, useState } from 'react';

export interface TrackCoverArtProps {
  coverArtPath: string | null | undefined;
  title: string;
  artist: string;
  /** Square art size in px (the Kotlin sizeDp). */
  sizePx?: number;
  /** Corner radius in px (StreamifyShapes.CardShape ≈ 8px). */
  radiusPx?: number;
  className?: string;
}

/** Extract a YouTube video id from a URL/thumbnail string (best-effort). */
function extractVideoId(raw: string): string | null {
  const patterns = [/watch\?v=([\w-]{6,})/, /youtu\.be\/([\w-]{6,})/, /\/vi\/([\w-]{6,})\//];
  for (const pattern of patterns) {
    const match = raw.match(pattern);
    if (match !== null) return match[1] ?? null;
  }
  return null;
}

/** Java String.hashCode — same seed derivation as the Kotlin fallback. */
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i += 1) {
    h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  }
  return h;
}

/** The two-stop seeded gradient (Kotlin: hsl(hue1, .65, .35) → hsl(hue2, .70, .15)). */
function fallbackGradient(seedText: string): string {
  const seed = javaHash(seedText);
  const hue1 = Math.abs(seed % 360);
  const hue2 = Math.abs((seed * 31) % 360);
  return `linear-gradient(135deg, hsl(${hue1} 65% 35%), hsl(${hue2} 70% 15%))`;
}

export function TrackCoverArt(props: TrackCoverArtProps): JSX.Element {
  const { coverArtPath, title, artist, sizePx = 140, radiusPx = 8, className } = props;

  // The pipeline descriptor (Kotlin: remember(coverArtPath, title, artist)).
  const descriptor = useMemo(() => {
    const raw = typeof coverArtPath === 'string' ? coverArtPath.trim() : '';
    const vid = raw === '' ? null : extractVideoId(raw);
    const primary = raw !== '' && /^https?:\/\//.test(raw) ? raw : null;
    const secondary = vid !== null ? `https://i.ytimg.com/vi/${vid}/hqdefault.jpg` : null;
    return {
      primary,
      secondary: secondary !== primary ? secondary : null,
      gradient: fallbackGradient(`${title}_${artist}`),
    };
  }, [coverArtPath, title, artist]);

  // Failure walks primary → secondary → generative (one state step per downgrade).
  const [stage, setStage] = useState(0);
  const candidateUrl = stage === 0 ? descriptor.primary : stage === 1 ? descriptor.secondary : null;

  const showArtist = artist.trim() !== '' && artist !== 'Unknown';

  return (
    <div
      className={`coverart ${className ?? ''}`}
      style={{
        width: sizePx,
        height: sizePx,
        borderRadius: radiusPx,
        backgroundImage: descriptor.gradient,
      }}
      role="img"
      aria-label={`${title} cover`}
    >
      <div className="coverart__generative" aria-hidden="true">
        <svg className="coverart__note" viewBox="0 0 24 24" width={sizePx * 0.22} height={sizePx * 0.22} fill="currentColor" aria-hidden="true" focusable="false">
          <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
        </svg>
        <span className="coverart__gen-title">{title}</span>
        {showArtist && <span className="coverart__gen-artist">{artist}</span>}
      </div>
      {candidateUrl !== null && (
        <img
          className="coverart__img"
          src={candidateUrl}
          alt=""
          loading="lazy"
          onError={() => {
            setStage((prev) => (prev < 2 ? prev + 1 : prev));
          }}
        />
      )}
    </div>
  );
}
