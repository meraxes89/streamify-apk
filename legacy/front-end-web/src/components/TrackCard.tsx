/**
 * components/TrackCard.tsx — P2 browse port (ui/components/TrackCard.kt).
 *
 * The feed/grid card: square cover (TrackCoverArt pipeline) over the
 * title (CardTitle type) and artist (CardSubtitle, 2 lines). The Kotlin
 * cardPressEffect animation lands as the CSS press transition.
 */

import { TrackCoverArt } from './TrackCoverArt';

export interface TrackCardProps {
  title: string;
  artist: string;
  artworkUrl: string | null | undefined;
  /** Fires the whole-card click (the Kotlin cardPressEffect onClick). */
  onClick: () => void;
  /** Playing-state tint for the card ring (current-track feedback). */
  isCurrent?: boolean;
  className?: string;
}

export function TrackCard(props: TrackCardProps): JSX.Element {
  const { title, artist, artworkUrl, onClick, isCurrent, className } = props;
  return (
    <button
      type="button"
      className={`trackcard ${isCurrent === true ? 'trackcard--current' : ''} ${className ?? ''}`}
      onClick={onClick}
      aria-label={`Play ${title} by ${artist}`}
    >
      <TrackCoverArt coverArtPath={artworkUrl} title={title} artist={artist} sizePx={140} radiusPx={8} />
      <span className="trackcard__title">{title}</span>
      <span className="trackcard__subtitle">{artist}</span>
    </button>
  );
}
