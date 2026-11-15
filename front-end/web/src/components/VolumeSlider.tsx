/**
 * components/VolumeSlider.tsx — P1 port of
 * shared/…/components/VolumeSlider.kt (the platform-agnostic output gain).
 *
 * Binds DIRECTLY to engine-authoritative volume (the value the engine
 * clamps to [0,1] and echoes back in every pushed snapshot — the slider
 * never owns state, it renders it), with a mute toggle and a percentage
 * readout. Hosts wire onVolumeChange to the store's setVolume and let
 * the push flow repaint the slider — round-trip discipline instead of
 * local echo, so two surfaces can never disagree.
 */

import { roundToInt } from '../theme/tokens';

export interface VolumeSliderProps {
  /** Engine-authoritative volume [0..1]. */
  volume: number;
  onVolumeChange: (volume: number) => void;
  /** Optional mute toggle (hosts without a mute verb omit it). */
  onToggleMute?: () => void;
  enabled?: boolean;
}

export function VolumeSlider(props: VolumeSliderProps): JSX.Element {
  const { volume, onVolumeChange, onToggleMute, enabled = true } = props;
  const clamped = Math.min(1, Math.max(0, volume));
  const muted = clamped <= 0;

  return (
    <div className="volumeslider">
      <button
        type="button"
        className={`volumeslider__mute ${muted ? 'volumeslider__mute--muted' : ''}`}
        onClick={() => onToggleMute?.()}
        disabled={!enabled || onToggleMute === undefined}
        aria-label={muted ? 'Unmute' : 'Mute'}
      >
        <svg viewBox="0 0 24 24" width={22} height={22} fill="currentColor" aria-hidden="true" focusable="false">
          <path
            d={
              muted
                ? 'M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.89-.46 3.57-1.44 4.97-2.77L19.73 21 21 19.73 4.27 3zM12 4 9.91 6.09 12 8.18V4z'
                : 'M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z'
            }
          />
        </svg>
      </button>
      <input
        type="range"
        className="volumeslider__slider"
        min={0}
        max={100}
        step={1}
        value={roundToInt(clamped * 100)}
        disabled={!enabled}
        aria-label="Volume"
        onInput={(e) => onVolumeChange(Number((e.target as HTMLInputElement).value) / 100)}
      />
      <span className="volumeslider__pct">{roundToInt(clamped * 100)}%</span>
    </div>
  );
}
