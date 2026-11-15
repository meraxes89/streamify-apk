/**
 * components/ArtistCircleCard.tsx — P2 browse port
 * (ui/components/ArtistCircleCard.kt).
 *
 * Circle avatar + centered name; the artwork miss falls back to the
 * TrackCoverArt generative pipeline (the Kotlin AsyncImage would just
 * render nothing — the web port keeps the seeded-gradient honesty).
 */

import { TrackCoverArt } from './TrackCoverArt';

export interface ArtistCircleCardProps {
  artistName: string;
  imageUrl: string | null | undefined;
  onClick: () => void;
  className?: string;
}

export function ArtistCircleCard(props: ArtistCircleCardProps): JSX.Element {
  const { artistName, imageUrl, onClick, className } = props;
  return (
    <button
      type="button"
      className={`artistcircle ${className ?? ''}`}
      onClick={onClick}
      aria-label={`Open artist ${artistName}`}
    >
      <TrackCoverArt
        coverArtPath={imageUrl}
        title={artistName}
        artist=""
        sizePx={128}
        radiusPx={999}
        className="artistcircle__art"
      />
      <span className="artistcircle__name">{artistName}</span>
    </button>
  );
}
