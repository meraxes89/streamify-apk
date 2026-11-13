/**
 * components/AuthPanel.tsx — W2.2: Supabase auth + the user's likes.
 *
 * Email+password lane (the web default — the Android Google id_token flow
 * needs a registered web origin in the Google console). Signed-in users
 * pull their cloud likes (the same `user_likes` rows the Android app
 * syncs — id-compatible upserts) and can play them straight into the
 * engine queue.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  fetchLikes,
  getSession,
  signInWithPassword,
  signOut,
  signUp,
  supabaseConfigured,
  type CloudTrack,
  type SupabaseSession,
} from '../lib/supabase';
import { toTrackRow, usePlayerStore } from '../lib/playerStore';
import type { WasmEnginePort } from '../lib/wasmEngine';

type AuthMode = 'signin' | 'signup';

export function AuthPanel({ port }: { port: WasmEnginePort | null }): JSX.Element {
  const [session, setSession] = useState<SupabaseSession | null>(null);
  const [mode, setMode] = useState<AuthMode>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [likes, setLikes] = useState<CloudTrack[]>([]);
  const [likesBusy, setLikesBusy] = useState(false);
  const send = usePlayerStore((s) => s.send);

  // Session boot: persisted → refresh → live.
  useEffect(() => {
    let cancelled = false;
    void getSession().then((s) => {
      if (!cancelled) setSession(s);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Likes pull on sign-in.
  useEffect(() => {
    if (session === null) {
      setLikes([]);
      return;
    }
    let cancelled = false;
    setLikesBusy(true);
    void fetchLikes().then((rows) => {
      if (!cancelled) {
        setLikes(rows);
        setLikesBusy(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [session]);

  const submit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (busy || email === '' || password === '') return;
      setBusy(true);
      setError(null);
      setNotice(null);
      const outcome =
        mode === 'signup'
          ? await signUp(email, password)
          : await signInWithPassword(email, password);
      setBusy(false);
      if (outcome.session !== null) {
        setSession(outcome.session);
        setPassword('');
      } else if (outcome.needsConfirmation) {
        setNotice('Account created — check your email for the confirmation link, then sign in.');
        setMode('signin');
      } else {
        setError(outcome.error ?? 'authentication failed');
      }
    },
    [busy, email, mode, password],
  );

  const onSignOut = useCallback(async () => {
    await signOut();
    setSession(null);
    setLikes([]);
  }, []);

  const playLike = useCallback(
    async (track: CloudTrack) => {
      if (port === null) return;
      // Cloud rows carry the video id in stream_url (the Android storage
      // convention); without it the row is not playable from the web.
      const videoId = track.streamUrl ?? null;
      if (videoId === null || videoId === '') {
        setError(`"${track.title}" has no video id on the cloud row — play it once from search.`);
        return;
      }
      await port.playNow({
        ...toTrackRow({
          videoId,
          title: track.title,
          artist: track.artist,
          album: track.album ?? undefined,
          durationSec: track.durationSec ?? undefined,
          thumbnailUrl: track.coverUrl ?? undefined,
        }),
      });
    },
    [port],
  );

  if (!supabaseConfigured()) {
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
        <form className="auth__form" onSubmit={submit}>
          <div className="auth__mode" role="tablist" aria-label="Auth mode">
            <button
              type="button"
              className={`btn btn--small ${mode === 'signin' ? 'btn--active' : ''}`}
              onClick={() => setMode('signin')}
              role="tab"
              aria-selected={mode === 'signin'}
            >
              Sign in
            </button>
            <button
              type="button"
              className={`btn btn--small ${mode === 'signup' ? 'btn--active' : ''}`}
              onClick={() => setMode('signup')}
              role="tab"
              aria-selected={mode === 'signup'}
            >
              Create account
            </button>
          </div>
          <input
            className="auth__input"
            type="email"
            placeholder="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
          <input
            className="auth__input"
            type="password"
            placeholder="password"
            autoComplete={mode === 'signup' ? 'new-password' : 'current-password'}
            minLength={6}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          <button type="submit" className="btn" disabled={busy}>
            {busy ? '…' : mode === 'signup' ? 'Create account' : 'Sign in'}
          </button>
        </form>
        {notice !== null && <p className="auth__notice">{notice}</p>}
        {error !== null && <p className="auth__error">{error}</p>}
      </section>
    );
  }

  return (
    <section className="card auth" aria-label="Account and library">
      <div className="queue__head">
        <h2 className="card-title">Account</h2>
        <span className="muted auth__email">{session.user.email ?? session.user.id}</span>
        <button type="button" className="btn btn--small" onClick={() => void onSignOut()}>
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
                  <span aria-hidden>▶</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {error !== null && <p className="auth__error">{error}</p>}
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
