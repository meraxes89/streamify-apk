/**
 * components/FluidSyllableText.tsx — P1 port of
 * app/…/ui/components/FluidSyllableText.kt (the karaoke sweep row).
 *
 * PERF CONTRACT (the Kotlin one, on the DOM): the playhead is supplied as
 * a PROVIDER and only ever read inside the per-frame style loop — ticks
 * never re-render this component. The sweep is written straight to the
 * highlight layer's clip-path (a style mutation, zero React state), so
 * the row composes once and animates at display rate.
 *
 * Two sweep modes, chosen by data:
 *  • SYLLABLE — when the parser produced word tags (<mm:ss.xx>), the
 *    highlight is word-accurate: full words fill as they complete, the
 *    active word fills proportionally inside its own bounds (the
 *    findActiveSyllableIndex lane).
 *  • LINEAR — the Kotlin default draw: a single clip sweeping the whole
 *    text box over [lineStartMs, lineEndMs] (lines without word tags get
 *    one whole-line pseudo-syllable from parseLrc, which degenerates to
 *    this same sweep with the line's own bounds).
 *
 * Colors are the Kotlin argbs: base 0x66/0x33 white (active/inactive),
 * past lines lift to 0xAA; highlight is 0xFF white.
 */

import { useEffect, useRef } from 'react';
import { findActiveSyllableIndex } from '../lyrics';
import type { Syllable } from '../lyrics';

export interface FluidSyllableTextProps {
  text: string;
  lineStartMs: number;
  lineEndMs: number;
  /** Read in the style loop ONLY — never in composition. */
  playbackMsProvider: () => number;
  isActive: boolean;
  isPast: boolean;
  /** parseLrc word tags — enables the word-accurate sweep. */
  syllables?: readonly Syllable[];
  onClick?: () => void;
  baseColor?: string;
  highlightColor?: string;
}

const BASE_ACTIVE = 'rgba(255, 255, 255, 0.40)'; // 0x66FFFFFF
const BASE_INACTIVE = 'rgba(255, 255, 255, 0.20)'; // 0x33FFFFFF
const BASE_PAST = 'rgba(255, 255, 255, 0.667)'; // 0xAAFFFFFF
const HIGHLIGHT = '#ffffff'; // 0xFFFFFFFF

export function FluidSyllableText(props: FluidSyllableTextProps): JSX.Element {
  const {
    text,
    lineStartMs,
    lineEndMs,
    playbackMsProvider,
    isActive,
    isPast,
    syllables,
    onClick,
    baseColor,
    highlightColor,
  } = props;

  const rootRef = useRef<HTMLButtonElement | null>(null);
  const hlRef = useRef<HTMLSpanElement | null>(null);

  const words: readonly Syllable[] | null =
    syllables !== undefined && syllables.length > 0 ? syllables : null;

  const base =
    baseColor ?? (isPast ? BASE_PAST : isActive ? BASE_ACTIVE : BASE_INACTIVE);
  const hl = highlightColor ?? HIGHLIGHT;

  // The style loop — mounted only while the row is the ACTIVE line (the
  // only row that moves). Past/future rows are static clips.
  useEffect(() => {
    const root = rootRef.current;
    const hlEl = hlRef.current;
    if (root === null || hlEl === null || !isActive) return undefined;
    const spans = words !== null ? Array.from(hlEl.children) : null;
    let raf = 0;
    const frame = (): void => {
      raf = requestAnimationFrame(frame);
      const pos = playbackMsProvider();
      let sweepPx = 0;
      if (words !== null && spans !== null) {
        // Word-accurate: complete words + the proportional active word.
        const idx = findActiveSyllableIndex(
          { timeMs: lineStartMs, text, syllables: words, durationMs: lineEndMs - lineStartMs },
          pos,
        );
        if (idx < 0) {
          sweepPx = 0;
        } else {
          const syl = words[idx];
          const inSyl = Math.min(1, Math.max(0, (pos - syl.startMs) / Math.max(1, syl.endMs - syl.startMs)));
          const span = spans[idx];
          if (span !== undefined) {
            const spanEl = span as HTMLElement;
            sweepPx = spanEl.offsetLeft + inSyl * spanEl.offsetWidth;
          } else {
            // Layout not measured yet — linear fallback for this frame.
            const frac = Math.min(1, Math.max(0, (pos - lineStartMs) / Math.max(1, lineEndMs - lineStartMs)));
            sweepPx = frac * root.offsetWidth;
          }
        }
      } else {
        // The Kotlin draw: linear over the line's own bounds.
        const frac = Math.min(1, Math.max(0, (pos - lineStartMs) / Math.max(1, lineEndMs - lineStartMs)));
        sweepPx = frac * root.offsetWidth;
      }
      hlEl.style.clipPath = `inset(0 calc(100% - ${Math.max(0, sweepPx).toFixed(1)}px) 0 0)`;
    };
    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, [isActive, words, lineStartMs, lineEndMs, text, playbackMsProvider]);

  // Static clips for non-active rows (past = fully lit, future = dark).
  useEffect(() => {
    const hlEl = hlRef.current;
    if (hlEl === null || isActive) return;
    hlEl.style.clipPath = isPast ? 'inset(0 0 0 0)' : 'inset(0 100% 0 0)';
  }, [isActive, isPast]);

  const layer = (className: string, color: string, ariaHidden: boolean): JSX.Element => (
    <span
      className={className}
      style={{ color }}
      aria-hidden={ariaHidden || undefined}
    >
      {words !== null
        ? words.map((w, i) => (
            <span className="fluidsyl__word" key={`${i}-${w.startMs}`}>
              {w.text}
            </span>
          ))
        : text}
    </span>
  );

  return (
    <button
      ref={rootRef}
      type="button"
      className={`fluidsyl ${isActive ? 'fluidsyl--active' : ''} ${isPast ? 'fluidsyl--past' : ''}`}
      onClick={onClick}
    >
      {layer('fluidsyl__base', base, false)}
      {layer('fluidsyl__hl', hl, true)}
    </button>
  );
}
