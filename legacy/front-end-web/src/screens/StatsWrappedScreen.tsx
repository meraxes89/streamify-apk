/**
 * screens/StatsWrappedScreen.tsx — W-W: the Wrapped port
 * (ui/screens/StatsWrappedScreen.kt).
 *
 * The shareable year-in-music card stack: hero telemetry, Top-5 songs
 * (gold/silver/bronze ranks, tap = play through the engine), the AI
 * persona card, the genre distribution bars, and the share CTA.
 *
 * Data honesty (P2 discipline — see lib/wrappedStore.ts):
 *   • minutes / tracks / rotations: the local tally + the signed-in
 *     user's cloud play events, merged monotonically (never summed);
 *   • persona: derived from the real hour-of-day histogram (the
 *     chronotype model). The APK derives it from track BPM — the web's
 *     BPM lives behind the taste-engine facade that isn't exposed yet,
 *     so the BPM slot renders an explicit placeholder and the card
 *     states its derivation. No fakes;
 *   • genres: the APK's exact keyword model over the real corpus;
 *   • Top-5 playback: rotations first, else the likes list (the APK's
 *     fallback chain), searching on tap when no videoId is known.
 */

import { useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { useTrackPlayer } from '../lib/trackPlayer';
import { useWrappedStore } from '../lib/wrappedStore';
import { toast } from '../lib/toastStore';
import type { WrappedTopTrack } from '../lib/wrappedStore';

const RANK_COLORS = ['#FFD700', '#C0C0C0', '#CD7F32'];

function shareText(stats: {
  totalMinutes: number;
  personaName: string;
  personaEmoji: string;
  topGenres: Array<{ genre: string; share: number }>;
  top5Tracks: WrappedTopTrack[];
}): string {
  const topSongs = stats.top5Tracks
    .map((t, i) => `${i + 1}. ${t.title} - ${t.artist}`)
    .join('\n');
  return (
    `🔥 My Streamify Wrapped:\n\n` +
    `🎧 ${stats.totalMinutes} Minutes Listened\n` +
    `✨ Persona: ${stats.personaName} ${stats.personaEmoji}\n` +
    `🎶 Top Genre: ${stats.topGenres[0]?.genre ?? 'Music'}\n\n` +
    `🎵 Top 5 Songs:\n${topSongs}\n\n` +
    `Listen on Streamify!`
  );
}

export function StatsWrappedScreen(): JSX.Element {
  const navigate = useNavigate();
  const phase = useWrappedStore((s) => s.phase);
  const stats = useWrappedStore((s) => s.stats);
  const compute = useWrappedStore((s) => s.compute);
  const player = useTrackPlayer();

  useEffect(() => {
    void compute();
  }, [compute]);

  const share = useMemo(() => (stats !== null ? shareText(stats) : ''), [stats]);

  const doShare = () => {
    if (stats === null) return;
    if (navigator.share !== undefined) {
      void navigator.share({ title: 'My Streamify Wrapped', text: share }).catch(() => undefined);
    } else {
      void navigator.clipboard?.writeText(share).then(() => toast('Wrapped card copied to clipboard'));
    }
  };

  const playTop = (track: WrappedTopTrack) => {
    void player.play({
      videoId: '',
      title: track.title,
      artist: track.artist,
      album: null,
      durationSec: null,
      thumbnailUrl: track.coverUrl,
    });
  };

  return (
    <div className="wrapped" role="main">
      <header className="screen-topbar">
        <button type="button" className="screen-topbar__back" onClick={() => navigate(-1)} aria-label="Back">
          <Icon name="arrowBack" size={24} />
        </button>
        <h1 className="screen-topbar__title">Streamify Wrapped</h1>
        {stats !== null && (
          <button type="button" className="wrapped__sharebtn" onClick={doShare} aria-label="Share your Wrapped">
            <Icon name="share" size={20} />
          </button>
        )}
      </header>

      <div className="wrapped__scroll">
        {phase !== 'ready' || stats === null ? (
          <div className="wrapped__loading" role="status">
            <span className="spinner" aria-hidden="true" />
            <p className="wrapped__loading-text">Computing your audio DNA…</p>
          </div>
        ) : (
          <>
            {/* 1. Hero telemetry card */}
            <section className="wrapped__hero" aria-label="Your listening totals">
              <p className="wrapped__hero-eyebrow">YOUR SOUNDTRACK IN NUMBERS</p>
              <p className="wrapped__hero-big">{stats.totalMinutes}</p>
              <p className="wrapped__hero-unit">Minutes Listened</p>
              <div className="wrapped__herostats">
                <div>
                  <span className="wrapped__herovalue">{stats.totalTracks}</span>
                  <span className="wrapped__herolabel">Tracks</span>
                </div>
                <div>
                  <span className="wrapped__herovalue">{stats.likedSongs}</span>
                  <span className="wrapped__herolabel">Liked Songs</span>
                </div>
                <div>
                  <span className="wrapped__herovalue">{stats.topPlayedCount}</span>
                  <span className="wrapped__herolabel">Plays</span>
                </div>
              </div>
              <p className="wrapped__heronote">
                {stats.fromCloud
                  ? 'local tally + your cloud play events (monotonic merge, never double-counted)'
                  : "this device's local tally — sign in to merge your cloud play events"}
              </p>
            </section>

            {/* 2. Top 5 songs */}
            {stats.top5Tracks.length > 0 && (
              <section className="wrapped__card" aria-label="Your top five songs">
                <p className="wrapped__cardlabel">YOUR TOP 5 SONGS</p>
                <ol className="wrapped__toplist">
                  {stats.top5Tracks.map((t, i) => (
                    <li key={`${t.trackSig}_${i}`}>
                      <button type="button" className="wrapped__toprow" onClick={() => playTop(t)}>
                        <span
                          className="wrapped__toprank"
                          style={i < 3 ? { color: RANK_COLORS[i] } : undefined}
                        >
                          #{i + 1}
                        </span>
                        {t.coverUrl !== null ? (
                          <img className="wrapped__topart" src={t.coverUrl} alt="" loading="lazy" />
                        ) : (
                          <span className="wrapped__topart wrapped__topart--empty" aria-hidden="true">
                            <Icon name="musicNote" size={20} />
                          </span>
                        )}
                        <span className="wrapped__toptitles">
                          <span className="wrapped__toptitle">{t.title}</span>
                          <span className="wrapped__topsub">
                            {t.artist}
                            {t.plays > 0 ? ` · ${t.plays} plays` : ' · from your likes'}
                          </span>
                        </span>
                        <Icon name="playArrow" size={18} className="wrapped__topplay" />
                      </button>
                    </li>
                  ))}
                </ol>
              </section>
            )}

            {/* 3. AI persona card */}
            <section className="wrapped__card wrapped__persona" aria-label="Your listening persona">
              <p className="wrapped__cardlabel">AI AUDIO PERSONA</p>
              <div className="wrapped__persona-row">
                <span className="wrapped__persona-emoji" aria-hidden="true">
                  {stats.personaEmoji}
                </span>
                <div>
                  <p className="wrapped__persona-name">{stats.personaName}</p>
                  <p className="wrapped__persona-desc">{stats.personaDescription}</p>
                </div>
              </div>
              <div className="wrapped__persona-meta">
                <span className="wrapped__persona-key">Avg BPM</span>
                <span className="wrapped__persona-val">
                  {stats.averageBpm !== null ? `${stats.averageBpm} BPM` : '— engine facade pending'}
                </span>
              </div>
              <p className="wrapped__persona-note">
                persona derived from your listening-time histogram (real data); the BPM persona rides
                the taste-engine facade — tracked for the engine tail, never faked.
              </p>
            </section>

            {/* 4. Top genres */}
            {stats.topGenres.length > 0 && (
              <section className="wrapped__card" aria-label="Top genres">
                <p className="wrapped__cardlabel">TOP GENRES</p>
                <ul className="wrapped__genres">
                  {stats.topGenres.map((g, i) => (
                    <li className="wrapped__genre" key={g.genre}>
                      <span className="wrapped__genre-rank">#{i + 1}</span>
                      <span className="wrapped__genre-name">{g.genre}</span>
                      <span className="wrapped__genre-bar">
                        <span className="wrapped__genre-fill" style={{ width: `${Math.round(g.share * 100)}%` }} />
                      </span>
                      <span className="wrapped__genre-pct">{Math.round(g.share * 100)}%</span>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            {/* 5. Top artists (bonus shelf, same honesty) */}
            {stats.topArtists.length > 0 && (
              <section className="wrapped__card" aria-label="Top artists">
                <p className="wrapped__cardlabel">TOP ARTISTS</p>
                <ul className="wrapped__artists">
                  {stats.topArtists.map((a, i) => (
                    <li className="wrapped__artist" key={a.artist}>
                      <span className="wrapped__genre-rank">#{i + 1}</span>
                      <span className="wrapped__artist-name">{a.artist}</span>
                      <span className="wrapped__artist-count">{a.count}</span>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            {/* 6. Share CTA */}
            <button type="button" className="wrapped__cta" onClick={doShare}>
              <Icon name="share" size={18} />
              Share Your Wrapped
            </button>
          </>
        )}
      </div>
    </div>
  );
}
