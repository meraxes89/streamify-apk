/**
 * components/FluidSyllableLine.tsx — P1 port of
 * app/…/ui/components/FluidSyllableLine.kt (the single-line sweep renderer
 * with the feathered glow bloom at the sweep edge).
 *
 * Where FluidSyllableText drives its sweep from a playhead provider, this
 * component takes the sweep progress directly (0.0–1.0) — the caller owns
 * the clock. The draw stack is the Kotlin one:
 *   1. the dim base text layer;
 *   2. the clipped highlight layer sweeping with progress;
 *   3. the horizontal glow bloom leading the sweep edge (18px feather,
 *      the primary token at 0.45→0.85 alpha, SrcAtop-equivalent blend).
 * The active line scales 1.02 (CompositingStrategy.Offscreen → its own
 * stacking context here).
 */

export interface FluidSyllableLineProps {
  text: string;
  isActive: boolean;
  /** 0.0–1.0 sweep progress across the line. */
  progressFraction: number;
  activeColor?: string;
  inactiveColor?: string;
  glowColor?: string;
}

export function FluidSyllableLine(props: FluidSyllableLineProps): JSX.Element {
  const {
    text,
    isActive,
    progressFraction,
    activeColor = '#ffffff',
    inactiveColor = 'rgba(255, 255, 255, 0.35)',
    glowColor = 'var(--sf-primary)',
  } = props;

  const sweep = Math.min(1, Math.max(0, progressFraction)) * 100;

  return (
    <div
      className={`fluidline ${isActive ? 'fluidline--active' : ''}`}
      style={
        {
          '--fluidline-sweep': `${sweep}%`,
          '--fluidline-active': activeColor,
          '--fluidline-inactive': inactiveColor,
          '--fluidline-glow': glowColor,
        } as React.CSSProperties
      }
    >
      <span className="fluidline__base">{text}</span>
      <span className="fluidline__hl" aria-hidden="true">
        {text}
      </span>
      <span className="fluidline__glow" aria-hidden="true" />
    </div>
  );
}
