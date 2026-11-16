/**
 * lib/useLyrics.ts — P1: THE LYRICS ENGINE ACTIVATION (the phase's core
 * deliverable). Until this hook existed, nothing imported src/lyrics/ —
 * the whole engine was tree-shaken out of the bundle.
 *
 * The player-flow wiring, exactly the merged engine's intended seam:
 *
 *   fetchSyncedLyrics()  ── src/lyrics resolver (two-tier: YTM pinned
 *        │                 ATV → verified YTM search + LRCLIB race →
 *        ▼                 synthesized plain), NEVER mock data
 *   parseLrc()           ── the LRC/syllable parser
 *        │
 *   LyricPlaybackClock    ── the PLL sync clock. Fed per frame with
 *        │                 audio.currentTime × 1000 read from the
 *        │                 DIRECT-EGRESS element sink (port.elementSink.
 *        │                 currentPositionMs); the 4 Hz snapshot lane is
 *        │                 the fallback while the sink is absent (spine
 *        │                 mode). The clock interpolates between ticks —
 *        │                 the interpolatedPosMs output is what the views
 *        │                 consume, per frame, via the provider.
 *        │
 *   findActiveLineIndex / findActiveSyllableIndex
 *        │                 ── the karaoke state, recomputed per frame but
 *        ▼                 setState'd ONLY on flips (the derivedStateOf
 *   FluidSyllableText       step-down: once per line, not 120 Hz)
 *
 * Offset nudges (±0.5s in the YtLyricsHeader) route through
 * clock.adjustOffset() — persisted per track via the lyrics offsetStore
 * (title|artist identity), so a nudge survives navigation AND reloads.
 *
 * Data-source discipline: the fetch is keyed to the CURRENT track's
 * identity; an in-flight resolve from a superseded track is dropped
 * (generation token) so a slow Tier-1 race can never hydrate the wrong
 * lyrics onto a later track.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePlayerStore } from './playerStore';
import { nowPlaying as nowPlayingOf, useQueueStore } from './playback/queueStore';
import type { TrackRow } from './wasmEngine';
import {
  EMPTY_LYRICS,
  LyricPlaybackClock,
  fetchSyncedLyrics,
  findActiveLineIndex,
  findActiveSyllableIndex,
  parseLrc,
} from '../lyrics';
import type { LyricsData, LyricsLine } from '../lyrics';
import type { LyricsSource } from '../lyrics';

export interface UseLyricsResult {
  /** idle = no current track; loading = resolver in flight. */
  phase: 'idle' | 'loading' | 'ready' | 'missing';
  lines: readonly LyricsLine[];
  isSynced: boolean;
  /** Which lane actually produced the lyrics (attribution + diagnostics). */
  source: LyricsSource | null;
  /** The active line (last whose start ≤ playhead); -1 before the first. */
  activeLineIndex: number;
  /** The active syllable INSIDE the active line; -1 before its first. */
  activeSyllableIndex: number;
  /** The clock itself — views read clock.interpolatedPosMs per frame. */
  clock: LyricPlaybackClock;
  /** The persisted per-track user offset (drives the nudge chip UI). */
  userOffsetMs: number;
  /** Nudge by delta (persisted immediately via the offsetStore). */
  adjustOffset: (deltaMs: number) => void;
  /** Clear the offset (persisted). */
  resetOffset: () => void;
}

/**
 * The DISPLAY track as the lyrics request identity — the same resolution
 * the player surfaces use (MiniPlayerBar / PlayerBar / FullPlayerSheet):
 * the engine's current track, falling back to the cursor row when nothing
 * is playing yet. A queued-but-not-started list must not read "no lyrics
 * target" while every bar shows the pending track.
 */
function useDisplayTrack(): TrackRow | null {
  const snapshot = usePlayerStore((s) => s.snapshot);
  const queueState = useQueueStore();
  // The MACHINE's now-playing first (paused advances / restores must not
  // show stale lyrics), the engine's current as the fallback.
  return nowPlayingOf(queueState) ?? snapshot?.currentTrack ?? null;
}

