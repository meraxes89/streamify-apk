/**
 * screens/FullPlayerSheet.tsx — P1 port of
 * app/…/ui/screens/FullPlayerSheet.kt (the full-player overlay).
 *
 * The overlay the Android MainActivity composes at layer 3
 * (`isPlayerExpanded && hasTrack`, zIndex above the shell) with its own
 * dismissal physics — this is NOT the shared Sheet primitive (that is
 * the ModalBottomSheet lowering); it is the full-screen surface whose
 * collapse gesture is the genuinely fiddly one:
 *
 *   • COLLAPSE DRAG — the header + hero track the finger 1:1 downward
 *     (translateY, alpha falling to 0.4 max); release past 140px OR
 *     above 2400 px/s collapses, anything less springs back with no
 *     bounce (DampingRatioNoBouncy). Upward drags are ignored.
 *   • HERO GESTURES — single tap dims the player chrome (0.35 floor,
 *     3 s auto-recover); long-press opens the lyrics sheet; double-tap
 *     seeks ±10 s with the ripple overlay (right/left side).
 *   • LAYOUT — portrait: the single-column stack (88% hero, max 320px,
 *     24px radius, buffering pulse); landscape (orientation: landscape):
 *     the 42/58 dual-pane with the UP NEXT / LYRICS / RELATED tab
 *     drawer — LocalScreenConfiguration.isLandscape → a matchMedia
 *     lowering.
 *   • AMBIENT — the dominant-color radial glow behind everything
 *     (the artwork-derived tint; BgPlayer until resolved).
 *
 * Web-adapted, documented: the Song/Video switcher, Cast, Canvas-loop
 * backdrop, Comments/Related pills and the BPM chip have no web engine
 * lane yet — omitted rather than shipped as dead controls. The like
 * pill is session-gated (the MiniPlayerBar discipline); the transport
 * row is the shared PlayerControls; the seekbar/volume are the shared
 * PlaybackSeekbar/VolumeSlider (round-trip: they render engine truth,
 * never own it).
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '../components/icons';
import { PlayerControls } from '../components/PlayerControls';
import { PlaybackSeekbar } from '../components/PlaybackSeekbar';
import { VolumeSlider } from '../components/VolumeSlider';
import { Sheet } from '../components/Sheet';
import { usePlayerStore } from '../lib/playerStore';
import { useSessionStore } from '../lib/sessionStore';
import { useDominantColor } from '../lib/useDominantColor';
import {
  LandscapeQueuePane,
  LandscapeLyricsPane,
  LandscapeRelatedPane,
} from './FullPlayerSheetPanes';
import type { LandscapePlayerTab } from './FullPlayerSheetPanes';

/** Collapse physics (Kotlin: 140dp / 2400f px/s / no-bounce spring). */
const COLLAPSE_PX = 140;
const COLLAPSE_VELOCITY = 2400;

/** Long-press arm (ms) — opens the lyrics sheet. */
const LONGPRESS_MS = 500;
/** Double-tap window (ms) — after which the tap commits as a single tap. */
const DOUBLETAP_MS = 260;
/** Ripple overlay dwell (Kotlin: 650 ms). */
const RIPPLE_MS = 650;
/** Double-tap seek delta (Kotlin: ±10_000 ms). */
const SEEK_DELTA_MS = 10_000;
/** Chrome-dim auto-recover (Kotlin: 3000 ms / 220 ms fade; the 0.35 floor lives in CSS). */
const CHROME_RECOVER_MS = 3000;

export interface FullPlayerSheetProps {
  open: boolean;
  onCollapse: () => void;
}

