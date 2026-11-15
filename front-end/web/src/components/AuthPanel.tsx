/**
 * components/AuthPanel.tsx — P3a: the account panel, Google-first.
 *
 * The P2 email/password form is REPLACED by the Android app's lane:
 * Supabase Google OAuth (signInWithGoogle → full-page authorize →
 * redirect back, tokens consumed pre-render). The panel binds to the
 * shared session store — signing in lights up likes/telemetry across
 * the whole app instantly (the P2 desync left them dark until reload).
 */

import { useCallback } from 'react';
import { useSessionStore } from '../lib/sessionStore';
import { usePlayerStore, toTrackRow } from '../lib/playerStore';
import type { WasmEnginePort } from '../lib/wasmEngine';
import type { CloudTrack } from '../lib/supabase';
import { Icon } from './icons';

/** The Google "G" mark (brand glyph, inline — no CDN). */
function GoogleMark(): JSX.Element {
  return (
    <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
      <path
        fill="#EA4335"
        d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
      />
      <path
        fill="#4285F4"
        d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
      />
      <path
        fill="#FBBC05"
        d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
      />
      <path
        fill="#34A853"
        d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
      />
    </svg>
  );
}

export function AuthPanel({ port }: { port: WasmEnginePort | null }): JSX.Element {
  const session = useSessionStore((s) => s.session);
  const configured = useSessionStore((s) => s.configured);
  const authError = useSessionStore((s) => s.authError);
  const likes = useSessionStore((s) => s.likes);
  const likesBusy = useSessionStore((s) => s.likesBusy);
  const signInWithGoogle = useSessionStore((s) => s.signInWithGoogle);
  const signOut = useSessionStore((s) => s.signOut);
  const send = usePlayerStore((s) => s.send);

  const playLike = useCallback(
    async (track: CloudTrack) => {
      if (port === null) return;
      // Cloud rows carry the video id in stream_url (the Android storage
      // convention); without it the row is not playable from the web.
      const videoId = track.streamUrl ?? null;
      if (videoId === null || videoId === '') {
        return;
      }
      port.unlockSink();
      await port.playNow(
        toTrackRow({
          videoId,
          title: track.title,
          artist: track.artist,
          album: track.album ?? undefined,
          durationSec: track.durationSec ?? undefined,
          thumbnailUrl: track.coverUrl ?? undefined,
        }),
      );
    },
    [port],
  );

  if (!configured) {
    return (
      <section className="card auth" aria-label="Account">
        <h2 className="card-title">Account</h2>
        <p className="muted">
          Supabase credentials not baked into this build (fork or missing CI secret) —
          auth and cloud library are disabled. The player works anonymously.
        </p>
      </section>
    );
  }

  if (session === null) {
    return (
      <section className="card auth" aria-label="Sign in">
        <h2 className="card-title">Account</h2>
        <button type="button" className="auth__google" onClick={() => signInWithGoogle()}>
          <GoogleMark />
          <span>Sign In with Google</span>
        </button>
        {authError !== null && <p className="auth__error">{authError}</p>}
        <p className="muted auth__hint">
          The same account as the Android app — likes and stats sync both ways.
        </p>
      </section>
    );
  }

  return (
    <section className="card auth" aria-label="Account and library">
      <div className="queue__head">
        <h2 className="card-title">Account</h2>
        <span className="muted auth__email">{session.user.email ?? session.user.id}</span>
        <button
          type="button"
          className="btn btn--small"
          onClick={() => {
            void signOut();
          }}
        >
          Sign out
        </button>
      </div>

      <div className="auth__likes">
        <h3 className="auth__likes-title">My likes (cloud)</h3>
        {likesBusy ? (
          <p className="muted">loading…</p>
        ) : likes.length === 0 ? (
          <p className="muted">
            No liked tracks yet — they sync with the Android app (the same rows).
          </p>
        ) : (
          <ul className="auth__likes-list">
            {likes.map((track) => (
              <li key={track.id}>
                <button
                  type="button"
                  className="auth__like-row"
                  onClick={() => {
                    void playLike(track);
                  }}
                  title={track.streamUrl === null ? 'no video id on this row' : 'Play'}
                >
                  {track.coverUrl !== null && (
                    <img className="queue__row-art" src={track.coverUrl} alt="" loading="lazy" />
                  )}
                  <span className="queue__row-titles">
                    <span className="queue__row-title">{track.title}</span>
                    <span className="queue__row-artist">{track.artist}</span>
                  </span>
                  <span aria-hidden>
                    <Icon name="playArrow" size={18} />
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {authError !== null && <p className="auth__error">{authError}</p>}
      <p className="muted auth__hint">
        Playing a like enqueues it into the engine queue —{' '}
        <button
          type="button"
          className="linklike"
          onClick={() => send({ type: 'next' })}
        >
          skip ahead
        </button>{' '}
        to hear it.
      </p>
    </section>
  );
}