export function useLyrics(): UseLyricsResult {
  const port = usePlayerStore((s) => s.port);
  const track = useDisplayTrack();

  const [phase, setPhase] = useState<UseLyricsResult['phase']>('idle');
  const [lines, setLines] = useState<readonly LyricsLine[]>([]);
  const [isSynced, setIsSynced] = useState(false);
  const [source, setSource] = useState<LyricsSource | null>(null);
  const [activeLineIndex, setActiveLineIndex] = useState(-1);
  const [activeSyllableIndex, setActiveSyllableIndex] = useState(-1);
  const [userOffsetMs, setUserOffsetMs] = useState(0);

  // One clock per consuming surface (the Kotlin remember-per-surface
  // shape); the raf injection is the withFrameNanos lowering.
  const clockRef = useRef<LyricPlaybackClock | null>(null);
  if (clockRef.current === null) {
    clockRef.current = new LyricPlaybackClock((cb) => requestAnimationFrame(cb));
  }
  const clock = clockRef.current;

  // -- bind: restore the persisted offset for this track ---------------
  useEffect(() => {
    clock.bindTrack(track?.title, track?.artist);
    setUserOffsetMs(clock.userOffsetMs);
    setActiveLineIndex(-1);
    setActiveSyllableIndex(-1);
    // Primitives only (see the fetch effect note).
  }, [track?.id, track?.title, track?.artist, clock]);

  // -- fetch: real engine calls only, keyed to the track identity --------
  useEffect(() => {
    if (track === null) {
      setPhase('idle');
      setLines([]);
      setIsSynced(false);
      setSource(null);
      return undefined;
    }
    let generation = 0;
    const myGen = generation;
    setPhase('loading');
    setLines([]);
    setIsSynced(false);
    setSource(null);
    void fetchSyncedLyrics({
      title: track.title,
      artist: track.artist,
      durationSec: track.durationMs > 0 ? track.durationMs / 1000 : 0,
      videoId: track.id, // the engine row id IS the pinned videoId
    }).then((result) => {
      if (myGen !== generation) return; // superseded by a later track
      if (result === null) {
        setPhase('missing');
        setLines([]);
        return;
      }
      const data: LyricsData = parseLrc(result.lrc);
      if (data === EMPTY_LYRICS || data.lines.length === 0) {
        setPhase('missing');
        setLines([]);
        return;
      }
      setSource(result.source);
      setIsSynced(data.isSynced);
      setLines(data.lines);
      setPhase('ready');
    });
    return () => {
      generation += 1;
    };
    // NOTE: primitives only — `track` object identity changes on every
    // 4 Hz snapshot push (fresh JSON.parse); an object dep here would
    // re-fire the resolver per push.
  }, [track?.id, track?.title, track?.artist, track?.durationMs, track === null]);

  // -- the frame loop: feed the PLL + derive the karaoke state -----------
  useEffect(() => {
    if (track === null) {
      clock.stop();
      return undefined;
    }
    clock.start(); // the PLL's own rAF (start() is idempotent)
    let raf = 0;
    let lastLine = -1;
    let lastSyl = -1;
    const frame = (): void => {
      raf = requestAnimationFrame(frame);
      // Feed: element sink position when the direct lane is live (per
      // frame, audio.currentTime × 1000); the pushed snapshot otherwise.
      const sink = port?.elementSink ?? null;
      if (sink !== null && sink.currentVideoId !== null) {
        clock.targetPositionMs = sink.currentPositionMs;
      }
      const snap = usePlayerStore.getState().snapshot;
      if (sink === null || sink.currentVideoId === null) {
        clock.targetPositionMs = snap?.positionMs ?? 0;
      }
      clock.isPlaying = (snap?.status ?? 'Idle') === 'Playing';
      // Derive: binary searches per frame, setState only on flips.
      const pos = clock.interpolatedPosMs;
      const idx = lines.length > 0 && isSynced ? findActiveLineIndex(lines, pos) : -1;
      if (idx !== lastLine) {
        lastLine = idx;
        setActiveLineIndex(idx);
      }
      const line = idx >= 0 ? lines[idx] : null;
      const syl = line !== null ? findActiveSyllableIndex(line, pos) : -1;
      if (syl !== lastSyl) {
        lastSyl = syl;
        setActiveSyllableIndex(syl);
      }
    };
    raf = requestAnimationFrame(frame);
    return () => {
      cancelAnimationFrame(raf);
      clock.stop();
    };
    // Primitives only (same reason as the fetch effect).
  }, [port, clock, track?.id, track === null, lines, isSynced]);

  // -- offset controls (persisted per track via the clock + offsetStore) --
  const adjustOffset = useCallback(
    (deltaMs: number) => {
      clock.adjustOffset(deltaMs);
      setUserOffsetMs(clock.userOffsetMs);
    },
    [clock],
  );
  const resetOffset = useCallback(() => {
    clock.resetOffset();
    setUserOffsetMs(clock.userOffsetMs);
  }, [clock]);

  return useMemo(
    () => ({
      phase,
      lines,
      isSynced,
      source,
      activeLineIndex,
      activeSyllableIndex,
      clock,
      userOffsetMs,
      adjustOffset,
      resetOffset,
    }),
    [
      phase,
      lines,
      isSynced,
      source,
      activeLineIndex,
      activeSyllableIndex,
      clock,
      userOffsetMs,
      adjustOffset,
      resetOffset,
    ],
  );
}