export function FullPlayerSheet(props: FullPlayerSheetProps): JSX.Element | null {
  const { open, onCollapse } = props;

  const snapshot = usePlayerStore((s) => s.snapshot);
  const queue = usePlayerStore((s) => s.queue);
  const port = usePlayerStore((s) => s.port);
  const playOrToggle = usePlayerStore((s) => s.playOrToggle);
  const next = usePlayerStore((s) => s.next);
  const previous = usePlayerStore((s) => s.previous);
  const seekTo = usePlayerStore((s) => s.seekTo);
  const setVolume = usePlayerStore((s) => s.setVolume);
  const session = useSessionStore((s) => s.session);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);

  // The display track (the MiniPlayerBar resolution: the cursor row when
  // the engine has no current yet — a queued list must not read empty).
  const track = useMemo(
    () => snapshot?.currentTrack ?? queue.rows[queue.cursor ?? 0]?.track ?? null,
    [snapshot?.currentTrack, queue],
  );

  // -- sub-sheets (the Kotlin modal stack) ----------------------------------
  const [subSheet, setSubSheet] = useState<'none' | 'queue' | 'lyrics' | 'related'>('none');
  // -- landscape tab drawer --------------------------------------------------
  const [landscapeTab, setLandscapeTab] = useState<LandscapePlayerTab>('UP_NEXT');

  // -- orientation (LocalScreenConfiguration.isLandscape) --------------------
  const [isLandscape, setIsLandscape] = useState<boolean>(() =>
    typeof window !== 'undefined' ? window.matchMedia('(orientation: landscape)').matches : false,
  );
  useEffect(() => {
    const mq = window.matchMedia('(orientation: landscape)');
    const onChange = (e: MediaQueryListEvent): void => setIsLandscape(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  // -- reduced motion ---------------------------------------------------------
  const [reducedMotion, setReducedMotion] = useState<boolean>(() =>
    typeof window !== 'undefined' ? window.matchMedia('(prefers-reduced-motion: reduce)').matches : false,
  );
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = (e: MediaQueryListEvent): void => setReducedMotion(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  // -- ambient tint -------------------------------------------------------------
  const dominant = useDominantColor(track?.thumbnailUrl ?? null);

  // -- open/close phase machine (entrance/exit choreography) ------------------
  // The effect keys on `open` ONLY: a phase dep would re-fire the machine
  // on every phase flip (entering → open → effect → entering …) and the
  // class would oscillate forever. A ref mirrors the phase for the exit
  // branch's at-close read.
  const [phase, setPhase] = useState<'entering' | 'open' | 'exiting' | null>(null);
  const phaseRef = useRef<'entering' | 'open' | 'exiting' | null>(null);
  phaseRef.current = phase;
  const exitTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (open) {
      if (exitTimer.current !== null) {
        clearTimeout(exitTimer.current);
        exitTimer.current = null;
      }
      setPhase('entering');
      const raf = requestAnimationFrame(() => setPhase('open'));
      return () => cancelAnimationFrame(raf);
    }
    if (phaseRef.current !== null) {
      setPhase('exiting');
      exitTimer.current = setTimeout(() => {
        exitTimer.current = null;
        setPhase(null);
      }, (reducedMotion ? 0 : 280) + 20);
    }
    return undefined;
  }, [open, reducedMotion]);
  useEffect(() => {
    return () => {
      if (exitTimer.current !== null) clearTimeout(exitTimer.current);
    };
  }, []);

  // -- chrome dim (single tap on the hero) -------------------------------------
  const [chromeDimmed, setChromeDimmed] = useState(false);
  useEffect(() => {
    if (!chromeDimmed) return undefined;
    const t = setTimeout(() => setChromeDimmed(false), CHROME_RECOVER_MS);
    return () => clearTimeout(t);
  }, [chromeDimmed]);

  // -- transport state ----------------------------------------------------------
  const status = snapshot?.status ?? 'Idle';
  const isPlaying = status === 'Playing';
  const isBuffering = status === 'Buffering';
  const positionMs = snapshot?.positionMs ?? 0;
  const durationMs = track?.durationMs ?? 0;
  const liked = track !== null && likedVideoIds.has(track.id);

  // The honest live position at event time (sink first, snapshot fallback).
  const livePositionMs = useCallback((): number => {
    const sink = port?.elementSink ?? null;
    if (sink !== null && sink.currentVideoId !== null) return sink.currentPositionMs;
    return snapshot?.positionMs ?? 0;
  }, [port, snapshot]);

  // -- collapse drag (header + hero) ---------------------------------------------
  const [dragY, setDragY] = useState(0);
  const [dragging, setDragging] = useState(false);
  const dragPointer = useRef<{ y0: number; samples: Array<{ t: number; y: number }> } | null>(null);

  const onDragSurfaceDown = useCallback((e: React.PointerEvent<HTMLElement>) => {
    if (e.button !== 0) return;
    const target = e.target as HTMLElement;
    if (target.closest('button, a, input') !== null) return; // furniture taps pass through
    e.currentTarget.setPointerCapture(e.pointerId);
    dragPointer.current = { y0: e.clientY, samples: [{ t: performance.now(), y: e.clientY }] };
    setDragging(true);
    setDragY(0);
  }, []);

  const onDragSurfaceMove = useCallback((e: React.PointerEvent<HTMLElement>) => {
    const p = dragPointer.current;
    if (p === null) return;
    const dy = e.clientY - p.y0;
    p.samples.push({ t: performance.now(), y: e.clientY });
    if (p.samples.length > 6) p.samples.shift();
    setDragY(Math.max(0, dy)); // downward only, 1:1
  }, []);

  const onDragSurfaceUp = useCallback(() => {
    const p = dragPointer.current;
    if (p === null) return;
    dragPointer.current = null;
    let velocity = 0;
    if (p.samples.length >= 2) {
      const a = p.samples[0];
      const b = p.samples[p.samples.length - 1];
      const dt = b.t - a.t;
      if (dt > 0) velocity = (b.y - a.y) / (dt / 1000);
    }
    const y = dragY;
    setDragging(false);
    setDragY(0);
    if (y > COLLAPSE_PX || velocity > COLLAPSE_VELOCITY) onCollapse();
    // else: the CSS transition class springs it back (no bounce); reduced
    // motion users snap instantly (the transition is 1ms there).
  }, [dragY, onCollapse]);

  // -- hero tap / long-press / double-tap ------------------------------------------
  const heroTapState = useRef<{ timer: number | null; taps: number; longPress: number | null; startX: number; startY: number }>({
    timer: null,
    taps: 0,
    longPress: null,
    startX: 0,
    startY: 0,
  });
  const [rippleSide, setRippleSide] = useState<-1 | 0 | 1>(0);
  const rippleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const heroClearTimers = useCallback(() => {
    const s = heroTapState.current;
    if (s.timer !== null) {
      clearTimeout(s.timer);
      s.timer = null;
    }
    if (s.longPress !== null) {
      clearTimeout(s.longPress);
      s.longPress = null;
    }
  }, []);

  const heroSeekDelta = useCallback(
    (side: 1 | -1) => {
      const target = Math.max(0, livePositionMs() + side * SEEK_DELTA_MS);
      seekTo(durationMs > 0 ? Math.min(target, durationMs) : target);
      setRippleSide(side);
      if (rippleTimer.current !== null) clearTimeout(rippleTimer.current);
      rippleTimer.current = setTimeout(() => setRippleSide(0), RIPPLE_MS);
    },
    [livePositionMs, seekTo, durationMs],
  );

  const onHeroPointerDown = (e: React.PointerEvent<HTMLElement>): void => {
    const s = heroTapState.current;
    s.startX = e.clientX;
    s.startY = e.clientY;
    const moved = (): boolean => Math.abs(e.clientX - s.startX) > 12 || Math.abs(e.clientY - s.startY) > 12;
    s.longPress = window.setTimeout(() => {
      if (!moved()) {
        s.longPress = null;
        setSubSheet('lyrics'); // the Kotlin long-press → lyrics sheet
        s.taps = 0;
      }
    }, LONGPRESS_MS);
    // Drag intent cancels the long-press (handled by the drag surface).
  };

  const onHeroPointerUp = (e: React.PointerEvent<HTMLElement>): void => {
    const s = heroTapState.current;
    const hadLongPress = s.longPress !== null;
    heroClearTimers();
    if (hadLongPress) return; // consumed by drag/long-press paths
    s.taps += 1;
    if (s.taps === 1) {
      s.timer = window.setTimeout(() => {
        // Single tap: chrome dim toggle (the immersive furniture fade).
        setChromeDimmed((v) => !v);
        s.taps = 0;
        s.timer = null;
      }, DOUBLETAP_MS);
    } else if (s.taps >= 2) {
      if (s.timer !== null) {
        clearTimeout(s.timer);
        s.timer = null;
      }
      s.taps = 0;
      // Double tap: ±10 s by side (the ripple side).
      const rect = e.currentTarget.getBoundingClientRect();
      const side = e.clientX - rect.left > rect.width / 2 ? 1 : -1;
      heroSeekDelta(side as 1 | -1);
    }
  };

  const onHeroPointerMove = (e: React.PointerEvent<HTMLElement>): void => {
    const s = heroTapState.current;
    if (s.longPress !== null && (Math.abs(e.clientX - s.startX) > 12 || Math.abs(e.clientY - s.startY) > 12)) {
      clearTimeout(s.longPress);
      s.longPress = null;
    }
  };

  // -- ESC (yield to any open sub-sheet — the LIFO BackHandler pile) ------------
  useEffect(() => {
    if (phase === null || phase === 'exiting') return undefined;
    const onKey = (e: KeyboardEvent): void => {
      if (e.key !== 'Escape') return;
      if (document.querySelector('.sheet__panel[role="dialog"]') !== null) return; // sub-sheet owns it
      onCollapse();
    };
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [phase, onCollapse]);

  // -- marquee overflow detection (basicMarquee lowering) -------------------------
  const titleRef = useRef<HTMLSpanElement | null>(null);
  const [titleOverflow, setTitleOverflow] = useState(false);
  useEffect(() => {
    const el = titleRef.current;
    if (el === null) return;
    const measure = (): void => setTitleOverflow(el.scrollWidth > el.clientWidth + 2);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [track?.title, phase]);

  if (phase === null || track === null) return null;

  const statusPlaying = isPlaying;
  const likeTrack = (): void => {
    if (track === null || session === null) return;
    void toggleLike({
      videoId: track.id,
      title: track.title,
      artist: track.artist,
      album: track.album,
      durationSec: track.durationMs / 1000,
      thumbnailUrl: track.thumbnailUrl,
    });
  };

  const glow = `radial-gradient(circle at 50% 28%, ${dominant}59 0%, ${dominant}d9 38%, ${'rgba(3,3,3,0.92)'} 78%, #030303 100%)`;

  const enterFrom = {
    transform: dragY > 0 ? `translateY(${dragY}px)` : undefined,
    opacity: dragY > 0 ? String(Math.max(0.6, 1 - dragY / 1000)) : undefined,
    transition: dragging ? 'none' : undefined,
  };

  const tabCount = Math.max(0, queue.rows.length - (queue.cursor !== null ? queue.cursor + 1 : 0));

  // The transport row + seek surfaces (shared by both layouts).
  const seekAndControls = (
    <>
      <PlaybackSeekbar
        positionMs={positionMs}
        durationMs={durationMs > 0 ? durationMs : null}
        onSeek={(ms) => seekTo(ms)}
        enabled={durationMs > 0}
      />
      <div className="fsheet__row">
        <PlayerControls
          isPlaying={statusPlaying}
          isShuffleActive={false}
          isRepeatActive={false}
          isBuffering={isBuffering}
          onPlayPause={playOrToggle}
          onSkipNext={next}
          onSkipPrevious={previous}
          onShuffleToggle={() => undefined}
          onRepeatToggle={() => undefined}
          hideShuffleRepeat
        />
      </div>
      <div className="fsheet__volume">
        <VolumeSlider
          volume={snapshot?.volume ?? 1}
          onVolumeChange={(v) => setVolume(v)}
          onToggleMute={() => setVolume((snapshot?.volume ?? 1) > 0 ? 0 : 1)}
        />
      </div>
    </>
  );

  return (
    <div
      className={`fpsheet ${phase === 'entering' ? 'fpsheet--entering' : ''} ${
        phase === 'open' ? 'fpsheet--open' : ''
      } ${phase === 'exiting' ? 'fpsheet--exiting' : ''} ${reducedMotion ? 'fpsheet--rm' : ''} ${
        isLandscape ? 'fpsheet--landscape' : ''
      } ${chromeDimmed ? 'fpsheet--dimmed' : ''}`}
      style={{ '--fpsheet-drag': `${dragY}px` } as React.CSSProperties}
      role="dialog"
      aria-modal="true"
      aria-label={`Now playing: ${track.title}`}
    >
      <div className="fpsheet__scrim" onClick={onCollapse} aria-hidden="true" />
      <section className="fpsheet__panel" style={enterFrom}>
        <div className="fpsheet__glow" style={{ background: glow }} aria-hidden="true" />

        {isLandscape ? (
          // =====================================================================
          // DUAL-PANE LANDSCAPE (42% hero + 58% tab drawer)
          // =====================================================================
          <div className="fpsheet__landscape">
            <div className="fpsheet__left">
              <header
                className="fpsheet__topbar"
                data-drag
                onPointerDown={onDragSurfaceDown}
                onPointerMove={onDragSurfaceMove}
                onPointerUp={onDragSurfaceUp}
                onPointerCancel={onDragSurfaceUp}
              >
                <button type="button" className="fpsheet__iconbtn" aria-label="Collapse player" onClick={onCollapse}>
                  <Icon name="expandMore" size={28} />
                </button>
                <div className="fpsheet__topbar-actions">
                  <button type="button" className="fpsheet__iconbtn" aria-label="Open queue" onClick={() => setSubSheet('queue')}>
                    <Icon name="queueMusic" size={22} />
                  </button>
                  <button type="button" className="fpsheet__iconbtn" aria-label="Open lyrics" onClick={() => setSubSheet('lyrics')}>
                    <Icon name="subtitles" size={22} />
                  </button>
                </div>
              </header>

              <div
                className={`fpsheet__hero fpsheet__hero--landscape ${isBuffering ? 'fpsheet__hero--pulse' : ''}`}
                data-drag
                onPointerDown={(e) => {
                  onDragSurfaceDown(e);
                  onHeroPointerDown(e);
                }}
                onPointerMove={(e) => {
                  onDragSurfaceMove(e);
                  onHeroPointerMove(e);
                }}
                onPointerUp={(e) => {
                  onDragSurfaceUp();
                  onHeroPointerUp(e);
                }}
                onPointerCancel={() => {
                  onDragSurfaceUp();
                  heroClearTimers();
                }}
              >
                {track.thumbnailUrl !== null ? (
                  <img src={track.thumbnailUrl} alt={`${track.title} artwork`} />
                ) : (
                  <span className="fpsheet__hero-fallback" aria-hidden="true">
                    <Icon name="musicNote" size={64} />
                  </span>
                )}
                {rippleSide !== 0 && (
                  <span className={`fpsheet__ripple fpsheet__ripple--${rippleSide === 1 ? 'fwd' : 'rew'}`} aria-hidden="true">
                    <Icon name={rippleSide === 1 ? 'fastForward' : 'fastRewind'} size={36} />
                    <span className="fpsheet__ripple-label">{rippleSide === 1 ? '+10s' : '-10s'}</span>
                  </span>
                )}
              </div>

              <div className="fpsheet__meta">
                <span
                  ref={titleRef}
                  className={`fpsheet__title ${titleOverflow ? 'fpsheet__title--marquee' : ''}`}
                  title={track.title}
                >
                  {track.title}
                </span>
                <span className="fpsheet__artist">
                  {track.artist}
                  {track.album !== '' && track.album !== 'Streamify' ? ` • ${track.album}` : ''}
                </span>
              </div>

              {session !== null && (
                <div className="fpsheet__pills">
                  <button
                    type="button"
                    className={`fsheet__pill ${liked ? 'fsheet__pill--on' : ''}`}
                    aria-pressed={liked}
                    aria-label={liked ? `Unlike ${track.title}` : `Like ${track.title}`}
                    onClick={likeTrack}
                  >
                    <Icon name={liked ? 'thumbUp' : 'thumbUpOutline'} size={18} />
                    <span>{liked ? 'Liked' : 'Like'}</span>
                  </button>
                </div>
              )}

              {seekAndControls}
            </div>

            <div className="fpsheet__right">
              <div className="fpsheet__tabs" role="tablist" aria-label="Player tabs">
                {(
                  [
                    ['UP_NEXT', `UP NEXT (${tabCount})`] as const,
                    ['LYRICS', 'LYRICS'] as const,
                    ['RELATED', 'RELATED'] as const,
                  ]
                ).map(([value, label]) => (
                  <button
                    type="button"
                    key={value}
                    role="tab"
                    aria-selected={landscapeTab === value}
                    className={`fpsheet__tab ${landscapeTab === value ? 'fpsheet__tab--on' : ''}`}
                    onClick={() => setLandscapeTab(value)}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <div className="fpsheet__tabbody" role="tabpanel">
                {landscapeTab === 'UP_NEXT' && <LandscapeQueuePane onTrackClick={(id) => playAt(id)} />}
                {landscapeTab === 'LYRICS' && <LandscapeLyricsPane onSeek={(ms) => seekTo(ms)} />}
                {landscapeTab === 'RELATED' && <LandscapeRelatedPane />}
              </div>
            </div>
          </div>
        ) : (
          // =====================================================================
          // PORTRAIT
          // =====================================================================
          <div className="fpsheet__portrait">
            <header
              className="fpsheet__topbar"
              data-drag
              onPointerDown={onDragSurfaceDown}
              onPointerMove={onDragSurfaceMove}
              onPointerUp={onDragSurfaceUp}
              onPointerCancel={onDragSurfaceUp}
            >
              <button type="button" className="fpsheet__iconbtn" aria-label="Collapse player" onClick={onCollapse}>
                <Icon name="expandMore" size={32} />
              </button>
              <span className="fpsheet__switcher-spacer" aria-hidden="true" />
              <div className="fpsheet__topbar-actions">
                <button type="button" className="fpsheet__iconbtn" aria-label="Open queue" onClick={() => setSubSheet('queue')}>
                  <Icon name="queueMusic" size={22} />
                </button>
                <button type="button" className="fpsheet__iconbtn" aria-label="Open lyrics" onClick={() => setSubSheet('lyrics')}>
                  <Icon name="subtitles" size={22} />
                </button>
              </div>
            </header>

            <div
              className={`fpsheet__hero ${isBuffering ? 'fpsheet__hero--pulse' : ''}`}
              data-drag
              onPointerDown={(e) => {
                onDragSurfaceDown(e);
                onHeroPointerDown(e);
              }}
              onPointerMove={(e) => {
                onDragSurfaceMove(e);
                onHeroPointerMove(e);
              }}
              onPointerUp={(e) => {
                onDragSurfaceUp();
                onHeroPointerUp(e);
              }}
              onPointerCancel={() => {
                onDragSurfaceUp();
                heroClearTimers();
              }}
            >
              {track.thumbnailUrl !== null ? (
                <img src={track.thumbnailUrl} alt={`${track.title} artwork`} />
              ) : (
                <span className="fpsheet__hero-fallback" aria-hidden="true">
                  <Icon name="musicNote" size={64} />
                </span>
              )}
              {rippleSide !== 0 && (
                <span className={`fpsheet__ripple fpsheet__ripple--${rippleSide === 1 ? 'fwd' : 'rew'}`} aria-hidden="true">
                  <Icon name={rippleSide === 1 ? 'fastForward' : 'fastRewind'} size={36} />
                  <span className="fpsheet__ripple-label">{rippleSide === 1 ? '+10s' : '-10s'}</span>
                </span>
              )}
            </div>

            <div className="fpsheet__meta">
              <span
                ref={titleRef}
                className={`fpsheet__title ${titleOverflow ? 'fpsheet__title--marquee' : ''}`}
                title={track.title}
              >
                {track.title}
              </span>
              <span className="fpsheet__artist">
                {track.artist}
                {track.album !== '' && track.album !== 'Streamify' ? ` • ${track.album}` : ''}
              </span>
            </div>

            {session !== null && (
              <div className="fpsheet__pills">
                <button
                  type="button"
                  className={`fsheet__pill ${liked ? 'fsheet__pill--on' : ''}`}
                  aria-pressed={liked}
                  aria-label={liked ? `Unlike ${track.title}` : `Like ${track.title}`}
                  onClick={likeTrack}
                >
                  <Icon name={liked ? 'thumbUp' : 'thumbUpOutline'} size={18} />
                  <span>{liked ? 'Liked' : 'Like'}</span>
                </button>
              </div>
            )}

            {seekAndControls}

            <nav className="fpsheet__bottomtabs" aria-label="Player sheets">
              <button type="button" className="fpsheet__bottomtab" onClick={() => setSubSheet('queue')}>
                UP NEXT <Icon name="keyboardArrowUp" size={16} />
              </button>
              <button type="button" className="fpsheet__bottomtab" onClick={() => setSubSheet('lyrics')}>
                LYRICS <Icon name="keyboardArrowUp" size={16} />
              </button>
              <button type="button" className="fpsheet__bottomtab" onClick={() => setSubSheet('related')}>
                RELATED <Icon name="keyboardArrowUp" size={16} />
              </button>
            </nav>
          </div>
        )}
      </section>

      {/* The modal sub-sheets (the Kotlin ModalBottomSheet stack) */}
      <Sheet
        open={subSheet === 'queue'}
        onDismiss={() => setSubSheet('none')}
        title="Up next"
      >
        <div className="fsheet__subsheet">
          <LandscapeQueuePane onTrackClick={(id) => playAt(id)} />
        </div>
      </Sheet>
      <Sheet
        open={subSheet === 'lyrics'}
        onDismiss={() => setSubSheet('none')}
        title="Lyrics"
        heightFraction={0.85}
      >
        <div className="fsheet__subsheet">
          <LandscapeLyricsPane onSeek={(ms) => seekTo(ms)} />
        </div>
      </Sheet>
      <Sheet
        open={subSheet === 'related'}
        onDismiss={() => setSubSheet('none')}
        title="Related"
      >
        <div className="fsheet__subsheet">
          <LandscapeRelatedPane />
        </div>
      </Sheet>
    </div>
  );

  /** Play-at-id: the lazy resolve + play pattern (the QueueScreen tap). */
  function playAt(id: string): void {
    if (port === null) return;
    port.unlockSink();
    if (!port.playTrack(id)) {
      void port.resolveAndStage(id).then((stream) => {
        if (stream !== null) port.playTrack(id);
      });
    }
    setSubSheet('none');
  }
}
