/**
 * components/PlayerControls.tsx — P1 port of
 * app/…/ui/components/PlayerControls.kt (the shared transport row).
 *
 * The 48dp circular icon wells (cardPressEffect → CSS press scale), the
 * 64dp PlayButtonSize pill in StreamifyColors.TextMain (white) with the
 * BgBase (dark) glyph, AnimatedContent's 140 ms crossfade for the
 * play/pause/buffering tri-state, and StreamifyDimens button sizes
 * (ShuffleButtonSize 24dp, SkipButtonSize 32dp) — all verbatim from the
 * Kotlin geometry, in CSS custom properties.
 *
 * Haptics (HapticFeedbackType.TextHandleMove on every press) lower to a
 * best-effort vibrate() where the browser exposes it; silent elsewhere.
 *
 * Web extension (documented): hideShuffleRepeat — hosts without a
 * shuffle/repeat engine verb hide those wells instead of shipping dead
 * buttons; the mirrored Kotlin signature stays intact.
 */

import { Icon } from './icons';

export interface PlayerControlsProps {
  isPlaying: boolean;
  isShuffleActive: boolean;
  isRepeatActive: boolean;
  /** Repeat-one mode: the repeat well shows the “1” badge (P2-B). */
  repeatOne?: boolean;
  isBuffering?: boolean;
  onPlayPause: () => void;
  onSkipNext: () => void;
  onSkipPrevious: () => void;
  onShuffleToggle: () => void;
  onRepeatToggle: () => void;
  /** Hide the shuffle/repeat wells (no backing engine verb on this host). */
  hideShuffleRepeat?: boolean;
}

/** The Kotlin PlaybackButtonState tri-state. */
type ButtonState = 'BUFFERING' | 'PLAYING' | 'PAUSED';

function haptic(): void {
  try {
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      navigator.vibrate(8);
    }
  } catch {
    /* no-op — haptics are always best-effort */
  }
}

export function PlayerControls(props: PlayerControlsProps): JSX.Element {
  const {
    isPlaying,
    isShuffleActive,
    isRepeatActive,
    repeatOne = false,
    isBuffering = false,
    onPlayPause,
    onSkipNext,
    onSkipPrevious,
    onShuffleToggle,
    onRepeatToggle,
    hideShuffleRepeat = false,
  } = props;

  const buttonState: ButtonState = isBuffering ? 'BUFFERING' : isPlaying ? 'PLAYING' : 'PAUSED';

  return (
    <div className="playercontrols" role="group" aria-label="Transport controls">
      {!hideShuffleRepeat && (
        <button
          type="button"
          className="playercontrols__well"
          aria-label="Shuffle"
          aria-pressed={isShuffleActive}
          onClick={() => {
            haptic();
            onShuffleToggle();
          }}
        >
          <Icon
            name="shuffle"
            size={24}
            className={`playercontrols__glyph ${isShuffleActive ? 'playercontrols__glyph--on' : ''}`}
          />
        </button>
      )}

      <button
        type="button"
        className="playercontrols__well"
        aria-label="Previous track"
        onClick={() => {
          haptic();
          onSkipPrevious();
        }}
      >
        <Icon name="skipPrevious" size={32} />
      </button>

      <button
        type="button"
        className={`playercontrols__play fsheet__playstate fsheet__playstate--${buttonState.toLowerCase()}`}
        aria-label={buttonState === 'PLAYING' ? 'Pause' : 'Play'}
        onClick={() => {
          haptic();
          onPlayPause();
        }}
      >
        {buttonState === 'BUFFERING' ? (
          <span className="fsheet__spinner" role="img" aria-label="Buffering" />
        ) : (
          <Icon name={buttonState === 'PLAYING' ? 'pause' : 'playArrow'} size={32} />
        )}
      </button>

      <button
        type="button"
        className="playercontrols__well"
        aria-label="Next track"
        onClick={() => {
          haptic();
          onSkipNext();
        }}
      >
        <Icon name="skipNext" size={32} />
      </button>

      {!hideShuffleRepeat && (
        <button
          type="button"
          className="playercontrols__well"
          aria-label={repeatOne ? 'Repeat one' : 'Repeat all'}
          aria-pressed={isRepeatActive}
          onClick={() => {
            haptic();
            onRepeatToggle();
          }}
        >
          <Icon
            name="repeat"
            size={24}
            className={`playercontrols__glyph ${isRepeatActive ? 'playercontrols__glyph--on' : ''}`}
          />
          {repeatOne && <span className="playercontrols__one" aria-hidden="true">1</span>}
        </button>
      )}
    </div>
  );
}
