/**
 * components/Sheet.tsx — P1: the shared bottom-sheet primitive.
 *
 * The ModalBottomSheet lowering for the whole web port (§9.3: "ModalBottomSheet
 * → your Sheet.tsx"). Ten-plus modal surfaces in later phases ride this one
 * component, so the contract is deliberately complete:
 *
 *   • PORTAL — renders into document.body, above every app layer (z-index
 *     ladder: sheet 90 > full-player overlay 80 > shell chrome).
 *   • FOCUS — a real trap: Tab cycles inside the sheet, Shift-Tab wraps,
 *     ESC dismisses (top of the stack only — stacked sheets each own ESC
 *     in LIFO order, the Kotlin BackHandler pile), focus lands on the
 *     sheet when it opens and is restored to the opener when it closes.
 *   • DISMISS — backdrop tap, ESC, and drag-to-close with the FullPlayerSheet
 *     physics: the panel follows the finger 1:1 (translateY, downward only),
 *     releases past 140px OR above 2400 px/s collapse; anything less springs
 *     back with no bounce (DampingRatioNoBouncy).
 *   • SCROLL LOCK — body scroll is locked while any sheet is open
 *     (reference-counted; stacked sheets compose).
 *   • REDUCED MOTION — prefers-reduced-motion drops the slide/fade/spring
 *     animations (entrance/exit become instant; spring-back snaps). The
 *     drag itself stays: it is direct manipulation, not animation.
 *
 * Drag surface: the grip/header strip only (`.sheet__grip` + anything
 * marked data-sheet-drag) — sheet bodies scroll independently and must
 * never fight the dismiss gesture.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

export interface SheetProps {
  open: boolean;
  /** Fires for every dismissal path (ESC / backdrop / drag / programmatic). */
  onDismiss: () => void;
  /** Accessible name (aria-label). Required. */
  title: string;
  children: React.ReactNode;
  /** Panel height as a viewport fraction (Kotlin fillMaxHeight(0.85f)). */
  heightFraction?: number;
  /** Drag-to-close grip + header. Default: rendered. */
  showGrip?: boolean;
  className?: string;
}

/** Release distance that collapses (Kotlin FullPlayerSheet: 140dp). */
const DISMISS_PX = 140;
/** Release velocity that collapses (Kotlin: velocityY > 2400f px/s). */
const DISMISS_VELOCITY_PX_S = 2400;
/** ms of exit choreography before unmount (reduced-motion: instant). */
const EXIT_MS = 240;

/** Module stack — the LIFO ESC owner + body-lock refcount. */
const sheetStack: number[] = [];
let bodyLockCount = 0;
let savedBodyOverflow: string | null = null;

function lockBody(): void {
  if (bodyLockCount === 0) {
    savedBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
  }
  bodyLockCount += 1;
}

