/**
 * screens/UserProfileScreen.tsx — P3a: the profile surface.
 *
 * The Android UserProfileScreen port: back top bar, 88px identity
 * block (avatar initial fallback — no photo on the web session),
 * follower stat row, the cloud-sync pill, the Google auth button, the
 * "Your Soundtrack in Numbers" Wrapped hero, the Musical Chronotype
 * card, and the onward navigation cards. Numbers are HONEST: the web
 * reads the local play tally + cloud likes count (the full stats RPC
 * lands with the P3b wrapped port — no invented telemetry).
 */

import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { useSessionStore } from '../lib/sessionStore';
import { chronotype, readTally, topRotationCount } from '../lib/playTally';

export function UserProfileScreen(): JSX.Element {
  const navigate = useNavigate();
  const session = useSessionStore((s) => s.session);
  const configured = useSessionStore((s) => s.configured);
  const signInWithGoogle = useSessionStore((s) => s.signInWithGoogle);
  const signOut = useSessionStore((s) => s.signOut);
  const likes = useSessionStore((s) => s.likes);

  const tally = useMemo(() => readTally(), [session]);
  const chromo = useMemo(() => chronotype(tally), [tally]);
  const minutes = Math.round(tally.seconds / 60);
  const topRotations = topRotationCount(tally);

  const email = session?.user.email ?? null;
  const displayName = email !== null ? email.split('@')[0] : 'Streamify Listener';

  return (
    <div className="userprofile" role="main">
      <header className="screen-topbar">
        <button type="button" className="screen-topbar__back" onClick={() => navigate(-1)} aria-label="Back">
          <Icon name="arrowBack" size={24} />
        </button>
        <h1 className="screen-topbar__title">Profile &amp; Cloud Account</h1>
      </header>

      <div className="userprofile__scroll">
        {/* identity block */}
        <div className="userprofile__identity">
          <span className="userprofile__avatar" aria-hidden="true">
            {displayName.charAt(0).toUpperCase()}
          </span>
          <h2 className="userprofile__name">{displayName}</h2>
          {email !== null && <p className="userprofile__email">{email}</p>}
          <p className="userprofile__bio">
            Music lover exploring acoustic soundscapes on Streamify 🎧
          </p>

          <div className="userprofile__stats" aria-label="Listener stats">
            <div className="userprofile__stat">
              <span className="userprofile__stat-value">—</span>
              <span className="userprofile__stat-label">Followers</span>
            </div>
            <div className="userprofile__stat">
              <span className="userprofile__stat-value">—</span>
              <span className="userprofile__stat-label">Following</span>
            </div>
          </div>
          <p className="userprofile__stats-note">the follow graph lands with the community port (P3b)</p>

          <span className={`userprofile__sync ${session !== null ? 'userprofile__sync--on' : ''}`}>
            <Icon name={session !== null ? 'cloudDone' : 'cloudOff'} size={16} />
            {session !== null
              ? 'Cloud Sync Active (PostgreSQL + pgvector)'
              : 'Local Only • Sign in to sync across devices'}
          </span>

          {configured &&
            (session === null ? (
              <button type="button" className="userprofile__auth" onClick={() => signInWithGoogle()}>
                Sign In with Google
              </button>
            ) : (
              <button
                type="button"
                className="userprofile__auth userprofile__auth--out"
                onClick={() => {
                  void signOut();
                }}
              >
                Sign Out
              </button>
            ))}
        </div>

        {/* wrapped hero */}
        <section className="userprofile__card" aria-label="Your soundtrack in numbers">
          <p className="userprofile__eyebrow">YOUR SOUNDTRACK IN NUMBERS</p>
          <p className="userprofile__bignum">{minutes} Minutes Listened</p>
          <div className="userprofile__wrappedstats">
            <div>
              <span className="userprofile__wrappedstats-value">{tally.tracks}</span>
              <span className="userprofile__wrappedstats-label">Total Tracks</span>
            </div>
            <div>
              <span className="userprofile__wrappedstats-value">{likes.length}</span>
              <span className="userprofile__wrappedstats-label">Liked Songs</span>
            </div>
            <div>
              <span className="userprofile__wrappedstats-value">{topRotations}</span>
              <span className="userprofile__wrappedstats-label">Top Rotations</span>
            </div>
          </div>
          <p className="userprofile__stats-note">this device's local tally — the cloud stats RPC lands in P3b</p>
        </section>

        {/* musical chronotype */}
        <section className="userprofile__card" aria-label="Musical chronotype">
          <span className="userprofile__chrono-icon" style={{ background: `${chromo.color}33` }}>
            <Icon name="pause" size={22} style={{ transform: 'rotate(-45deg)' }} />
          </span>
          <div>
            <p className="userprofile__eyebrow" style={{ color: chromo.color }}>
              MUSICAL CHRONOTYPE
            </p>
            <p className="userprofile__chrono-title">{chromo.title}</p>
            <p className="userprofile__chrono-sub">
              Peak listening hours · {chromo.bpm} BPM acoustic signature
            </p>
          </div>
        </section>

        {/* onward cards */}
        <button type="button" className="userprofile__onward" onClick={() => navigate('/profile-selection')}>
          <span className="userprofile__onward-icon">
            <Icon name="link" size={20} />
          </span>
          <span className="userprofile__onward-titles">
            <span className="userprofile__onward-title">Connected Streaming Accounts</span>
            <span className="userprofile__onward-sub">Connect Spotify &amp; YouTube Music</span>
          </span>
          <Icon name="arrowBack" size={18} className="settings__fwd" />
        </button>

        <button type="button" className="userprofile__onward" onClick={() => navigate('/settings')}>
          <span className="userprofile__onward-icon">
            <Icon name="settings" size={20} />
          </span>
          <span className="userprofile__onward-titles">
            <span className="userprofile__onward-title">Settings &amp; Playback</span>
            <span className="userprofile__onward-sub">Audio quality, Equalizer &amp; Database Reset</span>
          </span>
          <Icon name="arrowBack" size={18} className="settings__fwd" />
        </button>
      </div>
    </div>
  );
}
