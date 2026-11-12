import type { Track } from '../lib/engine';

export interface RowPlayback {
  status: 'resolving' | 'playing' | 'passed' | 'failed';
  message?: string;
}

export interface TrackRowProps {
  track: Track;
  playback: RowPlayback | undefined;
  onPlay: (track: Track) => void;
}

function formatDuration(durationSec: number | undefined): string {
  if (durationSec === undefined || !Number.isFinite(durationSec) || durationSec < 0) {
    return '--:--';
  }
  const minutes = Math.floor(durationSec / 60);
  const seconds = Math.floor(durationSec % 60);
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

export function TrackRow(props: TrackRowProps): JSX.Element {
  const { track, playback, onPlay } = props;
  const status = playback?.status ?? 'idle';
  const busy = status === 'resolving' || status === 'playing';

  const metaParts: string[] = [];
  if (track.artist.length > 0) {
    metaParts.push(track.artist);
  }
  if (typeof track.album === 'string' && track.album.length > 0) {
    metaParts.push(track.album);
  }
  const durationText = formatDuration(track.durationSec);
  if (durationText !== '--:--') {
    metaParts.push(durationText);
  }
  const metaText = metaParts.length > 0 ? metaParts.join(' • ') : '—';

  return (
    <li className="track-row">
      <div className="track-media">
        {typeof track.thumbnailUrl === 'string' && track.thumbnailUrl.length > 0 ? (
          <img
            className="track-thumb"
            src={track.thumbnailUrl}
            alt={track.title}
            loading="lazy"
            width={96}
            height={54}
          />
        ) : (
          <div className="track-thumb-fallback" aria-hidden="true">
            no art
          </div>
        )}
      </div>
      <div className="track-body">
        <h3 className="track-title">{track.title}</h3>
        <p className="track-meta">{metaText}</p>
        <div className="track-actions">
          {track.isLicensed === true && (
            <span className="badge badge--licensed">licensed</span>
          )}
          {status === 'passed' && (
            <span className="badge badge--pass">PASS: 10s audio played</span>
          )}
          {status === 'failed' && (
            <span className="badge badge--fail">FAIL: {playback?.message ?? 'unknown error'}</span>
          )}
          <button
            type="button"
            className={status === 'idle' ? 'button--primary' : undefined}
            disabled={busy}
            aria-label={`Play ${track.title} for 10 seconds`}
            onClick={() => onPlay(track)}
          >
            {status === 'resolving'
              ? 'resolving…'
              : status === 'playing'
                ? 'playing (10s proof)…'
                : 'Play 10s'}
          </button>
        </div>
      </div>
    </li>
  );
}