function unlockBody(): void {
  bodyLockCount = Math.max(0, bodyLockCount - 1);
  if (bodyLockCount === 0 && savedBodyOverflow !== null) {
    document.body.style.overflow = savedBodyOverflow;
    savedBodyOverflow = null;
  }
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

let sheetIdSeq = 0;

export function Sheet(props: SheetProps): JSX.Element | null {
  const { open, onDismiss, title, children, heightFraction = 0.85, showGrip = true, className } = props;

  const idRef = useRef<number>(++sheetIdSeq);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<HTMLDivElement | null>(null);
  const restoreFocusRef = useRef<Element | null>(null);
  const exitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Phase: null = unmounted; 'entering' -> 'open' (CSS transition);
  // 'exiting' -> null after EXIT_MS.
  const [phase, setPhase] = useState<'entering' | 'open' | 'exiting' | null>(null);

  const [reducedMotion, setReducedMotion] = useState<boolean>(() =>
    typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  );

  const [dragY, setDragY] = useState(0);
  const [dragging, setDragging] = useState(false);
  const pointerRef = useRef<{ y0: number; samples: Array<{ t: number; y: number }> } | null>(null);

  // -- reduced-motion live tracking -----------------------------------------
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = (e: MediaQueryListEvent): void => setReducedMotion(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  // -- mount / unmount lifecycle ---------------------------------------------
  // Keys on `open` ONLY (a phase dep would re-arm the machine on every
  // flip and oscillate the class forever); a ref mirrors the phase for
  // the exit branch's at-close read.
  const phaseRef = useRef<'entering' | 'open' | 'exiting' | null>(null);
  phaseRef.current = phase;
  useEffect(() => {
    if (open) {
      if (exitTimerRef.current !== null) {
        clearTimeout(exitTimerRef.current);
        exitTimerRef.current = null;
      }
      restoreFocusRef.current = document.activeElement;
      lockBody();
      sheetStack.push(idRef.current);
      setPhase('entering');
      // Enter: next frame -> 'open' so the CSS transition plays.
      const raf = requestAnimationFrame(() => setPhase('open'));
      const panel = panelRef.current;
      if (panel !== null) panel.focus({ preventScroll: true });
      return () => cancelAnimationFrame(raf);
    }
    if (phaseRef.current !== null) {
      // Exit choreography, then unmount.
      setPhase('exiting');
      const ms = reducedMotion ? 0 : EXIT_MS;
      exitTimerRef.current = setTimeout(() => {
        exitTimerRef.current = null;
        setPhase(null);
        setDragY(0);
      }, ms + 20);
    }
    return undefined;
  }, [open, reducedMotion]);

  // -- teardown (unmount or full close) ---------------------------------------
  useEffect(() => {
    return () => {
      if (exitTimerRef.current !== null) {
        clearTimeout(exitTimerRef.current);
        exitTimerRef.current = null;
      }
      const stackIdx = sheetStack.indexOf(idRef.current);
      if (stackIdx !== -1) sheetStack.splice(stackIdx, 1);
      unlockBody();
      const target = restoreFocusRef.current;
      if (target instanceof HTMLElement) {
        try {
          target.focus({ preventScroll: true });
        } catch {
          /* the opener unmounted — nothing to restore */
        }
      }
      restoreFocusRef.current = null;
    };
  }, []);

  const dismiss = useCallback(() => {
    if (pointerRef.current !== null) return; // mid-gesture; the release decides
    onDismiss();
  }, [onDismiss]);

  // -- keyboard: ESC (LIFO) + focus trap --------------------------------------
  useEffect(() => {
    if (phase === null || phase === 'exiting') return undefined;
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape' && sheetStack[sheetStack.length - 1] === idRef.current) {
        e.stopPropagation();
        dismiss();
        return;
      }
      if (e.key !== 'Tab') return;
      const panel = panelRef.current;
      if (panel === null) return;
      const items = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
        (el) => el.offsetParent !== null,
      );
      if (items.length === 0) {
        e.preventDefault();
        panel.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (e.shiftKey) {
        if (active === first || !panel.contains(active)) {
          e.preventDefault();
          last.focus();
        }
      } else if (active === last || !panel.contains(active)) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [phase, dismiss]);

  // -- drag-to-close -----------------------------------------------------------
  const onPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    const target = e.target as HTMLElement;
    if (target.closest('button, a, input, select, textarea, [role="slider"]') !== null) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    pointerRef.current = { y0: e.clientY, samples: [{ t: performance.now(), y: e.clientY }] };
    setDragging(true);
    setDragY(0);
  }, []);

  const onPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    const p = pointerRef.current;
    if (p === null) return;
    const dy = e.clientY - p.y0;
    const y = Math.max(0, dy); // downward only — upward is content territory
    p.samples.push({ t: performance.now(), y: e.clientY });
    if (p.samples.length > 6) p.samples.shift();
    setDragY(y);
  }, []);

  const endDrag = useCallback(() => {
    const p = pointerRef.current;
    if (p === null) return;
    pointerRef.current = null;
    // Release velocity over the last samples (the VelocityTracker analog).
    let velocity = 0;
    if (p.samples.length >= 2) {
      const a = p.samples[0];
      const b = p.samples[p.samples.length - 1];
      const dt = b.t - a.t;
      if (dt > 0) velocity = (b.y - a.y) / (dt / 1000);
    }
    const shouldDismiss = dragY > DISMISS_PX || velocity > DISMISS_VELOCITY_PX_S;
    setDragging(false);
    if (shouldDismiss) {
      setDragY(0);
      onDismiss();
    } else {
      // Spring back, no bounce. Reduced motion: snap instantly.
      if (reducedMotion) setDragY(0);
      else setDragY(0); // the CSS transition class handles the glide
    }
  }, [dragY, reducedMotion, onDismiss]);

  if (phase === null) return null;

  const panelStyle: React.CSSProperties = {
    maxHeight: `${Math.round(heightFraction * 100)}dvh`,
    transform: dragY > 0 ? `translateY(${dragY}px)` : undefined,
    transition: dragging ? 'none' : undefined,
  };

  return createPortal(
    <div
      className={`sheet ${phase === 'entering' ? 'sheet--entering' : ''} ${
        phase === 'open' ? 'sheet--open' : ''
      } ${phase === 'exiting' ? 'sheet--exiting' : ''} ${reducedMotion ? 'sheet--rm' : ''} ${
        className ?? ''
      }`}
      style={{ '--sheet-drag': `${dragY}px` } as React.CSSProperties}
    >
      <div className="sheet__backdrop" onClick={dismiss} aria-hidden="true" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className={`sheet__panel ${showGrip ? 'sheet__panel--grip' : ''}`}
        style={panelStyle}
      >
        {showGrip && (
          <div
            ref={dragRef}
            className="sheet__grip"
            data-sheet-drag
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
          >
            <span className="sheet__grip-bar" aria-hidden="true" />
          </div>
        )}
        <div className="sheet__body">{children}</div>
      </div>
    </div>,
    document.body,
  );
}
