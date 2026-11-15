/**
 * components/LyricsCanvas.tsx — P1 port of
 * app/…/ui/components/LyricsCanvas.kt (the 120 FPS canvas lyric wall).
 *
 * The Kotlin original reads LyricsEngine's singleton lines through
 * withFrameNanos; the web port takes the lines + a progress provider
 * (the consuming surface's clock) and renders on a 2D canvas:
 *
 *   • the active-line index derives per frame (findActiveLineIndex —
 *     the LyricsEngine.getActiveIndex analog) with state cached so the
 *     canvas only redraws when something actually moved;
 *   • the scroll offset chases the active row's 130px-pitch slot with a
 *     damped spring (the Animatable + DampingRatioLowBouncy feel);
 *   • focal anchor at 45% height, lines culled ±200px beyond the viewport;
 *   • active paint: white bold 54px; inactive: white @ 47% (120/255),
 *     46px regular — the Kotlin text sizes verbatim.
 *
 * Reduced motion: the spring snaps instead of gliding.
 */

import { useEffect, useRef } from 'react';
import { findActiveLineIndex } from '../lyrics';
import type { LyricsLine } from '../lyrics';

export interface LyricsCanvasProps {
  lines: readonly LyricsLine[];
  /** Read in the draw loop ONLY. */
  progressProvider: () => number;
  className?: string;
}

/** Row pitch in CSS px (Kotlin: 130f). */
const ROW_PITCH = 130;
/** Active/inactive text sizes (Kotlin paint sizes, dp → px 1:1). */
const ACTIVE_SIZE = 54;
const INACTIVE_SIZE = 46;
const ACTIVE_FONT = `700 ${ACTIVE_SIZE}px system-ui, -apple-system, 'Segoe UI', sans-serif`;
const INACTIVE_FONT = `400 ${INACTIVE_SIZE}px system-ui, -apple-system, 'Segoe UI', sans-serif`;
const X_PAD = 48; // Kotlin drawText x = 48f
const FOCAL = 0.45; // center line sits at 45% height
const CULL = 200;

export function LyricsCanvas(props: LyricsCanvasProps): JSX.Element | null {
  const { lines, progressProvider, className } = props;
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null || lines.length === 0) return undefined;
    const ctx = canvas.getContext('2d');
    if (ctx === null) return undefined;

    let raf = 0;
    let activeIndex = 0;
    let scrollOffset = 0;
    let dirty = true;

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    // Spring (the Animatable spring feel): stiffness/damping tuned to
    // settle in ~0.5 s with a single small overshoot.
    let scrollVel = 0;
    const stiffness = 90;
    const damping = 14;

    const resize = (): void => {
      const parent = canvas.parentElement;
      if (parent === null) return;
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = parent.clientWidth;
      const h = parent.clientHeight;
      if (w === 0 || h === 0) return;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      canvas.style.width = `${w}px`;
      canvas.style.height = `${h}px`;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      dirty = true;
    };
    resize();
    const ro = new ResizeObserver(resize);
    if (canvas.parentElement !== null) ro.observe(canvas.parentElement);

    const draw = (): void => {
      const w = canvas.width / (ctx.getTransform().a || 1);
      const h = canvas.height / (ctx.getTransform().d || 1);
      if (w === 0 || h === 0) return;
      ctx.clearRect(0, 0, w, h);
      const centerY = h * FOCAL;
      for (let i = 0; i < lines.length; i++) {
        const yPos = i * ROW_PITCH;
        const onScreen =
          yPos - scrollOffset > -centerY - CULL && yPos - scrollOffset < h + CULL;
        if (!onScreen) continue; // culling (the Kotlin guard)
        const isActive = i === activeIndex;
        ctx.font = isActive ? ACTIVE_FONT : INACTIVE_FONT;
        ctx.fillStyle = isActive ? '#ffffff' : 'rgba(255, 255, 255, 0.47)';
        const baseline = centerY + (yPos - scrollOffset) + ACTIVE_SIZE / 3;
        ctx.fillText(lines[i].text, X_PAD, baseline);
      }
    };

    const frame = (): void => {
      raf = requestAnimationFrame(frame);
      const pos = progressProvider();
      const nextIndex = findActiveLineIndex(lines, pos);
      if (nextIndex !== activeIndex) {
        activeIndex = Math.max(0, nextIndex); // before-first renders line 0 dim
        dirty = true;
      }
      const targetY = activeIndex * ROW_PITCH;
      if (Math.abs(targetY - scrollOffset) > 0.5) {
        if (reduced) {
          scrollOffset = targetY;
        } else {
          // One spring step at display rate.
          const dt = 1 / 60;
          const force = -stiffness * (scrollOffset - targetY);
          scrollVel += force * dt;
          scrollVel -= damping * scrollVel * dt;
          scrollOffset += scrollVel * dt;
        }
        dirty = true;
      } else if (scrollOffset !== targetY) {
        scrollOffset = targetY;
        dirty = true;
      }
      if (dirty) {
        dirty = false;
        draw();
      }
    };
    raf = requestAnimationFrame(frame);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, [lines, progressProvider]);

  if (lines.length === 0) return null;
  return <canvas ref={canvasRef} className={`lyricscanvas ${className ?? ''}`} role="img" aria-label="Lyrics" />;
}
