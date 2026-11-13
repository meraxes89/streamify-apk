/**
 * components/QueueList.tsx — the engine queue surface (W2.1).
 *
 * The queue lives IN THE WASM ENGINE (the QueueLane mirror); this is a
 * pure projection: rows from getQueue(), the cursor highlighted, tap to
 * play (resolve-on-demand when the row lacks a URL), clear all.
 */

import { useCallback } from 'react';
import { usePlayerStore } from '../lib/playerStore';
import type { WasmEnginePort } from '../lib/wasmEngine';

function fmtMs(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '--:--';
  const total = Math.floor(ms / 1000);
  return `${Math.floor(total / 60)}:${(total % 60).toString().padStart(2, '0')}`;
}

export function QueueList({ port }: { port: WasmEnginePort | null }): JSX.Element {
  const queue = usePlayerStore((s) => s.queue);
  const snapshot = usePlayerStore((s) => s.snapshot);

  const playRow = useCallback(
    async (trackId: string) => {
      if (port === null) return;
      // Unlock inside the gesture (mobile autoplay policy), then
      // playTrack — resolving on demand when the row lacks a URL.
      port.unlockSink();
      if (!port.playTrack(trackId)) {
        await port.resolveAndStage(trackId);
        port.playTrack(trackId);
      }
    },
    [port],
  );

  const cursor = queue.cursor ?? snapshot?.cursor ?? null;

  return (
    <section className="card queue" aria-label="Play queue">
      <div className="queue__head">
        <h2 className="card-title">Queue</h2>
        <span className="muted">{queue.rows.length} rows</span>
        <button
          type="button"
          className="btn btn--small"
          onClick={() => port?.clearQueue()}
          disabled={queue.rows.length === 0}
        >
          Clear
        </button>
      </div>
      {queue.rows.length === 0 ? (
        <p className="muted queue__empty">
          Nothing queued — search a track and tap <strong>Play</strong> or{' '}
          <strong>Queue</strong>.
        </p>
      ) : (
        <ol className="queue__list">
          {queue.rows.map((row) => {
            const isCurrent = row.index === cursor;
            return (
              <li key={`${row.index}-${row.track.id}`}>
                <button
                  type="button"
                  className={`queue__row ${isCurrent ? 'queue__row--current' : ''}`}
                  onClick={() => {
                    void playRow(row.track.id);
                  }}
                >
                  <span className="queue__row-index">
                    {isCurrent ? '♪' : row.index + 1}
                  </span>
                  {row.track.thumbnailUrl !== null && (
                    <img
                      className="queue__row-art"
                      src={row.track.thumbnailUrl}
                      alt=""
                      loading="lazy"
                    />
                  )}
                  <span className="queue__row-titles">
                    <span className="queue__row-title">{row.track.title}</span>
                    <span className="queue__row-artist">{row.track.artist}</span>
                  </span>
                  <span className="queue__row-dur">{fmtMs(row.track.durationMs)}</span>
                </button>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
