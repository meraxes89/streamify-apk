/**
 * screens/LyricsScreen.tsx — P1 port of
 * app/…/ui/screens/LyricsScreen.kt (the full-route lyrics surface).
 *
 * The four states in the Kotlin discipline:
 *   • loading — the "Looking for synchronized lyrics…" honest wait state
 *     (the web is explicit where Android's cache-only read waited forever:
 *     a completed miss renders the miss, never a spinner that lies);
 *   • missing — "No synchronized lyrics found for this track.";
 *   • unsynced — the static reading sheet (zero discarded data — plain
 *     lines are never dropped), 21px/32 SemiBold at 0.92 alpha;
 *   • synced — the karaoke wall: FluidSyllableText rows reading the
 *     per-surface PLL clock through the draw-phase provider, the 35%
 *     focal auto-scroll, tap-a-line-to-seek, the dominant-color ambient
 *     glow, and the attribution footer.
 *
 * The nudge controls (YtLyricsHeader) adjust the per-track offset —
 * persisted via the lyrics offsetStore the moment a nudge lands, so it
 * survives navigation and reloads (the acceptance bar).
 */

import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { FluidSyllableText } from '../components/FluidSyllableText';
import { YtLyricsHeader } from '../components/YtLyricsHeader';
import { usePlayerStore } from '../lib/playerStore';
import { nowPlaying as nowPlayingOf, useQueueStore } from '../lib/playback/queueStore';
import { useDominantColor } from '../lib/useDominantColor';
import { useLyrics, useLyricAutoScroll } from '../lib/useLyrics';
import { sourceLabel } from './FullPlayerSheetPanes';

export interface LyricsScreenProps {
  onClose?: () => void;
}

export function LyricsScreen(props: LyricsScreenProps): JSX.Element {
  const { onClose } = props;
  const navigate = useNavigate();
  const snapshot = usePlayerStore((s) => s.snapshot);
  const queueState = useQueueStore();
  const seekTo = useQueueStore((s) => s.seekTo);

  const track = nowPlayingOf(queueState) ?? snapshot?.currentTrack ?? null;
  const lyrics = useLyrics();
  const dominant = useDominantColor(track?.thumbnailUrl ?? null);
  const listRef = useRef<HTMLDivElement | null>(null);
  useLyricAutoScroll(listRef, lyrics.activeLineIndex, lyrics.isSynced && lyrics.phase === 'ready');

  const close = (): void => {
    if (onClose !== undefined) onClose();
    else navigate(-1);
  };

  const glow = `radial-gradient(circle at 50% 25%, ${dominant}3d 0%, ${dominant}e0 40%, rgba(3,3,3,0.92) 80%, #030303 100%)`;

  return (
    <div className="lyricsscreen" style={{ '--lyrics-glow': glow } as React.CSSProperties}>
      <div className="lyricsscreen__glow" aria-hidden="true" />
      <div className="lyricsscreen__column">
        <YtLyricsHeader
          source={sourceLabel(lyrics.source)}
          isSynced={lyrics.isSynced}
          userOffsetMs={lyrics.userOffsetMs}
          onAdjustOffset={lyrics.adjustOffset}
          onResetOffset={lyrics.resetOffset}
          onClose={close}
        />

        {lyrics.phase === 'idle' || lyrics.phase === 'loading' ? (
          <div className="lyricsscreen__wait" role="status">
            <Icon name="musicNote" size={48} className="lyricsscreen__wait-icon" />
            <p className="lyricsscreen__wait-title">Looking for synchronized lyrics...</p>
            <p className="lyricsscreen__wait-sub">Lyrics will appear when available</p>
          </div>
        ) : lyrics.phase === 'missing' || lyrics.lines.length === 0 ? (
          <div className="lyricsscreen__wait">
            <p className="lyricsscreen__wait-title">No synchronized lyrics found for this track.</p>
          </div>
        ) : !lyrics.isSynced ? (
          <div className="lyricsscreen__static" role="list">
            {lyrics.lines.map((line, i) => (
              <p className="lyricsscreen__static-line" role="listitem" key={`unsynced-${i}-${line.timeMs}`}>
                {line.text}
              </p>
            ))}
            <p className="lyricsscreen__attribution">
              Unsynchronized lyrics provided by {sourceLabel(lyrics.source)}
            </p>
          </div>
        ) : (
          <div className="lyricsscreen__scroll" ref={listRef}>
            {lyrics.lines.map((line, i) => {
              const nextStart = i + 1 < lyrics.lines.length ? lyrics.lines[i + 1].timeMs : line.timeMs + 3500;
              const isActive = i === lyrics.activeLineIndex;
              const isPast = i < lyrics.activeLineIndex;
              return (
                <div className="lyricsscreen__lyricrow" data-lyric-index={i} key={`${i}-${line.timeMs}`}>
                  <FluidSyllableText
                    text={line.text}
                    lineStartMs={line.timeMs}
                    lineEndMs={nextStart}
                    syllables={line.syllables}
                    playbackMsProvider={() => lyrics.clock.interpolatedPosMs}
                    isActive={isActive}
                    isPast={isPast}
                    onClick={() => seekTo(line.timeMs)}
                  />
                </div>
              );
            })}
            <p className="lyricsscreen__attribution">
              Lyrics by {sourceLabel(lyrics.source)} • Real-Time Fluid Sync
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