// ---------------------------------------------------------------------------
// The focal auto-scroll (the LyricsScreen/LandscapeLyricsPane math engine)
// ---------------------------------------------------------------------------

/**
 * Scrolls the active line to the 35% viewport focal anchor on every flip
 * (Kotlin: animateScrollBy spring, DampingRatioLowBouncy / StiffnessMediumLow
 * → the browser's smooth scroll here). Skips while the user is scrolling
 * the surface themselves (wheel/touch within the last 350 ms), and honors
 * prefers-reduced-motion by jumping without animation.
 */
export function useLyricAutoScroll(
  containerRef: React.RefObject<HTMLElement | null>,
  activeIndex: number,
  enabled: boolean,
): void {
  const userScrollUntilRef = useRef(0);
  const programmaticUntilRef = useRef(0);

  useEffect(() => {
    const el = containerRef.current;
    if (el === null) return undefined;
    const markUser = (): void => {
      userScrollUntilRef.current = performance.now() + 350;
    };
    el.addEventListener('wheel', markUser, { passive: true });
    el.addEventListener('touchstart', markUser, { passive: true });
    el.addEventListener('pointerdown', markUser, { passive: true });
    return () => {
      el.removeEventListener('wheel', markUser);
      el.removeEventListener('touchstart', markUser);
      el.removeEventListener('pointerdown', markUser);
    };
  }, [containerRef]);

  useEffect(() => {
    const el = containerRef.current;
    if (el === null || !enabled || activeIndex < 0) return;
    const now = performance.now();
    if (now < userScrollUntilRef.current || now < programmaticUntilRef.current) return;
    const viewport = el.clientHeight;
    if (viewport <= 0) return;
    const focal = viewport * 0.35;
    const rows = el.querySelectorAll<HTMLElement>('[data-lyric-index]');
    const row = rows[activeIndex];
    if (row === undefined) return;
    // Rect-delta math: robust to any offsetParent positioning.
    const delta = row.getBoundingClientRect().top - el.getBoundingClientRect().top;
    const target = Math.max(0, el.scrollTop + delta - focal);
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    programmaticUntilRef.current = now + 600;
    el.scrollTo({ top: target, behavior: reduced ? 'auto' : 'smooth' });
  }, [activeIndex, enabled, containerRef]);
}
