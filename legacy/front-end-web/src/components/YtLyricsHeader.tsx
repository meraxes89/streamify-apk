/**
 * components/YtLyricsHeader.tsx — P1 port of
 * app/…/ui/components/yt/YtLyricsHeader.kt (the lyrics top bar with the
 * micro-nudge timing controls).
 *
 * Web-adapted, documented (the one deliberate divergence): the Kotlin
 * SAVE button ("Save & Share Offset") bakes the offset into the cached
 * LRC and syncs it to the community cloud. The web lyrics stack persists
 * the offset AS the offset (lyrics/offsetStore, per title|artist key)
 * the moment a nudge happens — clock.adjustOffset() writes through — so
 * there is nothing left for a save verb to do; it is omitted rather
 * than shipped as a dead control.
 *
 * The nudge step is the Kotlin ±500 ms; the chip shows the live offset
 * (sign + seconds, one decimal) and doubles as the reset control.
 */

import { Icon } from './icons';

export interface YtLyricsHeaderProps {
  source: string;
  isSynced: boolean;
  userOffsetMs: number;
  onAdjustOffset?: (deltaMs: number) => void;
  onResetOffset?: () => void;
  onClose?: (() => void) | null;
}

export function YtLyricsHeader(props: YtLyricsHeaderProps): JSX.Element {
  const { source, isSynced, userOffsetMs, onAdjustOffset, onResetOffset, onClose } = props;
  const showNudges = isSynced && onAdjustOffset !== undefined;
  const hasOffset = userOffsetMs !== 0;
  const sign = userOffsetMs > 0 ? '+' : '';
  const offsetLabel = `${sign}${(userOffsetMs / 1000).toFixed(1)}s`;

  return (
    <div className="lyricsheader">
      <div className="lyricsheader__lead">
        {onClose != null ? (
          <button type="button" className="lyricsheader__close" aria-label="Close lyrics" onClick={onClose}>
            <Icon name="expandMore" size={32} />
          </button>
        ) : (
          <span className="lyricsheader__spacer" aria-hidden="true" />
        )}
        <div className="lyricsheader__titles">
          <span className="lyricsheader__title">Lyrics</span>
          <span className="lyricsheader__source">
            {isSynced ? `Source: ${source} • Real-Time Sync` : `Source: ${source} • Unsynchronized`}
          </span>
        </div>
      </div>

      {showNudges && (
        <div className="lyricsheader__nudges">
          <button type="button" className="lyricsheader__nudge" aria-label="Nudge lyrics 0.5 seconds earlier" onClick={() => onAdjustOffset?.(-500)}>
            -0.5s
          </button>
          {hasOffset && (
            <button
              type="button"
              className="lyricsheader__offset"
              aria-label={`Reset offset (${offsetLabel})`}
              onClick={() => onResetOffset?.()}
            >
              {offsetLabel}
            </button>
          )}
          <button type="button" className="lyricsheader__nudge" aria-label="Nudge lyrics 0.5 seconds later" onClick={() => onAdjustOffset?.(500)}>
            +0.5s
          </button>
        </div>
      )}
    </div>
  );
}
